"""Propose each person's face in each of their photos, for checking by eye.
Single-face photos: that face. Group photos: the face that recurs most across the person's other
photos (cosine >= 0.30 to a face in another photo), if it recurs at all."""
import json, collections, os, sys, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import WORK
faces = json.load(open(os.path.join(WORK, 'faces.json')))
by = collections.defaultdict(lambda: collections.defaultdict(list))
for f in faces: by[f['person']][f['image']].append(f)
out = []
for p, imgs in by.items():
    allf = [f for v in imgs.values() for f in v]
    E = np.array([f['emb'] for f in allf]); S = E @ E.T
    img_of = [f['image'] for f in allf]
    rec = {}
    for i, f in enumerate(allf):
        others = {img_of[j] for j in np.where(S[i] >= 0.30)[0] if img_of[j] != f['image']}
        rec[f['face']] = len(others)
    for im, fs in imgs.items():
        if len(fs) == 1: out.append(dict(face=fs[0]['face'], person=p, why='single')); continue
        best = max(fs, key=lambda f: (rec[f['face']], f['box'][2]))
        if rec[best['face']] >= 1: out.append(dict(face=best['face'], person=p, why='group'))
json.dump(out, open(os.path.join(WORK, 'proposals.json'), 'w'))
c = collections.Counter(o['why'] for o in out); print(len(out), dict(c))
