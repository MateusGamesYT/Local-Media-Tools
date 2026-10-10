"""Adds the photos that fool duplicate finders: screenshots, documents and text, dark and night shots,
plain skies and snow, black-and-white, patterns and close-ups — Open Images validation photos with
those human-verified labels (CC BY 2.0), plus ordinary ones, one per photographer and none by the
photographers already in photos.json. They are all unrelated to each other, so any group among them
is a false match.

Uses the Open Images validation photos already downloaded for the gallery calibration
(buildtools/gallery/data/oi, see buildtools/gallery/README.md) and their human labels.

    python pick_hard.py photos.json extra.json [n_total]
"""
import csv, gzip, json, os, random, re, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import META, WORK, REPO

OI_DIR = os.environ.get('OI_VAL_IMAGES') or os.path.join(REPO, 'buildtools', 'gallery', 'data', 'oi')
OI_LABELS = os.environ.get('OI_LABELS') or os.path.join(REPO, 'buildtools', 'dl', 'oi')
HARD = ['Screenshot', 'Website', 'Document', 'Text', 'Font', 'Handwriting', 'Paper', 'Book', 'Poster', 'Diagram', 'Map',
        'Whiteboard', 'Blackboard', 'Menu', 'Receipt', 'Newspaper', 'Display device', 'Computer monitor', 'Night',
        'Darkness', 'Black', 'Sky', 'Cloud', 'Snow', 'Sunset', 'Black-and-white', 'Monochrome', 'Pattern', 'Close-up']

sel = json.load(open(sys.argv[1]))
n_total = int(sys.argv[3]) if len(sys.argv) > 3 else 4500
names = {r['LabelName']: r['DisplayName'] for r in csv.DictReader(open(os.path.join(OI_LABELS, 'oidv6-class-descriptions.csv')))}
local = {f[:-4] for f in os.listdir(OI_DIR) if f.endswith('.jpg')}
labels = collections.defaultdict(set)
for r in csv.DictReader(open(os.path.join(OI_LABELS, 'validation-annotations-human-imagelabels.csv'))):
    if r['Confidence'] == '1' and r['ImageID'] in local and names.get(r['LabelName']) in HARD:
        labels[r['ImageID']].add(names[r['LabelName']])
meta = {}
for r in csv.DictReader(gzip.open(META, 'rt')):
    if r['ImageID'] in local:
        m = re.match(r'https://www.flickr.com/photos/([^/]+)/', r['Landing'])
        meta[r['ImageID']] = {'id': r['ImageID'], 'author': r['Author'], 'user': m.group(1) if m else r['Author'],
                              'title': r['Title'], 'url': r['Landing'], 'license': r['License']}
used = set()
for r in sel['runs']:
    for p in r['photos']: used.add(re.match(r'https://www.flickr.com/photos/([^/]+)/', p['url']).group(1))
for p in sel['pool']:
    used.add(re.match(r'https://www.flickr.com/photos/([^/]+)/', p['url']).group(1))
rng = random.Random(5)
ids = sorted(meta)
rng.shuffle(ids)
ids.sort(key=lambda i: 0 if i in labels else 1)  # hard ones first, random order within
out = []
for i in ids:
    m = meta[i]
    if m['user'] in used: continue
    used.add(m['user'])
    m['hard'] = sorted(labels.get(i, ()))
    out.append(m)
    if len(out) >= n_total: break
os.makedirs(os.path.join(WORK, 'img'), exist_ok=True)
for m in out:
    dst = os.path.join(WORK, 'img', m['id'] + '.jpg')
    if not os.path.exists(dst): os.symlink(os.path.join(OI_DIR, m['id'] + '.jpg'), dst)
json.dump(out, open(sys.argv[2], 'w'), indent=0)
c = collections.Counter(l for m in out for l in m['hard'])
print(len(out), 'photos,', sum(1 for m in out if m['hard']), 'hard:', dict(c.most_common()))
