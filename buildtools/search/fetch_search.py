"""Real photos for measuring the Gallery's search, with what people said is in them.

    python3 fetch_search.py <work dir> oi <n> [seed]   Open Images V7 validation photos (CC BY 2.0)
                                                        with human-verified image labels: oi-search.json
    python3 fetch_search.py <work dir> xm3600           Crossmodal-3600 (Thapliyal et al. 2022, captions
                                                        CC BY 4.0) on Open Images photos, with the
                                                        captions in the app's ten languages: xm3600.json

Needs in <work dir>/oi: oidv7-val-annotations-human-imagelabels.csv, oidv7-class-descriptions.csv,
validation/test-images-with-rotation.csv (and train-images-boxable-with-rotation.csv for credits of
XM3600's train photos); and xm3600_captions.jsonl in <work dir>. Photos are downloaded from the CVDF
mirror with their rotation applied and scaled to at most 640 px (<work dir>/img/<id>.jpg).

oi: n photos picked at random (seeded) among validation photos with verified labels, at most 4 per
photographer; per photo the classes people confirmed (positives) and rejected (negatives).
"""
import csv, io, json, os, random, sys, urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

work, what = sys.argv[1], sys.argv[2]
oi = f'{work}/oi'
os.makedirs(f'{work}/img', exist_ok=True)
LANGS = ['en', 'pt', 'es', 'fr', 'de', 'it', 'ru', 'zh', 'ja', 'ko']

def meta_for(ids):
    out = {}
    for split, name in (('validation', 'validation-images-with-rotation.csv'), ('test', 'test-images-with-rotation.csv'),
                        ('train', 'train-images-boxable-with-rotation.csv')):
        path = f'{oi}/{name}'
        if not os.path.exists(path): continue
        with open(path, newline='') as f:
            for r in csv.DictReader(f):
                if r['ImageID'] in ids and r['ImageID'] not in out: out[r['ImageID']] = (split, r)
    return out

def fetch(i, split, rot):
    path = f'{work}/img/{i}.jpg'
    if os.path.exists(path): return True
    for sp in ([split] if split else ['validation', 'test', 'train']):
        try:
            data = urllib.request.urlopen(f'https://open-images-dataset.s3.amazonaws.com/{sp}/{i}.jpg', timeout=60).read()
        except Exception:
            continue
        im = Image.open(io.BytesIO(data)).convert('RGB')
        if rot and float(rot) != 0: im = im.rotate(-float(rot), expand=True)
        if max(im.size) > 640:
            s = 640 / max(im.size); im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        im.save(path, quality=92)
        return True
    print('skip', i, file=sys.stderr)
    return False

def credit(m):
    return {'author': m['Author'], 'title': m['Title'], 'license': m['License'], 'url': m['OriginalLandingURL']} if m else None

if what == 'oi':
    n = int(sys.argv[3]); seed = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    pos = defaultdict(set); neg = defaultdict(set)
    with open(f'{oi}/oidv7-val-annotations-human-imagelabels.csv', newline='') as f:
        for r in csv.DictReader(f):
            (pos if r['Confidence'] == '1' else neg)[r['ImageID']].add(r['LabelName'])
    meta = meta_for(set(pos))
    ids = sorted(i for i in pos if i in meta and meta[i][0] == 'validation' and 'by/2.0' in meta[i][1]['License'])
    random.Random(seed).shuffle(ids)
    per = defaultdict(int); chosen = []
    for i in ids:
        a = meta[i][1]['Author']
        if per[a] >= 4: continue
        per[a] += 1; chosen.append(i)
        if len(chosen) >= n: break
    with ThreadPoolExecutor(24) as ex:
        ok = list(ex.map(lambda i: fetch(i, 'validation', meta[i][1]['Rotation']), chosen))
    rows = [{'id': i, 'file': f'img/{i}.jpg', 'pos': sorted(pos[i]), 'neg': sorted(neg[i]), 'credit': credit(meta[i][1])}
            for i, k in zip(chosen, ok) if k]
    names = {}
    with open(f'{oi}/oidv7-class-descriptions.csv', newline='') as f:
        for r in csv.reader(f): names[r[0]] = r[1]
    json.dump({'classes': names, 'photos': rows}, open(f'{work}/oi-search.json', 'w'))
    print(len(rows), 'photos')
else:
    caps = [json.loads(l) for l in open(f'{work}/xm3600_captions.jsonl')]
    ids = {c['image/key'] for c in caps}
    meta = meta_for(ids)
    with ThreadPoolExecutor(24) as ex:
        ok = dict(zip([c['image/key'] for c in caps], ex.map(
            lambda c: fetch(c['image/key'], meta.get(c['image/key'], (None, None))[0], (meta.get(c['image/key'], (None, {'Rotation': ''}))[1] or {}).get('Rotation')), caps)))
    rows = [{'id': c['image/key'], 'file': f"img/{c['image/key']}.jpg", 'locale': c['image/locale'],
             'captions': {l: c[l]['caption'] for l in LANGS if l in c}, 'credit': credit(meta.get(c['image/key'], (None, None))[1])}
            for c in caps if ok.get(c['image/key'])]
    json.dump(rows, open(f'{work}/xm3600.json', 'w'))
    print(len(rows), 'photos;', sum(1 for r in rows if r['credit']), 'with credits')
