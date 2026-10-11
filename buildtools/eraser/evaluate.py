#!/usr/bin/env python3
"""Measures the Magic Eraser's choices on real photos: an object-shaped hole (an AIM-500 matte, so the
shapes are real people, animals and things) is cut into a photo whose real content behind it is known,
filled the way the app fills it (edit/MagicEraser.kt, Retouch.erase: a window around the hole scaled to
512 px for MI-GAN, the result scaled back, feathered), and compared with what was really there.

    evaluate.py <photos dir> <aim dir> <migan.onnx> [--factors 1.5,1.9,2.5] [--models 512,1024] [--count 60]

MI-GAN through ONNX Runtime (the author's export, the same Places2 512 model the app converts to
TensorFlow Lite). Scores inside the hole: PSNR, SSIM (8×8 windows) and detail (gradient energy of
the fill ÷ the real content's; 1 = as detailed, lower = blurrier).
"""
import glob
import os
import random
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageFilter

MODEL = 512


def context_for(W, H, l, t, r, b, factor, pad=48, model=MODEL):
    """MaskOps.contextFor with the window factor as a parameter (the app uses 1.9)."""
    bw, bh = r - l, b - t
    side = round(max(max(bw, bh) * factor + pad, model))
    w = min(W, max(side, bw + 16)); h = min(H, max(side, bh + 16))
    cx, cy = (l + r) / 2, (t + b) / 2
    left = min(max(round(cx - w / 2), 0), W - w); top = min(max(round(cy - h / 2), 0), H - h)
    return left, top, w, h, model / max(w, h)


def fill(sess, photo, hole, factor, model=MODEL):
    """photo: HxWx3 uint8; hole: HxW bool (grown as the app grows the brush). Returns the filled photo.
    [model]: the size the window is scaled to for MI-GAN (the app: 512)."""
    MODEL = model
    H, W = hole.shape
    ys, xs = np.nonzero(hole)
    l, t, r, b = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    left, top, w, h, s = context_for(W, H, l, t, r, b, factor, model=model)
    sw, sh = max(1, round(w * s)), max(1, round(h * s))
    win = Image.fromarray(photo[top:top + h, left:left + w])
    # The app decodes at a power-of-two subsample, then scales bilinearly.
    sample = 1
    while max(w, h) / (sample * 2) >= MODEL: sample *= 2
    if sample > 1: win = win.reduce(sample)
    small = np.asarray(win.resize((sw, sh), Image.BILINEAR))
    mwin = Image.fromarray((hole[top:top + h, left:left + w] * 255).astype(np.uint8)).resize((sw, sh), Image.BILINEAR)
    mhole = np.asarray(mwin) > 40
    img = np.zeros((MODEL, MODEL, 3), np.uint8); img[:sh, :sw] = small
    img[:sh, sw:] = small[:, -1:]; img[sh:, :] = img[sh - 1:sh, :]
    known = np.full((MODEL, MODEL), 255, np.uint8); known[:sh, :sw][mhole] = 0
    if 'lama' in sess._model_path:
        # LaMa (for comparison): RGB 0..1 and the hole as 1.
        o = sess.run(None, {'image': (img.astype(np.float32) / 255).transpose(2, 0, 1)[None],
                            'mask': (known == 0).astype(np.float32)[None, None]})[0][0].transpose(1, 2, 0)
        out = np.clip(o * (255 if o.max() <= 1.5 else 1), 0, 255).round().astype(np.uint8)
    else:
        out = sess.run(None, {'image': img.transpose(2, 0, 1)[None], 'mask': known[None, None]})[0][0].transpose(1, 2, 0)
    up = np.asarray(Image.fromarray(out[:sh, :sw]).resize((w, h), Image.BILINEAR))
    res = photo.copy()
    region = res[top:top + h, left:left + w]
    hw = hole[top:top + h, left:left + w]
    region[hw] = up[hw]
    return res


def ssim(a, b):
    a = a.astype(np.float64); b = b.astype(np.float64)
    def mean8(x):
        k = np.ones((8, 8)) / 64
        from numpy.lib.stride_tricks import sliding_window_view
        return sliding_window_view(x, (8, 8))[::4, ::4].mean(axis=(-1, -2))
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    out = []
    for ch in range(3):
        x, y = a[..., ch], b[..., ch]
        mx, my = mean8(x), mean8(y)
        vx, vy = mean8(x * x) - mx * mx, mean8(y * y) - my * my
        cxy = mean8(x * y) - mx * my
        out.append(((2 * mx * my + c1) * (2 * cxy + c2) / ((mx * mx + my * my + c1) * (vx + vy + c2))).mean())
    return float(np.mean(out))


def detail(x):
    g = x.astype(np.float64).mean(-1)
    return float((np.abs(np.diff(g, axis=0))[:, :-1] + np.abs(np.diff(g, axis=1))[:-1, :]).mean())


def main():
    photos = sorted(glob.glob(os.path.join(sys.argv[1], '*.jpg')))
    aim = sys.argv[2]
    sess = ort.InferenceSession(sys.argv[3])
    factors = [float(x) for x in (sys.argv[sys.argv.index('--factors') + 1] if '--factors' in sys.argv else '1.5,1.9,2.5').split(',')]
    models = [int(x) for x in (sys.argv[sys.argv.index('--models') + 1] if '--models' in sys.argv else '512').split(',')]
    variants = [(f, m) for m in models for f in factors]
    count = int(sys.argv[sys.argv.index('--count') + 1]) if '--count' in sys.argv else 60
    mattes = sorted(glob.glob(os.path.join(aim, '*_a.png')))
    rnd = random.Random(7)
    score = {v: [] for v in variants}
    for k in range(count):
        photo = np.asarray(Image.open(photos[k % len(photos)]).convert('RGB'))
        H, W = photo.shape[:2]
        # An object 3–20 % of the photo's area, somewhere inside it.
        m = Image.open(rnd.choice(mattes)).convert('L')
        bbox = m.point(lambda v: 255 if v > 128 else 0).getbbox()
        if not bbox: continue
        m = m.crop(bbox)
        area = rnd.uniform(0.03, 0.20) * W * H
        cover = (np.asarray(m) > 128).mean()
        scale = (area / (m.width * m.height * max(cover, 0.05))) ** 0.5
        mw, mh = max(8, int(m.width * scale)), max(8, int(m.height * scale))
        if mw >= W - 8 or mh >= H - 8: continue
        m = m.resize((mw, mh), Image.BILINEAR)
        x0, y0 = rnd.randint(0, W - mw), rnd.randint(0, H - mh)
        hole = np.zeros((H, W), bool)
        hole[y0:y0 + mh, x0:x0 + mw] = np.asarray(m) > 128
        # The app grows the brush by max(3, 0.5 % of the short side) plus the feather.
        grow = max(3, int(min(W, H) * 0.005)) * 2
        hole = np.asarray(Image.fromarray((hole * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(2 * (grow // 2) + 1))) > 0
        ys, xs = np.nonzero(hole)
        l, t, r, b = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
        for (f, mdl) in variants:
            res = fill(sess, photo, hole, f, mdl)
            a, g = res[t:b, l:r], photo[t:b, l:r]
            hm = hole[t:b, l:r]
            mse = ((a.astype(np.float64) - g) ** 2)[hm].mean()
            score[(f, mdl)].append((10 * np.log10(255 ** 2 / max(mse, 1e-6)), ssim(a, g), detail(a) / max(detail(g), 1e-6)))
        if k % 10 == 9: print(f'{k + 1}/{count}', file=sys.stderr)
    for (f, mdl) in variants:
        s = np.array(score[(f, mdl)])
        print(f'model {mdl} px, factor {f}: holes {len(s)}  PSNR {s[:, 0].mean():.2f}  SSIM {s[:, 1].mean():.4f}  detail {s[:, 2].mean():.3f}')


if __name__ == '__main__':
    main()
