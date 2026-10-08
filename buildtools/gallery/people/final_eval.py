"""Old (1.4.1) vs new grouping, run through the app's own Kotlin code."""
import sys, os, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evaluate import *
from common import WORK, HERE
faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
OLD = {'join': 0.42, 'merge': 0.40, 'assign': 0.40, 'low': 0.50, 'margin': 0.05, 'goodYaw': 0.45, 'goodEye': 24, 'goodScore': 0.75, 'link': 'avg', 'lowScore': 0}
NEW = json.loads(sys.argv[1]) if len(sys.argv) > 1 else None
def extra(fs, lab):
    """People shown as more than one group (groups of 2+), and how many extra groups in total."""
    groups = collections.defaultdict(list)
    for i, l in enumerate(lab): groups[l].append(i)
    per = collections.defaultdict(set)
    for i, f in enumerate(fs):
        if f['label'] and len(groups[lab[i]]) >= 2: per[f['label']].add(lab[i])
    people = {f['label'] for f in fs if f['label']}
    split = sum(1 for p in people if len(per[p]) >= 2); ex = sum(max(0, len(per[p]) - 1) for p in people)
    none = sum(1 for p in people if not per[p])
    return split, ex, none, len(people)
A, B = split(faces)
out = {}
for nm, fs in [('half A', subset(faces, A)), ('half B', subset(faces, B)), ('all 78', faces)]:
    for tag, p in [('1.4.1', OLD), ('new', NEW)]:
        if p is None: continue
        lab = cluster_app(fs, p); m = metrics(fs, lab); h = harm(fs, lab); s, ex, none, n = extra(fs, lab)
        c = m['cond']
        print(f"{nm:7s} {tag:6s} R={m['R']:.3f} P={m['P']:.3f} main={m['main_share']:.2f} alone={m['alone']:.2f} impure={m['impure_groups']} wrong={m['wrong_faces']} "
              f"split={s}/{n} extra={ex} none={none} bystanders-in-wrong-person={h['bad']} | " + ' '.join(f"{k} {v[0]:.2f}" for k, v in c.items()))
        out[f"{nm}|{tag}"] = dict(R=m['R'], P=m['P'], main=m['main_share'], alone=m['alone'], impure=m['impure_groups'], wrong=m['wrong_faces'], split=s, extra=ex, none=none, people=n, bad=h['bad'], cond={k: v for k, v in c.items()})
json.dump(out, open(os.path.join(WORK, 'final_eval.json'), 'w'), indent=1)
