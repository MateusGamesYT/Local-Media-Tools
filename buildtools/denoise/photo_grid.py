#!/usr/bin/env python3
"""photo_grid.py <sidd dir> <out.jsonl> [h values]

The photo denoiser's filter (OpenCV's coloured non-local means, template 7, search 21) on real phone
noise: for every SIDD crop (sidd_fetch.py) the PSNR against its clean reference at each luma strength
h and colour multiplier, plus the noisy PSNR. Scenes alternate between the halves "even" (the rule is
fitted there) and "odd" (checked there). Appends one line per crop and skips crops already in the
file, so it survives restarts. Default h: 2 … 24; the extension run used 28 34 40 48 on the crops
whose best h was 17 or more (it helped 5 of 58 crops, by at most 0.3 dB, so 24 is the cap).
"""
import cv2, numpy as np, os, sys, json, multiprocessing as mp
d = sys.argv[1]; out = sys.argv[2]
HS = [int(x) for x in sys.argv[3].split(',')] if len(sys.argv) > 3 else [2, 3, 4, 5, 6, 8, 10, 12, 14, 17, 20, 24]
HC = [0.75, 1.0, 1.5, 2.0, 3.0]
rows = [l.split('\t') for l in open(f'{d}/scenes.tsv').read().split('\n') if l]
def psnr(a, b): return 10 * np.log10(255 ** 2 / max(((a.astype(np.float64) - b) ** 2).mean(), 1e-9))
def work(arg):
    i, n, k = arg
    cv2.setNumThreads(1)
    a = cv2.imread(f'{d}/{n}_{k}_noisy.png'); g = cv2.imread(f'{d}/{n}_{k}_gt.png')
    res = {'scene': n, 'crop': k, 'half': 'even' if i % 2 == 0 else 'odd', 'noisy': psnr(a, g), 'grid': {}}
    for h in HS:
        for m in HC: res['grid'][f'{h},{m}'] = psnr(cv2.fastNlMeansDenoisingColored(a, None, h, h * m, 7, 21), g)
    return res
if __name__ == '__main__':
    done = set()
    if os.path.exists(out):
        for l in open(out):
            try: r = json.loads(l); done.add((r['scene'], r['crop']))
            except Exception: pass
    jobs = [(i, r[0], k) for i, r in enumerate(rows) for k in range(4) if (r[0], k) not in done]
    with mp.Pool(4) as p, open(out, 'a') as f:
        for r in p.imap_unordered(work, jobs): f.write(json.dumps(r) + '\n'); f.flush()
