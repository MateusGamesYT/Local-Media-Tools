"""The app's other face settings for a new face-recognition model (extract_models.py with --single first),
measured on the labelled real-people set (labels as in compare_models.py):

1. Suggestions ("is this …?", 1.7.0's measure): on each half of the people (alternate people), each
   person's faces split into 3 single faces and groups of 2–8; how alike groups of the same / of
   different people are (cosine of their sums), and single faces to groups (cosine to the group's
   mean face). A suggestion threshold sits above the most alike different people on both halves.
2. Face blur and videos: the thresholds the app uses with SFace carried over to the model at the same
   rate of different-people face pairs above them (same as compare_models.carried), with the share of
   same-person pairs above each: same person 0.40 and the tracking gate 0.20 (one view of a face, no
   mirror image, as face blur describes faces) and one person seen in several frames of a video 0.55
   (face and mirror averaged, as the gallery describes faces).
3. Merging: the most alike mean faces of two different people (all of each person's faces), per half.

    python model_settings.py <model>   (e.g. mbfapp; SFace is "sfacecrop")
"""
import os, sys, random, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from compare_models import faces, lab, emb, ROWS
from common import WORK

name = sys.argv[1] if __name__ == "__main__" else None


def single(n):
    e = np.load(os.path.join(WORK, f'models_{n}_single.npy')).astype(np.float64)[ROWS]
    return e / np.linalg.norm(e, axis=1, keepdims=True)


def suggestions(E):
    by = collections.defaultdict(list)
    for i, l in enumerate(lab):
        if l: by[l].append(E[i])
    people = sorted(by); half = {p: i % 2 for i, p in enumerate(people)}
    out = {}
    for h in (0, 1):
        rng = random.Random(7); groups = []; singles = []
        for p in people:
            if half[p] != h: continue
            es = by[p][:]; rng.shuffle(es)
            singles += [(p, e) for e in es[:3]]
            i = 3
            while i < len(es):
                k = rng.randint(2, 8); g = es[i:i + k]; i += k
                if len(g) >= 2: groups.append((p, np.sum(g, 0)))
        sg, dg, sf, df = [], [], [], []
        for a in range(len(groups)):
            for b in range(a + 1, len(groups)):
                (pa, sa), (pb, sb) = groups[a], groups[b]
                (sg if pa == pb else dg).append(float(sa @ sb / np.linalg.norm(sa) / np.linalg.norm(sb)))
        for p, e in singles:
            for q, s in groups: (sf if p == q else df).append(float(e @ s / np.linalg.norm(s)))
        out['AB'[h]] = tuple(map(np.array, (sg, dg, sf, df)))
    return out


def impostor_pairs(E, n=400000):
    rng = np.random.default_rng(0)
    i = rng.integers(0, len(faces), n); j = rng.integers(0, len(faces), n)
    keep = ((lab[i] != lab[j]) | (lab[i] == '')) & (i != j)
    return np.einsum('ij,ij->i', E[i[keep]], E[j[keep]])


def same_pairs(E):
    idx = np.where(lab != '')[0]; out = []
    for a in range(len(idx)):
        for b in range(a + 1, len(idx)):
            if lab[idx[a]] == lab[idx[b]]: out.append(float(E[idx[a]] @ E[idx[b]]))
    return np.array(out)


def carry(E0, E1, t0):
    i0, i1 = impostor_pairs(E0), impostor_pairs(E1)
    rate = (i0 >= t0).mean()
    t1 = float(np.quantile(i1, 1 - rate))
    s0, s1 = same_pairs(E0), same_pairs(E1)
    return t1, rate, (s0 >= t0).mean(), (s1 >= t1).mean()


def main():
    E = emb(name)
    print(f'1. Suggestions ({name})')
    for h, (sg, dg, sf, df) in suggestions(E).items():
        ts = np.round(np.arange(0.30, 0.70, 0.02), 2)
        print(f"  half {h}: groups of different people at most {dg.max():.3f} ({len(dg)} pairs), faces {df.max():.3f} ({len(df)})")
        print('    groups, same person above:', ' '.join(f"{t:.2f}:{(sg >= t).mean():.2f}/{int((dg >= t).sum())}" for t in ts))
        print('    faces, same person above: ', ' '.join(f"{t:.2f}:{(sf >= t).mean():.2f}/{int((df >= t).sum())}" for t in ts))
    print('2. SFace thresholds carried over (threshold → same rate of different-people pairs; same-person pairs above, SFace → model)')
    S1, M1 = single('sfacecrop'), single(name)
    for what, t in [('same person (face blur)', 0.40), ('tracking gate', 0.20)]:
        t1, rate, a, b = carry(S1, M1, t)
        print(f"  {what}: {t:.2f} → {t1:.3f} (different people above: {rate:.2e}); same person {a:.3f} → {b:.3f}")
    t1, rate, a, b = carry(emb('sfacecrop'), E, 0.55)
    print(f"  one person in several video frames: 0.55 → {t1:.3f} (different people above: {rate:.2e}); same person {a:.3f} → {b:.3f}")
    print('3. Mean faces of two different people, most alike')
    people = sorted(set(lab) - {''}); half = dict(zip(people, [i % 2 for i in range(len(people))]))
    C = {p: E[lab == p].sum(0) for p in people}
    for h in (0, 1, None):
        ps = [p for p in people if h is None or half[p] == h]
        M = np.array([C[p] / np.linalg.norm(C[p]) for p in ps]); S = M @ M.T; np.fill_diagonal(S, -9)
        a, b = np.unravel_index(np.argmax(S), S.shape)
        print(f"  {'all' if h is None else 'half ' + 'AB'[h]}: {S.max():.3f} ({ps[a]} / {ps[b]})")


if __name__ == '__main__':
    main()
