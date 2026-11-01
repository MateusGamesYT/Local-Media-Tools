"""Where should a search's list of matches end? Measured with the app's own model files (appmodel.py) on
the benchmark's Open Images photos (4,999; 112 classes with 15+ human-confirmed photos), with the class
names in the app's ten languages (queries.json), so every rule is judged on 1,120 queries.

    python3 cutoff.py <work dir>

Rules (cos: a photo's cosine with the query; best: the best photo's):
  absolute   cos >= t                                   (one threshold for every query)
  relative   cos >= min(t, best - d) and cos >= floor   (a query whose best match scores low, as short
                                                         words in other languages do, keeps the photos
                                                         close to its best one)
Each rule's settings are chosen on half the classes and scored on the other half (and the other way
round): mean F1, precision (unverified photos count as wrong: a lower bound) and recall, per language.
"""
import json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appmodel import AppModel

work = sys.argv[1]
from huggingface_hub import hf_hub_download
M = AppModel(hf_hub_download('google/siglip2-base-patch32-256', 'tokenizer.json'))
data = json.load(open(f'{work}/oi-search.json')); photos = data['photos']; names = data['classes']; N = len(photos)
E = np.load(f'{work}/emb/app_oi.npy')
pos = {}
for i, p in enumerate(photos):
    for m in p['pos']: pos.setdefault(m, set()).add(i)
classes = sorted(m for m, s in pos.items() if len(s) >= 15 and m in names)
REL = np.array([np.isin(np.arange(N), list(pos[c])) for c in classes])
Q = json.load(open(f'{work}/queries.json'))
LANGS = ['en', 'pt', 'es', 'fr', 'de', 'it', 'ru', 'zh', 'ja', 'ko']
qc = f'{work}/emb/app_queries.npy'
if os.path.exists(qc): T = np.load(qc)
else:
    T = np.stack([M.texts([names[c] if l == 'en' else Q[l][names[c]] for c in classes]) for l in LANGS]); np.save(qc, T)
S = np.einsum('nd,lkd->lkn', E, T)       # language, class, photo
best = S.max(2, keepdims=True)

def f1s(found):
    tp = (found & REL[None]).sum(2); n = found.sum(2); r = tp / REL.sum(1)[None]
    p = np.where(n > 0, tp / np.maximum(n, 1), 1.0)
    return p, r, np.where(p + r > 0, 2 * p * r / np.maximum(p + r, 1e-9), 0)

def rule(params):
    if params[0] == 'absolute': return S >= params[1]
    _, t, d, floor = params
    return (S >= np.minimum(t, best - d)) & (S >= floor)

t0 = M.threshold_cos()
grid = [('absolute', t) for t in np.arange(0.04, 0.12, 0.0025)]
grid += [('relative', t, d, fl) for t in (t0, t0 + 0.01, t0 + 0.02) for d in np.arange(0.01, 0.06, 0.0025) for fl in np.arange(0.02, 0.08, 0.005)]
order = np.random.default_rng(0).permutation(len(classes)); halves = [order[::2], order[1::2]]
results = {}
for name in ('absolute', 'relative'):
    cands = [g for g in grid if g[0] == name]
    scores = [f1s(rule(g)) for g in cands]
    out = []
    for a, b in ((0, 1), (1, 0)):
        k = int(np.argmax([s[2][:, halves[a]].mean() for s in scores]))
        p, r, f = scores[k]
        out.append((cands[k], p[:, halves[b]], r[:, halves[b]], f[:, halves[b]]))
    P = np.concatenate([o[1] for o in out], 1); R = np.concatenate([o[2] for o in out], 1); F = np.concatenate([o[3] for o in out], 1)
    k = int(np.argmax([s[2].mean() for s in scores]))
    results[name] = {'chosen_on_halves': [o[0][1:] for o in out], 'chosen_on_all': cands[k][1:],
                     'held_out': {l: [float(P[i].mean()), float(R[i].mean()), float(F[i].mean())] for i, l in enumerate(LANGS)}}
    print(f'{name}: settings chosen on each half {[tuple(round(float(x), 4) for x in o[0][1:]) for o in out]}; on all {tuple(round(float(x), 4) for x in cands[k][1:])}')
    print(f'  held-out halves, all languages: precision {P.mean():.3f}, recall {R.mean():.3f}, F1 {F.mean():.3f}')
    print('  per language F1: ' + ' '.join(f'{l} {F[i].mean():.3f}' for i, l in enumerate(LANGS)))
    print('  per language recall: ' + ' '.join(f'{l} {R[i].mean():.3f}' for i, l in enumerate(LANGS)))
# The app's current rule (MatchScale.THRESHOLD), for comparison.
p, r, f = f1s(S >= t0)
print(f'the app now (cosine {t0:.4f}): precision {p.mean():.3f}, recall {r.mean():.3f}, F1 {f.mean():.3f}; per language F1 ' + ' '.join(f'{l} {f[i].mean():.3f}' for i, l in enumerate(LANGS)))
json.dump(results, open(f'{work}/cutoff.json', 'w'), indent=1, default=float)
