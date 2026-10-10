"""Scores duplicate grouping on the simulated camera roll: 1.6.0's rules (a faithful port of
Duplicates.group) against the new ones.

    python evaluate.py features.npz
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from library import load, score


def quantized(emb):
    """The app caches embeddings as int8 with a per-photo scale, then normalises again."""
    s = np.abs(emb).max(axis=1, keepdims=True) / 127
    q = np.clip(np.round(emb / s), -127, 127) * s
    return q / np.linalg.norm(q, axis=1, keepdims=True)


class UF:
    def __init__(self, n): self.p = list(range(n))
    def find(self, x):
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]; x = self.p[x]
        return x
    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb: self.p[rb] = ra


def old_rules(lib, hash_distance=6, similar=0.82, burst=0.70, burst_ms=90_000):
    f = lib['f']
    n = len(lib['ids'])
    dh = f['dhash'].astype(np.uint64)
    emb = quantized(f['emb'])
    sizes = f['sizes']
    taken = lib['taken']
    uf = UF(n)
    # Re-saved: hash distance and same aspect ratio.
    x = dh[:, None] ^ dh[None, :]
    dist = np.zeros((n, n), dtype=np.int32)
    for b in range(64):
        dist += ((x >> np.uint64(b)) & np.uint64(1)).astype(np.int32)
    ar = sizes[:, 0] / sizes[:, 1]
    for i in range(n):
        for j in np.nonzero(dist[i, i + 1:] <= hash_distance)[0] + i + 1:
            if abs(ar[i] - ar[j]) / ar[i] < 0.03: uf.union(i, j)
    sim = emb @ emb.T
    timed = [i for i in range(n) if not np.isnan(taken[i])]
    timed.sort(key=lambda i: taken[i])
    for a in range(len(timed)):
        b = a + 1
        while b < len(timed) and taken[timed[b]] - taken[timed[a]] <= 3_600_000:
            i, j = timed[a], timed[b]
            need = burst if abs(taken[i] - taken[j]) <= burst_ms else similar
            if sim[i, j] >= need: uf.union(i, j)
            b += 1
    untimed = [i for i in range(n) if np.isnan(taken[i])]
    for a, i in enumerate(untimed):
        for j in untimed[a + 1:]:
            if sim[i, j] >= similar: uf.union(i, j)
        for j in timed:
            if sim[i, j] >= similar: uf.union(i, j)
    groups = {}
    for i in range(n): groups.setdefault(uf.find(i), []).append(i)
    return [g for g in groups.values() if len(g) > 1]




def new_rules(lib, window_ms=600_000, use_orb=True, verbose=False):
    """The new rules (Duplicates.kt): two independent signals for every link, a time window for
    similar shots, stricter checks without capture times, and no chaining (average linkage)."""
    from signals import structure
    from rules import inliers
    f = lib['f']
    n = len(lib['ids'])
    emb = quantized(f['emb'])
    if 'g16' in f: s16, std16 = f['g16'], f['contrast']   # Duplicates.grid, as the app computes it
    else: s16, std16 = structure(f['g64'], 16)
    dh = f['dhash'].astype(np.uint64)
    ar = f['sizes'][:, 0] / f['sizes'][:, 1]
    taken = lib['taken']
    sim = emb @ emb.T
    links = {}
    def relation(i, j):
        flat = min(std16[i], std16[j]) < 4
        c = sim[i, j]
        if flat:
            d = bin(int(dh[i] ^ dh[j])).count('1')
            same_shape = abs(ar[i] - ar[j]) / max(ar[i], ar[j]) <= 0.03
            return ('dup', c) if (same_shape and c >= 0.95 and d <= 6) else None
        def orb_ok(k, cover):
            if not use_orb: return False
            inl, cov = inliers(lib, i, j)
            return inl >= k and cov >= cover
        ti, tj = taken[i], taken[j]
        timed = not (np.isnan(ti) or np.isnan(tj))
        if abs(ar[i] - ar[j]) / max(ar[i], ar[j]) > 0.03:
            # Different shapes (a screenshot of a photo, a crop): only the feature check can tell.
            if (not timed or abs(ti - tj) <= window_ms) and c >= 0.8 and orb_ok(50, 0.0): return ('similar', c)
            return None
        s = float(s16[i] @ s16[j])
        if s >= 0.95 and c >= 0.93: return ('dup', c)
        if timed and abs(ti - tj) > window_ms: return None
        if timed:
            if (c >= 0.7 and s >= 0.7) or (c >= 0.85 and s >= 0.5) or (c >= 0.7 and orb_ok(30, 0.1)) or (c >= 0.85 and orb_ok(25, 0.0)):
                return ('similar', c)
        elif (c >= 0.8 and (s >= 0.75 or orb_ok(50, 0.0))) or (c >= 0.92 and s >= 0.5):
            return ('similar', c)
        return None
    cand = np.argwhere(np.triu(sim >= 0.7, 1))
    for i, j in cand:
        r = relation(i, j)
        if r: links[(i, j)] = r
    # Average linkage: two groups join only if at least half of the pairs between them are linked.
    parent = list(range(n)); members = {i: [i] for i in range(n)}
    def find(x):
        while parent[x] != x: parent[x] = parent[parent[x]]; x = parent[x]
        return x
    def linked(a, b): return (min(a, b), max(a, b)) in links
    for (i, j), (k, c) in sorted(links.items(), key=lambda kv: -kv[1][1]):
        ri, rj = find(i), find(j)
        if ri == rj: continue
        A, B = members[ri], members[rj]
        hit = sum(1 for a in A for b in B if linked(a, b))
        if hit * 2 >= len(A) * len(B):
            parent[rj] = ri; members[ri] = A + B; del members[rj]
    return [g for g in members.values() if len(g) > 1]


def main():
    lib = load(sys.argv[1], extra_path=sys.argv[2] if len(sys.argv) > 2 else None)
    old = old_rules(lib)
    score(lib, old, '1.6.0 rules')
    print('  largest groups:', sorted((len(g) for g in old), reverse=True)[:8])
    for orb in (False, True):
        new = new_rules(lib, use_orb=orb)
        c, bad, ex = score(lib, new, 'new rules' + (' + feature check' if orb else ''))
        print('  largest groups:', sorted((len(g) for g in new), reverse=True)[:8])
        for g in ex[:5]: print('  bad group:', [lib['ids'][i] for i in g])


if __name__ == '__main__':
    main()
