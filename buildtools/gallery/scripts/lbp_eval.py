"""Calibrate the classical LBP face descriptor (fallback) — numpy port of gallery/core/Lbp.kt."""
import cv2, numpy as np, os, sys, glob, csv, random
G = sys.argv[1]
det = cv2.FaceDetectorYN.create(f"{G}/models/face_detection_yunet_2023mar.onnx", "", (320, 320), 0.6, 0.3, 500)
SIZE, CELLS, BINS = 64, 8, 59
uni = np.zeros(256, np.int32); k = 0
for v in range(256):
    t = sum(((v >> b) & 1) != ((v >> ((b + 1) % 8)) & 1) for b in range(8))
    if t <= 2: uni[v] = k; k += 1
    else: uni[v] = 58
def describe(gray, cx, cy, ed):
    n = SIZE + 2; s = ed / 24.0
    u = np.arange(n); X = cx + (u - 1 - SIZE / 2) * s; Y = cy + (u - 1 - 24) * s
    h, w = gray.shape
    xx, yy = np.meshgrid(np.clip(X, 0, w - 1), np.clip(Y, 0, h - 1))
    x0 = np.floor(xx).astype(int); y0 = np.floor(yy).astype(int); x1 = np.minimum(x0 + 1, w - 1); y1 = np.minimum(y0 + 1, h - 1)
    ax = xx - x0; ay = yy - y0
    g = gray.astype(np.float32)
    crop = (g[y0, x0] * (1 - ax) + g[y0, x1] * ax) * (1 - ay) + (g[y1, x0] * (1 - ax) + g[y1, x1] * ax) * ay
    c = crop[1:-1, 1:-1]
    code = np.zeros_like(c, np.int32)
    nb = [(-1, -1), (-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1)]
    for bit, (dy, dx) in enumerate(nb):
        code |= ((crop[1 + dy:n - 1 + dy, 1 + dx:n - 1 + dx] >= c).astype(np.int32) << bit)
    lab = uni[code]
    cell = SIZE // CELLS
    hist = np.zeros((CELLS, CELLS, BINS), np.float32)
    for i in range(CELLS):
        for j in range(CELLS):
            hist[i, j] = np.bincount(lab[i*cell:(i+1)*cell, j*cell:(j+1)*cell].ravel(), minlength=BINS)
    hist = np.sqrt(hist / (cell * cell)).ravel()
    return hist / max(np.linalg.norm(hist), 1e-6)
def faces_of(img):
    h, w = img.shape[:2]; det.setInputSize((w, h)); _, f = det.detect(img)
    return [] if f is None else sorted(f, key=lambda r: -r[2] * r[3])
def desc(img, row):
    re, le = row[4:6], row[6:8]
    mid = (re + le) / 2; ed = np.linalg.norm(le - re)
    return describe(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY), mid[0], mid[1], ed), ed
# deepface pairs
D = {}
for f in glob.glob(f"{G}/faces/*.jpg"):
    img = cv2.imread(f); fs = faces_of(img)
    if fs: D[os.path.basename(f)] = desc(img, fs[0])[0]
pos, neg = [], []
for r in csv.DictReader(open(f"{G}/faces/master.csv")):
    a, b = r['file_x'], r['file_y']
    if a in D and b in D: (pos if r['Decision'] == 'Yes' else neg).append(float(D[a] @ D[b]))
pos, neg = np.array(pos), np.array(neg)
print('deepface pos', len(pos), 'neg', len(neg), 'pos min/med', pos.min().round(3), np.median(pos).round(3), 'neg max/med', neg.max().round(3), np.median(neg).round(3))
# BIWI: every 8th frame (same frames as the SFace calibration)
import json
B = []
seqs = sorted(d for d in os.listdir(f"{G}/data/biwi_head_pose") if d.isdigit())
for s in seqs:
    for fpath in sorted(glob.glob(f"{G}/data/biwi_head_pose/{s}/frame_*_rgb.jpg"))[::8]:
        img = cv2.imread(fpath); fs = faces_of(img)
        if not fs: continue
        row = fs[0]; d, ed = desc(img, row)
        yaw = abs(row[8] - (row[4] + row[6]) / 2) / max(ed, 1e-3)
        B.append((s, d, float(row[14]), float(ed), float(yaw)))
print('biwi faces', len(B))
same = {"22": "07", "15": "03", "18": "05"}; amb = {"08", "11", "02", "21", "19"}
B = [b for b in B if b[0] not in amb]
P = [same.get(b[0], b[0]) for b in B]
E = np.array([b[1] for b in B]); S = E @ E.T
iu = np.triu_indices(len(B), 1)
lab = np.array(P)[iu[0]] == np.array(P)[iu[1]]
good = np.array([b[2] >= 0.75 and b[4] <= 0.45 and b[3] >= 24 for b in B])
gg = good[iu[0]] & good[iu[1]]
for name, m in [('all', np.ones_like(lab)), ('good', gg)]:
    s, l = S[iu][m], lab[m]
    ns = np.sort(s[~l])[::-1]
    for fpr in (1e-2, 1e-3, 1e-4):
        t = ns[int(len(ns) * fpr)]
        print(f'BIWI {name} FPR {fpr}: thr {t:.3f} TPR {(s[l] > t).mean():.3f}')
np.savez(f"{G}/eval/lbp_biwi.npz", E=E, P=np.array(P), good=good, score=np.array([b[2] for b in B]), eye=np.array([b[3] for b in B]), yaw=np.array([b[4] for b in B]))
