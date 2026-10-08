import json, numpy as np, sys, itertools
G = sys.argv[1]
d = [r for r in json.load(open(f"{G}/eval/biwi_faces.json")) if r["found"]]
seqs = sorted(set(r["seq"] for r in d))
print("faces", len(d), "yaw quantiles", np.quantile([r["yaw"] for r in d], [0.25, 0.5, 0.75, 0.9]).round(3), "eye px median", np.median([r["eye"] for r in d]))
def M(key, sel): return np.array([r[key] for r in sel], np.float32)
# Same-person sequences: mean similarity of frontal faces across sequences.
front = [r for r in d if r["yaw"] < 0.15]
E = M("fp32_flip", front); S = np.array([r["seq"] for r in front])
cs = {}
for a, b in itertools.combinations(seqs, 2):
    A = E[S == a]; B = E[S == b]
    if len(A) and len(B): cs[(a, b)] = float((A @ B.T).mean())
top = sorted(cs.items(), key=lambda x: -x[1])[:8]
print("most similar sequence pairs (frontal, fp32_flip):", [(k, round(v, 3)) for k, v in top])
same = set(k for k, v in cs.items() if v > 0.35)
print("treated as same person:", same)
pid = {s: s for s in seqs}
for a, b in same: pid[b] = pid[a]
def evaluate(key, sel, label):
    E = M(key, sel); P = np.array([pid[r["seq"]] for r in sel])
    sim = E @ E.T
    iu = np.triu_indices(len(sel), 1)
    s = sim[iu]; same_ = (P[:, None] == P[None, :])[iu]
    pos, neg = s[same_], s[~same_]
    out = [f"{label:28s} n={len(sel):5d} pos={len(pos):6d} neg={len(neg):7d}"]
    for fpr in (1e-3, 1e-4, 1e-5):
        t = np.quantile(neg, 1 - fpr)
        out.append(f"FPR {fpr:g}: thr {t:.3f} TPR {(pos >= t).mean():.3f}")
    for t in (0.363, 0.40, 0.45, 0.50):
        out.append(f"@{t}: TPR {(pos >= t).mean():.3f} FPR {(neg >= t).mean():.5f}")
    print(" | ".join(out))
for tier, cond in [("all", lambda r: True), ("yaw<0.35", lambda r: r["yaw"] < 0.35), ("yaw<0.2", lambda r: r["yaw"] < 0.2)]:
    sel = [r for r in d if cond(r)]
    for key in ("int8", "int8_flip", "fp32", "fp32_flip"):
        evaluate(key, sel, f"{tier} {key}")
