"""Finding and reading text, measured on real photos (fetch_textocr.py: TextOCR on Open Images).

    python3 eval_text.py <work dir> [--side N] [--limit N] [--rec-width W]

Text is found with PP-OCRv3's detector (OpenCV Zoo) and read with PP-OCRv5's mobile recogniser
(PaddleOCR, Apache 2.0; ONNX with a fixed 48 × 320 input so OpenCV's DNN module runs it): the app's
own files (build_models.py), as the app runs them. A legible word counts as found when the found boxes, grown by the blur margin, cover at least
80 % of it, and as read when the text read from a box over it contains the word (letters and digits,
any case).
"""
import argparse, json, os, re, time, unicodedata
import numpy as np, cv2 as cv

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('--side', type=int, default=1600); ap.add_argument('--limit', type=int, default=10**9)
ap.add_argument('--det-side', type=int, default=1280); ap.add_argument('--rec-width', type=int, default=320)
ap.add_argument('--margin', type=float, default=0.10); ap.add_argument('--no-rec', action='store_true')
ap.add_argument('--det-model', default='text_detection_ppocrv3_en_fp16.onnx')     # in the app's assets unless found in <work>/models
ap.add_argument('--rec-model', default='text_recognition_ppocrv5_mobile_fp16.onnx')
ap.add_argument('--square', action='store_true')   # a model with a fixed det-side × det-side input
ap.add_argument('--bgr', action='store_true'); ap.add_argument('--box-th', type=float, default=0.5); ap.add_argument('--bin-th', type=float, default=0.3)
a = ap.parse_args()
M = f'{a.work}/models'
APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')
def model(n): return f'{M}/{n}' if os.path.exists(f'{M}/{n}') else f'{APP}/models/{n}'
det = cv.dnn_TextDetectionModel_DB(cv.dnn.readNet(model(a.det_model)))
det.setBinaryThreshold(a.bin_th); det.setPolygonThreshold(a.box_th); det.setUnclipRatio(2.0); det.setMaxCandidates(400)
det.setInputMean((123.675, 116.28, 103.53)); det.setInputScale(1.0 / 255.0 / np.array([0.229, 0.224, 0.225]))
rec = cv.dnn.readNet(model(a.rec_model)) if not a.no_rec else None
chars = [''] + open(f'{APP}/privacy/ppocrv5_dict.txt', encoding='utf-8').read().splitlines() + [' ']

def fit(img, side):
    h, w = img.shape[:2]; s = min(1.0, side / max(h, w))
    return (cv.resize(img, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv.INTER_AREA) if s < 1 else img), s

def find(img):
    small, s = fit(img, a.det_side)
    h, w = small.shape[:2]; W, H = (w + 31) // 32 * 32, (h + 31) // 32 * 32
    if a.square: W = H = a.det_side
    pad = cv.copyMakeBorder(small, 0, H - h, 0, W - w, cv.BORDER_CONSTANT)
    det.setInputSize((W, H))
    quads, _ = det.detect(pad)
    return [np.array(q, np.float32) / s for q in quads]

def order(q):
    # Corners as top-left, top-right, bottom-right, bottom-left, the long side horizontal.
    c = q.mean(0); ang = np.arctan2(q[:, 1] - c[1], q[:, 0] - c[0]); q = q[np.argsort(ang)]
    tl = np.argmin(q.sum(1)); q = np.roll(q, -tl, 0)
    return q

def read(img, q):
    q = order(q)
    wq = max(np.linalg.norm(q[1] - q[0]), np.linalg.norm(q[2] - q[3])); hq = max(np.linalg.norm(q[3] - q[0]), np.linalg.norm(q[2] - q[1]))
    if hq < 4 or wq < 4: return ''
    if hq > wq * 1.5:  # vertical text: turn it
        q = np.roll(q, 1, 0); wq, hq = hq, wq
    tw = max(1, int(round(48 * wq / hq)))
    dst = np.array([[0, 0], [tw - 1, 0], [tw - 1, 47], [0, 47]], np.float32)
    crop = cv.warpPerspective(img, cv.getPerspectiveTransform(q.astype(np.float32), dst), (tw, 48), flags=cv.INTER_CUBIC, borderMode=cv.BORDER_REPLICATE)
    return ''.join(read_line(piece) for piece in pieces(crop))

def pieces(crop):
    # Long lines are read in pieces of at most the recogniser's width, cut where there's least ink
    # (between words), so no letter is cut in half.
    tw = crop.shape[1]
    if tw <= a.rec_width: return [crop]
    g = cv.cvtColor(crop, cv.COLOR_BGR2GRAY)
    _, b = cv.threshold(g, 0, 255, cv.THRESH_BINARY + cv.THRESH_OTSU)
    if (b > 0).mean() > 0.5: b = 255 - b                       # ink as the minority
    ink = (b > 0).sum(0).astype(np.float32)
    ink = np.convolve(ink, np.ones(5) / 5, mode='same')
    out = []; start = 0
    while tw - start > a.rec_width:
        lo = start + a.rec_width * 5 // 8; hi = start + a.rec_width
        cut = lo + int(np.argmin(ink[lo:hi]))
        out.append(crop[:, start:cut]); start = cut
    out.append(crop[:, start:])
    return out

def read_line(crop):
    tw = crop.shape[1]
    if tw > a.rec_width: crop = cv.resize(crop, (a.rec_width, 48), interpolation=cv.INTER_AREA); tw = a.rec_width
    # As PaddleOCR does: normalised, then padded with zeros (mid grey) to the full width.
    line = np.zeros((48, a.rec_width, 3), np.float32)
    line[:, :tw] = ((crop if a.bgr else crop[:, :, ::-1]) / 255 - 0.5) / 0.5
    x = line.transpose(2, 0, 1)[None].astype(np.float32)
    rec.setInput(x); ids = rec.forward()[0].argmax(-1)
    return ''.join(chars[c] for t, c in enumerate(ids) if c != 0 and (t == 0 or c != ids[t - 1]) and c < len(chars))

def norm(s): return ''.join(ch for ch in unicodedata.normalize('NFKC', s).casefold() if ch.isalnum())

def covered(gq, quads, shape):
    x0, y0 = np.floor(gq.min(0)).astype(int); x1, y1 = np.ceil(gq.max(0)).astype(int)
    x0, y0 = max(0, x0), max(0, y0); x1, y1 = min(shape[1], x1), min(shape[0], y1)
    if x1 <= x0 or y1 <= y0: return 0.0
    gm = np.zeros((y1 - y0, x1 - x0), np.uint8); cv.fillPoly(gm, [np.round(gq - [x0, y0]).astype(np.int32)], 1)
    dm = np.zeros_like(gm)
    for q in quads:
        c = q.mean(0); g = c + (q - c) * (1 + a.margin)
        cv.fillPoly(dm, [np.round(g - [x0, y0]).astype(np.int32)], 1)
    n = gm.sum()
    return (gm & dm).sum() / n if n else 0.0

rows = json.load(open(f'{a.work}/textocr-val.json'))[:a.limit]
legible = found = read_ok = long_ok = long_n = 0; boxes = 0; t0 = time.time(); trec = 0.0
for r in rows:
    img = cv.imread(f'{a.work}/{r["file"]}')
    work, s = fit(img, a.side)
    quads = [q * s for q in find(work)]
    boxes += len(quads)
    texts = []
    if rec is not None:
        t1 = time.time(); texts = [read(work, q) for q in quads]; trec += time.time() - t1
    for wd in r['words']:
        if wd['text'] == '.': continue
        gq = np.array(wd['points'], np.float32).reshape(-1, 2) * s
        legible += 1
        ok = covered(gq, quads, work.shape) >= 0.8
        found += ok
        if rec is not None and len(norm(wd['text'])) >= 2:
            gx0, gy0 = gq.min(0); gx1, gy1 = gq.max(0)
            over = [t for q, t in zip(quads, texts) if q[:, 0].max() > gx0 and q[:, 0].min() < gx1 and q[:, 1].max() > gy0 and q[:, 1].min() < gy1]
            hit = any(norm(wd['text']) in norm(t) for t in over)
            read_ok += hit
            if len(norm(wd['text'])) >= 4: long_n += 1; long_ok += hit
n = len(rows)
print(f'side={a.side} det={a.det_side} bin={a.bin_th} box={a.box_th} rec={a.rec_width}: legible words found {found}/{legible} = {found/legible:.1%}'
      + (f'; read correctly {read_ok/legible:.1%} (words of 4+ characters {long_ok}/{long_n} = {long_ok/max(1,long_n):.1%})' if rec is not None else '')
      + f'; {boxes/n:.1f} boxes/photo; {1000*(time.time()-t0)/n:.0f} ms/photo ({1000*trec/n:.0f} reading)')
