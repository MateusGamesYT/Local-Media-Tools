"""Licence plate finding measured on real photos (fetch_oi.py: Open Images, CC BY 2.0).

    python3 eval_plates.py <work dir> <split> <method> [options]

Methods: lpd (OpenCV Zoo's LPD-YuNet), text (PP-OCRv3 text detection, OpenCV Zoo), both; the app's
own model files (build_models.py).
A plate counts as found when the found boxes, grown by the margin the app blurs with, cover at least
80 % of it (what matters when blurring). Also: plates found at IoU ≥ 0.5, and boxes per photo that
touch no plate. Photos are worked on at up to --side px, as the app would.
"""
import argparse, json, sys, time
import numpy as np, cv2 as cv
sys.path.insert(0, sys.argv[1] + '/models')

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('split'); ap.add_argument('method')
ap.add_argument('--side', type=int, default=1600)
ap.add_argument('--lpd-side', type=int, default=1024)
ap.add_argument('--lpd-th', type=float, default=0.6)
ap.add_argument('--text-side', type=int, default=1280)
ap.add_argument('--margin', type=float, default=0.15)
ap.add_argument('--limit', type=int, default=10**9)
ap.add_argument('--vehicles', default='')            # '', 'lpd', 'text' or 'both': look again inside each vehicle
ap.add_argument('--veh-th', type=float, default=0.3)
ap.add_argument('--veh-crop', type=int, default=640)
ap.add_argument('--veh-lpd-th', type=float, default=0.5)
ap.add_argument('--veh-max', type=int, default=8)
ap.add_argument('--plate-shape', action='store_true')   # drop plate-detector boxes not shaped like a plate (as the app)
ap.add_argument('--text-filter', default='none')     # none, veh (near a vehicle), geom (plate-shaped), vehgeom (either), vehandgeom (both)
a = ap.parse_args()
rows = json.load(open(f'{a.work}/plates-{a.split}.json'))[:a.limit]

import os
APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'models')
lpd = text = None
if a.method in ('lpd', 'both') or a.vehicles in ('lpd', 'both'):
    from lpd_yunet import LPD_YuNet
    class AppLPD(LPD_YuNet):
        # As the app (PlateDetector): non-maximum suppression on each plate's bounding box (OpenCV
        # Zoo's demo passes two corners as x, y, width, height).
        def _postprocess(self, blob):
            d = self._decode(blob); q = d[:, :8].reshape(-1, 4, 2)
            x0, y0 = q[:, :, 0].min(1), q[:, :, 1].min(1); x1, y1 = q[:, :, 0].max(1), q[:, :, 1].max(1)
            keep = cv.dnn.NMSBoxes(np.stack([x0, y0, x1 - x0, y1 - y0], 1).tolist(), d[:, -1].tolist(), self.confidence_threshold, self.nms_threshold, top_k=self.top_k)
            return d[np.array(keep).flatten()][:self.keep_top_k] if len(keep) else np.empty((0, 9))
    lpd = AppLPD(f'{APP}/license_plate_lpd_yunet_2023mar_fp16.onnx', confThreshold=a.lpd_th)
if a.method in ('text', 'both') or a.vehicles in ('text', 'both'):
    text = cv.dnn_TextDetectionModel_DB(cv.dnn.readNet(f'{APP}/text_detection_ppocrv3_en_fp16.onnx'))
    text.setBinaryThreshold(0.3); text.setPolygonThreshold(0.5); text.setUnclipRatio(2.0); text.setMaxCandidates(200)
    text.setInputMean((123.675, 116.28, 103.53)); text.setInputScale(1.0 / 255.0 / np.array([0.229, 0.224, 0.225]))

det = None
if a.vehicles:
    from ai_edge_litert.interpreter import Interpreter
    det = Interpreter(model_path=f'{a.work}/../../../app/src/main/assets/models/efficientdet_lite2_int8.tflite'); det.allocate_tensors()
    anchors = []
    for level in range(3, 8):
        fm = int(np.ceil(448 / 2 ** level))
        for y in range(fm):
            for x in range(fm):
                for sc in range(3):
                    size = 3.0 * 2 ** (sc / 3.0) / fm
                    for ar in (1.0, 2.0, 0.5):
                        anchors.append(((x + 0.5) / fm, (y + 0.5) / fm, size * ar ** 0.5, size / ar ** 0.5))
    anchors = np.array(anchors, np.float32)

def vehicles(img):
    h, w = img.shape[:2]; s = 448 / max(h, w)
    r = cv.resize(img[:, :, ::-1], (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv.INTER_AREA)
    x = np.zeros((448, 448, 3), np.uint8); x[:r.shape[0], :r.shape[1]] = r
    det.set_tensor(det.get_input_details()[0]['index'], x[None]); det.invoke()
    o = {d['shape'][-1]: det.get_tensor(d['index'])[0] for d in det.get_output_details()}
    raw, sc = o[4], o[90]
    cw, ch = r.shape[1] / 448, r.shape[0] / 448
    yc = raw[:, 0] * anchors[:, 3] + anchors[:, 1]; xc = raw[:, 1] * anchors[:, 2] + anchors[:, 0]
    hh = np.exp(raw[:, 2]) * anchors[:, 3]; ww = np.exp(raw[:, 3]) * anchors[:, 2]
    boxes = np.stack([(xc - ww / 2) / cw * w, (yc - hh / 2) / ch * h, (xc + ww / 2) / cw * w, (yc + hh / 2) / ch * h], 1)
    best = sc[:, [2, 3, 5, 7]].max(1)
    idx = np.where(best >= a.veh_th)[0]
    keep = cv.dnn.NMSBoxes([[float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])] for b in boxes[idx]], best[idx].tolist(), a.veh_th, 0.5)
    out = [boxes[idx[k]] for k in np.array(keep).flatten()]
    return sorted(out, key=lambda b: -(b[2] - b[0]) * (b[3] - b[1]))[:a.veh_max]

def vehicle_pass(img, vs=None):
    H, W = img.shape[:2]; out = []
    for b in (vehicles(img) if vs is None else vs):
        x0, y0, x1, y1 = b; gx = (x1 - x0) * 0.08; gy = (y1 - y0) * 0.08
        x0, y0, x1, y1 = int(max(0, x0 - gx)), int(max(0, y0 - gy)), int(min(W, x1 + gx)), int(min(H, y1 + gy))
        if x1 - x0 < 16 or y1 - y0 < 16: continue
        crop = img[y0:y1, x0:x1]
        s = min(4.0, a.veh_crop / max(crop.shape[:2]))
        if s <= 1.05 and max(crop.shape[:2]) > 0.6 * max(H, W): continue   # big: the whole-photo pass sees it
        c = cv.resize(crop, (max(1, round(crop.shape[1] * s)), max(1, round(crop.shape[0] * s))), interpolation=cv.INTER_CUBIC if s > 1 else cv.INTER_AREA)
        found = []
        if a.vehicles in ('lpd', 'both'):
            lpd.confidence_threshold = a.veh_lpd_th
            found += lpd_boxes_at(c)
            lpd.confidence_threshold = a.lpd_th
        if a.vehicles in ('text', 'both'): found += text_boxes_at(c)
        out += [(x0 + f[0] / s, y0 + f[1] / s, x0 + f[2] / s, y0 + f[3] / s, f[4]) for f in found]
    return out

def fit(img, side):
    h, w = img.shape[:2]; s = min(1.0, side / max(h, w))
    return (cv.resize(img, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv.INTER_AREA) if s < 1 else img), s

def lpd_boxes(img):
    small, s = fit(img, a.lpd_side)
    return [(b[0] / s, b[1] / s, b[2] / s, b[3] / s, b[4]) for b in lpd_boxes_at(small)]

def lpd_boxes_at(small):
    s = 1.0
    h, w = small.shape[:2]
    lpd.setInputSize([w, h])
    out = []
    for d in lpd.infer(small):
        q = d[:8].reshape(4, 2) / s
        if a.plate_shape:
            # PlateDetector.plateLike: at most half the picture across, 1.2 to 8 times as long as high.
            e = lambda i, j: float(np.linalg.norm(q[i] - q[j]))
            wq = max(e(0, 1), e(3, 2)); hq = max(e(0, 3), e(1, 2)); lo, hi = min(wq, hq), max(wq, hq)
            if hi > 0.5 * max(w, h) or lo <= 0 or not 1.2 <= hi / lo <= 8: continue
        out.append((q[:, 0].min(), q[:, 1].min(), q[:, 0].max(), q[:, 1].max(), float(d[8])))
    return out

def text_boxes(img):
    small, s = fit(img, a.text_side)
    return [(b[0] / s, b[1] / s, b[2] / s, b[3] / s, b[4]) for b in text_boxes_at(small)]

def text_boxes_at(small):
    s = 1.0
    h, w = small.shape[:2]
    W, H = (w + 31) // 32 * 32, (h + 31) // 32 * 32
    pad = cv.copyMakeBorder(small, 0, H - h, 0, W - w, cv.BORDER_CONSTANT)
    text.setInputSize((W, H))
    quads, conf = text.detect(pad)
    out = []
    for q, c in zip(quads, conf):
        q = np.array(q, dtype=np.float32) / s
        out.append((q[:, 0].min(), q[:, 1].min(), q[:, 0].max(), q[:, 1].max(), float(c)))
    return out

def grow(b, m):
    x0, y0, x1, y1 = b[:4]; dx = (x1 - x0) * m; dy = (y1 - y0) * m
    return (x0 - dx, y0 - dy, x1 + dx, y1 + dy)

def covered(g, boxes, W, H):
    # Share of g covered by the union of boxes (on a pixel grid of the plate).
    x0, y0, x1, y1 = g
    gw, gh = max(1, int(x1 - x0)), max(1, int(y1 - y0))
    mask = np.zeros((gh, gw), bool)
    for b in boxes:
        bx0 = int(max(0, b[0] - x0)); by0 = int(max(0, b[1] - y0)); bx1 = int(min(gw, b[2] - x0)); by1 = int(min(gh, b[3] - y0))
        if bx1 > bx0 and by1 > by0: mask[by0:by1, bx0:bx1] = True
    return mask.mean()

def iou(p, q):
    ix = max(0, min(p[2], q[2]) - max(p[0], q[0])); iy = max(0, min(p[3], q[3]) - max(p[1], q[1]))
    i = ix * iy; u = (p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - i
    return i / u if u > 0 else 0

readable = json.load(open(os.path.dirname(os.path.abspath(__file__)) + '/plates_readable.json'))['labels'] if a.split == 'validation' else {}
found = found_iou = total = 0; stray = 0; t0 = time.time(); sizes = []
r_found = r_total = 0
by_size = {'small': [0, 0], 'medium': [0, 0], 'large': [0, 0]}
for r in rows:
    img = cv.imread(f'{a.work}/{r["file"]}')
    H, W = img.shape[:2]
    work, s = fit(img, a.side)
    boxes = []
    if lpd and a.method in ('lpd', 'both'): boxes += lpd_boxes(work)
    vs = vehicles(work) if det else []
    if text and a.method in ('text', 'both'):
        tb = text_boxes(work)
        if a.text_filter != 'none':
            def near(b):
                for v in vs:
                    gx = (v[2] - v[0]) * 0.3; gy = (v[3] - v[1]) * 0.3
                    if b[0] >= v[0] - gx and b[2] <= v[2] + gx and b[1] >= v[1] - gy and b[3] <= v[3] + gy: return True
                return False
            def plate_shaped(b):
                w = b[2] - b[0]; h = b[3] - b[1]
                return h >= 8 and 1.6 <= w / max(h, 1) <= 6.5
            keep = {'veh': near, 'geom': plate_shaped, 'vehgeom': lambda b: near(b) or plate_shaped(b), 'vehandgeom': lambda b: near(b) and plate_shaped(b)}[a.text_filter]
            tb = [b for b in tb if keep(b)]
        boxes += tb
    if det: boxes += vehicle_pass(work, vs)
    boxes = [(b[0] / s, b[1] / s, b[2] / s, b[3] / s, b[4]) for b in boxes]
    gk = [(k, (g['XMin'] * W, g['YMin'] * H, g['XMax'] * W, g['YMax'] * H)) for k, g in enumerate(r['boxes']) if not g['IsGroupOf']]
    gts = [g for _, g in gk]
    grown = [grow(b, a.margin) for b in boxes]
    for k, g in gk:
        total += 1
        c = covered(g, grown, W, H)
        ok = c >= 0.8
        if readable.get(f"{r['id']}/{k}") == 'readable': r_total += 1; r_found += ok
        found += ok
        found_iou += any(iou(g, b) >= 0.5 for b in boxes)
        wfrac = (g[2] - g[0]) / W
        k = 'small' if wfrac < 0.04 else 'medium' if wfrac < 0.12 else 'large'
        by_size[k][0] += ok; by_size[k][1] += 1
    for b in grown:
        if not any(iou(b, g) > 0 for g in gts): stray += 1
n = len(rows)
print(f'filter={a.text_filter} {a.method}+veh:{a.vehicles or "-"}@{a.veh_th}/{a.veh_crop}/{a.veh_lpd_th} side={a.side} lpd={a.lpd_side}@{a.lpd_th} text={a.text_side}: plates covered {found}/{total} = {found/total:.1%}{f" (readable {r_found}/{r_total} = {r_found/max(1,r_total):.1%})" if r_total else ""}, IoU≥0.5 {found_iou/total:.1%}; '
      f'by size ' + ', '.join(f'{k} {v[0]}/{v[1]}' for k, v in by_size.items()) + f'; other boxes {stray/n:.2f}/photo; {1000*(time.time()-t0)/n:.0f} ms/photo')
