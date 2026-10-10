"""A simulated camera roll from the test photos, and how to score the finder's groups against the labels.

Capture times: each run's shots are 3–60 s apart (inside the old "burst" window), pool photos come in
outings of unrelated shots minutes apart, and some photos have no capture time at all (saved from
apps, screenshots), as do half of the re-saved copies. The hard unrelated photos (pick_hard.py) are
taken in quick sequences of their own kind, 5–40 s apart: screens, documents and text, dark shots,
skies and snow — like screenshotting several chats or photographing pages one after another.

Pair truth: 'copy' (a re-saved copy and its original), 'same' (same moment, labels.json),
'related' (another shot of the same place or subject), 'different' (anything else).
"""
import json, os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))



def load(features_path, seed=11, extra_path=None):
    f = np.load(features_path)
    if extra_path:
        e = np.load(extra_path)
        f = {k: np.concatenate([f[k], e[k]]) for k in f.files}
    ids = [str(x) for x in f['ids']]
    idx = {k: i for i, k in enumerate(ids)}
    labels = json.load(open(os.path.join(HERE, 'labels.json')))
    rng = np.random.default_rng(seed)
    n = len(ids)
    taken = np.full(n, np.nan)
    t = 1.6e12
    moment = {}   # photo index -> (run, letter)
    related = {}  # photo index -> run, for runs whose different shots are related
    run_of = {}
    for r in labels['runs']:
        t += rng.uniform(2, 30) * 3600e3
        for k, pid in enumerate(r['ids']):
            i = idx[pid]
            t += rng.uniform(3, 60) * 1e3
            taken[i] = t
            moment[i] = (r['run'], r['moments'][k])
            run_of[i] = r['run']
            if r['related']: related[i] = r['run']
    kinds = [str(x) for x in f['kinds']]
    of = [str(x) for x in f['of']]
    hard = {}
    if extra_path:
        families = {'screens': {'Screenshot', 'Website', 'Display device', 'Computer monitor'},
                    'documents': {'Document', 'Paper', 'Receipt', 'Newspaper', 'Handwriting', 'Text', 'Font', 'Book',
                                  'Poster', 'Menu', 'Diagram', 'Map', 'Whiteboard', 'Blackboard'},
                    'dark': {'Night', 'Darkness', 'Black'}, 'sky': {'Sky', 'Cloud', 'Sunset', 'Snow'},
                    'plain': {'Black-and-white', 'Monochrome', 'Pattern', 'Close-up'}}
        for m in json.load(open(os.path.join(os.path.dirname(extra_path), 'extra.json'))):
            for fam, labels in families.items():
                if labels & set(m['hard']):
                    hard.setdefault(fam, []).append(idx[m['id']]); break
        for fam, members in hard.items():
            members = list(members)
            rng.shuffle(members)
            k = 0
            while k < len(members):
                size = int(rng.integers(6, 21))
                t += rng.uniform(2, 30) * 3600e3
                for i in members[k:k + size]:
                    t += rng.uniform(5, 40) * 1e3
                    taken[i] = t if rng.random() > 0.1 else np.nan
                k += size
    in_hard = {i for m in hard.values() for i in m}
    pool = [i for i in range(n) if kinds[i] in ('pool', 'extra') and i not in in_hard]
    rng.shuffle(pool)
    k = 0
    while k < len(pool):
        size = int(rng.integers(4, 16))
        t += rng.uniform(2, 30) * 3600e3
        for i in pool[k:k + size]:
            t += rng.uniform(10, 600) * 1e3
            taken[i] = t if rng.random() > 0.2 else np.nan
        k += size
    for i in range(n):
        if kinds[i] == 'copy':
            o = idx[of[i]]
            taken[i] = taken[o] if (rng.random() < 0.5 and not np.isnan(taken[o])) else np.nan
    return dict(f=f, ids=ids, idx=idx, kinds=kinds, of=of, taken=taken, moment=moment, related=related, run_of=run_of)


def truth(lib, i, j):
    kinds, of, idx = lib['kinds'], lib['of'], lib['idx']
    oi = idx[of[i]] if kinds[i] == 'copy' else i
    oj = idx[of[j]] if kinds[j] == 'copy' else j
    if oi == oj:
        return 'copy'
    if kinds[i] == 'copy' or kinds[j] == 'copy':
        # A copy and another photo: judged like their originals.
        return truth(lib, oi, oj) if (oi, oj) != (i, j) else 'different'
    mi, mj = lib['moment'].get(i), lib['moment'].get(j)
    if mi and mj and mi[0] == mj[0]:
        if mi[1] == mj[1]: return 'same'
        return 'related' if i in lib['related'] else 'different'
    return 'different'


def score(lib, groups, name=''):
    """groups: lists of photo indices. Prints pair counts by truth and groups with unrelated photos."""
    from collections import Counter
    c = Counter()
    bad_groups = 0
    bad_examples = []
    for g in groups:
        bad = False
        for a in range(len(g)):
            for b in range(a + 1, len(g)):
                t = truth(lib, g[a], g[b])
                c[t] += 1
                if t == 'different': bad = True
        if bad:
            bad_groups += 1
            if len(bad_examples) < 30: bad_examples.append(g)
    n = len(lib['ids'])
    # Recall over all true pairs.
    total = Counter()
    for i, (r, l) in lib['moment'].items():
        pass
    same_total = sum(1 for r in json.load(open(os.path.join(HERE, 'labels.json')))['runs']
                     for a in range(len(r['moments'])) for b in range(a + 1, len(r['moments'])) if r['moments'][a] == r['moments'][b])
    copies = [i for i in range(n) if lib['kinds'][i] == 'copy']
    by_kind = Counter(lib['ids'][i].split(':')[1] for i in copies)
    found_kind = Counter()
    gid = {}
    for k, g in enumerate(groups):
        for i in g: gid[i] = k
    for i in copies:
        o = lib['idx'][lib['of'][i]]
        if i in gid and gid.get(o) == gid[i]: found_kind[lib['ids'][i].split(':')[1]] += 1
    print(f"{name:28s} groups {len(groups):4d} | unrelated in group: {bad_groups:3d} groups, {c['different']:4d} pairs"
          f" | related pairs {c['related']:3d} | same-moment found {c['same']}/{same_total}"
          f" | copies found " + ' '.join(f"{k} {found_kind[k]}/{by_kind[k]}" for k in sorted(by_kind)))
    return c, bad_groups, bad_examples
