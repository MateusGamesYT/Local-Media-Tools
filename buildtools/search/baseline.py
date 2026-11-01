"""The Gallery's category search as the app runs it (1.14), on the search benchmark's photos.

    python3 baseline.py <work dir>          → <work dir>/baseline.npz: per photo and category, the
                                              score the app stores (0.5 and up: tagged), and the
                                              scene classifier's 1,536 features
    python3 baseline.py <work dir> xm3600   → <work dir>/baseline_xm.npz: the features of XM3600's photos

The detector (EfficientDet-Lite2, letterboxed to 448) and the scene classifier (EfficientNetV2-B3-21k,
centre crop 224 of 256), the 4-bit head, the probes and categories.tsv, as in
buildtools/gallery/scripts/app_parity.py (TFLite through ai_edge_litert), and Taxonomy.score.
"""
import json, os, struct, sys
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

work = sys.argv[1]
APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')
xm = len(sys.argv) > 2 and sys.argv[2] == 'xm3600'
rows = json.load(open(f'{work}/xm3600.json')) if xm else json.load(open(f'{work}/oi-search.json'))['photos']

def to8(a): return np.clip(np.round(a), 0, 255).astype(np.uint8)
def resample(a, tw, th): return np.asarray(Image.fromarray(to8(a)).resize((tw, th), Image.BILINEAR)).astype(np.float32)

det = Interpreter(model_path=f'{APP}/models/efficientdet_lite2_int8.tflite', num_threads=4); det.allocate_tensors()
dI = det.get_input_details()[0]; dO = {d['shape'][-1]: d['index'] for d in det.get_output_details()}
scn = Interpreter(model_path=f'{APP}/models/scene_effnetv2_b3_21k.tflite', num_threads=4); scn.allocate_tensors()
sI = scn.get_input_details()[0]; sO = scn.get_output_details()[0]
anchors = []
for level in range(3, 8):
    fm = int(np.ceil(448 / 2 ** level))
    for y in range(fm):
        for x in range(fm):
            for s in range(3):
                size = 3.0 * 2 ** (s / 3.0) / fm
                for ar in (1.0, 2.0, 0.5): anchors.append(((x + 0.5) / fm, (y + 0.5) / fm, size * ar ** 0.5, size / ar ** 0.5))
anchors = np.array(anchors, np.float32)

def app_path(a):
    h, w = a.shape[:2]
    side = max(1, min(w, h) * 224 // (224 + 32))
    crop = a[(h - side) // 2:(h + side) // 2, (w - side) // 2:(w + side) // 2]
    x = to8(resample(crop, 224, 224)).astype(np.float32)[None]
    scn.set_tensor(sI['index'], x); scn.invoke(); feat = scn.get_tensor(sO['index'])[0].copy()
    s = 448 / max(w, h); w2 = max(1, round(w * s)); h2 = max(1, round(h * s))
    box = np.zeros((448, 448, 3), np.uint8); box[:h2, :w2] = to8(resample(a, w2, h2))
    det.set_tensor(dI['index'], box[None]); det.invoke()
    raw = det.get_tensor(dO[4])[0]; sc = det.get_tensor(dO[90])[0]
    # Box areas (fractions of the photo) for each anchor, for the categories' minimum box area.
    cw, ch = w2 / 448, h2 / 448
    hh = np.exp(raw[:, 2]) * anchors[:, 3] / ch; ww = np.exp(raw[:, 3]) * anchors[:, 2] / cw
    return feat, sc, np.clip(ww, 0, 1) * np.clip(hh, 0, 1)

raw = open(f'{APP}/gallery/scene_head.bin', 'rb').read()
_, R, D, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24; ng = D // GS
hsc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
hb = np.frombuffer(raw, '<f4', R, o); o += R * 4
pk = np.frombuffer(raw, np.uint8, R * D // 2, o).reshape(R, D // 2); o += R * D // 2
PW = np.frombuffer(raw, '<f4', NP * D, o).reshape(NP, D); o += NP * D * 4; PB = np.frombuffer(raw, '<f4', NP, o)
lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
q = np.empty((R, D), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi; Wd = (q.reshape(R, ng, GS) * hsc[:, :, None]).reshape(R, D)
cats = []
for line in open(f'{APP}/gallery/categories.tsv'):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t'); ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    cats.append((c[0], ints(c[4]), float(c[5]), float(c[6] or 0), ints(c[7]), float(c[8]), int(c[9]), float(c[10])))

def calibrate(s, t):
    # Taxonomy.calibrate: the source's threshold lands on 0.5.
    return 0.5 + 0.5 * min(max((s - t) / (1 - t), 0), 1) if s >= t else 0.5 * max(s / t, 0)

def scores(feat, sc, area):
    z = Wd @ feat + hb; z -= z.max(); p = np.exp(z.astype(np.float64)); p /= p.sum()
    pr = 1 / (1 + np.exp(-(PW @ feat + PB)))
    out = np.zeros(len(cats), np.float32)
    for k, (key, dc, dt, dmin, rows_, st, pi, pt) in enumerate(cats):
        best = 0.0
        if dc and dt > 0:
            ok = (sc[:, dc] >= 0.15) & (area[:, None] >= dmin)
            s = float((sc[:, dc] * ok).max()) if ok.any() else 0.0
            best = max(best, calibrate(s, dt))
        if rows_ and st > 0: best = max(best, calibrate(float(p[rows_].sum()), st))
        if pi >= 0 and pt > 0: best = max(best, calibrate(float(pr[pi]), pt))
        out[k] = best
    return out

S = np.zeros((len(rows), len(cats)), np.float32); F = np.zeros((len(rows), D), np.float32)
for i, r in enumerate(rows):
    a = np.asarray(Image.open(f'{work}/{r["file"]}').convert('RGB'))
    out = app_path(a); F[i] = out[0]
    S[i] = scores(*out)
    if i % 500 == 0: print(i, flush=True)
np.savez(f'{work}/baseline{"_xm" if xm else ""}.npz', scores=S, feats=F, keys=np.array([c[0] for c in cats]), ids=np.array([r['id'] for r in rows]))
print('done', S.shape)
