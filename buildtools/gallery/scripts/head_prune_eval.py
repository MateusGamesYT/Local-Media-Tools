"""Tag agreement vs the exact fp32 head: full 4-bit head, and pruned 4-bit heads (+ linear estimate of the rest)."""
import sys, os, numpy as np
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = sys.argv[1]
W = np.load(os.path.join(G, 'models/b3_21k/head_W.npy')).astype(np.float32); b = np.load(os.path.join(G, 'models/b3_21k/head_b.npy')).astype(np.float32)
F = np.load(os.path.join(G, 'out/oi_feat_center.npz')); X = F['feats'][F['ok']]
fit, test = X[0::2], X[1::2]
cats = []
for line in open(os.path.join(APP, 'gallery/categories.tsv')):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t'); ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    if c[7].strip() and float(c[8]) > 0: cats.append((c[0], np.array(ints(c[7])), float(c[8])))
U0 = np.unique(np.concatenate([r for _, r, _ in cats]))
def lse(z): m = z.max(1, keepdims=True); return (m + np.log(np.exp(z - m).sum(1, keepdims=True)))[:, 0]
def q4(Wsub, GS=64):
    D, C = Wsub.shape; Wg = Wsub.T.reshape(C, D // GS, GS)
    sc = (np.abs(Wg).max(2) / 7.0).astype(np.float16).astype(np.float32)
    return (np.round(Wg / np.maximum(sc[:, :, None], 1e-12)).clip(-7, 7) * sc[:, :, None]).reshape(C, D).T
def tagsets(logp_rows, U):
    # logp_rows: log-probabilities for the classes in U (sorted)
    T = np.zeros((logp_rows.shape[0], len(cats)), bool)
    for j, (k, rows, t) in enumerate(cats):
        T[:, j] = np.exp(logp_rows[:, np.searchsorted(U, rows)]).sum(1) >= t
    return T
Zt = test @ W + b
ref = tagsets(Zt[:, U0] - lse(Zt)[:, None], U0)
print('scene-sourced tags in reference:', ref.sum(), 'over', len(test), 'photos;', len(cats), 'categories use the classifier;', len(U0), 'classes')
def report(name, T, mb):
    print(f'{name:38s} {mb:5.1f} MB  lost {int((ref & ~T).sum()):4d} added {int((~ref & T).sum()):4d}  photos identical {np.mean((ref == T).all(1)):.4f}')
Zq = test @ q4(W) + b
report('full head, 4-bit', tagsets(Zq[:, U0] - lse(Zq)[:, None], U0), 21843 * 768 / 1e6 + 1.1)
Zf = fit @ W + b; Pf = np.exp(Zf - lse(Zf)[:, None])
freq = np.bincount(np.argsort(-Pf, 1)[:, :10].ravel(), minlength=W.shape[1])
for mf in (None, 3, 2, 1):
    U = U0 if mf is None else np.union1d(U0, np.where(freq >= mf)[0])
    rest = np.setdiff1d(np.arange(W.shape[1]), U)
    A = np.hstack([fit, np.ones((len(fit), 1))]); y = lse(Zf[:, rest])
    coef = np.linalg.solve(A.T @ A + 1e-2 * len(A) * np.eye(A.shape[1]), A.T @ y)
    zk = test @ q4(W[:, U]) + b[U]
    r = np.hstack([test, np.ones((len(test), 1))]) @ coef
    tot = np.logaddexp(lse(zk), r)
    report(f'pruned to {len(U)} classes, 4-bit', tagsets(zk - tot[:, None], U), len(U) * 768 / 1e6 + len(U) * 54 / 1e6)
