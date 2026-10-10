"""Chooses the layout thresholds of Duplicates.kt: for each kind of labelled pair, how the embedding
(c) and layout (s, Duplicates.grid) are distributed, and how many of the 18 million unrelated pairs
each candidate rule would let through. Runs are split in two halves (even / odd run numbers) to see
that what works on one works on the other.

    python tune.py features.npz extra.npz
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from library import load, truth
from evaluate import quantized


def main():
    lib = load(sys.argv[1], extra_path=sys.argv[2])
    f = lib['f']; n = len(lib['ids']); kinds = lib['kinds']
    emb = quantized(f['emb']); g = f['g16'].astype(np.float32); con = f['contrast']
    pairs = {}
    for i in range(n):
        if kinds[i] == 'copy':
            pairs.setdefault('copy:' + lib['ids'][i].split(':')[1], []).append((lib['idx'][lib['of'][i]], i))
    runs = {}
    for i, (r, l) in lib['moment'].items(): runs.setdefault(r, []).append(i)
    for members in runs.values():
        for a in range(len(members)):
            for b in range(a + 1, len(members)):
                pairs.setdefault(truth(lib, members[a], members[b]), []).append((members[a], members[b]))
    print('contrast: photos below 4:', int(np.sum(con < 4)), 'of', n, '| percentiles 1/5/50:', np.percentile(con, [1, 5, 50]).round(1))
    for k in sorted(pairs):
        P = np.array(pairs[k]); c = np.sum(emb[P[:, 0]] * emb[P[:, 1]], 1); s = np.sum(g[P[:, 0]] * g[P[:, 1]], 1)
        print(f"  {k:16s} n={len(P):4d}  c {np.median(c):.2f} [{np.percentile(c, 10):.2f}..{np.percentile(c, 90):.2f}]  s {np.median(s):.2f} [{np.percentile(s, 10):.2f}..{np.percentile(s, 90):.2f}]")
    orig = np.array([i for i in range(n) if kinds[i] in ('pool', 'extra')])
    iu = np.triu_indices(len(orig), 1)
    C = (emb[orig] @ emb[orig].T)[iu]; S = (g[orig] @ g[orig].T)[iu]
    flat = np.minimum(con[orig][:, None], con[orig][None, :])[iu] < 4
    print(f'unrelated pairs: {len(C)}; s>=0.7: {np.sum(S >= 0.7)}, s>=0.8: {np.sum(S >= 0.8)}, s>=0.9: {np.sum(S >= 0.9)}, s>=0.95: {np.sum(S >= 0.95)}')
    rules = {}
    for cs in (0.6, 0.65, 0.7, 0.75):
        for ss in (0.5, 0.6, 0.7, 0.8):
            rules[f'c>={cs} & s>={ss}'] = (cs, ss)
    def count(P, cs, ss):
        P = np.array(P); c = np.sum(emb[P[:, 0]] * emb[P[:, 1]], 1); s = np.sum(g[P[:, 0]] * g[P[:, 1]], 1)
        return int(np.sum((c >= cs) & (s >= ss)))
    half = lambda P, h: [p for p in P if lib['moment'][p[0]][0] % 2 == h]
    print('rule                     same A   same B   related A  related B  different  unrelated(18M)')
    for name, (cs, ss) in rules.items():
        u = int(np.sum((C >= cs) & (S >= ss) & ~flat))
        print(f"{name:24s} {count(half(pairs['same'], 0), cs, ss):3d}/{len(half(pairs['same'], 0))}   {count(half(pairs['same'], 1), cs, ss):3d}/{len(half(pairs['same'], 1))}"
              f"   {count(half(pairs['related'], 0), cs, ss):3d}/{len(half(pairs['related'], 0))}    {count(half(pairs['related'], 1), cs, ss):3d}/{len(half(pairs['related'], 1))}"
              f"    {count(pairs['different'], cs, ss):3d}        {u}")
    for name, cond in [('dup: s>=.95 & c>=.93', (S >= 0.95) & (C >= 0.93)), ('dup: s>=.9 & c>=.93', (S >= 0.9) & (C >= 0.93)),
                       ('c>=.85 & s>=.5', (C >= 0.85) & (S >= 0.5)), ('c>=.92 & s>=.5', (C >= 0.92) & (S >= 0.5)), ('c>=.8 & s>=.75', (C >= 0.8) & (S >= 0.75))]:
        print(f'  unrelated {name}: {int(np.sum(cond & ~flat))}')
    for k in ('copy:small', 'copy:lowq', 'copy:crop', 'copy:bright'):
        P = np.array(pairs[k]); c = np.sum(emb[P[:, 0]] * emb[P[:, 1]], 1); s = np.sum(g[P[:, 0]] * g[P[:, 1]], 1)
        print(f'  {k}: dup rule (s>=.95 & c>=.93) {int(np.sum((s >= .95) & (c >= .93)))}/{len(P)}, s>=.9&c>=.93 {int(np.sum((s >= .9) & (c >= .93)))}')


main()
