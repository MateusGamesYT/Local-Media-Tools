#!/usr/bin/env python3
"""raw_eval.py <run.sh> <work dir> <high-ISO dir> <clean RAW files...> [options]

Measures the app's RAW denoiser (RawDenoiser, through harness/run.sh) on real camera RAW files
(CC0, raw.pixls.us):

  noise levels  the noise the app measures in real high-ISO files, as shares of each file's
                white−black range (median over the four colour-filter sites);
  clean         1024×1024 crops of low-ISO files' picture area (2 per file, fixed seed);
  noisy         each crop with Poisson-Gaussian noise at each level, scaled to its camera's range;
  score         PSNR of (value − black) / (white − black) against the clean crop, linear and
                through a 1/2.2 display curve (where the eye sees shadow noise).

Options:
  --settings h,template,search[;...]   settings to compare (default 1.0,7,21)
  --half tune|check|all                files with even/odd positions (sorted by name), or all
  --estimator                          also report how well the app's measured noise matches the added noise
"""
import json, os, subprocess, sys, zlib, concurrent.futures as cf
import numpy as np, rawpy

args = sys.argv[1:]
run, work, hiso = args[:3]
opts = {}
files = []
i = 3
while i < len(args):
    a = args[i]
    if a.startswith('--'):
        if a == '--estimator': opts[a] = True; i += 1; continue
        opts[a] = args[i + 1]; i += 2
    else: files.append(a); i += 1
settings = [tuple(s.split(',')) for s in opts.get('--settings', '1.0,7,21').split(';')]
half = opts.get('--half', 'all')
files = sorted(files)
if half != 'all': files = [f for k, f in enumerate(files) if (k % 2 == 0) == (half == 'tune')]
os.makedirs(work, exist_ok=True)

def sh(*a):
    r = subprocess.run(a, capture_output=True, text=True)
    if r.returncode != 0: raise RuntimeError(r.stderr[-3000:])
    return [l for l in r.stdout.strip().split('\n') if not l.startswith('Picked up')]

def site_black(r):
    pat = r.raw_pattern; bl = r.black_level_per_channel
    return [bl[pat[k >> 1][k & 1]] for k in range(4)]

# 1. Noise levels from real high-ISO files.
levels = []
for f in sorted(os.listdir(hiso)):
    p = os.path.join(hiso, f)
    out = sh(run, 'noise', p)[0].split('\t')
    if out[1] == 'UNSUPPORTED': continue
    with rawpy.imread(p) as r: B = site_black(r); W = r.white_level
    sl = []; rd = []
    for k, m in enumerate(out[2:6]):
        if m == '-': continue
        a, c = map(float, m.split())
        sl.append(a / (W - B[k])); rd.append((c + a * B[k]) / (W - B[k]) ** 2)
    if sl:
        levels.append((f, float(np.median(sl)), max(float(np.median(rd)), 0.0)))
for name, a, r2 in levels:
    print(f'level {name}: slope {a:.2e} of range per unit, read noise {np.sqrt(r2) * 100:.3f} % of range')

# 2. Clean crops.
rng = np.random.default_rng(5)
crops = []
for f in files:
    with rawpy.imread(f) as r:
        raw = r.raw_image.copy(); B = site_black(r); W = r.white_level
        s = r.sizes
    for c in range(2):
        y = s.top_margin + 2 * rng.integers(0, (s.height - 1024) // 2)
        x = s.left_margin + 2 * rng.integers(0, (s.width - 1024) // 2)
        crops.append((os.path.basename(f), c, raw[y:y + 1024, x:x + 1024].astype(np.int64), B, W))

def norm(img, B, W):
    b = np.empty(img.shape); b[0::2, 0::2] = B[0]; b[0::2, 1::2] = B[1]; b[1::2, 0::2] = B[2]; b[1::2, 1::2] = B[3]
    return np.clip((img - b) / (W - b), 0, 1)

def psnr(a, b): return 10 * np.log10(1.0 / max(((a - b) ** 2).mean(), 1e-12))

def job(arg):
    (name, c, clean, B, W), (lname, a_n, r2_n), li = arg
    seed = zlib.crc32(f'{name}/{c}/{lname}'.encode())
    g = np.random.default_rng(seed)
    b = np.empty(clean.shape); b[0::2, 0::2] = B[0]; b[0::2, 1::2] = B[1]; b[1::2, 0::2] = B[2]; b[1::2, 1::2] = B[3]
    rng_ = W - b
    var = a_n * rng_ * np.maximum(clean - b, 0) + r2_n * rng_ ** 2
    noisy = np.clip(np.round(clean + g.standard_normal(clean.shape) * np.sqrt(var)), 0, W).astype(np.uint16)
    tag = f'{name}_{c}_{li}'
    inp = os.path.join(work, tag + '.u16'); noisy.tofile(inp)
    gt = norm(clean, B, W); nz = norm(noisy.astype(np.int64), B, W)
    res = {'file': name, 'crop': c, 'level': lname, 'noisy': psnr(nz, gt), 'noisy_disp': psnr(nz ** (1 / 2.2), gt ** (1 / 2.2)), 'out': {}}
    if opts.get('--estimator'):
        res['true'] = [float(a_n * (W - B[k])), float(r2_n * (W - B[k]) ** 2 - a_n * (W - B[k]) * B[k])]
    for st in settings:
        outp = os.path.join(work, tag + '.out.u16')
        line = sh(run, 'crop', inp, '1024', '1024', str(W), *st, outp)[0].split('\t')
        o = np.fromfile(outp, '<u2').reshape(clean.shape).astype(np.int64)
        on = norm(o, B, W)
        res['out'][','.join(st)] = {'psnr': psnr(on, gt), 'disp': psnr(on ** (1 / 2.2), gt ** (1 / 2.2)), 'ms': int(line[0]), 'models': line[1:]}
        os.remove(outp)
    os.remove(inp)
    return res

jobs = [(cr, lv, li) for cr in crops for li, lv in enumerate(levels)]
with cf.ProcessPoolExecutor(int(opts.get('--jobs', '4'))) as ex:
    results = list(ex.map(job, jobs))
json.dump(results, open(os.path.join(work, f'results_{half}.json'), 'w'))
print(f'{len(crops)} crops × {len(levels)} levels ({half})')
print(f'noisy: PSNR {np.mean([r["noisy"] for r in results]):.2f}, display {np.mean([r["noisy_disp"] for r in results]):.2f}')
for st in settings:
    k = ','.join(st)
    print(f'h,template,search {k}: PSNR {np.mean([r["out"][k]["psnr"] for r in results]):.2f}, display {np.mean([r["out"][k]["disp"] for r in results]):.2f}, '
          f'{np.mean([r["out"][k]["ms"] for r in results]):.0f} ms per 1 MP crop')
if opts.get('--estimator'):
    errs = []
    for r in results:
        m = r['out'][','.join(settings[0])]['models']
        a_est = np.median([float(x.split()[0]) for x in m if x != '-'])
        errs.append(a_est / r['true'][0])
    print(f'estimated slope / added slope: median {np.median(errs):.2f}, 10-90% {np.percentile(errs, 10):.2f}-{np.percentile(errs, 90):.2f}')
