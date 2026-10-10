"""People-grouping evaluation on real, identity-labelled photos.

faces.json: every detected face (embedding, quality). labels.json: {face: {"person": name, "tags": "HGME"}}
(verified by eye; tags: H headwear, G glasses, M heavy make-up, E strong expression). Unlabelled faces
(other people in the photos) are kept as distractors: they are clustered but not scored.
"""
import json, os, sys, subprocess, struct, random, collections
import numpy as np

D = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, D)
from common import WORK, HERE
H = os.path.join(WORK, 'harness')

def load(faces_path, labels_path, extra=False):
    """extra: also apply labels_extra.json (faces of the labelled people the original labels missed, checked
    by eye in 2026-10; the faces nobody could be sure of are left out entirely)."""
    faces = json.load(open(faces_path)); labels = json.load(open(labels_path))
    # A photo selected for two people (both named in its title) was analysed twice: keep each face once.
    seen = set(); uniq = []
    for f in faces:
        if f['face'] not in seen: seen.add(f['face']); uniq.append(f)
    faces = uniq
    for f in faces:
        l = labels.get(f['face'])
        f['label'] = l['person'] if l else None
        f['tags'] = l.get('tags', '') if l else ''
    if extra:
        x = json.load(open(os.path.join(HERE, 'labels_extra.json')))
        unsure = set(x['unsure'])
        faces = [f for f in faces if f['face'] not in unsure]
        for f in faces:
            if f['face'] in x['same']: f['label'] = x['same'][f['face']]
    return faces

def good_default(f, p):
    return f['score'] >= p.get('goodScore', 0.75) and f['yaw'] <= p.get('goodYaw', 0.45) and f['eye'] >= p.get('goodEye', 24) \
        and f.get('norm', 99) >= p.get('goodNorm', 0)

def usable(f, p):
    """May a low-quality face join a group at all?"""
    return f['eye'] >= p.get('lowEye', 0) and f.get('norm', 99) >= p.get('lowNorm', 0) and f['score'] >= p.get('lowScore', 0)

def quality(f):
    return f['score'] * min(f['eye'], 60) * (1 - min(f['yaw'], 1))

# ---------------------------------------------------------------- the app's own code (Kotlin)
def cluster_app(faces, p=None):
    """The app's FaceClustering (Kotlin harness). p overrides ClusterParams.SFACE (keys as in cluster_py)."""
    E = np.array([f['emb'] for f in faces], np.float32)
    path = os.path.join(H, f'in_{os.getpid()}.bin')   # one per process: several evaluations may run at once
    with open(path, 'wb') as o:
        o.write(struct.pack('<ii', len(faces), E.shape[1]))
        for f, e in zip(faces, E):
            o.write(struct.pack('<fff', f['score'], f['eye'], f['yaw'])); o.write(e.astype('<f4').tobytes())
    outp = os.path.join(H, f'out_{os.getpid()}.txt')
    args = [os.path.join(H, 'run.sh'), path, outp]
    if p: args.append(','.join(f"{k}={v}" for k, v in p.items() if k in ('join', 'merge', 'assign', 'low', 'margin', 'goodScore', 'goodYaw', 'goodEye', 'lowScore', 'link')))
    subprocess.run(args, check=True, capture_output=True)
    lab = np.full(len(faces), -1)
    for line in open(outp):
        i, g = map(int, line.split()); lab[i] = g
    os.remove(path); os.remove(outp)
    return lab

# ---------------------------------------------------------------- Python variants
def cluster_py(faces, p):
    E = np.array([f['emb'] for f in faces], np.float64)
    n = len(faces); Q = np.array([quality(f) for f in faces])
    good = np.array([good_default(f, p) for f in faces])
    link = p.get('link', 'avg')
    join, merge, assign, low, margin = p['join'], p['merge'], p['assign'], p['low'], p['margin']
    def sims(e, S, cnt, own=-1):
        if link == 'avg':
            s = (S @ e) / np.maximum(cnt, 1)
            if own >= 0: s[own] = (S[own] @ e - 1.0) / (cnt[own] - 1) if cnt[own] > 1 else -9
        else:
            s = (S @ e) / np.maximum(np.linalg.norm(S, axis=1), 1e-9)
            if own >= 0:
                r = S[own] - e; nr = np.linalg.norm(r)
                s[own] = (r @ e) / nr if cnt[own] > 1 and nr > 1e-9 else -9
        return s
    order = sorted(np.where(good)[0], key=lambda i: -Q[i])
    S = np.zeros((0, E.shape[1])); cnt = np.zeros(0); members = []
    for i in order:
        if len(members):
            s = sims(E[i], S, cnt); b = int(np.argmax(s))
            if s[b] >= join:
                S[b] += E[i]; cnt[b] += 1; members[b].append(i); continue
        S = np.vstack([S, E[i]]); cnt = np.append(cnt, 1); members.append([i])
    # merging: best pair first
    while len(members) > 1:
        if link == 'avg': L = (S @ S.T) / np.outer(cnt, cnt)
        else:
            Nm = S / np.linalg.norm(S, axis=1, keepdims=True); L = Nm @ Nm.T
        np.fill_diagonal(L, -9)
        a, b = np.unravel_index(np.argmax(L), L.shape)
        if L[a, b] < merge: break
        S[a] += S[b]; cnt[a] += cnt[b]; members[a] += members[b]
        S = np.delete(S, b, 0); cnt = np.delete(cnt, b); del members[b]
    for _ in range(p.get('passes', 2)):
        home = np.full(n, -1)
        for c, m in enumerate(members): home[m] = c
        target = {}
        for i in order:
            own = home[i]; s = sims(E[i], S, cnt, own)
            if own >= 0 and cnt[own] > 1: s[(cnt < 2) & (np.arange(len(cnt)) != own)] = -9
            b = int(np.argmax(s)) if len(s) else -1
            target[i] = b if b >= 0 and s[b] >= assign else -1
        newm = collections.defaultdict(list); alone = []
        for i in order:
            (newm[target[i]].append(i) if target[i] >= 0 else alone.append([i]))
        members = [m for m in newm.values()] + alone
        S = np.array([E[m].sum(0) for m in members]); cnt = np.array([len(m) for m in members], float)
    # low-quality faces
    big = cnt >= 2; lab = np.full(n, -1)
    for c, m in enumerate(members): lab[m] = c
    nxt = len(members)
    for i in range(n):
        if good[i]: continue
        if not usable(faces[i], p): lab[i] = nxt; nxt += 1; continue
        s = sims(E[i], S, cnt); s[cnt < p.get('lowMinGroup', 2)] = -9
        o = np.argsort(-s)
        s1 = s[o[0]] if len(o) else -9; s2 = s[o[1]] if len(o) > 1 else -9
        if s1 >= low and s1 - s2 >= margin: lab[i] = o[0]
        else: lab[i] = nxt; nxt += 1
    return lab

# ---------------------------------------------------------------- metrics
def metrics(faces, lab):
    idx = [i for i, f in enumerate(faces) if f['label']]
    P = [faces[i]['label'] for i in idx]; L = [lab[i] for i in idx]
    groups = collections.defaultdict(list)
    for i in range(len(lab)): groups[lab[i]].append(i)
    # B-cubed over labelled faces (unlabelled faces count as other identities)
    prec = rec = 0
    by_person = collections.Counter(P)
    for i, p, l in zip(idx, P, L):
        g = [j for j in groups[l] if faces[j]['label']]
        same = sum(1 for j in g if faces[j]['label'] == p)
        prec += same / len(g); rec += same / by_person[p]
    n = len(idx)
    # per person: groups of 2+ that hold them, share in their main group, share alone
    per = {}
    for p in by_person:
        mine = [l for q, l in zip(P, L) if q == p]
        c = collections.Counter(mine)
        multi = [g for g in c if len(groups[g]) >= 2]
        main = max(c.values())
        per[p] = dict(n=by_person[p], groups=len(multi), main=main / by_person[p], alone=sum(1 for g in mine if len(groups[g]) == 1) / by_person[p])
    impure = 0; wrong = 0
    for g, m in groups.items():
        labs = [faces[j]['label'] for j in m if faces[j]['label']]
        if len(set(labs)) > 1:
            impure += 1; maj = collections.Counter(labs).most_common(1)[0][1]; wrong += len(labs) - maj
    main_of = {p: collections.Counter(l for q, l in zip(P, L) if q == p).most_common(1)[0][0] for p in by_person}
    def share(sel):
        ii = [k for k, i in enumerate(idx) if sel(faces[i])]
        return (np.mean([L[k] == main_of[P[k]] for k in ii]), len(ii)) if ii else (float('nan'), 0)
    cond = {
        'headwear': share(lambda f: 'H' in f['tags']), 'glasses': share(lambda f: 'G' in f['tags']),
        'make-up': share(lambda f: 'M' in f['tags']), 'expression': share(lambda f: 'E' in f['tags']),
        'frontal': share(lambda f: f['yaw'] < 0.25), 'turned': share(lambda f: 0.25 <= f['yaw'] < 0.6),
        'profile': share(lambda f: f['yaw'] >= 0.6), 'small': share(lambda f: f['eye'] < 24),
        'plain': share(lambda f: not f['tags'] and f['yaw'] < 0.25 and f['eye'] >= 24),
    }
    return dict(P=prec / n, R=rec / n, faces=n, people=len(by_person),
                groups_per_person=np.mean([v['groups'] for v in per.values()]),
                main_share=np.mean([v['main'] for v in per.values()]),
                alone=np.mean([v['alone'] for v in per.values()]),
                impure_groups=impure, wrong_faces=wrong, cond=cond, per=per)

def show(name, m):
    c = ' '.join(f"{k} {v[0]:.2f}({v[1]})" for k, v in m['cond'].items() if v[1])
    print(f"{name:34s} B3 P={m['P']:.3f} R={m['R']:.3f} | groups/person {m['groups_per_person']:.2f} main {m['main_share']:.2f} alone {m['alone']:.2f} | impure {m['impure_groups']} wrong {m['wrong_faces']} | {c}")

def split(faces, seed=0):
    people = sorted({f['label'] for f in faces if f['label']})
    rnd = random.Random(seed); rnd.shuffle(people)
    return set(people[:len(people) // 2]), set(people[len(people) // 2:])

def subset(faces, people):
    """Faces of these people plus every unlabelled face from their photos (the other people in them)."""
    imgs = {f['image'] for f in faces if f['label'] in people}
    return [f for f in faces if f['label'] in people or (f['label'] is None and f['image'] in imgs)]

def absorbed(faces, lab):
    """Unlabelled faces (bystanders, mostly strangers) that ended up in a group with a verified person."""
    groups = collections.defaultdict(list)
    for i, l in enumerate(lab): groups[l].append(i)
    n = 0
    for m in groups.values():
        if any(faces[j]['label'] for j in m): n += sum(1 for j in m if not faces[j]['label'])
    return n

def harm(faces, lab):
    """Bystanders (unlabelled) put in a verified person's group from a photo where that person isn't verified:
    each adds a photo the person may not be in. (A bystander from a photo where they are only adds a wrong face.)"""
    groups = collections.defaultdict(list)
    for i, l in enumerate(lab): groups[l].append(i)
    ver = collections.defaultdict(set)
    for f in faces:
        if f['label']: ver[f['image']].add(f['label'])
    bad = same = strangers = 0
    for m in groups.values():
        labs = [faces[j]['label'] for j in m if faces[j]['label']]
        if not labs:
            if len({faces[j]['image'] for j in m}) > 1: strangers += 1
            continue
        x = collections.Counter(labs).most_common(1)[0][0]
        for j in m:
            if faces[j]['label']: continue
            if x in ver[faces[j]['image']]: same += 1
            else: bad += 1
    return dict(bad=bad, inphoto=same, stranger_groups=strangers)
