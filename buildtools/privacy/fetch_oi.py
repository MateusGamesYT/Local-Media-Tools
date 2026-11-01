"""Real photos with human-checked boxes for the private-info detectors, from Open Images V7 (Flickr
photos, CC BY 2.0): faces (Human face) and licence plates (Vehicle registration plate).

    python3 fetch_oi.py <work dir> <class: plates|faces> <split: validation|test> <max images> [seed]

Reads the box annotations and image lists (validation/test-annotations-bbox.csv,
…-images-with-rotation.csv in <work dir>/oi), picks up to <max images> photos containing the class
(seeded, spread over photographers: at most 3 per author), downloads them from the CVDF mirror with
the rotation applied, at most 2,048 px, and writes <work dir>/<class>-<split>.json: per photo its
boxes (fractions; group, occluded, truncated, depiction flags) and its credit (author, title,
licence, URL). Photos that fail to download are left out.
"""
import csv, io, json, os, random, sys, urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

work, cls, split, n = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
seed = int(sys.argv[5]) if len(sys.argv) > 5 else 1
label = {'plates': '/m/01jfm_', 'faces': '/m/0dzct'}[cls]
boxes = defaultdict(list)
with open(f'{work}/oi/{split}-annotations-bbox.csv') as f:
    for r in csv.DictReader(f):
        if r['LabelName'] == label:
            boxes[r['ImageID']].append({k: float(r[k]) for k in ('XMin', 'XMax', 'YMin', 'YMax')} |
                                       {k: int(r[k]) for k in ('IsGroupOf', 'IsOccluded', 'IsTruncated', 'IsDepiction')})
meta = {}
with open(f'{work}/oi/{split}-images-with-rotation.csv') as f:
    for r in csv.DictReader(f):
        if r['ImageID'] in boxes: meta[r['ImageID']] = r
ids = sorted(i for i in boxes if i in meta and 'by/2.0' in meta[i]['License'])
random.Random(seed).shuffle(ids)
per_author = defaultdict(int); chosen = []
for i in ids:
    a = meta[i]['Author']
    if per_author[a] >= 3: continue
    per_author[a] += 1; chosen.append(i)
    if len(chosen) >= n: break
out_dir = f'{work}/img/{split}'; os.makedirs(out_dir, exist_ok=True)

def fetch(i):
    path = f'{out_dir}/{i}.jpg'
    if not os.path.exists(path):
        try:
            data = urllib.request.urlopen(f'https://open-images-dataset.s3.amazonaws.com/{split}/{i}.jpg', timeout=60).read()
            im = Image.open(io.BytesIO(data)).convert('RGB')
            rot = meta[i]['Rotation']
            if rot and float(rot) != 0: im = im.rotate(-float(rot), expand=True)  # the annotations are on the upright photo
            if max(im.size) > 2048:
                s = 2048 / max(im.size); im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
            im.save(path, quality=93)
        except Exception as e:
            print('skip', i, e, file=sys.stderr); return None
    m = meta[i]
    return {'id': i, 'split': split, 'file': f'img/{split}/{i}.jpg', 'boxes': boxes[i],
            'credit': {'author': m['Author'], 'title': m['Title'], 'license': m['License'], 'url': m['OriginalLandingURL']}}

with ThreadPoolExecutor(16) as ex:
    rows = [r for r in ex.map(fetch, chosen) if r]
json.dump(rows, open(f'{work}/{cls}-{split}.json', 'w'), indent=0)
print(cls, split, len(rows), 'photos,', sum(len(r['boxes']) for r in rows), 'boxes')
