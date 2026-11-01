"""Does looking at smaller copies of a photo (as face blur does since 1.14) help the Gallery's people?

    python3 detect_scales.py photos.json [sides]     (sides: default 800,400,256)

1. The Gallery's face pipeline as shipped (extract.py with ResNet-50: YuNet at 0.62 on a copy of at
   most 1,600 px, small faces looked at again in the full photo) gives every face its id
   ("<photo>_<k>", the order the labels in labels.json use): faces_r50.json. The labelled faces of each
   person are checked against each other (a label on the wrong face would stand out).
2. The same with the smaller copies too, at the same score, boxes of one face merged (the best box's
   landmarks, as FaceMerge): faces_r50_scales.json; each new face takes the label of the old face it
   overlaps (IoU 0.5 or more).
3. The app's grouping (harness, ClusterParams.R50) on both: B-cubed recall, a person's faces in their
   main group, faces of other people and strangers in someone's group, per half of the people and all
   at once; and how many faces the new pipeline finds that the old one didn't.
"""
import json, os, sys, collections
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract
from common import WORK, HERE
from evaluate import load, split, subset, metrics, cluster_app

extract.use_r50()
sel = json.load(open(sys.argv[1]))
SIDES = [int(s) for s in (sys.argv[2] if len(sys.argv) > 2 else '800,400,256').split(',')]
R50 = {'join': 0.392, 'merge': 0.42, 'assign': 0.305, 'low': 0.332, 'margin': 0.06, 'goodScore': 0.80, 'goodYaw': 0.70, 'goodEye': 16,
       'link': 'centroid', 'lowScore': 0.80}

def same(a, b):
    # FaceMerge.same, on (x, y, w, h)
    ix = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])); iy = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    i = ix * iy; aa = a[2] * a[3]; ab = b[2] * b[3]
    return i > 0.3 * (aa + ab - i) or i > 0.6 * min(aa, ab)

def detect_scales(work):
    found = list(extract.detect(work))
    long = max(work.shape[:2])
    for side in SIDES:
        if side >= long * 0.9: continue
        s = side / long
        small = cv2.resize(work, (max(1, round(work.shape[1] * s)), max(1, round(work.shape[0] * s))), interpolation=cv2.INTER_AREA)
        for f in extract.detect(small):
            g = f.copy(); g[:14] = g[:14] / s; found.append(g)
    kept = []
    for f in sorted(found, key=lambda r: -r[14]):
        if not any(same(f[:4], k[:4]) for k in kept): kept.append(f)
    return sorted(kept, key=lambda r: -r[2] * r[3])

def run(analyze_detect, out_path):
    if os.path.exists(out_path): return json.load(open(out_path))
    orig = extract.detect
    out = []
    for person, rows in sel.items():
        for r in rows:
            full = cv2.imread(os.path.join(WORK, 'img', r['id'] + '.jpg'))
            if full is None: continue
            if analyze_detect:
                # Only the first look (the 1,600 px copy) changes; looking again at small faces is as before.
                calls = {'n': 0}
                def det(img, _o=orig):
                    calls['n'] += 1
                    return analyze_detect(img) if calls['n'] == 1 else _o(img)
                extract.detect = det
            try: faces = extract.analyze(full)
            finally: extract.detect = orig
            for k, res in enumerate(faces):
                res['emb'] = res['emb'].tolist()
                out.append(dict(face=f"{r['id']}_{k}", image=r['id'], person=person, nfaces=len(faces), big=k == 0, **res))
        print(person, sum(1 for o in out if o['person'] == person), flush=True)
    json.dump(out, open(out_path, 'w'), default=float)
    return json.load(open(out_path))

old = run(None, os.path.join(WORK, 'faces_r50.json'))
new = run(detect_scales, os.path.join(WORK, 'faces_r50_scales.json'))

# Labels → the new faces, by overlap.
labels = json.load(open(os.path.join(HERE, 'labels.json')))
extra = json.load(open(os.path.join(HERE, 'labels_extra.json')))
def iou(a, b):
    ix = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])); iy = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    i = ix * iy; return i / (a[2] * a[3] + b[2] * b[3] - i + 1e-9)
by_image = collections.defaultdict(list)
for f in new: by_image[f['image']].append(f)
mapping = {}; lost = []
for f in old:
    cands = [(iou(f['box'], g['box']), g['face']) for g in by_image[f['image']]]
    best = max(cands, default=(0, None))
    if best[0] >= 0.5: mapping[f['face']] = best[1]
    elif f['face'] in labels: lost.append(f['face'])
new_ids = set(mapping.values())
new_faces = [g for g in new if g['face'] not in new_ids]
def remap(d): return {mapping[k]: v for k, v in d.items() if k in mapping}
labels_new = remap(labels)
extra_new = {'labels': remap(extra.get('labels', {})) if isinstance(extra.get('labels'), dict) else extra.get('labels'),
             'unsure': [mapping[k] for k in extra.get('unsure', []) if k in mapping]}
for k, v in extra.items():
    if k not in extra_new: extra_new[k] = remap(v) if isinstance(v, dict) else v
json.dump(labels_new, open(os.path.join(WORK, 'labels_scales.json'), 'w'))
json.dump(extra_new, open(os.path.join(WORK, 'labels_extra_scales.json'), 'w'))
print(f'old pipeline: {len(old)} faces; with smaller copies: {len(new)} faces, {len(new_faces)} not found before; '
      f'labelled faces lost: {len(lost)} of {sum(1 for f in old if f["face"] in labels)}')

# Are the reproduced labels on the right faces? Each labelled face against its person's mean face.
E = {f['face']: np.array(f['emb']) for f in old}
per = collections.defaultdict(list)
for k, v in labels.items():
    if k in E: per[v['person']].append(k)
low = []
for p, ks in per.items():
    M = np.mean([E[k] for k in ks], 0); M /= np.linalg.norm(M)
    for k in ks:
        if E[k] @ M < 0.2: low.append((p, k, round(float(E[k] @ M), 2)))
print(f'labelled faces far from their person\'s mean face (cosine < 0.2): {len(low)} of {sum(len(v) for v in per.values())}', low[:10])

def report(faces_path, labels_path, tag):
    fs = load(faces_path, labels_path)
    A, B = split(fs)
    for nm, sub in (('half A', subset(fs, A)), ('half B', subset(fs, B)), ('all', fs)):
        lab = cluster_app(sub, R50); m = metrics(sub, lab)
        strangers = sum(1 for f, l in zip(sub, lab) if f['label'] is None and l >= 0 and
                        any(g['label'] for g, ll in zip(sub, lab) if ll == l))
        print(f'{tag:14s} {nm:6s} recall {m["R"]:.3f}  precision {m["P"]:.3f}  main group {m["main_share"]:.2f}  alone {m["alone"]:.2f}  '
              f'wrong faces {m["wrong_faces"]}  unlabelled faces in a labelled person\'s group {strangers}')

json.dump(old, open(os.path.join(WORK, 'faces.json'), 'w'), default=float)
report(os.path.join(WORK, 'faces_r50.json'), os.path.join(HERE, 'labels.json'), 'shipped')
report(os.path.join(WORK, 'faces_r50_scales.json'), os.path.join(WORK, 'labels_scales.json'), 'smaller copies')
