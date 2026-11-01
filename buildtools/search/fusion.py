"""How should the Gallery's search use SigLIP 2 next to its categories? Measured on the benchmark's
Open Images photos (verified labels; unverified photos count as wrong, so precision is a lower bound).

    python3 fusion.py <work dir>

- Where to cut the ranked list: one threshold on SigLIP's match probability for every query,
  chosen on half of the Open Images classes and checked on the other half (and the other way round).
- The app's categories (1.14) against SigLIP with the category's English name, and combined
  (a photo is found when either says so), for the categories with 15+ confirmed photos.
"""
import json, os, sys
import numpy as np, torch

work = sys.argv[1]
from transformers import AutoModel, AutoProcessor
RID = 'google/siglip2-base-patch32-256'
m = AutoModel.from_pretrained(RID).eval(); proc = AutoProcessor.from_pretrained(RID)
scale, bias = float(m.logit_scale.exp()), float(m.logit_bias)

@torch.no_grad()
def texts(ts):
    x = proc(text=[t.lower() for t in ts], padding='max_length', max_length=64, truncation=True, return_tensors='pt')
    e = m.get_text_features(**x)
    if not torch.is_tensor(e): e = e.pooler_output
    return torch.nn.functional.normalize(e, dim=-1).numpy()

def prob(cos): return 1 / (1 + np.exp(-(scale * cos + bias)))

def ap(scores, rel):
    order = np.argsort(-scores); r = rel[order]
    hits = np.cumsum(r); prec = hits / np.arange(1, len(r) + 1)
    return float((prec * r).sum() / max(1, r.sum()))

def prf(found, rel, neg=None):
    # With [neg] (photos where people said the class is not there), precision among the found photos
    # whose label was checked either way: closer to what a user sees than counting unchecked ones wrong.
    tp = float((found & (rel > 0)).sum())
    den = found.sum() if neg is None else (found & ((rel > 0) | (neg > 0))).sum()
    p = tp / den if den else 1.0
    r = tp / max(1, rel.sum())
    return p, r, 2 * p * r / max(1e-9, p + r)

data = json.load(open(f'{work}/oi-search.json')); photos = data['photos']; names = data['classes']
E = np.load(f'{work}/emb/siglip2-b32_oi.npy'); N = len(photos)
pos = {}
for i, p in enumerate(photos):
    for mid in p['pos']: pos.setdefault(mid, set()).add(i)
classes = sorted(mid for mid, s in pos.items() if len(s) >= 15 and mid in names)
rel = {c: np.isin(np.arange(N), list(pos[c])).astype(float) for c in classes}
negs = {}
for i, p in enumerate(photos):
    for mid in p['neg']: negs.setdefault(mid, set()).add(i)
neg = {c: np.isin(np.arange(N), list(negs.get(c, ()))).astype(float) for c in classes}
P = prob(E @ texts([names[c] for c in classes]).T)
print(f'SigLIP 2 B/32: probability = sigmoid({scale:.2f} x cosine {bias:+.2f}); {len(classes)} classes, {N} photos')

# One threshold for all queries, two-fold over the classes.
ts = np.exp(np.linspace(np.log(1e-4), np.log(0.5), 60))
def mean_f1(idx, t): return np.mean([prf(P[:, k] >= t, rel[classes[k]])[2] for k in idx])
order = np.random.default_rng(0).permutation(len(classes)); halves = [order[::2], order[1::2]]
picked = []
for a, b in ((0, 1), (1, 0)):
    t = ts[np.argmax([mean_f1(halves[a], t) for t in ts])]
    pr = np.array([prf(P[:, k] >= t, rel[classes[k]]) for k in halves[b]])
    picked.append(t)
    print(f'threshold chosen on half {a + 1}: {t:.4f}; on half {b + 1}: precision {pr[:, 0].mean():.3f}, recall {pr[:, 1].mean():.3f}, F1 {pr[:, 2].mean():.3f}')
t_all = ts[np.argmax([mean_f1(range(len(classes)), t) for t in ts])]
pr = np.array([prf(P[:, k] >= t_all, rel[classes[k]]) for k in range(len(classes))])
print(f'threshold on all classes: {t_all:.4f} (cosine {(np.log(t_all / (1 - t_all)) - bias) / scale:.4f}): precision {pr[:, 0].mean():.3f}, recall {pr[:, 1].mean():.3f}, F1 {pr[:, 2].mean():.3f}; '
      f'queries finding nothing: {int((np.array([(P[:, k] >= t_all).sum() for k in range(len(classes))]) == 0).sum())}')
for t in (0.0002, t_all, 0.001, 0.003, 0.01, 0.03, 0.1):
    pr = np.array([prf(P[:, k] >= t, rel[classes[k]]) for k in range(len(classes))])
    pv = np.array([prf(P[:, k] >= t, rel[classes[k]], neg[classes[k]])[0] for k in range(len(classes))])
    print(f'  at {t:.4f}: precision {pr[:, 0].mean():.3f} (among checked photos {pv.mean():.3f}), recall {pr[:, 1].mean():.3f}, F1 {pr[:, 2].mean():.3f}')

# The app's categories against SigLIP and combined.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gallery', 'scripts'))
from taxonomy import CATS
B = np.load(f'{work}/baseline.npz'); keys = list(B['keys']); BS = B['scores']
inv = {}
for mid, n in names.items(): inv.setdefault(n, []).append(mid)
rows = []
for c in CATS:
    if c['key'] not in keys: continue
    r = np.zeros(N); ng = np.zeros(N)
    for mid in (x for l in c['oi'] for x in inv.get(l, [])): r[list(pos.get(mid, ()))] = 1; ng[list(negs.get(mid, ()))] = 1
    if r.sum() < 15: continue
    rows.append((c, r, np.where(r > 0, 0, ng)))
CP = prob(E @ texts([c['name'] for c, _, _ in rows]).T)
def cal(p, t): return np.where(p >= t, 0.5 + 0.5 * np.clip((p - t) / (1 - t), 0, 1), 0.5 * p / t)
out = []
for k, (c, r, ng) in enumerate(rows):
    cs = BS[:, keys.index(c['key'])]; sp = CP[:, k]
    fused = np.maximum(cs, cal(sp, t_all))
    out.append([ap(cs, r), *prf(cs >= 0.5, r), prf(cs >= 0.5, r, ng)[0], ap(sp, r), *prf(sp >= t_all, r), prf(sp >= t_all, r, ng)[0],
                ap(fused, r), *prf(fused >= 0.5, r), prf(fused >= 0.5, r, ng)[0]])
o = np.array(out).mean(0)
print(f'{len(rows)} categories: the app (1.14) AP {o[0]:.3f}, tagged P {o[1]:.3f} (checked {o[4]:.3f}) R {o[2]:.3f} F1 {o[3]:.3f}')
print(f'  SigLIP with the category name: AP {o[5]:.3f}, at the threshold P {o[6]:.3f} (checked {o[9]:.3f}) R {o[7]:.3f} F1 {o[8]:.3f}')
print(f'  either (the higher of the two calibrated scores): AP {o[10]:.3f}, P {o[11]:.3f} (checked {o[14]:.3f}) R {o[12]:.3f} F1 {o[13]:.3f}')
json.dump({'scale': scale, 'bias': bias, 'threshold': float(t_all), 'halves': [float(x) for x in picked],
           'categories': {c['key']: v for (c, _, _), v in zip(rows, out)}}, open(f'{work}/fusion.json', 'w'), indent=1)
