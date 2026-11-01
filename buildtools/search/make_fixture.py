"""The search tests' real photos: Open Images photos (CC BY 2.0) of sixteen kinds of things, three each
(the first three of the benchmark with that label among the photos where it is the only one of the
sixteen), and twelve photos with none of them; scaled to 320 px. Their descriptions come from the
app's converted image model (TFLite, 8-bit), the queries' from its text model through the app's
reduced vocabulary, exactly as the app computes them.

    python3 make_fixture.py <work dir>

Writes app/src/test/resources/search: photos/*.jpg, CREDITS.tsv, photos.tsv (file, labels, packed
description in base64) and queries.tsv (query, language, the label it looks for, packed description),
and contact.jpg in <work dir> (to look the photos over).
"""
import base64, json, os, struct, sys
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

work = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, '..', '..')
assets = os.path.join(root, 'app', 'src', 'main', 'assets')
out = os.path.join(root, 'app', 'src', 'test', 'resources', 'search')
os.makedirs(os.path.join(out, 'photos'), exist_ok=True)

KINDS = ['Dog', 'Cat', 'Car', 'Cake', 'Bicycle', 'Boat', 'Guitar', 'Snow', 'Airplane', 'Train', 'Balloon', 'Umbrella',
         'Pizza', 'Book', 'Horse', 'Bird']
data = json.load(open(f'{work}/oi-search.json')); names = data['classes']
mid = {n: m for m, n in names.items() if n in KINDS}
picked, others = [], []
for p in data['photos']:
    have = [n for n in KINDS if mid[n] in p['pos']]
    if len(have) == 1 and sum(1 for q in picked if q[1] == have[0]) < 3: picked.append((p, have[0]))
    elif not have and len(others) < 12 and len(p['pos']) >= 2: others.append((p, ''))
rows = picked + others
assert len(picked) == 3 * len(KINDS), [n for n in KINDS if sum(1 for q in picked if q[1] == n) < 3]

def pack(e):
    m = float(np.abs(e).max()); s = m / 127 if m > 0 else 1.0
    return struct.pack('>f', s) + np.clip(np.round(e / s), -127, 127).astype(np.int8).tobytes()

from huggingface_hub import hf_hub_download
from appmodel import AppModel
M = AppModel(hf_hub_download('google/siglip2-base-patch32-256', 'tokenizer.json'))
embed_image = M.image

credits = ['# file\tauthor\ttitle\tsource\tlicence\tchanges']
lines = ['# file\tlabel (one of the sixteen kinds, or empty)\tthe app\'s description of it ([Embedding.pack], base64)']
thumbs = []
for p, label in rows:
    im = Image.open(f'{work}/{p["file"]}').convert('RGB')
    s = 320 / max(im.size)
    if s < 1: im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    name = os.path.basename(p['file'])
    im.save(os.path.join(out, 'photos', name), quality=88)
    # As the app: the description is made from the stored photo.
    e = embed_image(Image.open(os.path.join(out, 'photos', name)).convert('RGB'))
    c = p['credit']
    credits.append(f"{name}\t{c['author']}\t{c['title']}\t{c['url']}\t{c['license']}\tscaled to 320 px")
    lines.append(f"{name}\t{label}\t{base64.b64encode(pack(e)).decode()}")
    thumbs.append(im.resize((160, 120)))
open(os.path.join(out, 'CREDITS.tsv'), 'w').write('\n'.join(credits) + '\n')
open(os.path.join(out, 'photos.tsv'), 'w').write('\n'.join(lines) + '\n')
sheet = Image.new('RGB', (160 * 10, 120 * ((len(thumbs) + 9) // 10)))
for k, t in enumerate(thumbs): sheet.paste(t, (160 * (k % 10), 120 * (k // 10)))
sheet.save(f'{work}/contact.jpg', quality=80)

# Queries, through the app's text path (TextTokenizer's rows with the shipped tables → TFLite).
cfg = M.cfg
embed_text = M.text
QUERIES = [('dog', 'en', 'Dog'), ('cachorro', 'pt', 'Dog'), ('a cat', 'en', 'Cat'), ('gato', 'es', 'Cat'), ('voiture', 'fr', 'Car'),
           ('birthday cake', 'en', 'Cake'), ('Kuchen', 'de', 'Cake'), ('bicicletta', 'it', 'Bicycle'), ('велосипед', 'ru', 'Bicycle'),
           ('boats on the water', 'en', 'Boat'), ('barco', 'pt', 'Boat'), ('someone playing the guitar', 'en', 'Guitar'), ('ギター', 'ja', 'Guitar'),
           ('snow', 'en', 'Snow'), ('neige', 'fr', 'Snow'), ('飞机', 'zh', 'Airplane'), ('an airplane', 'en', 'Airplane'), ('기차', 'ko', 'Train'),
           ('a train', 'en', 'Train'), ('balloons', 'en', 'Balloon'), ('umbrella', 'en', 'Umbrella'), ('Regenschirm', 'de', 'Umbrella'),
           ('pizza', 'en', 'Pizza'), ('libro', 'es', 'Book'), ('книга', 'ru', 'Book'), ('horse', 'en', 'Horse'), ('cavalo', 'pt', 'Horse'),
           ('a bird', 'en', 'Bird'), ('鳥', 'ja', 'Bird'), ('volcano erupting at night', 'en', '')]
ql = ['# query\tlanguage\tthe kind it looks for (empty: none of the photos)\tthe app\'s description of it (base64)']
for q, lang, want in QUERIES: ql.append(f"{q}\t{lang}\t{want}\t{base64.b64encode(pack(embed_text(q))).decode()}")
open(os.path.join(out, 'queries.tsv'), 'w', encoding='utf-8').write('\n'.join(ql) + '\n')
open(os.path.join(out, 'model.tsv'), 'w').write(f"logit_scale\t{cfg['logit_scale']}\nlogit_bias\t{cfg['logit_bias']}\n")

# How the fixture searches with the app's threshold (the JVM test checks the same).
E = np.array([np.frombuffer(base64.b64decode(l.split('\t')[2])[4:], np.int8) * struct.unpack('>f', base64.b64decode(l.split('\t')[2])[:4])[0] for l in lines[1:]])
labels = [l.split('\t')[1] for l in lines[1:]]
thr = (np.log(0.0004 / (1 - 0.0004)) - cfg['logit_bias']) / cfg['logit_scale']
for q, lang, want in QUERIES:
    s = E @ embed_text(q)
    found = [labels[i] for i in np.argsort(-s) if s[i] >= thr]
    top = [labels[i] for i in np.argsort(-s)[:3]]
    print(f'{q:28s} {lang} want {want or "-":9s} found {len(found):2d}: right {sum(1 for f in found if f == want)}/3, top 3 {top}')
