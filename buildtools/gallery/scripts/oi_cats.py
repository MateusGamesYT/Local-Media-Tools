"""Per-category scores (detector, 21k WordNet sums, probes) on OI validation, with verified labels."""
import sys, os, csv, json, collections, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from taxonomy import CATS
import tax_check
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(G, '..', 'dl', 'oi')
feat_file = sys.argv[1] if len(sys.argv) > 1 else os.path.join(G, 'out', 'oi_feat_center.npz')
out_file = sys.argv[2] if len(sys.argv) > 2 else os.path.join(G, 'out', 'oi_scores.npz')

names = {r[0]: r[1] for r in csv.reader(open(os.path.join(D, 'oidv6-class-descriptions.csv')))}
inv = collections.defaultdict(list)
for k, v in names.items(): inv[v].append(k)

F = np.load(feat_file)
ids = [n[:-4] for n in F['names']]; feats = F['feats']; ok = F['ok'] if 'ok' in F else np.ones(len(ids), bool)
idx = {i: k for k, i in enumerate(ids)}
N = len(ids)
# Human-verified labels for these images.
pos = collections.defaultdict(set); neg = collections.defaultdict(set)
for r in csv.DictReader(open(os.path.join(D, 'validation-annotations-human-imagelabels.csv'))):
    k = idx.get(r['ImageID'])
    if k is None: continue
    (pos if r['Confidence'] == '1' else neg)[r['LabelName']].add(k)

# Classifier: full softmax over 21843 classes, summed per category subtree.
W = np.load(os.path.join(G, 'models/b3_21k/head_W.npy')); b = np.load(os.path.join(G, 'models/b3_21k/head_b.npy'))
if os.environ.get('HEAD_ASSET'):
    # Score with the head exactly as shipped (4-bit weights, half-float scales).
    import struct
    raw = open(os.environ['HEAD_ASSET'], 'rb').read()
    _, R, Dm, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24; ng = Dm // GS
    sc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
    b = np.frombuffer(raw, '<f4', R, o).copy(); o += R * 4
    pk = np.frombuffer(raw, np.uint8, R * Dm // 2, o).reshape(R, Dm // 2)
    lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
    q = np.empty((R, Dm), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi
    W = np.ascontiguousarray((q.reshape(R, ng, GS) * sc[:, :, None]).reshape(R, Dm).T)
    print('using shipped head', os.environ['HEAD_ASSET'])
cls = [np.array(tax_check.classes(c), dtype=np.int64) for c in CATS]
S_wn = np.zeros((N, len(CATS)), np.float32)
TOP = np.zeros((N, 5), np.int32)
for s in range(0, N, 512):
    lg = feats[s:s + 512] @ W + b
    lg -= lg.max(1, keepdims=True); p = np.exp(lg); p /= p.sum(1, keepdims=True)
    for j, c in enumerate(cls):
        if len(c): S_wn[s:s + 512, j] = p[:, c].sum(1)
    TOP[s:s + 512] = np.argsort(-p, 1)[:, :5]

# Detector: max score per category's COCO classes (any size; and boxes >= 1% of the photo).
labels = [l.strip() for l in open(os.path.join(G, 'meta/labels.txt'))]
det = json.load(open(os.path.join(G, 'out/oi_det.json')))
S_det = np.zeros((N, len(CATS)), np.float32); S_det_big = np.zeros((N, len(CATS)), np.float32)
for name, dets in det.items():
    k = idx.get(name[:-4])
    if k is None: continue
    for j, c in enumerate(CATS):
        want = {labels.index(x) for x in c['det']}
        for d in dets:
            if d[0] in want:
                S_det[k, j] = max(S_det[k, j], d[1])
                if (d[4] - d[2]) * (d[5] - d[3]) >= 0.01: S_det_big[k, j] = max(S_det_big[k, j], d[1])
has_det = np.zeros(N, bool)
for name in det:
    k = idx.get(name[:-4])
    if k is not None: has_det[k] = True

# Ground truth per category: 1 positive, 0 verified negative, -1 unknown.
Y = -np.ones((N, len(CATS)), np.int8)
for j, c in enumerate(CATS):
    mids = [m for l in c['oi'] for m in inv[l]]
    P = set().union(*[pos[m] for m in mids]) if mids else set()
    Nn = set().union(*[neg[m] for m in mids]) if mids else set()
    for k in Nn: Y[k, j] = 0
    for k in P: Y[k, j] = 1
Y[~ok | ~has_det] = -1
np.savez_compressed(out_file, S_wn=S_wn, S_det=S_det, S_det_big=S_det_big, Y=Y, ids=np.array(ids), top=TOP, keys=np.array([c['key'] for c in CATS]))
print('saved', out_file, 'images', int((ok & has_det).sum()))
