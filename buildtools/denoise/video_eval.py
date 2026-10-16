#!/usr/bin/env python3
"""video_eval.py <run.sh> <sidd dir> <work dir> <half: tune|check|all>[/step] <params>...

Measures the video denoiser's arithmetic (VideoDenoise, through harness/run.sh) on short test
videos made from real material: SIDD's clean phone photos (MIT licence) as the picture and SIDD's
real phone noise (each noisy photo minus its clean reference, a different noise sample in every
frame, from scenes of the same camera and ISO) on top. Four kinds of motion: a still scene, a slow
pan (0.4 px per frame), a fast pan (4 px per frame) and an object crossing a still background
(5 px per frame). Scenes alternate between the halves (settings chosen on one, checked on the other).

Each <params> is still,lo,hi,range,spatialStill,spatialMoving. "tune/2" uses every second scene
of the half (a quicker second round). Scores: PSNR against the clean
frames after the first 5 (the denoiser needs a few frames to settle), overall and on the moving
object (where a temporal filter leaves trails).
"""
import os, sys, zlib, subprocess, shutil
import numpy as np, cv2

run, sidd, work, half = sys.argv[1:5]
step = int(half.split('/')[1]) if '/' in half else 1
half = half.split('/')[0]
params = sys.argv[5:]
rows = [l.split('\t') for l in open(os.path.join(sidd, 'scenes.tsv')).read().split('\n') if l]
scenes = [r for i, r in enumerate(rows) if half == 'all' or (i % 2 == 0) == (half == 'tune')][::step]
N = 30; S = 384

def img(n, k, kind):
    return cv2.imread(os.path.join(sidd, f'{n}_{k}_{kind}.png'))[:, :, ::-1].astype(np.float64)

def shift(a, dx, dy):
    M = np.float32([[1, 0, -dx], [0, 1, -dy]])
    return cv2.warpAffine(a, M, (a.shape[1], a.shape[0]), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)

# Noise samples per (camera, ISO): residuals of all crops of those scenes.
groups = {}
for r in rows: groups.setdefault((r[1], r[2]), []).append(r[0])

def psnr(a, b, mask=None):
    d = (a.astype(np.float64) - b) ** 2
    if mask is not None: d = d[mask]
    return 10 * np.log10(255 ** 2 / max(d.mean(), 1e-9))

results = {p: {} for p in ['noisy'] + params}
for r in scenes:
    n = r[0]
    resid = [img(m, k, 'noisy') - img(m, k, 'gt') for m in groups[(r[1], r[2])] for k in range(4)]
    for kind in ('still', 'slowpan', 'fastpan', 'object'):
        rng = np.random.default_rng(zlib.crc32(f'{n}{kind}'.encode()))
        base = img(n, 0, 'gt')
        obj = img(n, 1, 'gt')[100:196, 100:196]
        clean = []
        for f in range(N):
            if kind == 'still': fr = base[:S, :S]
            elif kind == 'slowpan': fr = shift(base, 0.4 * f, 0.15 * f)[:S, :S]
            elif kind == 'fastpan': fr = shift(base, 4 * f, 1.0 * f)[:S, :S]
            else:
                fr = base[:S, :S].copy()
                x = 20 + 5 * f; y = 140
                fr[y:y + 96, x:x + 96] = obj
            clean.append(fr)
        d = os.path.join(work, f'{n}_{kind}'); shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
        noisy = []
        for f in range(N):
            rs = resid[rng.integers(len(resid))]
            oy, ox = rng.integers(0, 512 - S, 2)
            nz = np.clip(np.round(clean[f] + rs[oy:oy + S, ox:ox + S]), 0, 255).astype(np.uint8)
            noisy.append(nz); nz.tofile(os.path.join(d, f'f{f:03d}.rgb'))
        out = os.path.join(d, 'out')
        subprocess.run([run, 'video', d, str(S), str(S), str(N), out] + params, capture_output=True, check=True)
        mask = None
        if kind == 'object':
            mask = np.zeros((S, S), bool)
        key = f'{n}_{kind}'
        for p in ['noisy'] + params:
            ps = []; pm = []
            for f in range(5, N):
                o = noisy[f] if p == 'noisy' else np.fromfile(os.path.join(out, f'p{params.index(p)}', f'f{f:03d}.rgb'), np.uint8).reshape(S, S, 3)
                ps.append(psnr(o, clean[f]))
                if kind == 'object':
                    m = np.zeros((S, S, 3), bool); x = 20 + 5 * f; m[140:236, max(0, x - 10):x + 106] = True
                    pm.append(psnr(o, clean[f], m))
            results[p][key] = (np.mean(ps), np.mean(pm) if pm else None)
        shutil.rmtree(d)
    print(n, flush=True)

for kind in ('still', 'slowpan', 'fastpan', 'object'):
    print(f'== {kind}')
    for p in ['noisy'] + params:
        v = [x for k, x in results[p].items() if k.endswith(kind)]
        line = f'  {p:32s} PSNR {np.mean([a for a, _ in v]):6.2f}'
        if kind == 'object': line += f'   on the object {np.mean([b for _, b in v]):6.2f}'
        print(line)
print('== all')
for p in ['noisy'] + params:
    print(f'  {p:32s} PSNR {np.mean([a for a, _ in results[p].values()]):6.2f}')
