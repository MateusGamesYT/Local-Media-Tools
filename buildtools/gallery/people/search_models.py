"""The grouping thresholds for another face-recognition model (extract_models.py first): a random search
around the shipped SFace thresholds carried over to the model (compare_models.carried: the same rate of
impostor pairs above each), tuned on one half of the people with the Python port of the grouping and
the same objective as search.py, then the best few checked on the other half through the app's own
Kotlin code. Labels as in compare_models.py (with labels_extra.json).

    python search_models.py <model> A|B <iterations>   → <work>/search_<model>_<half>.json
"""
import os, sys, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_models import faces, A, B, emb, carried, SHIPPED
from evaluate import subset, cluster_py, cluster_app, metrics, harm
from common import WORK

name, tune, iters = sys.argv[1], sys.argv[2], int(sys.argv[3])
E = emb(name)
base = SHIPPED if name == 'sface' else carried(emb('sface'), E, SHIPPED)
fs = [dict(f, emb=E[k].tolist()) for k, f in enumerate(faces)]
halves = {'A': subset(fs, A), 'B': subset(fs, B)}
other = 'B' if tune == 'A' else 'A'
# Wide around the carried thresholds: they match impostor rates of single faces, while merging compares
# groups' mean faces, whose spread differs between models (two people's mean faces reach 0.55 with SFace,
# 0.50 with AuraFace, 0.29 with ResNet-50).
steps = dict(join=[-0.10, -0.08, -0.06, -0.04, -0.02, 0.0, 0.02, 0.04], assign=[-0.10, -0.08, -0.06, -0.04, -0.02, 0.0, 0.02, 0.04],
             low=[-0.08, -0.06, -0.04, -0.02, 0.0, 0.02, 0.04, 0.06], merge=[round(-0.30 + 0.03 * k, 2) for k in range(17)])
space = dict(margin=[0.0, 0.03, 0.06], goodYaw=[0.45, 0.6, 0.7, 1.0], goodEye=[16, 20, 24],
             goodScore=[0.7, 0.75, 0.8], lowScore=[0.75, 0.8, 0.85, 0.88])


def score(m, h):
    return m['R'] - 0.006 * h['bad'] - 0.02 * m['impure_groups']


rnd = random.Random(7); res = []; seen = set()
cands = [dict(base)]
for _ in range(iters):
    p = {k: round(base[k] + rnd.choice(steps[k]), 3) for k in ('join', 'merge', 'assign', 'low')}
    p.update({k: rnd.choice(v) for k, v in space.items()}); p['link'] = 'centroid'
    cands.append(p)
for p in cands:
    key = json.dumps(p, sort_keys=True)
    if key in seen: continue
    seen.add(key)
    lab = cluster_py(halves[tune], p); m = metrics(halves[tune], lab); h = harm(halves[tune], lab)
    res.append((score(m, h), m['R'], h['bad'], m['impure_groups'], p))
res.sort(key=lambda r: -r[0])
json.dump(res, open(os.path.join(WORK, f'search_{name}_{tune}.json'), 'w'))
print(f"{name}: carried {json.dumps({k: round(base[k], 3) for k in ('join', 'merge', 'assign', 'low')})}")
for r in res[:5]:
    out = []
    for hn in (tune, other):
        lab = cluster_app(halves[hn], r[4]); m = metrics(halves[hn], lab); h = harm(halves[hn], lab)
        out.append(f"{hn}{'(tuned)' if hn == tune else '(held out)'} R {m['R']:.3f} P {m['P']:.3f} main {m['main_share']:.3f} "
                   f"alone {m['alone']:.3f} wrong {m['wrong_faces']} impure {m['impure_groups']} strangers {h['bad']}")
    print(f"  obj {r[0]:.3f} | " + ' | '.join(out))
    print('     ', json.dumps(r[4]))
