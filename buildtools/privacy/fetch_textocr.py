"""Real photos with every word marked and transcribed, for finding and reading text: TextOCR
(Singh et al., CVPR 2021; annotations CC BY 4.0) on Open Images photos (CC BY 2.0).

    python3 fetch_textocr.py <work dir> <max images> [seed]
    python3 fetch_textocr.py <work dir> private

Reads <work dir>/textocr/TextOCR_0.1_val.json, picks photos with at least 3 legible words (seeded),
downloads them (Open Images train set, CVDF mirror) and writes <work dir>/textocr-val.json: per photo
its words (polygon, box, text; "." marks an illegible word). With "private": every photo with a word
that is a phone number (8 or more digits) or an e-mail address, into textocr-private.json.
"""
import io, json, os, random, re, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

work = sys.argv[1]
private = sys.argv[2] == 'private'
d = json.load(open(f'{work}/textocr/TextOCR_0.1_val.json'))
EMAIL = re.compile(r'[\w.+-]+@[\w-]+\.[\w.]+')
def personal(t): return bool(EMAIL.search(t)) or sum(c.isdigit() for c in t) >= 8
if private:
    ids = sorted(i for i, anns in d['imgToAnns'].items() if any(personal(d['anns'][a]['utf8_string']) for a in anns)); n = len(ids)
else:
    n = int(sys.argv[2]); seed = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    ids = sorted(i for i, anns in d['imgToAnns'].items() if sum(1 for a in anns if d['anns'][a]['utf8_string'] != '.') >= 3)
    random.Random(seed).shuffle(ids)
out_dir = f'{work}/img/textocr'; os.makedirs(out_dir, exist_ok=True)

def fetch(i):
    path = f'{out_dir}/{i}.jpg'
    if not os.path.exists(path):
        try:
            data = urllib.request.urlopen(f'https://open-images-dataset.s3.amazonaws.com/train/{i}.jpg', timeout=60).read()
            im = Image.open(io.BytesIO(data)).convert('RGB')
            meta = d['imgs'][i]
            # TextOCR's coordinates are for the photo at its listed size.
            if im.size != (meta['width'], meta['height']): im = im.resize((meta['width'], meta['height']), Image.LANCZOS)
            im.save(path, quality=93)
        except Exception as e:
            print('skip', i, e, file=sys.stderr); return None
    words = [{'box': d['anns'][a]['bbox'], 'points': d['anns'][a]['points'], 'text': d['anns'][a]['utf8_string']} for a in d['imgToAnns'][i]]
    return {'id': i, 'file': f'img/textocr/{i}.jpg', 'words': words}

with ThreadPoolExecutor(16) as ex:
    rows = [r for r in ex.map(fetch, ids[:n]) if r]
json.dump(rows, open(f'{work}/textocr-{"private" if private else "val"}.json', 'w'))
print(len(rows), 'photos,', sum(len(r['words']) for r in rows), 'words,', sum(1 for r in rows for w in r['words'] if w['text'] != '.'), 'legible')
