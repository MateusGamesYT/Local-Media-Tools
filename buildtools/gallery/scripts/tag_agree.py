"""Do two backbones give the same final tags (shipped 4-bit head, probes and thresholds; scene/probe sources)?"""
import sys, os, struct, numpy as np
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = sys.argv[1]; A = np.load(sys.argv[2]); B = np.load(sys.argv[3]); n = len(B['feats'])
raw = open(os.path.join(APP, 'gallery/scene_head.bin'), 'rb').read()
ver, R, D, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24; ng = D // GS
sc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
b = np.frombuffer(raw, '<f4', R, o); o += R * 4
pk = np.frombuffer(raw, np.uint8, R * D // 2, o).reshape(R, D // 2); o += R * D // 2
PW = np.frombuffer(raw, '<f4', NP * D, o).reshape(NP, D); o += NP * D * 4
PB = np.frombuffer(raw, '<f4', NP, o)
lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
q = np.empty((R, D), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi
Wd = (q.reshape(R, ng, GS) * sc[:, :, None]).reshape(R, D)
cats = []
for line in open(os.path.join(APP, 'gallery/categories.tsv')):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t'); ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    cats.append((c[0], ints(c[7]), float(c[8]), int(c[9]), float(c[10])))
def tags(X):
    Z = X @ Wd.T + b; Z -= Z.max(1, keepdims=True); P = np.exp(Z.astype(np.float64)); P /= P.sum(1, keepdims=True)
    Q = 1 / (1 + np.exp(-(X @ PW.T + PB)))
    T = np.zeros((len(X), len(cats)), bool)
    for j, (k, rows, st, pi, pt) in enumerate(cats):
        if rows and st > 0: T[:, j] |= P[:, rows].sum(1) >= st
        if pi >= 0 and pt > 0: T[:, j] |= Q[:, pi] >= pt
    return T
ta = tags(A['feats'][:n]); tb = tags(B['feats'])
both = (ta & tb).sum(); only_a = (ta & ~tb).sum(); only_b = (~ta & tb).sum()
print(f'tags in reference {ta.sum()}, other {tb.sum()}; same {both}, lost {only_a}, added {only_b}; photos with identical tag sets {np.mean((ta == tb).all(1)):.3f}')
for j in np.argsort(-((ta != tb).sum(0)))[:8]:
    print(' ', cats[j][0], 'lost', int((ta[:, j] & ~tb[:, j]).sum()), 'added', int((~ta[:, j] & tb[:, j]).sum()), 'of', int(ta[:, j].sum()))
