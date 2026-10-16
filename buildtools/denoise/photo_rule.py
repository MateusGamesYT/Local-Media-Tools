#!/usr/bin/env python3
"""photo_rule.py <sidd dir> <grid.jsonl> [<grid_ext.jsonl>] <clean crops dir>

Fits and checks the photo denoiser's rule (denoise/core/PhotoDenoiser.kt): strength
h = K × (σL + σa + σb) / 2 × confidence, capped at 24, colour strength = h. σ per CIELAB channel is
the 10 % quantile of the 8×8 blocks' Immerkær estimates (blocks touching black or white clipping left
out), scaled by that quantile's value for pure Gaussian noise; confidence = 1 while the quietest
blocks are as alike as noise makes them (spread = 10 % / 2 % quantile ≤ s0), else (s0 / spread)^γ.

For each (q, s0, γ) K is fitted on the even SIDD scenes, then reported on the odd ones (not used for
fitting) and on clean crops (clean_crops.py): how much the photo changes (PSNR of the result against
the photo itself; a change below about 35 dB is visible smoothing on fine detail).
"""
import cv2, numpy as np, json, sys, os, itertools
sidd, grid = sys.argv[1], sys.argv[2]
ext = sys.argv[3] if len(sys.argv) > 4 else None
cleand = sys.argv[-1]
def imm(B):
    r = (B[:, :-2, :-2] - 2 * B[:, :-2, 1:-1] + B[:, :-2, 2:] - 2 * B[:, 1:-1, :-2] + 4 * B[:, 1:-1, 1:-1] - 2 * B[:, 1:-1, 2:] + B[:, 2:, :-2] - 2 * B[:, 2:, 1:-1] + B[:, 2:, 2:])
    return (r ** 2).mean(axis=(1, 2)) / 36
def blocks(p, b=8):
    h, w = p.shape; ny, nx = h // b, w // b
    return p[:ny * b, :nx * b].reshape(ny, b, nx, b).transpose(0, 2, 1, 3).reshape(-1, b, b).astype(np.float64)
Z = np.sort(imm(np.random.default_rng(1234).standard_normal((40000, 8, 8))))
SC = {q: Z.mean() / Z[int(q * len(Z))] for q in (0.05, 0.10)}
def feats(a):
    lab = cv2.cvtColor(a, cv2.COLOR_BGR2Lab)
    BL = blocks(lab[:, :, 0]); keep = (BL.max(axis=(1, 2)) < 254.5) & (BL.min(axis=(1, 2)) > 0.5)
    r = {}
    for c, nm in ((0, 'L'), (1, 'a'), (2, 'b')):
        v = np.sort(imm(blocks(lab[:, :, c]))[keep]) if keep.sum() >= 64 else np.zeros(1)
        for q in SC: r[f'{nm}{q}'] = float(np.sqrt(v[int(q * len(v))] * SC[q]))
        if c == 0: r['spread'] = float(v[int(.1 * len(v))] / max(v[int(.02 * len(v))], 1e-9)) if v[int(.1 * len(v))] > 0 else 1.0
    return r
G = [json.loads(l) for l in open(grid)]
E = {(e['scene'], e['crop']): e['grid'] for e in map(json.loads, open(ext))} if ext else {}
HS = [2, 3, 4, 5, 6, 8, 10, 12, 14, 17, 20, 24]
sidd_rows = []
for g in G:
    f = feats(cv2.imread(f"{sidd}/{g['scene']}_{g['crop']}_noisy.png")); f.update(g=g); sidd_rows.append(f)
clean = []
for fn in sorted(os.listdir(cleand)):
    img = cv2.imread(os.path.join(cleand, fn)); f = feats(img); f.update(name=fn, img=img); clean.append(f)
def score(r, h):
    g = r['g']; hs = list(HS); ps = [g['grid'][f'{x},1.0'] for x in HS]
    e = E.get((g['scene'], g['crop']))
    if e:
        for x in (28, 34, 40, 48): hs.append(x); ps.append(e[f'{x},1.0'])
    h = min(max(h, 0), hs[-1])
    if h < 2: return g['noisy'] + (ps[0] - g['noisy']) * h / 2
    return float(np.interp(h, hs, ps))
CH = {}
def change(r, h):
    h = round(h * 4) / 4
    if (r['name'], h) not in CH:
        o = cv2.fastNlMeansDenoisingColored(r['img'], None, h, h, 7, 21) if h > 0.05 else r['img']
        CH[(r['name'], h)] = 10 * np.log10(255 ** 2 / max(((o.astype(float) - r['img']) ** 2).mean(), 1e-9))
    return CH[(r['name'], h)]
def feat(r, q, s0, gam):
    f = 1.0 if gam == 0 or r['spread'] <= s0 else (s0 / r['spread']) ** gam
    return (r[f'L{q}'] + r[f'a{q}'] + r[f'b{q}']) / 2 * f
even = [r for r in sidd_rows if r['g']['half'] == 'even']; odd = [r for r in sidd_rows if r['g']['half'] == 'odd']
print('noisy: even %.2f odd %.2f' % (np.mean([r['g']['noisy'] for r in even]), np.mean([r['g']['noisy'] for r in odd])))
for q, s0, gam in [(0.1, 1.5, 0)] + list(itertools.product((0.1, 0.05), (1.5, 1.6, 1.7), (2, 4, 8, 16))):
    K = max(np.arange(2, 12.01, 0.25), key=lambda K: np.mean([score(r, min(K * feat(r, q, s0, gam), 24)) for r in even]))
    pe = np.mean([score(r, min(K * feat(r, q, s0, gam), 24)) for r in even]); po = np.mean([score(r, min(K * feat(r, q, s0, gam), 24)) for r in odd])
    hc = [min(K * feat(r, q, s0, gam), 24) for r in clean]; ch = np.array([change(r, h) for r, h in zip(clean, hc)])
    print(f'q {q} s0 {s0} gamma {gam:2d}: K {K:.2f} even {pe:.2f} odd {po:.2f} | clean: h max {max(hc):5.2f}, change min {ch.min():.1f} dB, below 35 dB {int((ch < 35).sum())}/{len(ch)}', flush=True)
