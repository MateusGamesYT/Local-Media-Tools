"""Golden values for the app's JVM test: features of real validation photos -> fused category scores,
computed exactly as the app does (4-bit head from the shipped asset, probes, calibrate, max)."""
import sys, os, struct, json, numpy as np
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = sys.argv[1]; OUT = sys.argv[2]
raw = open(os.path.join(APP, 'gallery/scene_head.bin'), 'rb').read()
assert raw[:4] == b'LMTH'
ver, R, D, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24
ng = D // GS
sc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
b = np.frombuffer(raw, '<f4', R, o); o += R * 4
pk = np.frombuffer(raw, np.uint8, R * D // 2, o).reshape(R, D // 2); o += R * D // 2
PW = np.frombuffer(raw, '<f4', NP * D, o).reshape(NP, D); o += NP * D * 4
PB = np.frombuffer(raw, '<f4', NP, o)
lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8)
lo[lo > 7] -= 16; hi[hi > 7] -= 16
q = np.empty((R, D), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi
Wd = (q.reshape(R, ng, GS) * sc[:, :, None]).reshape(R, D)
cats = []
for line in open(os.path.join(APP, 'gallery/categories.tsv')):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t')
    ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    cats.append(dict(key=c[0], scene=ints(c[7]), st=float(c[8]), probe=int(c[9]), pt=float(c[10])))
def cal(s, t):
    if t <= 0 or t >= 1: return 0.0
    return 0.5 + 0.5 * min(1, (s - t) / (1 - t)) if s >= t else 0.5 * max(0, s / t)
F = np.load(os.path.join(G, 'out/oi_feat_w8.npz')); X = F['feats']
Y = np.load(os.path.join(G, 'out/oi_scores.npz')); keys = list(Y['keys'])
rng = np.random.RandomState(4)
# Photos with a verified positive for some scene/thing category, plus random ones.
pick = []
for k in ('dog', 'cat', 'beach', 'sunset', 'night', 'mountain', 'food', 'pizza', 'car', 'flower', 'snow', 'document', 'waterfall', 'forest', 'wedding', 'bird'):
    cand = np.where(Y['Y'][:, keys.index(k)] == 1)[0]; pick += list(rng.choice(cand, 2, replace=False))
pick += list(rng.choice(len(X), 8, replace=False))
recs = []
for i in pick:
    f = X[i].astype(np.float32)
    z = Wd @ f + b; z = z - z.max(); p = np.exp(z.astype(np.float64)); p /= p.sum()
    pr = 1 / (1 + np.exp(-(PW @ f + PB).astype(np.float64)))
    s = []
    for c in cats:
        best = 0.0
        if c['scene'] and c['st'] > 0: best = max(best, cal(p[c['scene']].sum(), c['st']))
        if c['probe'] >= 0 and c['pt'] > 0: best = max(best, cal(pr[c['probe']], c['pt']))
        s.append(best)
    recs.append((f, np.array(s, np.float32)))
with open(OUT, 'wb') as fo:
    fo.write(struct.pack('<iii', len(recs), D, len(cats)))
    for f, s in recs: fo.write(f.astype('<f4').tobytes()); fo.write(s.astype('<f4').tobytes())
tagged = [[cats[j]['key'] for j in np.where(s >= 0.5)[0]] for _, s in recs]
print(len(recs), 'records;', sum(len(t) for t in tagged), 'tags; e.g.', tagged[:6])
