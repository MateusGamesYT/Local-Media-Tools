"""What auto reframe sees in real videos, and where the people really are.

    python3 reframe_detect.py <clips dir> <out dir>

For each clip (fetch_clips.py: Global Lives Project recordings, CC BY 3.0, 640 x 360, 25 fps):

- <clip>.thumbs: every frame as a 32 x 18 grey picture (what the app's cut detection looks at),
  after a header (frame count, then each frame's time in ms as int32).
- <clip>.found.tsv: what the app's detectors find, every second frame (12.5 a second; the harness
  takes the app's sampling from these): YuNet faces as the app runs them (score 0.62, 12 px and up)
  and EfficientDet-Lite2's people, animals and things (letterboxed into 448, the app's decoder:
  anchors, per-class NMS 0.5), at 0.35 and up. Lines: ms, kind, x, y, w, h, score (fractions).
- <clip>.ref.tsv: the reference, from a much larger, independent detector (torchvision's Faster
  R-CNN ResNet-50 FPN v2, COCO), 5 times a second between the app's samples: every person at 0.6 and up.
"""
import os, sys, struct
import numpy as np, cv2
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

clips, out = sys.argv[1:3]; os.makedirs(out, exist_ok=True)
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')

yunet = cv2.FaceDetectorYN.create(os.path.join(ASSETS, 'models', 'face_detection_yunet_2023mar.onnx'), '', (320, 320), 0.62, 0.3, 500)
det = Interpreter(model_path=os.path.join(ASSETS, 'models', 'efficientdet_lite2_int8.tflite'), num_threads=4); det.allocate_tensors()
dI = det.get_input_details()[0]; dO = {d['shape'][-1]: d['index'] for d in det.get_output_details()}
anchors = []
for level in range(3, 8):
    fm = int(np.ceil(448 / 2 ** level))
    for y in range(fm):
        for x in range(fm):
            for s in range(3):
                size = 3.0 * 2 ** (s / 3.0) / fm
                for ar in (1.0, 2.0, 0.5): anchors.append(((x + 0.5) / fm, (y + 0.5) / fm, size * ar ** 0.5, size / ar ** 0.5))
anchors = np.array(anchors, np.float32)
ANIMALS = set(range(15, 25))

def nms(boxes, scores, iou=0.5, keep=20):
    order = np.argsort(-scores); out = []
    for i in order:
        if len(out) >= keep: break
        ok = True
        for j in out:
            xx0 = max(boxes[i, 0], boxes[j, 0]); yy0 = max(boxes[i, 1], boxes[j, 1]); xx1 = min(boxes[i, 2], boxes[j, 2]); yy1 = min(boxes[i, 3], boxes[j, 3])
            inter = max(0, xx1 - xx0) * max(0, yy1 - yy0)
            a = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1]); b = (boxes[j, 2] - boxes[j, 0]) * (boxes[j, 3] - boxes[j, 1])
            if inter / max(1e-9, a + b - inter) > iou: ok = False; break
        if ok: out.append(i)
    return out

def objects(rgb, min_score=0.35):
    h, w = rgb.shape[:2]; s = 448 / max(w, h); w2, h2 = max(1, round(w * s)), max(1, round(h * s))
    box = np.zeros((448, 448, 3), np.uint8); box[:h2, :w2] = np.asarray(Image.fromarray(rgb).resize((w2, h2), Image.BILINEAR))
    det.set_tensor(dI['index'], box[None]); det.invoke()
    raw = det.get_tensor(dO[4])[0]; sc = det.get_tensor(dO[90])[0]
    cw, ch = w2 / 448, h2 / 448
    yc = raw[:, 0] * anchors[:, 3] + anchors[:, 1]; xc = raw[:, 1] * anchors[:, 2] + anchors[:, 0]
    hh = np.exp(raw[:, 2]) * anchors[:, 3]; ww = np.exp(raw[:, 3]) * anchors[:, 2]
    b = np.stack([(xc - ww / 2) / cw, (yc - hh / 2) / ch, (xc + ww / 2) / cw, (yc + hh / 2) / ch], 1).clip(0, 1)
    found = []
    for c in np.where((sc >= min_score).any(0))[0]:
        idx = np.where(sc[:, c] >= min_score)[0]
        for i in nms(b[idx], sc[idx, c]):
            k = idx[i]; x0, y0, x1, y1 = b[k]
            kind = 'PERSON' if c == 0 else 'ANIMAL' if c in ANIMALS else 'THING'
            found.append((kind, x0, y0, x1 - x0, y1 - y0, float(sc[k, c])))
    return found

import torch, torchvision
torch.set_num_threads(4)
# At 480 px (the clips are 360 px high; its default 800 would only enlarge them, five times slower).
ref = torchvision.models.detection.fasterrcnn_resnet50_fpn_v2(weights='COCO_V1', min_size=480, max_size=854).eval()

for name in sorted(f for f in os.listdir(clips) if f.endswith('.mp4')):
    base = os.path.join(out, name[:-4])
    if os.path.exists(base + '.ref.tsv'): continue
    cap = cv2.VideoCapture(os.path.join(clips, name)); fps = cap.get(cv2.CAP_PROP_FPS) or 25
    thumbs, times, found, refs = [], [], [], []
    n = 0
    while True:
        ok, bgr = cap.read()
        if not ok: break
        ms = int(round(n * 1000 / fps)); h, w = bgr.shape[:2]
        times.append(ms); thumbs.append(cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY), (32, 18), interpolation=cv2.INTER_AREA))
        if n % 2 == 0:
            yunet.setInputSize((w, h)); _, faces = yunet.detect(bgr)
            for f in (faces if faces is not None else []):
                if f[2] >= 12 and f[3] >= 12: found.append((ms, 'FACE', f[0] / w, f[1] / h, f[2] / w, f[3] / h, float(f[-1])))
            for k in objects(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)): found.append((ms,) + k)
        # The reference 5 times a second, half-way between the app's samples.
        if n % 5 == 1:
            with torch.no_grad():
                r = ref([torch.from_numpy(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float() / 255])[0]
            for bx, lab, sc in zip(r['boxes'].numpy(), r['labels'].numpy(), r['scores'].numpy()):
                if lab == 1 and sc >= 0.6: refs.append((ms, bx[0] / w, bx[1] / h, (bx[2] - bx[0]) / w, (bx[3] - bx[1]) / h, float(sc)))
        n += 1
    with open(base + '.thumbs', 'wb') as f:
        f.write(struct.pack('<i', len(times))); f.write(np.array(times, '<i4').tobytes()); f.write(np.stack(thumbs).astype(np.uint8).tobytes())
    with open(base + '.found.tsv', 'w') as f:
        for r in found: f.write(f'{r[0]}\t{r[1]}\t' + '\t'.join(f'{v:.5f}' for v in r[2:]) + '\n')
    with open(base + '.ref.tsv', 'w') as f:
        for r in refs: f.write(f'{r[0]}\t' + '\t'.join(f'{v:.5f}' for v in r[1:]) + '\n')
    print(name, n, 'frames,', len(found), 'found,', len(refs), 'reference people', flush=True)
