"""Candidate pair rules: recall on labelled pairs and false links among the 18 million unrelated pairs.

    python rules.py features.npz extra.npz
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2
from library import load, truth
from evaluate import quantized
from signals import popcount, structure
from common import WORK


def thumb(path, variant=None):
    from PIL import Image
    img = Image.open(path).convert('RGB')
    if variant:
        from variants import variants
        img = dict(variants(img, None))[variant]
    s = 512 / max(img.size)
    t = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.BILINEAR) if s < 1 else img
    k = max(1, int(min(t.width / 256, t.height / 256)))
    if k > 1: t = t.reduce(k)
    a = np.asarray(t.convert('L'))
    return a


_orb = cv2.ORB_create(nfeatures=500, scaleFactor=1.2, nlevels=6, edgeThreshold=15, patchSize=15)
_cache = {}


def orb(lib, i):
    if i in _cache: return _cache[i]
    pid, _, variant = lib['ids'][i].partition(':')
    g = thumb(os.path.join(WORK, 'img', pid + '.jpg'), variant or None)
    kp, des = _orb.detectAndCompute(g, None)
    _cache[i] = (np.float32([k.pt for k in kp]), des, g.shape)
    return _cache[i]


def inliers(lib, i, j):
    a, b = orb(lib, i), orb(lib, j)
    if a is None or b is None or a[1] is None or b[1] is None or len(a[0]) < 8 or len(b[0]) < 8: return 0, 0.0
    m = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(a[1], b[1], k=2)
    good = [x[0] for x in m if len(x) == 2 and x[0].distance < 0.8 * x[1].distance]
    if len(good) < 8: return len(good) // 4, 0.0
    pa = a[0][[g.queryIdx for g in good]]; pb = b[0][[g.trainIdx for g in good]]
    H, mask = cv2.findHomography(pa, pb, cv2.RANSAC, 4.0)
    if H is None: return 0, 0.0
    inl = int(mask.sum())
    # Area of the first picture covered by the inliers (bounding box), as a fraction.
    p = pa[mask.ravel() == 1]
    cover = ((p[:, 0].max() - p[:, 0].min()) * (p[:, 1].max() - p[:, 1].min())) / (a[2][0] * a[2][1]) if len(p) else 0
    return inl, float(cover)


def main():
    lib = load(sys.argv[1], extra_path=sys.argv[2])
    f = lib['f']
    emb = quantized(f['emb'])
    s16, _ = structure(f['g64'], 16)
    top = np.load(os.path.join(os.path.dirname(sys.argv[2]), 'unrelated_top.npz'))
    runs = {}
    for i, (r, l) in lib['moment'].items(): runs.setdefault(r, []).append(i)
    lab = {'same': [], 'related': [], 'different': []}
    for members in runs.values():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                lab[truth(lib, members[a], members[b])].append((members[a], members[b]))
    def F(P):
        P = np.array(P); a, b = P[:, 0], P[:, 1]
        return np.sum(emb[a] * emb[b], 1), np.sum(s16[a] * s16[b], 1)
    # ORB on labelled pairs and on unrelated candidates passing a loose prefilter.
    print('computing ORB…', flush=True)
    orbs = {k: np.array([inliers(lib, a, b) for a, b in v]) for k, v in lab.items()}
    pre = (top['cos'] >= 0.55)
    ua, ub = top['a'][pre], top['b'][pre]
    uorb = np.array([inliers(lib, a, b) for a, b in zip(ua, ub)])
    ucos, un16 = top['cos'][pre], top['n16'][pre]
    print(f'unrelated pairs with cos>=0.55: {len(ua)}')
    for k in lab:
        print(k, 'inliers median', np.median(orbs[k][:, 0]), '90th', np.percentile(orbs[k][:, 0], 90), 'max', orbs[k][:, 0].max())
    print('unrelated inliers 99th', np.percentile(uorb[:, 0], 99), 'max', uorb[:, 0].max())
    rules = {
        'old untimed (cos>=.82)': lambda c, n, o: c >= 0.82,
        'old burst (cos>=.70)': lambda c, n, o: c >= 0.70,
        'cos>=.7 & n16>=.7': lambda c, n, o: (c >= 0.7) & (n >= 0.7),
        'cos>=.6 & n16>=.75': lambda c, n, o: (c >= 0.6) & (n >= 0.75),
        'cos>=.65 & n16>=.6': lambda c, n, o: (c >= 0.65) & (n >= 0.6),
        'cos>=.6 & inl>=20': lambda c, n, o: (c >= 0.6) & (o[:, 0] >= 20),
        'cos>=.6 & inl>=30': lambda c, n, o: (c >= 0.6) & (o[:, 0] >= 30),
        'cos>=.55 & inl>=25 & cov>=.2': lambda c, n, o: (c >= 0.55) & (o[:, 0] >= 25) & (o[:, 1] >= 0.2),
        '(cos>=.7&n16>=.7) | (cos>=.6&inl>=25&cov>=.2)': lambda c, n, o: ((c >= 0.7) & (n >= 0.7)) | ((c >= 0.6) & (o[:, 0] >= 25) & (o[:, 1] >= 0.2)),
        '(cos>=.65&n16>=.65) | (cos>=.6&inl>=25&cov>=.2)': lambda c, n, o: ((c >= 0.65) & (n >= 0.65)) | ((c >= 0.6) & (o[:, 0] >= 25) & (o[:, 1] >= 0.2)),
    }
    for name, r in rules.items():
        res = {k: int(np.sum(r(*F(v), orbs[k]))) for k, v in lab.items()}
        u = int(np.sum(r(ucos, un16, uorb)))
        print(f"{name:52s} same {res['same']:3d}/{len(lab['same'])}  related {res['related']:3d}/{len(lab['related'])}  different {res['different']:2d}/{len(lab['different'])}  unrelated {u} (of 18M)")


if __name__ == '__main__':
    main()
