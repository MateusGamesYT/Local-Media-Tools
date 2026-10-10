"""How well each signal separates true pairs from false ones, per kind of pair: re-saved copies (by
how they were changed), same moment, related shots, other shots of a run, and unrelated photos
(millions of pairs — the rates that decide how often a large library gets a false group).

    python signals.py features.npz extra.npz
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2
from library import load, truth
from evaluate import quantized


def popcount(x):
    x = x.astype(np.uint64)
    c = np.zeros(x.shape, np.int32)
    for b in range(64):
        c += ((x >> np.uint64(b)) & np.uint64(1)).astype(np.int32)
    return c


def structure(g64, size):
    """Zero-mean, unit-norm grey thumbnails at [size]×[size] (area-averaged)."""
    v = np.stack([cv2.resize(g, (size, size), interpolation=cv2.INTER_AREA) for g in g64]).reshape(len(g64), -1).astype(np.float32)
    v -= v.mean(axis=1, keepdims=True)
    n = np.linalg.norm(v, axis=1, keepdims=True)
    return v / np.maximum(n, 1e-6), n[:, 0] / np.sqrt(v.shape[1])  # vectors, contrast (std)


def colour(c8):
    v = c8.reshape(len(c8), -1).astype(np.float32) / 255
    return v


def main():
    lib = load(sys.argv[1], extra_path=sys.argv[2])
    f = lib['f']
    n = len(lib['ids'])
    emb = quantized(f['emb'])
    s16, std16 = structure(f['g64'], 16)
    s32, std32 = structure(f['g64'], 32)
    col = colour(f['c8'])
    dh = f['dhash'].astype(np.uint64)
    kinds = lib['kinds']
    # Labelled pairs.
    pairs = {}
    for i in range(n):
        if kinds[i] == 'copy':
            o = lib['idx'][lib['of'][i]]
            pairs.setdefault('copy:' + lib['ids'][i].split(':')[1], []).append((o, i))
    runs = {}
    for i, (r, l) in lib['moment'].items(): runs.setdefault(r, []).append(i)
    for r, members in runs.items():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                pairs.setdefault(truth(lib, members[a], members[b]), []).append((members[a], members[b]))
    def feats(P):
        P = np.array(P)
        a, b = P[:, 0], P[:, 1]
        return dict(cos=np.sum(emb[a] * emb[b], 1), dh=popcount(dh[a] ^ dh[b]), n16=np.sum(s16[a] * s16[b], 1),
                    n32=np.sum(s32[a] * s32[b], 1), col=np.abs(col[a] - col[b]).mean(1), lowc=np.minimum(std16[a], std16[b]))
    print('labelled pairs (median and 10th / 90th percentile):')
    for k in sorted(pairs):
        F = feats(pairs[k])
        print(f"  {k:16s} n={len(pairs[k]):4d}  " + '  '.join(f"{m} {np.median(v):.2f} [{np.percentile(v, 10):.2f}..{np.percentile(v, 90):.2f}]" for m, v in F.items()))
    # Unrelated: all pairs among pool and extra photos.
    orig = np.array([i for i in range(n) if kinds[i] in ('pool', 'extra')])
    iu = np.triu_indices(len(orig), 1)
    E = emb[orig]; S16 = s16[orig]; S32 = s32[orig]
    cos = (E @ E.T)[iu]
    n16 = (S16 @ S16.T)[iu]
    n32 = (S32 @ S32.T)[iu]
    print(f'unrelated pairs: {len(cos)}')
    for name, cond in [('cos>=0.70', cos >= 0.70), ('cos>=0.82', cos >= 0.82), ('n16>=0.80', n16 >= 0.80), ('n16>=0.90', n16 >= 0.90),
                       ('n32>=0.80', n32 >= 0.80), ('cos>=0.6&n16>=0.6', (cos >= 0.6) & (n16 >= 0.6)),
                       ('cos>=0.7&n16>=0.7', (cos >= 0.7) & (n16 >= 0.7)), ('cos>=0.6&n16>=0.75', (cos >= 0.6) & (n16 >= 0.75)),
                       ('cos>=0.5&n16>=0.8', (cos >= 0.5) & (n16 >= 0.8))]:
        print(f'  {name:22s} {np.mean(cond):.2e} ({np.sum(cond)})')
    np.savez(os.path.join(os.path.dirname(sys.argv[2]), 'unrelated_top.npz'), cos=cos[(cos >= 0.55) | (n16 >= 0.6)], n16=n16[(cos >= 0.55) | (n16 >= 0.6)],
             n32=n32[(cos >= 0.55) | (n16 >= 0.6)], a=orig[iu[0][(cos >= 0.55) | (n16 >= 0.6)]], b=orig[iu[1][(cos >= 0.55) | (n16 >= 0.6)]])


if __name__ == '__main__':
    main()
