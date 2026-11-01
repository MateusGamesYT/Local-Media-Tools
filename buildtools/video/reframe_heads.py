"""Where people's heads really are in the test clips: a second, independent reference for auto reframe.

    python3 reframe_heads.py <clips dir> <det dir>

A body can be too wide for a portrait crop of a person close to the camera; the head is what has to
stay in. torchvision's Keypoint R-CNN (ResNet-50 FPN, COCO keypoints; independent of the app's models)
looks at 2.5 frames a second, on frames where reframe_detect.py's reference also looked (so its people
match those). <clip>.heads.tsv: ms, the person's box, then the head's box (around the nose, eyes and
ears it sees, widened to the head's size), all fractions of the picture.
"""
import os, sys
import numpy as np, cv2
import torch, torchvision

clips, det = sys.argv[1:3]
torch.set_num_threads(int(os.environ.get('THREADS', '2')))
model = torchvision.models.detection.keypointrcnn_resnet50_fpn(weights='COCO_V1', min_size=480, max_size=854).eval()
HEAD = [0, 1, 2, 3, 4]  # nose, eyes, ears

for name in sorted(f for f in os.listdir(clips) if f.endswith('.mp4')):
    out = os.path.join(det, name[:-4] + '.heads.tsv')
    if os.path.exists(out): continue
    cap = cv2.VideoCapture(os.path.join(clips, name)); fps = cap.get(cv2.CAP_PROP_FPS) or 25
    rows = []; n = 0
    while True:
        ok, bgr = cap.read()
        if not ok: break
        if n % 10 == 1:
            ms = int(round(n * 1000 / fps)); h, w = bgr.shape[:2]
            with torch.no_grad():
                r = model([torch.from_numpy(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)).permute(2, 0, 1).float() / 255])[0]
            for bx, sc, kp, ks in zip(r['boxes'].numpy(), r['scores'].numpy(), r['keypoints'].numpy(), r['keypoints_scores'].numpy()):
                if sc < 0.6: continue
                pts = [kp[i, :2] for i in HEAD if ks[i] > 2.0]
                if len(pts) < 2: continue
                p = np.array(pts); x0, y0 = p.min(0); x1, y1 = p.max(0)
                # Keypoints span the face; a head is about 1.6 eye-to-ear spans wide and as tall as wide, more or less.
                side = max(x1 - x0, y1 - y0, 6) * 1.6
                cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
                hb = (cx - side / 2, cy - side * 0.6, side, side * 1.1)
                rows.append((ms, bx[0] / w, bx[1] / h, (bx[2] - bx[0]) / w, (bx[3] - bx[1]) / h, hb[0] / w, hb[1] / h, hb[2] / w, hb[3] / h))
        n += 1
    with open(out, 'w') as f:
        for r in rows: f.write(f'{r[0]}\t' + '\t'.join(f'{v:.5f}' for v in r[1:]) + '\n')
    print(name, len(rows), 'heads', flush=True)
