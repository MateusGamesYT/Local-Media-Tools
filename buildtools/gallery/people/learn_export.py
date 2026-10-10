"""Writes the labelled faces (faces.json + labels.json) for harness/LearnHarness.kt: learn_in.bin
(per face: detector score, eye distance, yaw, person index or -1 for strangers, photo index,
embedding), learn_people.json and learn_halfA.txt (the same halves of the people as evaluate.split).

    python learn_export.py [model]
model: the embeddings of another face-recognition model (extract_models.py; labels then as in
compare_models.py, with labels_extra.json) → learn_in_<model>.bin.
"""
import sys, os, json, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from evaluate import load, split
from common import WORK, HERE

model = sys.argv[1] if len(sys.argv) > 1 else None
if model:
    from compare_models import faces, emb
    E = emb(model).astype(np.float32)
else:
    faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
    E = np.array([f['emb'] for f in faces], np.float32)
people = sorted({f['label'] for f in faces if f['label']})
images = sorted({f['image'] for f in faces})
pid = {p: i for i, p in enumerate(people)}; iid = {m: i for i, m in enumerate(images)}
out = os.path.join(WORK, 'harness')
with open(os.path.join(out, f'learn_in_{model}.bin' if model else 'learn_in.bin'), 'wb') as o:
    o.write(struct.pack('<iii', len(faces), E.shape[1], len(people)))
    for f, e in zip(faces, E):
        o.write(struct.pack('<fffii', f['score'], f['eye'], f['yaw'], pid[f['label']] if f['label'] else -1, iid[f['image']]))
        o.write(e.astype('<f4').tobytes())
json.dump(people, open(os.path.join(out, 'learn_people.json'), 'w'))
A, _ = split(faces)
open(os.path.join(out, 'learn_halfA.txt'), 'w').write(' '.join(str(pid[p]) for p in sorted(A)))
print(len(faces), 'faces,', sum(1 for f in faces if f['label']), 'labelled,', len(people), 'people')
