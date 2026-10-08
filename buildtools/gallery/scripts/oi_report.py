"""Choose per-category thresholds (OR of detector / classifier / probe) for a target precision on verified OI labels.
Reports 2-fold cross-validated precision/recall so the numbers aren't fitted to themselves."""
import sys, os, json, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from taxonomy import CATS
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
d = np.load(sys.argv[1]); target = float(sys.argv[2]) if len(sys.argv) > 2 else 0.9
probe = np.load(sys.argv[3]) if len(sys.argv) > 3 and os.path.exists(sys.argv[3]) else None
S = {'det': d['S_det'], 'wn': d['S_wn']}
if probe is not None: S['probe'] = probe['P']
Y = d['Y']; keys = list(d['keys'])
GRID = np.concatenate([np.linspace(0.02, 0.2, 10), np.linspace(0.22, 0.98, 39)])

def fbeta(p, r, b=0.5):
    return 0 if p + r == 0 else (1 + b * b) * p * r / (b * b * p + r)

def pr(scores, y, ts):
    hit = np.zeros(len(y), bool)
    for s, t in zip(scores, ts):
        if t is not None: hit |= s >= t
    tp = (hit & (y == 1)).sum(); fp = (hit & (y == 0)).sum()
    return tp / max(1, tp + fp), tp / max(1, (y == 1).sum()), tp, fp

def best(scores, y, target):
    """Thresholds (one per source, None = unused): best F0.5 (precision counts double) at precision >= target.
    Exhaustive over the grid for up to three sources (OR of the sources' decisions)."""
    n = len(scores)
    T = [None] + list(GRID)
    pos = y == 1; neg = y == 0; npos = max(1, pos.sum())
    H = [np.vstack([np.zeros(len(y), bool)] + [s >= t for t in GRID]) for s in scores]
    while len(H) < 3: H.append(np.zeros((1, len(y)), bool))
    bestv = (-1, None)
    for i0 in range(H[0].shape[0]):
        for i1 in range(H[1].shape[0]):
            base = H[0][i0] | H[1][i1]
            hits = base[None, :] | H[2]
            tp = (hits & pos).sum(1); fp = (hits & neg).sum(1)
            p = tp / np.maximum(1, tp + fp); r = tp / npos
            f = np.where(p + r > 0, 1.25 * p * r / np.maximum(1e-9, 0.25 * p + r), 0)
            f[(p < target) | (tp == 0)] = -1
            k = int(f.argmax())
            if f[k] > bestv[0]:
                ts = [T[i0], T[i1], T[k] if H[2].shape[0] > 1 else None][:n]
                bestv = (f[k], ts)
    return bestv[1] if bestv[0] > 0 else None

rng = np.random.RandomState(0)
out = {}
print(f"{'cat':11s} {'pos':>4s} {'neg':>4s} | {'AP det':>6s} {'AP wn':>6s} {'AP pr':>6s} | thresholds -> all-data P/R | 2-fold CV P/R")
for j, c in enumerate(CATS):
    key = c['key']; y = Y[:, j]; m = y >= 0
    npos, nneg = int((y == 1).sum()), int((y == 0).sum())
    srcs = []
    if c['det']: srcs.append('det')
    if len(c['wn']) and S['wn'][:, j].max() > 0: srcs.append('wn')
    if 'probe' in S and key in list(probe['keys']): srcs.append('probe')
    if not srcs or npos < 8:
        print(f"{key:11s} {npos:4d} {nneg:4d} | too little data ({srcs})"); out[key] = None; continue
    def col(s):
        if s == 'probe': return S['probe'][:, list(probe['keys']).index(key)]
        return S[s][:, j]
    scores = [col(s)[m] for s in srcs]; yy = y[m]
    aps = {}
    for s, sc in zip(srcs, scores):
        o = np.argsort(-sc); t = yy[o] == 1; tp = np.cumsum(t); prec = tp / np.arange(1, len(o) + 1)
        aps[s] = float((prec * t).sum() / max(1, t.sum()))
    ts = best(scores, yy, target)
    if ts is None:
        print(f"{key:11s} {npos:4d} {nneg:4d} | " + ' '.join(f"{aps.get(s, 0):6.3f}" if s in aps else '     -' for s in ('det', 'wn', 'probe')) + " | no threshold reaches the target")
        out[key] = None; continue
    P, R, tp, fp = pr(scores, yy, ts)
    # 2-fold CV
    cv = []
    idx = np.arange(len(yy)); rng.shuffle(idx); half = len(idx) // 2
    for a, bb in ((idx[:half], idx[half:]), (idx[half:], idx[:half])):
        t2 = best([s[a] for s in scores], yy[a], target)
        if t2 is None: cv.append((np.nan, 0.0)); continue
        p2, r2, _, _ = pr([s[bb] for s in scores], yy[bb], t2); cv.append((p2, r2))
    cvp = np.nanmean([x[0] for x in cv]); cvr = np.mean([x[1] for x in cv])
    out[key] = dict(src=srcs, t=[None if t is None else float(t) for t in ts], P=float(P), R=float(R), cvP=float(cvp), cvR=float(cvr), npos=npos, nneg=nneg, ap=aps)
    tstr = ' '.join(f"{s}={'-' if t is None else f'{t:.2f}'}" for s, t in zip(srcs, ts))
    print(f"{key:11s} {npos:4d} {nneg:4d} | " + ' '.join(f"{aps[s]:6.3f}" if s in aps else '     -' for s in ('det', 'wn', 'probe')) + f" | {tstr:30s} -> {P:.2f}/{R:.2f} | {cvp:.2f}/{cvr:.2f}")
json.dump(out, open(os.path.join(G, 'out', f'thresholds_{target}.json'), 'w'), indent=1)
