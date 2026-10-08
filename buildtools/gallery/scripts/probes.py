"""Train linear probes (logistic regression on 21k features) for scenes the classifier has no class for.
Train: OI train images (verified positives vs verified negatives + random images not labelled positive).
Output: probe weights with standardisation folded in, and probe scores on OI validation images."""
import sys, os, json, numpy as np
from scipy.optimize import minimize
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
pick = json.load(open(os.path.join(G, 'out/probe_pick.json')))
T = np.load(sys.argv[2] if len(sys.argv) > 2 else os.path.join(G, 'out/oitrain_feat_center.npz'))
V = np.load(sys.argv[1] if len(sys.argv) > 1 else os.path.join(G, 'out/oi_feat_center.npz'))
tid = {n[:-4]: k for k, n in enumerate(T['names'])}
okT = T['ok'] if 'ok' in T else np.ones(len(tid), bool)
X = T['feats']
mu = X[okT].mean(0); sd = X[okT].std(0) + 1e-6
PROBES = [k for k in pick if not k.startswith('_')]
rand = [r for r in pick['_random'] if r in tid and okT[tid[r]]]
rpos = pick['_random_pos']

def fit(Xs, y, l2):
    w0 = np.zeros(Xs.shape[1] + 1)
    pw = (y == 0).sum() / max(1, (y == 1).sum())   # balance classes
    sw = np.where(y == 1, pw, 1.0)
    def f(w):
        z = Xs @ w[:-1] + w[-1]
        p = 1 / (1 + np.exp(-z))
        ll = -(sw * (y * np.log(p + 1e-9) + (1 - y) * np.log(1 - p + 1e-9))).sum() / sw.sum()
        g = Xs.T @ (sw * (p - y)) / sw.sum()
        gb = (sw * (p - y)).sum() / sw.sum()
        return ll + l2 * (w[:-1] ** 2).sum(), np.concatenate([g + 2 * l2 * w[:-1], [gb]])
    r = minimize(f, w0, jac=True, method='L-BFGS-B', options=dict(maxiter=500))
    return r.x

def auc(s, y):
    o = np.argsort(s); r = np.empty(len(s)); r[o] = np.arange(1, len(s) + 1)
    n1 = (y == 1).sum(); n0 = (y == 0).sum()
    return (r[y == 1].sum() - n1 * (n1 + 1) / 2) / max(1, n1 * n0)

W, B, report = [], [], {}
for p in PROBES:
    P = [i for i in pick[p]['pos'] if i in tid and okT[tid[i]]]
    N = [i for i in pick[p]['neg'] if i in tid and okT[tid[i]]] + [r for r in rand if p not in rpos.get(r, [])]
    N = [i for i in dict.fromkeys(N) if i not in set(P)]
    xi = [tid[i] for i in P] + [tid[i] for i in N]
    y = np.array([1] * len(P) + [0] * len(N), float)
    Xs = (X[xi] - mu) / sd
    # pick L2 by 3-fold CV on train
    rng = np.random.RandomState(1); perm = rng.permutation(len(y)); folds = np.array_split(perm, 3)
    best = None
    for l2 in (1e-3, 1e-2, 3e-2, 0.1, 0.3):
        a = []
        for k in range(3):
            te = folds[k]; tr = np.concatenate([folds[j] for j in range(3) if j != k])
            w = fit(Xs[tr], y[tr], l2); a.append(auc(Xs[te] @ w[:-1] + w[-1], y[te]))
        if best is None or np.mean(a) > best[0]: best = (np.mean(a), l2)
    w = fit(Xs, y, best[1])
    # fold standardisation into the weights: z = ((x - mu)/sd) @ w + b = x @ (w/sd) + (b - mu @ (w/sd))
    wf = w[:-1] / sd; bf = w[-1] - mu @ wf
    W.append(wf); B.append(bf)
    report[p] = dict(pos=len(P), neg=len(N), l2=best[1], cv_auc=round(float(best[0]), 4))
    print(p, report[p], flush=True)
W = np.array(W, np.float32); B = np.array(B, np.float32)
okV = V['ok'] if 'ok' in V else np.ones(len(V['names']), bool)
Pv = 1 / (1 + np.exp(-(V['feats'] @ W.T + B)))
Pv[~okV] = 0
np.savez(os.path.join(G, 'out/probes.npz'), W=W, B=B, keys=np.array(PROBES), P=Pv.astype(np.float32), names=V['names'])
json.dump(report, open(os.path.join(G, 'out/probes_report.json'), 'w'), indent=1)
print('saved probes', W.shape)
