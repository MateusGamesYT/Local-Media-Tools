"""Compares face-recognition models on the labelled real-people set (extract_models.py first).

1. Pairs: of all pairs of one person's faces, the share a model recognises at false-match rates of
   1 in 1,000 / 10,000 / 100,000 (the impostors: that person's faces against every other face,
   other people and strangers alike), on each half of the people and per condition.
2. Each face's own person nearest: the share of faces closer to their own person's mean face (without
   them) than to any other person's.
3. Grouping through the app's own Kotlin code (harness/build.sh): the shipped thresholds carried over to
   each model at the same impostor rates, then searched around on half A and checked on half B.

    python compare_models.py sface aura lvt r50 mbf [--group]
"""
import os, sys, json, collections, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from evaluate import load, split, subset, cluster_app, metrics, harm
from common import WORK, HERE

faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
order = json.load(open(os.path.join(WORK, 'models_faces.json')))
assert order == [f['face'] for f in faces]
lab = np.array([f['label'] or '' for f in faces])
A, B = split(faces)
COND = {'headwear': lambda f: 'H' in f['tags'], 'glasses': lambda f: 'G' in f['tags'], 'make-up': lambda f: 'M' in f['tags'],
        'expression': lambda f: 'E' in f['tags'], 'turned/profile': lambda f: f['yaw'] >= 0.25, 'small': lambda f: f['eye'] < 24}


def emb(name):
    e = np.load(os.path.join(WORK, f'models_{name}.npy')).astype(np.float64)
    return e / np.linalg.norm(e, axis=1, keepdims=True)


def pairs(E, people, cond=None):
    idx = np.array([i for i in range(len(faces)) if lab[i] in people])
    S = E[idx] @ E.T
    shows = np.array([cond(f) for f in faces]) if cond is not None else None
    same, imp = [], []
    for r, i in enumerate(idx):
        m = lab == lab[i]; m[i] = False
        # With a condition: pairs where at least one of the two faces shows it.
        same.append(S[r, m & (shows | shows[i])] if shows is not None else S[r, m])
        imp.append(S[r, lab != lab[i]])
    return np.concatenate(same), np.concatenate(imp)


def tar(same, imp, far):
    t = np.quantile(imp, 1 - far)
    return (same >= t).mean()


def nearest(E):
    people = sorted(set(lab) - {''})
    sums = {p: E[lab == p].sum(0) for p in people}
    ok = n = 0
    for i in np.where(lab != '')[0]:
        own = sums[lab[i]] - E[i]
        best = max((float(E[i] @ (own if p == lab[i] else sums[p]) / np.linalg.norm(own if p == lab[i] else sums[p])), p) for p in people)
        ok += best[1] == lab[i]; n += 1
    return ok / n


SHIPPED = dict(join=0.46, merge=0.65, assign=0.42, low=0.46, margin=0.06, goodScore=0.80, goodYaw=1.0, goodEye=24, lowScore=0.85, link='centroid')


def carried(E0, E1, p):
    """The shipped thresholds moved to another model at the same rate of impostor face pairs above them."""
    rng = np.random.default_rng(0)
    i = rng.integers(0, len(faces), 400000); j = rng.integers(0, len(faces), 400000)
    keep = (lab[i] != lab[j]) | (lab[i] == '')
    s0 = np.einsum('ij,ij->i', E0[i[keep]], E0[j[keep]]); s1 = np.einsum('ij,ij->i', E1[i[keep]], E1[j[keep]])
    out = dict(p)
    for k in ('join', 'merge', 'assign', 'low'):
        rate = (s0 >= p[k]).mean()
        out[k] = float(np.quantile(s1, 1 - rate)) if rate > 0 else float(s1.max())
    return out


def grouping(E, p, people):
    fs = [dict(f, emb=E[k].tolist()) for k, f in enumerate(faces)]
    sub = subset(fs, people)
    lab_ = cluster_app(sub, p)
    m = metrics(sub, lab_); h = harm(sub, lab_)
    return m, h


def main():
    names = [a for a in sys.argv[1:] if not a.startswith('--')]
    Es = {n: emb(n) for n in names}
    print('Same-person pairs recognised at a false-match rate of 1e-3 / 1e-4 / 1e-5')
    for n, E in Es.items():
        row = []
        for hn, half in [('A', A), ('B', B), ('all', A | B)]:
            s, i = pairs(E, half)
            row.append(f"{hn}: {tar(s, i, 1e-3):.3f} {tar(s, i, 1e-4):.3f} {tar(s, i, 1e-5):.3f}")
        print(f"  {n:6s} " + ' | '.join(row) + f" | own person nearest {nearest(E):.3f}")
    print('At 1e-4 by condition (all people; pairs where one face shows it)')
    for n, E in Es.items():
        cells = []
        for c, fn in COND.items():
            s, i = pairs(E, A | B, fn)
            cells.append(f"{c} {tar(s, i, 1e-4):.2f} ({len(s)})")
        print(f"  {n:6s} " + ' '.join(cells))
    if '--group' in sys.argv:
        E0 = Es['sface']
        for n, E in Es.items():
            p = SHIPPED if n == 'sface' else carried(E0, E, SHIPPED)
            for hn, half in [('A', A), ('B', B)]:
                m, h = grouping(E, p, half)
                print(f"  {n:6s} half {hn} join {p['join']:.3f} merge {p['merge']:.3f} assign {p['assign']:.3f} low {p['low']:.3f} | "
                      f"B3 R {m['R']:.3f} P {m['P']:.3f} main {m['main_share']:.3f} alone {m['alone']:.3f} groups/person {m['groups_per_person']:.2f} "
                      f"wrong {m['wrong_faces']} impure {m['impure_groups']} strangers-in-person {h['bad']}")


if __name__ == '__main__':
    main()
