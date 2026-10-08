"""Full 21k head in 4-bit (group-wise scales): error of category sums vs fp32."""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from taxonomy import CATS
import tax_check
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = np.load(os.path.join(G, 'models/b3_21k/head_W.npy')); b = np.load(os.path.join(G, 'models/b3_21k/head_b.npy'))
F = np.load(os.path.join(G, 'out/oi_feat_center.npz')); X = F['feats'][F['ok']][1::3]
cls = [np.array(tax_check.classes(c), dtype=np.int64) for c in CATS]
def lse(z): m = z.max(1, keepdims=True); return (m + np.log(np.exp(z - m).sum(1, keepdims=True)))[:, 0]
def cat_sums(Z):
    P = np.exp(Z - lse(Z)[:, None]); return np.stack([P[:, c].sum(1) if len(c) else np.zeros(len(P)) for c in cls], 1)
exact = cat_sums(X @ W + b)
D, C = W.shape
for bits, g in ((4, 64), (4, 32), (8, 1536)):
    q = 2 ** (bits - 1) - 1
    Wg = W.T.reshape(C, D // g, g)
    sc = np.abs(Wg).max(2, keepdims=True) / q
    Wq = np.round(Wg / np.maximum(sc, 1e-12)).clip(-q, q) * sc
    Z = X @ Wq.reshape(C, D).T + b
    err = np.abs(cat_sums(Z) - exact)
    size = C * D * bits / 8 / 1e6 + C * (D // g) * 2 / 1e6
    print(f'{bits}-bit group {g}: {size:.1f} MB  mean {err.mean():.5f} p99 {np.quantile(err, 0.99):.4f} p999 {np.quantile(err, 0.999):.4f} max {err.max():.4f}')
