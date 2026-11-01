"""Real photos with QR codes and barcodes, from Open Images V7 (Flickr photos, CC BY 2.0): every
validation and test photo people confirmed shows a "QR code" or "Barcode" (image-level labels).

    python3 fetch_codes.py <work dir>

Needs <work dir>/oi/oidv7-val-annotations-human-imagelabels.csv, oidv7-test-annotations-human-
imagelabels.csv and validation/test-images-with-rotation.csv. Downloads the photos (CVDF mirror,
rotation applied, at most 2,048 px) and writes <work dir>/codes.json: per photo its labels and credit.
There are no boxes: eval_codes.py draws what the app finds for checking by eye.
"""
import csv, io, json, os, sys, urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

work = sys.argv[1]
LABELS = {'/m/03ffw1': 'QR code', '/m/0ggbx': 'Barcode'}
found = defaultdict(set); split_of = {}
for split, name in (('validation', 'val'), ('test', 'test')):
    with open(f'{work}/oi/oidv7-{name}-annotations-human-imagelabels.csv') as f:
        for r in csv.DictReader(f):
            if r['LabelName'] in LABELS and r['Confidence'] == '1':
                found[r['ImageID']].add(LABELS[r['LabelName']]); split_of[r['ImageID']] = split
meta = {}
for split in ('validation', 'test'):
    with open(f'{work}/oi/{split}-images-with-rotation.csv') as f:
        for r in csv.DictReader(f):
            if r['ImageID'] in found: meta[r['ImageID']] = r

def fetch(i):
    split = split_of[i]; out_dir = f'{work}/img/{split}'; os.makedirs(out_dir, exist_ok=True)
    path = f'{out_dir}/{i}.jpg'
    if not os.path.exists(path):
        try:
            data = urllib.request.urlopen(f'https://open-images-dataset.s3.amazonaws.com/{split}/{i}.jpg', timeout=60).read()
            im = Image.open(io.BytesIO(data)).convert('RGB')
            rot = meta[i]['Rotation']
            if rot and float(rot) != 0: im = im.rotate(-float(rot), expand=True)
            if max(im.size) > 2048:
                s = 2048 / max(im.size); im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
            im.save(path, quality=93)
        except Exception as e:
            print('skip', i, e, file=sys.stderr); return None
    m = meta[i]
    return {'id': i, 'split': split, 'file': f'img/{split}/{i}.jpg', 'labels': sorted(found[i]),
            'credit': {'author': m['Author'], 'title': m['Title'], 'license': m['License'], 'url': m['OriginalLandingURL']}}

with ThreadPoolExecutor(16) as ex:
    rows = [r for r in ex.map(fetch, sorted(i for i in found if i in meta)) if r]
json.dump(rows, open(f'{work}/codes.json', 'w'), indent=0)
print(len(rows), 'photos:', sum('QR code' in r['labels'] for r in rows), 'with a QR code,', sum('Barcode' in r['labels'] for r in rows), 'with a barcode')
