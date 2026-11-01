"""How much does a smaller text side cost? SigLIP 2 B/32's text tower with a reduced vocabulary
(the tokens of the most common N words of each of the app's ten languages, FrequencyWords lists from
OpenSubtitles, CC BY-SA 4.0, used only to choose tokens) and 8- or 4-bit embedding rows, tokenized by
bpe.py exactly as the app's TextTokenizer does (tokens without a row taken apart along their merge).

    python3 text_variants.py <work dir>

Short queries (Open Images classes, ten languages) and Crossmodal-3600 captions (the same 600
photos as eval_search.py), against the cached image embeddings.
"""
import json, os, sys
import numpy as np, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bpe import BPE

def average_precision(scores, rel):
    # As eval_search.py.
    order = np.argsort(-scores); r = rel[order]
    hits = np.cumsum(r); prec = hits / np.arange(1, len(r) + 1)
    return float((prec * r).sum() / max(1, r.sum()))

work = sys.argv[1]
torch.set_num_threads(4)
from transformers import AutoModel
RID = 'google/siglip2-base-patch32-256'
m = AutoModel.from_pretrained(RID).eval()
tm = m.text_model
from huggingface_hub import hf_hub_download
TOK = hf_hub_download(RID, 'tokenizer.json')
full = BPE(TOK)
V = full.vocab; ids_of = V
merges = []
for mg in json.load(open(TOK))['model']['merges']:
    a, b = mg if isinstance(mg, list) else mg.split(' ')
    merges.append((V[a], V[b], V[a + b]))
made = {}
for r, (a, b, c) in enumerate(merges):
    made.setdefault(c, (a, b))
W = tm.embeddings.token_embedding.weight.detach().numpy()

LANGS = {'en': 'en', 'pt': 'pt_br', 'es': 'es', 'fr': 'fr', 'de': 'de', 'it': 'it', 'ru': 'ru', 'zh': 'zh_cn', 'ja': 'ja', 'ko': 'ko'}
words = {l: [x.split(' ')[0].lower() for x in open(f'{work}/freq/{f}_50k.txt', encoding='utf-8') if x.split(' ')[0]] for l, f in LANGS.items()}
singles = {i for t, i in V.items() if len(t) == 1}; byte = {i for t, i in V.items() if t.startswith('<0x')}
special = {V['<pad>'], V['<eos>'], V['<unk>'], V['<bos>']}

def keep_set(n):
    s = set(singles | byte | special)
    for l, ws in words.items():
        for w in ws[:n]:
            s |= {V[p] for p in full.pieces(w)} | {V[p] for p in full.pieces(' ' + w)}
    return s

def quantize(rows, bits):
    if bits >= 32: return rows
    q = 2 ** (bits - 1) - 1
    s = np.abs(rows).max(1, keepdims=True) / q + 1e-12
    return np.clip(np.round(rows / s), -q - 1, q) * s

def encoder(table, keep, bits):
    rows = {i: k for k, i in enumerate(sorted(keep))}
    T = quantize(W[sorted(keep)], bits)
    def ids(text):
        out = []
        def add(t):
            if t in rows or t not in made: out.append(rows.get(t, rows[V['<pad>']])); return
            a, b = made[t]; add(a); add(b)
        for p in full.pieces(text.lower()): add(V[p])
        out = out[:63] + [rows[V['<eos>']]]
        return out + [rows[V['<pad>']]] * (64 - len(out))
    @torch.no_grad()
    def encode(texts):
        res = []
        for i in range(0, len(texts), 32):
            x = torch.tensor(np.stack([T[ids(t)] for t in texts[i:i + 32]]), dtype=torch.float32)
            x = x + tm.embeddings.position_embedding.weight[:64][None]
            h = tm.encoder(inputs_embeds=x).last_hidden_state
            h = tm.final_layer_norm(h)[:, -1]
            res.append(torch.nn.functional.normalize(tm.head(h), dim=-1).numpy())
        return np.concatenate(res)
    return encode

data = json.load(open(f'{work}/oi-search.json')); photos = data['photos']; names = data['classes']
E = np.load(f'{work}/emb/siglip2-b32_oi.npy'); X = np.load(f'{work}/emb/siglip2-b32_xm.npy')
xm = json.load(open(f'{work}/xm3600.json'))
pick = set(np.random.default_rng(0).permutation(len(xm))[:600].tolist())
Q = json.load(open(f'{work}/queries.json'))
pos = {}
for i, p in enumerate(photos):
    for mid in p['pos']: pos.setdefault(mid, set()).add(i)
classes = sorted(mid for mid, s in pos.items() if len(s) >= 15 and mid in names)
N = len(photos)

def evaluate(encode, label):
    out = {}
    for lang in LANGS:
        qs = [names[c] if lang == 'en' else Q[lang][names[c]] for c in classes]
        S = E @ encode(qs).T
        out[lang] = np.mean([average_precision(S[:, k], np.isin(np.arange(N), list(pos[c])).astype(float)) for k, c in enumerate(classes)])
        q = [(i, r['captions'][lang][0]) for i, r in enumerate(xm) if i in pick and r['captions'].get(lang)]
        S3 = encode([t for _, t in q]) @ X.T
        rank = np.array([(S3[j] > S3[j, i]).sum() for j, (i, _) in enumerate(q)])
        out[lang + '_r1'] = float((rank < 1).mean())
    print(f'{label:34s} mAP ' + ' '.join(f'{l} {out[l]:.3f}' for l in LANGS) + ' | XM R@1 ' + ' '.join(f'{l} {out[l + "_r1"]:.3f}' for l in LANGS), flush=True)
    return out

results = {}
allv = set(range(W.shape[0]))
results['full'] = evaluate(encoder(W, allv, 32), 'full vocabulary, float')
for n in (10000, 20000):
    k = keep_set(n)
    for bits in (8, 4):
        results[f'{n}_{bits}'] = evaluate(encoder(W, k, bits), f'{n} words/language ({len(k)} rows), {bits}-bit')
json.dump(results, open(f'{work}/text_variants.json', 'w'), default=float)
