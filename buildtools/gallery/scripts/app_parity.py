"""Does the app's own preprocessing give the same tags as the calibration's?
Emulates the Kotlin path (ImageSource.preview at 1600 px, centre crop with Kotlin's integer maths,
letterbox for the detector) with the shipped models, category table and head, and compares the final
tags with the calibration path. RESAMPLER=app-1.4.0 emulates the 1.4.0 app's 2x2 box halvings plus
plain bilinear (93.0 % of 2,000 photos got identical tags); the default is Pillow's bilinear, which
the app's PilResample reproduces bit for bit since 1.4.1 (see GalleryCoreTest.resizesExactlyLikePillow)."""
import sys, os, json, struct, numpy as np, tensorflow as tf
from PIL import Image
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP, LIST, N = sys.argv[1], sys.argv[2], int(sys.argv[3])
names = [l.strip() for l in open(LIST)][:N]

def halve(a):
    h, w = a.shape[:2]; h2, w2 = h // 2, w // 2
    a = a[:h2 * 2, :w2 * 2].astype(np.float32)
    return (a[0::2, 0::2] + a[1::2, 0::2] + a[0::2, 1::2] + a[1::2, 1::2]) / 4
def bilinear(a, tw, th):
    h, w = a.shape[:2]
    if (w, h) == (tw, th): return a.astype(np.float32)
    xs = np.clip((np.arange(tw) + 0.5) * w / tw - 0.5, 0, w - 1); ys = np.clip((np.arange(th) + 0.5) * h / th - 0.5, 0, h - 1)
    x0 = np.floor(xs).astype(int); y0 = np.floor(ys).astype(int); x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    fx = (xs - x0)[None, :, None]; fy = (ys - y0)[:, None, None]; a = a.astype(np.float32)
    top = a[y0][:, x0] * (1 - fx) + a[y0][:, x1] * fx; bot = a[y1][:, x0] * (1 - fx) + a[y1][:, x1] * fx
    return top * (1 - fy) + bot * fy
def resample(a, tw, th):
    if os.environ.get('RESAMPLER') == 'app-1.4.0':
        cur = a.astype(np.float32)
        while cur.shape[1] >= tw * 2 and cur.shape[0] >= th * 2: cur = halve(cur)
        return bilinear(cur, tw, th)
    return np.asarray(Image.fromarray(to8(a)).resize((tw, th), Image.BILINEAR)).astype(np.float32)
def to8(a): return np.clip(np.round(a), 0, 255).astype(np.uint8)

def preview(img):   # ImageSource.preview(1600): power-of-two sample size for 800, then scale down to 1600
    a = np.asarray(img); h, w = a.shape[:2]; s = 1
    while w // (s * 2) >= 800 and h // (s * 2) >= 800: s *= 2
    if s > 1: a = to8(resample(a, w // s, h // s))
    h, w = a.shape[:2]; sc = 1600 / max(w, h)
    if sc < 1: a = to8(bilinear(a, int(w * sc), int(h * sc)))
    return a

det = tf.lite.Interpreter(model_path=os.path.join(APP, 'models/efficientdet_lite2_int8.tflite'), num_threads=4); det.allocate_tensors()
dI = det.get_input_details()[0]; dO = {d['shape'][-1]: d['index'] for d in det.get_output_details()}
scn = tf.lite.Interpreter(model_path=os.path.join(APP, 'models/scene_effnetv2_b3_21k.tflite'), num_threads=4); scn.allocate_tensors()
sI = scn.get_input_details()[0]; sO = scn.get_output_details()[0]
def app_path(img):
    a = preview(img); h, w = a.shape[:2]
    side = max(1, min(w, h) * 224 // (224 + 32))
    crop = a[(h - side) // 2:(h + side) // 2, (w - side) // 2:(w + side) // 2]
    if crop.shape[:2] != (side, side): crop = np.asarray(Image.fromarray(crop).resize((side, side), Image.NEAREST))
    x = to8(resample(crop, 224, 224)).astype(np.float32)[None]
    scn.set_tensor(sI['index'], x); scn.invoke(); feat = scn.get_tensor(sO['index'])[0].copy()
    s = 448 / max(w, h); w2 = max(1, round(w * s)); h2 = max(1, round(h * s))
    box = np.zeros((448, 448, 3), np.uint8); box[:h2, :w2] = to8(resample(a, w2, h2))
    det.set_tensor(dI['index'], box[None]); det.invoke()
    sc = det.get_tensor(dO[90])[0]
    return feat, sc.max(0)

# Shipped scoring: categories, 4-bit head, probes.
raw = open(os.path.join(APP, 'gallery/scene_head.bin'), 'rb').read()
_, R, D, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24; ng = D // GS
sc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
b = np.frombuffer(raw, '<f4', R, o); o += R * 4
pk = np.frombuffer(raw, np.uint8, R * D // 2, o).reshape(R, D // 2); o += R * D // 2
PW = np.frombuffer(raw, '<f4', NP * D, o).reshape(NP, D); o += NP * D * 4; PB = np.frombuffer(raw, '<f4', NP, o)
lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
q = np.empty((R, D), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi; Wd = (q.reshape(R, ng, GS) * sc[:, :, None]).reshape(R, D)
cats = []
for line in open(os.path.join(APP, 'gallery/categories.tsv')):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t'); ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    cats.append((c[0], ints(c[4]), float(c[5]), ints(c[7]), float(c[8]), int(c[9]), float(c[10])))
def tags(feat, detmax):
    z = Wd @ feat + b; z -= z.max(); p = np.exp(z.astype(np.float64)); p /= p.sum()
    pr = 1 / (1 + np.exp(-(PW @ feat + PB)))
    out = set()
    for k, dc, dt, rows, st, pi, pt in cats:
        if dc and dt > 0 and max(detmax[c] if detmax[c] >= 0.15 else 0 for c in dc) >= dt: out.add(k)
        if rows and st > 0 and p[rows].sum() >= st: out.add(k)
        if pi >= 0 and pt > 0 and pr[pi] >= pt: out.add(k)
    return out

ref_feat = np.load(os.path.join(G, 'out/oi_feat_w8.npz')); fidx = {n: i for i, n in enumerate(ref_feat['names'])}
ref_det = json.load(open(os.path.join(G, 'out/oi_det.json')))
same = lost = added = total_ref = 0; per = {}; identical = 0; cos = []
for k, path in enumerate(names):
    base = os.path.basename(path)
    img = Image.open(path).convert('RGB')
    feat, dmax = app_path(img)
    rf = ref_feat['feats'][fidx[base]]
    rd = np.zeros(90)
    for d in ref_det.get(base, []): rd[d[0]] = max(rd[d[0]], d[1])
    ta, tr = tags(feat, dmax), tags(rf, rd)
    cos.append(float(feat @ rf / np.linalg.norm(feat) / np.linalg.norm(rf)))
    same += len(ta & tr); lost += len(tr - ta); added += len(ta - tr); total_ref += len(tr); identical += ta == tr
    for t in tr - ta: per.setdefault(t, [0, 0])[0] += 1
    for t in ta - tr: per.setdefault(t, [0, 0])[1] += 1
    if k % 500 == 0: print(k, flush=True)
print(f'{len(names)} photos: tags (calibration path) {total_ref}, kept {same}, lost {lost}, added {added}; photos with identical tags {identical / len(names):.3f}; feature cosine median {np.median(cos):.4f}')
for t, (l, a) in sorted(per.items(), key=lambda x: -sum(x[1]))[:10]: print(f'  {t}: lost {l}, added {a}')
