"""Writes the gallery's recognition assets into the app: category table, 4-bit scene head with probes, models."""
import sys, os, json, struct, shutil, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from taxonomy import CATS
import tax_check
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = sys.argv[1]                      # app/src/main/assets
TH = json.load(open(sys.argv[2]))      # thresholds json from oi_report.py
SCENE_MODEL = sys.argv[3]              # backbone tflite to ship
ONLY = set(sys.argv[4].split(',')) if len(sys.argv) > 4 and sys.argv[4] else None
labels = [l.strip() for l in open(os.path.join(G, 'meta/labels.txt'))]
probes = np.load(os.path.join(G, 'out/probes.npz'))
pkeys = list(probes['keys'])

rows, used_probes = [], []
for c in CATS:
    k = c['key']; t = TH.get(k)
    if t is None or (ONLY is not None and k not in ONLY): continue
    th = dict(zip(t['src'], t['t']))
    det = [labels.index(x) for x in c['det']] if th.get('det') is not None else []
    scene = tax_check.classes(c) if th.get('wn') is not None else []
    pi = -1
    if th.get('probe') is not None:
        if k not in used_probes: used_probes.append(k)
        pi = used_probes.index(k)
    rows.append([k, c['name'], c['group'], ','.join(c['words']), ' '.join(map(str, det)), f"{th.get('det') or 0:.3f}", '0',
                 ' '.join(map(str, scene)), f"{th.get('wn') or 0:.3f}", str(pi), f"{th.get('probe') or 0:.3f}"])
os.makedirs(os.path.join(APP, 'gallery'), exist_ok=True)
with open(os.path.join(APP, 'gallery/categories.tsv'), 'w') as f:
    f.write('# key\tname\tgroup\twords\tdetector classes\tdetector threshold\tmin box area\tclassifier rows\tclassifier threshold\tprobe\tprobe threshold\n')
    for r in rows: f.write('\t'.join(r) + '\n')
print(len(rows), 'categories;', len(used_probes), 'probes:', used_probes)

# 4-bit head, group 64, half-float scales.
W = np.load(os.path.join(G, 'models/b3_21k/head_W.npy')).astype(np.float32); b = np.load(os.path.join(G, 'models/b3_21k/head_b.npy')).astype(np.float32)
D, C = W.shape; GS = 64
Wg = W.T.reshape(C, D // GS, GS)
sc = (np.abs(Wg).max(2) / 7.0).astype(np.float16)
scf = sc.astype(np.float32)
q = np.round(Wg / np.maximum(scf[:, :, None], 1e-12)).clip(-7, 7).astype(np.int8).reshape(C, D)
lo = (q[:, 0::2] & 15).astype(np.uint8); hi = (q[:, 1::2] & 15).astype(np.uint8)
packed = (lo | (hi << 4)).astype(np.uint8)
PW = np.stack([probes['W'][pkeys.index(k)] for k in used_probes]).astype('<f4') if used_probes else np.zeros((0, D), '<f4')
PB = np.array([probes['B'][pkeys.index(k)] for k in used_probes], '<f4')
with open(os.path.join(APP, 'gallery/scene_head.bin'), 'wb') as f:
    f.write(b'LMTH'); f.write(struct.pack('<iiiii', 2, C, D, GS, len(used_probes)))
    f.write(sc.astype('<f2').tobytes()); f.write(b.astype('<f4').tobytes()); f.write(packed.tobytes())
    f.write(PW.tobytes()); f.write(PB.tobytes())
print('head', round(os.path.getsize(os.path.join(APP, 'gallery/scene_head.bin')) / 1e6, 1), 'MB')
shutil.copy(os.path.join(G, 'models/efficientdet_lite2_int8.tflite'), os.path.join(APP, 'models/efficientdet_lite2_int8.tflite'))
shutil.copy(SCENE_MODEL, os.path.join(APP, 'models/scene_effnetv2_b3_21k.tflite'))
# Golden values for the app's tests: features -> category scores computed exactly like the app.
np.save(os.path.join(G, 'out/head_q.npy'), q)
