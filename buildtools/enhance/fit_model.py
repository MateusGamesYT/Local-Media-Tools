#!/usr/bin/env python3
"""Fits auto-enhance's slider model: ridge regression from what the app measures in a photo
(AutoEnhance.Stats, plus the scene) to the slider values that bring the photo closest to expert C's
retouch (harness `fit` mode). Prints the coefficients as Kotlin for AutoEnhance.kt.

    fit_model.py train.csv [more.csv…] [--lambda 3] [--finished-weight 0.5] [--light-share 0.3]

Only NumPy. The features are built exactly as AutoEnhance.features() builds them in the app.
"""
import csv
import math
import sys

import numpy as np

SLIDERS = ['exposure', 'brightness', 'contrast', 'highlights', 'shadows', 'warmth', 'tint', 'vibrance', 'saturation']
SCENES = ['PORTRAIT', 'LANDSCAPE', 'ANIMAL', 'CITY', 'NIGHT']  # GENERAL is the baseline


def srgb_to_linear(v):
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def features(r, scene):
    """Mirror of AutoEnhance.features(): keep the two in step."""
    p1, p5, p50, p95, p99 = (float(r[k]) for k in ('p1', 'p5', 'p50', 'p95', 'p99'))
    clip, dark, sat = float(r['clip_hi']), float(r['dark']), float(r['mean_sat'])
    has_cast = float(r['has_cast'])
    warm, green = float(r['warm_cast']) * has_cast, float(r['green_cast']) * has_cast
    face_luma, face_area = float(r['face_luma']), float(r['face_area'])
    has_face = 1.0 if face_luma >= 0 else 0.0
    ev_mid = math.log2(srgb_to_linear(0.46) / max(srgb_to_linear(max(p50, 0.02)), 1e-4))
    ev_face = math.log2(srgb_to_linear(0.56) / max(srgb_to_linear(max(face_luma, 0.02)), 1e-4)) if has_face else 0.0
    f = [ev_mid, ev_face, has_face, min(face_area, 0.5),
         p1, p5, p50, p95, p99, p95 - p5, min(clip * 10, 1.0), min(dark * 2, 1.0), sat, warm, green, has_cast]
    f += [1.0 if scene == s else 0.0 for s in SCENES]
    f += [float(r['face_warm']) if has_face else 0.0]
    return f


NAMES = ['ev_mid', 'ev_face', 'has_face', 'face_area', 'p1', 'p5', 'p50', 'p95', 'p99', 'spread', 'clip', 'dark', 'sat',
         'warm', 'green', 'has_cast'] + ['scene_' + s.lower() for s in SCENES] + ['face_warm']


def final_scene(r):
    """The scene AutoEnhance.analyze() settles on: faces make a portrait, a dark photo is night, else the classifier's."""
    if int(float(r['face_count'])) > 0 and (float(r['face_area']) >= 0.012 or int(float(r['face_count'])) >= 2): return 'PORTRAIT'
    if float(r['p50']) < 0.16 and float(r['p95']) < 0.62: return 'NIGHT'
    return r['scene']


def load(paths):
    X, Y, rows = [], [], []
    for p in paths:
        for r in csv.DictReader(open(p)):
            X.append(features(r, final_scene(r))); Y.append([float(r['best_' + s]) for s in SLIDERS]); rows.append(r)
    return np.array(X), np.array(Y), rows


def weights(rows, finished):
    """Rows of finished photos (the experts' versions, which need no change, harness `finished`) count [finished]."""
    return np.array([finished if r['name'].endswith('_ex') else 1.0 for r in rows])


# White balance (warmth, tint) only from the measured colour of the light, of the skin and the scene: other
# measurements (saturation, darkness…) correlate with casts in the photos but don't cause them.
WB_FEATURES = {'warm', 'green', 'has_cast', 'has_face', 'face_warm'} | {'scene_' + s.lower() for s in SCENES}
MASKS = {s: np.array([n in WB_FEATURES for n in NAMES]) if s in ('warmth', 'tint') else np.ones(len(NAMES), bool) for s in SLIDERS}


# Sliders the finished photos (which need no change) are fitted for at full weight. For brightness only a
# share (LIGHT_SHARE): they include dark night photos the experts left dark, which at full weight held
# the model back from brightening dark as-shot photos as much as the experts did.
FINISHED_FOR = {'contrast', 'highlights', 'shadows', 'warmth', 'tint', 'vibrance', 'saturation'}
LIGHT_SHARE = 0.0  # their weight for exposure and brightness, as a share of the others' (--light-share)


def fit(X, Y, lam, wt=None, finished=None):
    """fit_all per slider, each with the features its MASKS entry allows (the others get weight 0)."""
    w = np.zeros((X.shape[1], Y.shape[1])); b = np.zeros(Y.shape[1])
    for j, s in enumerate(SLIDERS):
        m = MASKS[s]
        wjt = wt if (wt is None or finished is None or s in FINISHED_FOR) else np.where(finished, wt * LIGHT_SHARE, wt)
        keep = wjt > 0 if wjt is not None else np.ones(len(X), bool)
        wj, bj = fit_all(X[keep][:, m], Y[keep, j:j + 1], lam, None if wjt is None else wjt[keep])
        w[m, j] = wj[:, 0]; b[j] = bj[0]
    return w, b


def fit_all(X, Y, lam, wt=None):
    """Weighted ridge regression ([wt] per row); returns raw-feature weights and intercepts."""
    wt = np.ones(len(X)) if wt is None else wt
    sw = wt.sum()
    mu = (wt[:, None] * X).sum(0) / sw; sd = np.sqrt((wt[:, None] * (X - mu) ** 2).sum(0) / sw); sd[sd < 1e-6] = 1
    ym = (wt[:, None] * Y).sum(0) / sw
    Z = (X - mu) / sd
    A = Z.T @ (wt[:, None] * Z) + lam * sw * np.eye(Z.shape[1])
    W = np.linalg.solve(A, Z.T @ (wt[:, None] * (Y - ym)))
    # Back to raw features: y = b + Σ w·x.
    w = W / sd[:, None]
    b = ym - mu @ w
    return w, b


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    lam = float(sys.argv[sys.argv.index('--lambda') + 1]) if '--lambda' in sys.argv else None
    fw = float(sys.argv[sys.argv.index('--finished-weight') + 1]) if '--finished-weight' in sys.argv else 1.0
    global LIGHT_SHARE
    LIGHT_SHARE = float(sys.argv[sys.argv.index('--light-share') + 1]) if '--light-share' in sys.argv else 0.0
    args = [a for a in args if a.endswith('.csv')]
    X, Y, rows = load(args)
    wt = weights(rows, fw)
    fin = np.array([r['name'].endswith('_ex') for r in rows])
    n = len(X)
    print(f'{n} photos', file=sys.stderr)
    # λ by 5-fold cross-validation on the training photos (error in slider units).
    lams = [lam] if lam is not None else [0.01, 0.03, 0.1, 0.3, 1.0, 3.0, 10.0]
    idx = np.arange(n); folds = [idx[k::5] for k in range(5)]
    best = None
    for l in lams:
        err = 0.0
        for f in folds:
            tr = np.setdiff1d(idx, f)
            w, b = fit(X[tr], Y[tr], l, wt[tr], fin[tr])
            err += (wt[f][:, None] * (X[f] @ w + b - Y[f]) ** 2).sum()
        err /= wt.sum() * len(SLIDERS)
        print(f'lambda {l:<6} cv mse {err:.5f}', file=sys.stderr)
        if best is None or err < best[0]: best = (err, l)
    w, b = fit(X, Y, best[1], wt, fin)
    print(f'chosen lambda {best[1]}', file=sys.stderr)
    # Kotlin: one row per slider, intercept first.
    k = sum(1 for r in rows if r['name'].endswith('_ex'))
    print('    // Fitted by buildtools/enhance/fit_model.py (ridge, lambda %g) on %d MIT-Adobe FiveK photos (expert C)%s.' % (
        best[1], n - k, '' if k == 0 else ', and their %d finished versions (no change, weight %g; %g of it for exposure and brightness)' % (k, fw, LIGHT_SHARE)))
    print('    // Features: ' + ', '.join(NAMES))
    print('    private val MODEL = arrayOf(')
    for j, s in enumerate(SLIDERS):
        vals = [b[j]] + list(w[:, j])
        print('        floatArrayOf(' + ', '.join(f'{v:.5f}f' for v in vals) + '), // ' + s)
    print('    )')


if __name__ == '__main__':
    main()
