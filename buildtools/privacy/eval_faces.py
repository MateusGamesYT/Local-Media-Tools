"""Face finding for blurring, measured on real photos (fetch_oi.py faces: Open Images, CC BY 2.0).

    python3 eval_faces.py <work dir> <split> [--side N] [--th T] [--tiles] [--flip] [--limit N]

The app's face detector (YuNet, OpenCV 4.12) as the app runs it (photo at up to --side px, score
--th), optionally also on overlapping tiles at twice the resolution (small faces) and on the mirrored
photo. A face counts as hidden when the found boxes, grown as the app blurs them, cover at least 80 %
of it; faces drawn in a picture (depictions) and groups don't count either way. Also: found at IoU ≥ 0.5,
boxes per photo on no labelled face (most are real faces the labels leave out: crowds, profiles), by
size of the face (width as a share of the photo's). --also adds passes on smaller copies (large faces);
their boxes are merged as the app merges them.
"""
import argparse, json, time
import numpy as np, cv2 as cv

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('split')
ap.add_argument('--side', type=int, default=1600); ap.add_argument('--th', type=float, default=0.62)
ap.add_argument('--tiles', type=int, default=0)        # 0: none; N: tile grid N×N at --tile-side
ap.add_argument('--tile-side', type=int, default=1024)
ap.add_argument('--also', type=int, nargs='*', default=[])   # further whole-photo passes at these sides
ap.add_argument('--flip', action='store_true'); ap.add_argument('--limit', type=int, default=10**9)
ap.add_argument('--no-merge', action='store_true')    # keep every pass's boxes (the app merges them)
ap.add_argument('--union', action='store_true')
a = ap.parse_args()
yunet = '/home/user/local-media-tools/app/src/main/assets/models/face_detection_yunet_2023mar.onnx'
det = cv.FaceDetectorYN.create(yunet, '', (320, 320), a.th, 0.3, 500)

def fit(img, side):
    h, w = img.shape[:2]; s = min(1.0, side / max(h, w))
    return (cv.resize(img, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv.INTER_AREA) if s < 1 else img), s

def detect(img):
    h, w = img.shape[:2]
    det.setInputSize((w, h))
    _, f = det.detect(img)
    return [] if f is None else [(r[0], r[1], r[0] + r[2], r[1] + r[3], r[14]) for r in f if r[2] >= 12 and r[3] >= 12]

def faces(img):
    work, s = fit(img, a.side)
    out = [(b[0] / s, b[1] / s, b[2] / s, b[3] / s, b[4]) for b in detect(work)]
    for side in a.also:
        w2, s2 = fit(img, side)
        out += [(b[0] / s2, b[1] / s2, b[2] / s2, b[3] / s2, b[4]) for b in detect(w2)]
    if a.flip:
        fw = work.shape[1]
        out += [((fw - b[2]) / s, b[1] / s, (fw - b[0]) / s, b[3] / s, b[4]) for b in detect(cv.flip(work, 1))]
    if a.tiles:
        H, W = img.shape[:2]
        n = a.tiles; ov = 0.15
        tw, th = W / (n - (n - 1) * ov), H / (n - (n - 1) * ov)
        for i in range(n):
            for j in range(n):
                x0 = int(j * tw * (1 - ov)); y0 = int(i * th * (1 - ov))
                tile = img[y0:int(y0 + th), x0:int(x0 + tw)]
                t, ts = fit(tile, a.tile_side)
                out += [(x0 + b[0] / ts, y0 + b[1] / ts, x0 + b[2] / ts, y0 + b[3] / ts, b[4]) for b in detect(t)]
    return out if a.no_merge else merge(out)

def merge(boxes):
    # As FaceEngine.detectScales: best score first; a box overlapping a kept one (IoU > 0.3, or
    # mostly inside it or around it) is the same face.
    kept = []
    for b in sorted(boxes, key=lambda b: -b[4]):
        for i, k in enumerate(kept):
            if same(b, k):
                if a.union: kept[i] = (min(k[0], b[0]), min(k[1], b[1]), max(k[2], b[2]), max(k[3], b[3]), k[4])
                break
        else: kept.append(b)
    return kept

def same(p, q):
    ix = max(0, min(p[2], q[2]) - max(p[0], q[0])); iy = max(0, min(p[3], q[3]) - max(p[1], q[1]))
    i = ix * iy; ap_ = (p[2] - p[0]) * (p[3] - p[1]); aq = (q[2] - q[0]) * (q[3] - q[1])
    return i > 0.3 * (ap_ + aq - i) or i > 0.6 * min(ap_, aq)

def grow(b):
    # The ellipse the app blurs (FaceTracker.trackRegionsAt, grow 1.45), as its bounding box.
    x0, y0, x1, y1 = b[:4]; w, h = x1 - x0, y1 - y0
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2 - 0.08 * h; rx, ry = w / 2 * 1.45, h / 2 * 1.45 * 1.12
    return (cx - rx, cy - ry, cx + rx, cy + ry)

def covered(g, boxes):
    x0, y0, x1, y1 = g; gw, gh = max(1, int(x1 - x0)), max(1, int(y1 - y0))
    m = np.zeros((gh, gw), bool)
    for b in boxes:
        cx, cy = (b[0] + b[2]) / 2 - x0, (b[1] + b[3]) / 2 - y0; rx, ry = (b[2] - b[0]) / 2, (b[3] - b[1]) / 2
        yy, xx = np.ogrid[:gh, :gw]
        m |= ((xx - cx) / max(rx, 1)) ** 2 + ((yy - cy) / max(ry, 1)) ** 2 <= 1
    return m.mean()

def iou(p, q):
    ix = max(0, min(p[2], q[2]) - max(p[0], q[0])); iy = max(0, min(p[3], q[3]) - max(p[1], q[1]))
    i = ix * iy; u = (p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - i
    return i / u if u > 0 else 0

rows = json.load(open(f'{a.work}/faces-{a.split}.json'))[:a.limit]
tot = hid = hit5 = 0; stray = 0; t0 = time.time()
by = {'tiny <1%': [0, 0], 'small 1-3%': [0, 0], 'medium 3-10%': [0, 0], 'large >10%': [0, 0]}
for r in rows:
    img = cv.imread(f'{a.work}/{r["file"]}'); H, W = img.shape[:2]
    found = faces(img); grown = [grow(b) for b in found]
    gts = [(g['XMin'] * W, g['YMin'] * H, g['XMax'] * W, g['YMax'] * H) for g in r['boxes'] if not g['IsGroupOf'] and not g['IsDepiction']]
    dont = [(g['XMin'] * W, g['YMin'] * H, g['XMax'] * W, g['YMax'] * H) for g in r['boxes'] if g['IsGroupOf'] or g['IsDepiction']]
    for g in gts:
        tot += 1; ok = covered(g, grown) >= 0.8; hid += ok
        hit5 += any(iou(g, b) >= 0.5 for b in found)
        f = (g[2] - g[0]) / W
        k = 'tiny <1%' if f < 0.01 else 'small 1-3%' if f < 0.03 else 'medium 3-10%' if f < 0.10 else 'large >10%'
        by[k][0] += ok; by[k][1] += 1
    for b in found:
        if not any(iou(b, g) > 0.1 for g in gts + dont): stray += 1
n = len(rows)
print(f'side={a.side}+{a.also} th={a.th} tiles={a.tiles}@{a.tile_side} flip={a.flip}: faces hidden {hid}/{tot} = {hid/tot:.1%}, IoU≥0.5 {hit5/tot:.1%}; '
      + ', '.join(f'{k} {v[0]}/{v[1]}' for k, v in by.items()) + f'; boxes on no face {stray/n:.2f}/photo; {1000*(time.time()-t0)/n:.0f} ms/photo')
