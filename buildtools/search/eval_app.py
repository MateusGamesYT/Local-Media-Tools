"""The search benchmark with the files the app ships (appmodel.py: 8-bit towers, reduced vocabulary),
against the original model's numbers (eval_search.py, fusion.py) on the same photos.

    python3 eval_app.py <work dir>

- Open Images (4,999 photos, 112 classes with 15+ human-confirmed photos): mAP of the class name in
  each of the app's languages; precision and recall where the app ends the list (MatchScale.cutoff);
  the app's 30 measurable categories alone, the model alone, and both (as GalleryRepo combines them).
- Crossmodal-3600: the first caption of the same 600 photos in each language, found among 3,600.
Writes <work dir>/eval_app.json.
"""
import json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appmodel import AppModel

work = sys.argv[1]
from huggingface_hub import hf_hub_download
M = AppModel(hf_hub_download('google/siglip2-base-patch32-256', 'tokenizer.json'))
def prob(c): return 1 / (1 + np.exp(-(M.scale * c + M.bias)))
def cal(c, cut):
    # MatchScale.calibrated: 0.5 at the query's cut.
    p, pc = prob(c), prob(cut)
    return np.where(p >= pc, 0.5 + 0.5 * np.clip((p - pc) / (1 - pc), 0, 1), 0.5 * p / pc)
def ap(scores, rel):
    order = np.argsort(-scores); r = rel[order]; hits = np.cumsum(r); prec = hits / np.arange(1, len(r) + 1)
    return float((prec * r).sum() / max(1, r.sum()))
def prf(found, rel, neg=None):
    tp = float((found & (rel > 0)).sum())
    den = found.sum() if neg is None else (found & ((rel > 0) | (neg > 0))).sum()
    p = tp / den if den else 1.0; r = tp / max(1, rel.sum())
    return p, r, 2 * p * r / max(1e-9, p + r)

data = json.load(open(f'{work}/oi-search.json')); photos = data['photos']; names = data['classes']; N = len(photos)
cache = f'{work}/emb/app_oi.npy'
if os.path.exists(cache): E = np.load(cache)
else: E = M.images([f'{work}/{p["file"]}' for p in photos]); np.save(cache, E)
ref = np.load(f'{work}/emb/siglip2-b32_oi.npy')
cos = (E * ref).sum(1)
print(f'image side, app against the original on {N} photos: cosine min {cos.min():.4f}, 1st percentile {np.percentile(cos, 1):.4f}, median {np.median(cos):.4f}')
pos, negs = {}, {}
for i, p in enumerate(photos):
    for m in p['pos']: pos.setdefault(m, set()).add(i)
    for m in p['neg']: negs.setdefault(m, set()).add(i)
classes = sorted(m for m, s in pos.items() if len(s) >= 15 and m in names)
rel = {c: np.isin(np.arange(N), list(pos[c])).astype(float) for c in classes}
neg = {c: np.isin(np.arange(N), list(negs.get(c, ()))).astype(float) for c in classes}
res = {}
Q = json.load(open(f'{work}/queries.json'))
for lang in ['en', 'pt', 'es', 'fr', 'de', 'it', 'ru', 'zh', 'ja', 'ko']:
    qs = [names[c] if lang == 'en' else Q[lang].get(names[c]) for c in classes]
    idx = [k for k, q in enumerate(qs) if q]
    S = E @ M.texts([qs[k] for k in idx]).T
    aps = [ap(S[:, j], rel[classes[k]]) for j, k in enumerate(idx)]
    F = S >= M.cut(S.max(0, keepdims=True))
    pr = np.array([prf(F[:, j], rel[classes[k]]) for j, k in enumerate(idx)])
    pv = np.mean([prf(F[:, j], rel[classes[k]], neg[classes[k]])[0] for j, k in enumerate(idx)])
    res[lang] = {'map': float(np.mean(aps)), 'precision': float(pr[:, 0].mean()), 'precision_checked': float(pv), 'recall': float(pr[:, 1].mean()), 'f1': float(pr[:, 2].mean())}
    print(f'{lang}: {len(idx)} classes, mAP {np.mean(aps):.3f}; where the list ends precision {pr[:, 0].mean():.3f} (checked photos {pv:.3f}), recall {pr[:, 1].mean():.3f}, F1 {pr[:, 2].mean():.3f}', flush=True)

# The app's categories, the model, and both (GalleryRepo: the higher of the two calibrated scores).
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gallery', 'scripts'))
from taxonomy import CATS
B = np.load(f'{work}/baseline.npz'); keys = list(B['keys']); BS = B['scores']
inv = {}
for m, n in names.items(): inv.setdefault(n, []).append(m)
rows = []
for c in CATS:
    if c['key'] not in keys: continue
    r = np.zeros(N); ng = np.zeros(N)
    for m in (x for l in c['oi'] for x in inv.get(l, [])): r[list(pos.get(m, ()))] = 1; ng[list(negs.get(m, ()))] = 1
    if r.sum() < 15: continue
    rows.append((c, r, np.where(r > 0, 0, ng)))
CS = E @ M.texts([c['name'] for c, _, _ in rows]).T
out = []
for k, (c, r, ng) in enumerate(rows):
    cs = BS[:, keys.index(c['key'])]; sc = CS[:, k]; cut = M.cut(sc.max()); fused = np.maximum(cs, cal(sc, cut))
    out.append([ap(cs, r), *prf(cs >= 0.5, r), prf(cs >= 0.5, r, ng)[0], ap(sc, r), *prf(sc >= cut, r), prf(sc >= cut, r, ng)[0],
                ap(fused, r), *prf(fused >= 0.5, r), prf(fused >= 0.5, r, ng)[0]])
o = np.array(out).mean(0)
res['categories'] = {'n': len(rows), 'app_1_14': o[0:5].tolist(), 'model': o[5:10].tolist(), 'both': o[10:15].tolist()}
print(f'{len(rows)} categories (AP, precision, recall, F1, precision among checked photos):')
print('  1.14 categories  ' + ' '.join(f'{x:.3f}' for x in o[0:5]))
print('  the model alone  ' + ' '.join(f'{x:.3f}' for x in o[5:10]))
print('  both (1.15)      ' + ' '.join(f'{x:.3f}' for x in o[10:15]))

# Crossmodal-3600 captions.
xm = json.load(open(f'{work}/xm3600.json'))
xc = f'{work}/emb/app_xm.npy'
if os.path.exists(xc): X = np.load(xc)
else: X = M.images([f'{work}/{r["file"]}' for r in xm]); np.save(xc, X)
pick = set(np.random.default_rng(0).permutation(len(xm))[:600].tolist())
for lang in ['en', 'pt', 'es', 'fr', 'de', 'it', 'ru', 'zh', 'ja', 'ko']:
    q = [(i, r['captions'][lang][0]) for i, r in enumerate(xm) if i in pick and r['captions'].get(lang)]
    S3 = M.texts([t for _, t in q]) @ X.T
    rank = np.array([(S3[j] > S3[j, i]).sum() for j, (i, _) in enumerate(q)])
    res[f'xm_{lang}'] = [float((rank < k).mean()) for k in (1, 5, 10)]
    print(f'  XM3600 {lang}: R@1 {res[f"xm_{lang}"][0]:.3f}  R@5 {res[f"xm_{lang}"][1]:.3f}  R@10 {res[f"xm_{lang}"][2]:.3f}', flush=True)
json.dump(res, open(f'{work}/eval_app.json', 'w'), indent=1)
