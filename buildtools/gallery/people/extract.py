"""The app's face pipeline (GalleryFaces.analyze) in Python: YuNet on a copy of at most 1600 px,
small faces re-detected from the full-resolution picture, SFace (int8) embeddings averaged with the
mirrored face. Saves every face with its embedding, quality numbers and a crop for checking."""
import cv2, numpy as np, json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import WORK as D, MODELS as M
det = cv2.FaceDetectorYN.create(os.path.join(M, 'face_detection_yunet_2023mar.onnx'), '', (320, 320), 0.62, 0.3, 500)
rec = cv2.FaceRecognizerSF.create(os.path.join(M, 'face_recognition_sface_2021dec_int8.onnx'), '')
def detect(img):
    h, w = img.shape[:2]; det.setInputSize((w, h))
    _, f = det.detect(img)
    if f is None: return []
    f = [r for r in f if r[14] >= 0.62 and r[2] >= 12 and r[3] >= 12]
    return sorted(f, key=lambda r: -r[2] * r[3])
def eyes_yaw(r):
    ed = float(np.hypot(r[6] - r[4], r[7] - r[5]))
    return ed, float(abs(r[8] - (r[4] + r[6]) / 2) / max(ed, 1e-3))
def pitch(r):
    # nose height between eye line and mouth line (0.5 = level); a rough up/down measure
    ey = (r[5] + r[7]) / 2; my = (r[11] + r[13]) / 2
    return float((r[9] - ey) / max(my - ey, 1e-3))
NORMS = {}
def emb(img, r):
    al = rec.alignCrop(img, r)
    a = rec.feature(al).flatten().astype(np.float32); b = rec.feature(cv2.flip(al, 1)).flatten().astype(np.float32)
    na, nb = float(np.linalg.norm(a)), float(np.linalg.norm(b))
    a /= na; b /= nb; e = a + b
    e = e / np.linalg.norm(e)
    NORMS[id(e)] = ((na + nb) / 2, float(a @ b))
    return e
def refine(full, r, up):
    eye, _ = eyes_yaw(r); fe = eye * up; sample = 1
    while fe / (sample * 2) >= 64 and sample < 16: sample *= 2
    cx = (r[0] + r[2] / 2) * up; cy = (r[1] + r[3] / 2) * up; half = max(r[2], r[3]) * up * 1.4
    H, W = full.shape[:2]
    x0, y0, x1, y1 = max(0, round(cx - half)), max(0, round(cy - half)), min(W, round(cx + half)), min(H, round(cy + half))
    if x1 - x0 < 16 or y1 - y0 < 16: return None
    crop = full[y0:y1, x0:x1]
    if sample > 1: crop = cv2.resize(crop, ((x1 - x0) // sample, (y1 - y0) // sample), interpolation=cv2.INTER_AREA)
    cs = (x1 - x0) / crop.shape[1]
    fs = detect(crop)
    if not fs: return None
    ccx, ccy = crop.shape[1] / 2, crop.shape[0] / 2
    g = min(fs, key=lambda q: (q[0] + q[2] / 2 - ccx) ** 2 + (q[1] + q[3] / 2 - ccy) ** 2)
    dist = np.hypot(g[0] + g[2] / 2 - ccx, g[1] + g[3] / 2 - ccy); exp = r[2] * up / cs
    if dist > exp * 0.5 or g[2] < exp * 0.5 or g[2] > exp * 2: return None
    e2, y2 = eyes_yaw(g)
    box = ((x0 + g[0] * cs) / W, (y0 + g[1] * cs) / H, g[2] * cs / W, g[3] * cs / H)
    return dict(box=box, score=float(g[14]), eye=e2, yaw=y2, pitch=pitch(g), emb=emb(crop, g), refined=True)
def analyze(full):
    """Every face in a BGR picture, as the app finds and describes it."""
    H, W = full.shape[:2]; s = 1600 / max(W, H)
    work = cv2.resize(full, (round(W * s), round(H * s)), interpolation=cv2.INTER_LINEAR) if s < 1 else full
    up = W / work.shape[1]
    out = []
    for f in detect(work):
        eye, yaw = eyes_yaw(f)
        res = refine(full, f, up) if (eye < 40 and up > 1.25) else None
        if res is None:
            ww, wh = work.shape[1], work.shape[0]
            res = dict(box=(f[0] / ww, f[1] / wh, f[2] / ww, f[3] / wh), score=float(f[14]), eye=eye, yaw=yaw, pitch=pitch(f), emb=emb(work, f), refined=False)
        res['norm'], res['flipsim'] = NORMS.get(id(res['emb']), (0.0, 0.0))
        out.append(res)
    return out

if __name__ == '__main__':
    sel = json.load(open(sys.argv[1]))
    out = []
    for person, rows in sel.items():
        for r in rows:
            full = cv2.imread(os.path.join(D, 'img', r['id'] + '.jpg'))
            if full is None: continue
            H, W = full.shape[:2]
            faces = analyze(full)
            for k, res in enumerate(faces):
                bx, by, bw, bh = res['box']
                cx, cy, side = (bx + bw / 2) * W, (by + bh / 2) * H, max(bw * W, bh * H) * 1.9
                x0, y0 = int(max(0, cx - side / 2)), int(max(0, cy - side / 2 - side * 0.08))
                c = full[y0:int(min(H, y0 + side)), x0:int(min(W, x0 + side))]
                name = f"{r['id']}_{k}"
                if c.size: cv2.imwrite(os.path.join(D, 'crops', name + '.jpg'), cv2.resize(c, (112, 112), interpolation=cv2.INTER_AREA))
                res['emb'] = res['emb'].tolist()
                out.append(dict(face=name, image=r['id'], person=person, nfaces=len(faces), big=k == 0, **res))
        print(person, sum(1 for o in out if o['person'] == person), flush=True)
    json.dump(out, open(sys.argv[2], 'w'))
