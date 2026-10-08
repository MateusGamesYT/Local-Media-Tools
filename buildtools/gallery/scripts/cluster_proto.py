import json, numpy as np, sys, random
G = sys.argv[1]
d = [r for r in json.load(open(f"{G}/eval/biwi_faces.json")) if r["found"]]
same = [("07","22"),("03","15"),("05","18")]   # clear repeats (others ambiguous -> dropped below)
amb = {"08","11","02","21","19"}
pid = {}
for r in d:
    s = r["seq"]; pid[s] = s
for a, b in same: pid[b] = a
d = [r for r in d if r["seq"] not in amb]
KEY = "int8_flip"

EYE = float(sys.argv[2]) if len(sys.argv) > 2 else 16
SINGLE_RULE = len(sys.argv) > 3 and sys.argv[3] == "1"
def good(r): return r["score"] >= 0.75 and r["yaw"] <= 0.45 and r["eye"] >= EYE

def cluster(E, Q, isgood, T_join=0.42, T_merge=0.40, T_assign=0.40, T_low=0.46, margin=0.05, small=0.0, prune=0.0):
    def tj(n): return T_join + small / np.sqrt(n)
    def tm(n): return T_merge + small / np.sqrt(n)
    n = len(E); order = sorted([i for i in range(n) if isgood[i]], key=lambda i: -Q[i])
    S = []; members = []
    for i in order:
        best, bs = -1, -9
        for c in range(len(S)):
            s = E[i] @ S[c] / len(members[c])
            if s > bs: bs, best = s, c
        if best >= 0 and bs >= tj(len(members[best])): S[best] = S[best] + E[i]; members[best].append(i)
        else: S.append(E[i].copy()); members.append([i])
    # average-linkage merging
    while True:
        C = len(S)
        if C < 2: break
        Sm = np.array(S); cnt = np.array([len(m) for m in members], np.float32)
        sim = (Sm @ Sm.T) / np.outer(cnt, cnt); np.fill_diagonal(sim, -9)
        need = T_merge + small / np.sqrt(np.minimum(cnt[:, None], cnt[None, :]))
        ok = sim - need
        a, b = np.unravel_index(np.argmax(ok), ok.shape)
        if ok[a, b] < 0: break
        S[a] = S[a] + S[b]; members[a] += members[b]; del S[b]; del members[b]
    # reassignment of good faces (one pass)
    for _ in range(2):
        Sm = np.array(S); cnt = np.array([len(m) for m in members], np.float32)
        lab = np.full(n, -1)
        for c, m in enumerate(members): lab[m] = c
        newm = [[] for _ in S]; extra = []
        for i in order:
            s = E[i] @ Sm.T
            own = lab[i]
            s = s.copy()
            if own >= 0:
                s[own] = (s[own] - 1.0) / max(cnt[own] - 1, 1) if cnt[own] > 1 else -9
            s[np.arange(len(S)) != own] /= cnt[np.arange(len(S)) != own]
            if SINGLE_RULE and own >= 0 and cnt[own] > 1:
                mask = (cnt < 2) & (np.arange(len(S)) != own)
                s[mask] = -9
            c = int(np.argmax(s))
            if s[c] >= T_assign: newm[c].append(i)
            else: extra.append([i])
        members = [m for m in newm if m] + extra
        S = [E[m].sum(0) for m in members]
    # Outlier pruning: every member must resemble the rest of its group on average.
    if prune > 0:
        changed = True
        while changed:
            changed = False
            nm = []
            for m in members:
                if len(m) < 2: nm.append(m); continue
                Sm_ = E[m].sum(0)
                keep, out = [], []
                for i in m:
                    a = (E[i] @ Sm_ - 1.0) / (len(m) - 1)
                    (keep if a >= prune else out).append(i)
                if out and keep: changed = True
                if not keep: keep, out = [], m
                if keep: nm.append(keep)
                nm += [[i] for i in out]
            members = nm
        S = [E[m].sum(0) for m in members]
    # low-quality faces join only with a clear margin
    Sm = np.array(S); cnt = np.array([len(m) for m in members], np.float32)
    for i in range(n):
        if isgood[i]: continue
        s = (E[i] @ Sm.T) / cnt
        o = np.argsort(-s)
        if s[o[0]] >= T_low and (len(o) < 2 or s[o[0]] - s[o[1]] >= margin) and cnt[o[0]] >= 2: members[o[0]].append(i)
        else: members.append([i])
    return members

def bcubed(members, P):
    lab = {}
    for c, m in enumerate(members):
        for i in m: lab[i] = c
    prec = rec = 0
    n = len(P)
    sizes = {}
    for i in range(n):
        ci = members[lab[i]]
        same_c = sum(1 for j in ci if P[j] == P[i])
        prec += same_c / len(ci)
        tot = sum(1 for j in range(n) if P[j] == P[i])
        rec += same_c / tot
    return prec / n, rec / n

random.seed(1)
for trial in range(3):
    # A simulated gallery: each person appears a random number of times.
    sel = []
    by = {}
    for r in d: by.setdefault(pid[r["seq"]], []).append(r)
    for p, rs in by.items():
        k = random.choice([3, 8, 20, 50, 120])
        sel += random.sample(rs, min(k, len(rs)))
    E = np.array([r[KEY] for r in sel], np.float32); P = [pid[r["seq"]] for r in sel]
    Q = [r["score"] * min(r["eye"], 60) * (1 - min(r["yaw"], 1)) for r in sel]
    isgood = [good(r) for r in sel]
    for params in [dict(T_low=0.50)]:
        mem = cluster(E, Q, isgood, **params)
        p, rc = bcubed(mem, P)
        big = [m for m in mem if len(m) >= 2]
        impure = sum(1 for m in big if len(set(P[i] for i in m)) > 1)
        people = len(set(P))
        cl_per_person = {}
        for m in big:
            maj = max(set(P[i] for i in m), key=lambda x: sum(1 for i in m if P[i] == x))
            cl_per_person[maj] = cl_per_person.get(maj, 0) + 1
        clustered = sum(len(m) for m in big) / len(sel)
        print(f"trial {trial} n={len(sel)} people={people} {params}: B3 P={p:.3f} R={rc:.3f} clusters>=2: {len(big)} impure={impure} clustered={clustered:.2f} max clusters/person={max(cl_per_person.values())}")

