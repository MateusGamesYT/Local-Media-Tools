import sys, os, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evaluate import *
from common import WORK, HERE
faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
A, B = split(faces)
halves = {'A': subset(faces, A), 'B': subset(faces, B)}
tune = sys.argv[1]; other = 'B' if tune == 'A' else 'A'
print('bystanders', {k: sum(1 for f in v if not f['label']) for k, v in halves.items()})
def run(fs, p):
    lab = cluster_py(fs, p); m = metrics(fs, lab); h = harm(fs, lab)
    return m, h
space = dict(join=[0.40, 0.42, 0.44, 0.46, 0.48, 0.50], merge=[0.6, 0.65, 0.7], assign=[0.40, 0.42, 0.44, 0.46, 0.48],
             low=[0.42, 0.44, 0.46, 0.48, 0.5], margin=[0.0, 0.03, 0.06], goodYaw=[0.45, 0.6, 0.7, 1.0], goodEye=[16, 20, 24],
             goodScore=[0.7, 0.75, 0.8], lowEye=[0, 8], lowScore=[0.75, 0.8, 0.85, 0.88])
rnd = random.Random(7); res = []
for it in range(int(sys.argv[2])):
    p = {k: rnd.choice(v) for k, v in space.items()}; p['link'] = 'centroid'
    m, h = run(halves[tune], p)
    res.append((m['R'] - 0.006 * h['bad'] - 0.02 * m['impure_groups'], m['R'], h['bad'], m['impure_groups'], p))
res.sort(key=lambda r: -r[0])
json.dump(res, open(os.path.join(WORK, f'search_{tune}.json'), 'w'))
for r in res[:6]:
    mo, ho = run(halves[other], r[4])
    print(f"{tune} obj={r[0]:.3f} R={r[1]:.3f} bad={r[2]} imp={r[3]} | {other} R={mo['R']:.3f} P={mo['P']:.3f} bad={ho['bad']} imp={mo['impure_groups']} gpp={mo['groups_per_person']:.2f}")
    print('    ', r[4])
