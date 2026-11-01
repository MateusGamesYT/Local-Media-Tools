"""How well image-text models find photos from typed words, on real photos (fetch_search.py).

    python3 eval_search.py <work dir> <model> [--queries file.json] [--template]

Models: siglip2-b32 (google/siglip2-base-patch32-256), siglip2-b16 (google/siglip2-base-patch16-224),
xlmr-b32 (OpenCLIP xlm-roberta-base-ViT-B-32, laion5B), mclip-b32 (OpenAI CLIP ViT-B/32 image encoder
with sentence-transformers' multilingual text encoder distilled from it). Weights are read from the
Hugging Face cache (HF_HOME).

- Short queries: Open Images class names with at least 15 people-confirmed photos among the
  benchmark's photos, ranked over all of them. Average precision (AP) per query: a photo counts as
  right only when people confirmed the class (photos nobody checked count as wrong, so this is a
  lower bound); mAP is the mean over queries. --queries gives the names in other languages
  ({"pt": {"Dog": "cachorro", ...}, ...}).
- The app's categories (baseline.npz, 1.14's search) on the same photos and the same relevance
  (a category's Open Images labels, buildtools/gallery/scripts/taxonomy.py): AP from the stored
  score, and precision / recall of the tagged photos (score 0.5 and up).
- Long queries: Crossmodal-3600, the first caption of 600 photos (the same photos in every language)
  in each of the app's languages, finding that photo among all 3,600: recall at 1, 5 and 10.
Embeddings are cached in <work dir>/emb.
"""
import argparse, json, os, sys, time
import numpy as np, torch
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('model')
ap.add_argument('--queries'); ap.add_argument('--template', action='store_true'); ap.add_argument('--min-pos', type=int, default=15)
ap.add_argument('--skip-xm', action='store_true'); ap.add_argument('--xm-n', type=int, default=600)
a = ap.parse_args()
torch.set_num_threads(4)
os.makedirs(f'{a.work}/emb', exist_ok=True)
HUB = os.environ.get('HF_HOME', f'{a.work}/hf')

class Model:
    def __init__(self, name):
        self.name = name
        if name.startswith('siglip2'):
            from transformers import AutoModel, AutoProcessor
            rid = {'siglip2-b32': 'google/siglip2-base-patch32-256', 'siglip2-b16': 'google/siglip2-base-patch16-224'}[name]
            self.m = AutoModel.from_pretrained(rid).eval(); self.p = AutoProcessor.from_pretrained(rid)
        elif name == 'xlmr-b32':
            import open_clip
            self.m, _, self.pre = open_clip.create_model_and_transforms('xlm-roberta-base-ViT-B-32', pretrained='laion5b_s13b_b90k')
            self.m.eval(); self.tok = open_clip.get_tokenizer('xlm-roberta-base-ViT-B-32')
        elif name == 'mclip-b32':
            from sentence_transformers import SentenceTransformer
            self.img = SentenceTransformer('sentence-transformers/clip-ViT-B-32'); self.txt = SentenceTransformer('sentence-transformers/clip-ViT-B-32-multilingual-v1')
        else: sys.exit('unknown model ' + name)

    @torch.no_grad()
    def images(self, paths, bs=32):
        out = []
        for i in range(0, len(paths), bs):
            ims = [Image.open(p).convert('RGB') for p in paths[i:i + bs]]
            if self.name.startswith('siglip2'):
                e = self.m.get_image_features(**self.p(images=ims, return_tensors='pt'))
                if not torch.is_tensor(e): e = e.pooler_output
            elif self.name == 'xlmr-b32':
                e = self.m.encode_image(torch.stack([self.pre(im) for im in ims]))
            else:
                e = torch.tensor(self.img.encode(ims, batch_size=bs))
            out.append(torch.nn.functional.normalize(e.float(), dim=-1).numpy())
            if i % 640 == 0: print(f'  images {i}/{len(paths)}', flush=True)
        return np.concatenate(out)

    @torch.no_grad()
    def texts(self, texts, bs=64):
        out = []
        for i in range(0, len(texts), bs):
            t = texts[i:i + bs]
            if self.name.startswith('siglip2'):
                x = self.p(text=[s.lower() for s in t], padding='max_length', max_length=64, truncation=True, return_tensors='pt')
                e = self.m.get_text_features(**x)
                if not torch.is_tensor(e): e = e.pooler_output
            elif self.name == 'xlmr-b32':
                e = self.m.encode_text(self.tok(t))
            else:
                e = torch.tensor(self.txt.encode(t, batch_size=bs))
            out.append(torch.nn.functional.normalize(e.float(), dim=-1).numpy())
        return np.concatenate(out)

    def params(self):
        if self.name.startswith('siglip2'):
            return sum(p.numel() for p in self.m.vision_model.parameters()), sum(p.numel() for p in self.m.text_model.parameters())
        if self.name == 'xlmr-b32':
            return sum(p.numel() for p in self.m.visual.parameters()), sum(p.numel() for n, p in self.m.named_parameters() if not n.startswith('visual'))
        return sum(p.numel() for p in self.img.parameters()), sum(p.numel() for p in self.txt.parameters())

def average_precision(scores, rel):
    order = np.argsort(-scores); r = rel[order]
    hits = np.cumsum(r); prec = hits / np.arange(1, len(r) + 1)
    return float((prec * r).sum() / max(1, r.sum()))

M = Model(a.model)
data = json.load(open(f'{a.work}/oi-search.json')); photos = data['photos']; names = data['classes']
cache = f'{a.work}/emb/{a.model}_oi.npy'
if os.path.exists(cache): E = np.load(cache)
else:
    t0 = time.time(); E = M.images([f'{a.work}/{p["file"]}' for p in photos]); np.save(cache, E)
    print(f'{len(photos)} photos embedded in {time.time() - t0:.0f} s')
vi, tx = M.params()
# One photo at a time, one thread: a rough idea of the cost per photo.
torch.set_num_threads(1); t0 = time.time()
for p in photos[:20]: M.images([f'{a.work}/{p["file"]}'], bs=1)
ms = (time.time() - t0) / 20 * 1000; torch.set_num_threads(4)
print(f'{a.model}: image encoder {vi / 1e6:.0f} M parameters, text encoder {tx / 1e6:.0f} M; {ms:.0f} ms per photo on one CPU thread')

pos = {};
for i, p in enumerate(photos):
    for m in p['pos']: pos.setdefault(m, set()).add(i)
classes = sorted(m for m, s in pos.items() if len(s) >= a.min_pos and m in names)
N = len(photos)
def run(qtexts, label):
    T = M.texts(qtexts)
    S = E @ T.T
    aps = []
    for k, m in enumerate(classes):
        rel = np.zeros(N); rel[list(pos[m])] = 1
        aps.append(average_precision(S[:, k], rel))
    return np.array(aps), S
en = [names[m] for m in classes]
tmpl = (lambda s: f'a photo of {s.lower()}') if a.template else (lambda s: s)
aps, S = run([tmpl(s) for s in en], 'en')
print(f'short queries, English: {len(classes)} Open Images classes, mAP {aps.mean():.3f} (median AP {np.median(aps):.3f})')
res = {'model': a.model, 'classes': classes, 'ap_en': aps.tolist(), 'ms': ms, 'params': [vi, tx]}
if a.queries:
    Q = json.load(open(a.queries))
    for lang, tr in Q.items():
        idx = [k for k, m in enumerate(classes) if names[m] in tr]
        if not idx: continue
        T = M.texts([tr[names[classes[k]]] for k in idx]); S2 = E @ T.T
        ap_l = [average_precision(S2[:, j], np.isin(np.arange(N), list(pos[classes[k]])).astype(float)) for j, k in enumerate(idx)]
        res[f'ap_{lang}'] = dict(zip([classes[k] for k in idx], ap_l))
        print(f'  {lang}: {len(idx)} queries, mAP {np.mean(ap_l):.3f} (English on the same: {aps[idx].mean():.3f})')

# The app's categories (1.14) on the same photos.
bl = f'{a.work}/baseline.npz'
if os.path.exists(bl):
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gallery', 'scripts'))
    from taxonomy import CATS
    B = np.load(bl); keys = list(B['keys']); BS = B['scores']
    inv = {}
    for m, n in names.items(): inv.setdefault(n, []).append(m)
    rows = []
    for c in CATS:
        if c['key'] not in keys: continue
        mids = [m for l in c['oi'] for m in inv.get(l, [])]
        rel = np.zeros(N)
        for m in mids: rel[list(pos.get(m, ()))] = 1
        if rel.sum() < a.min_pos: continue
        bs = BS[:, keys.index(c['key'])]
        T = M.texts([tmpl(c['name'])]); sm = (E @ T.T)[:, 0]
        tagged = bs >= 0.5
        rows.append((c['key'], int(rel.sum()), average_precision(bs, rel), average_precision(sm, rel),
                     float((tagged & (rel > 0)).sum() / max(1, tagged.sum())), float((tagged & (rel > 0)).sum() / rel.sum())))
    r = np.array([x[2:] for x in rows])
    print(f'the app\'s categories ({len(rows)} with 15+ confirmed photos): AP {r[:, 0].mean():.3f}; this model with the category name as query: AP {r[:, 1].mean():.3f}; '
          f'categories\' tagged photos: precision {r[:, 2].mean():.3f}, recall {r[:, 3].mean():.3f} (lower bounds)')
    res['categories'] = rows

if not a.skip_xm and os.path.exists(f'{a.work}/xm3600.json'):
    xm = json.load(open(f'{a.work}/xm3600.json'))
    xc = f'{a.work}/emb/{a.model}_xm.npy'
    if os.path.exists(xc): X = np.load(xc)
    else: X = M.images([f'{a.work}/{r["file"]}' for r in xm]); np.save(xc, X)
    # The same photos' captions in every language (picked once, seeded), searched among all 3,600.
    pick = set(np.random.default_rng(0).permutation(len(xm))[:a.xm_n].tolist())
    for lang in ['en', 'pt', 'es', 'fr', 'de', 'it', 'ru', 'zh', 'ja', 'ko']:
        q = [(i, r['captions'][lang][0]) for i, r in enumerate(xm) if i in pick and r['captions'].get(lang)]
        T = M.texts([t for _, t in q]); S3 = T @ X.T
        rank = np.array([(S3[j] > S3[j, i]).sum() for j, (i, _) in enumerate(q)])
        res[f'xm_{lang}'] = [float((rank < k).mean()) for k in (1, 5, 10)]
        print(f'  XM3600 {lang}: R@1 {res[f"xm_{lang}"][0]:.3f}  R@5 {res[f"xm_{lang}"][1]:.3f}  R@10 {res[f"xm_{lang}"][2]:.3f}')
json.dump(res, open(f'{a.work}/result_{a.model}{"_t" if a.template else ""}.json', 'w'))
