"""Pick OI *train* images for linear probes on concepts the 21k classifier lacks."""
import csv, gzip, random, collections, sys, os, io, json
D = sys.argv[1]; out = sys.argv[2]
names = {r[0]: r[1] for r in csv.reader(open(os.path.join(D, 'oidv6-class-descriptions.csv')))}
inv = collections.defaultdict(list)
for k, v in names.items(): inv[v].append(k)
PROBES = {
    'sunset': ['Sunset', 'Sunrise', 'Dusk', 'Afterglow'], 'night': ['Night'], 'waterfall': ['Waterfall'], 'fireworks': ['Fireworks'],
    'christmas': ['Christmas tree', 'Christmas'], 'birthday': ['Birthday'], 'pool': ['Swimming pool'], 'forest': ['Forest'],
    'river': ['River'], 'sea': ['Sea', 'Ocean'], 'lake': ['Lake'], 'desert': ['Desert'], 'snow': ['Snow'], 'sky': ['Sky'],
    'beach': ['Beach'], 'document': ['Document', 'Text'], 'concert': ['Concert'], 'wedding': ['Wedding'], 'mountain': ['Mountain'],
    'city': ['City', 'Cityscape'], 'flower': ['Flower'], 'food': ['Food'],
}
mid2p = {}
for p, ls in PROBES.items():
    for l in ls:
        for m in inv[l]: mid2p.setdefault(m, []).append(p)
pos, neg = collections.defaultdict(set), collections.defaultdict(set)
okset = set(l.strip() for l in open(os.path.join(D, 'train_boxable_ids.txt')))
allimg = []
with gzip.open(os.path.join(D, 'train-human-labels.csv.gz'), 'rt') as f:
    r = csv.reader(f); next(r)
    for i, row in enumerate(r):
        img, src, mid, conf = row
        if img not in okset: continue
        if i % 97 == 0: allimg.append(img)
        for p in mid2p.get(mid, ()):
            (pos if conf == '1' else neg)[p].add(img)
random.seed(11)
pick = {}
for p in PROBES:
    P = sorted(pos[p]); N = sorted(neg[p] - pos[p]); random.shuffle(P); random.shuffle(N)
    pick[p] = dict(pos=P[:500], neg=N[:250])
    print(p, 'pos', len(P), 'neg', len(N))
rnd = sorted(set(allimg)); random.shuffle(rnd)
pick['_random'] = rnd[:3000]
pick['_random_pos'] = {r: [p for p in PROBES if r in pos[p]] for r in pick['_random']}
json.dump(pick, open(out, 'w'))
ids = set(pick['_random'])
for p in PROBES: ids |= set(pick[p]['pos']) | set(pick[p]['neg'])
print('images', len(ids))
open(out.replace('.json', '_ids.txt'), 'w').write('\n'.join(sorted(ids)))
# per random image: all positive probe labels known? store labels for random images to drop known positives
