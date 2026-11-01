"""Builds the Gallery's search model files (SigLIP 2 B/32, Google, Apache 2.0) into app/src/main/assets.

    python3 build_search_model.py <work dir> <words per language> <embedding bits>

<work dir>/venv312 must hold Python 3.12 with tensorflow-cpu 2.16.1 and onnx2tf 1.22.3 (the app's
TFLite runtime is 2.16); weights come from the Hugging Face cache (HF_HOME).

  models/search_image_siglip2_b32.tflite   image tower, 256 x 256 RGB scaled to -1..1 → 768 numbers
                                           (L2-normalised); 8-bit weights. Its attention-pooling head
                                           is written with 3-D products only (the same computation),
                                           which onnx2tf converts correctly (its 4-D form came out wrong).
  models/search_text_siglip2_b32.tflite    text tower without its token table: 64 token embeddings
                                           [1, 64, 768] → 768 numbers (L2-normalised); 8-bit weights
  search/tokenizer.bin                     TextTokenizer: characters, byte tokens, all 580,604 merges,
                                           and each token's row in the table
  search/embeddings.bin                    the rows of the tokens of the most common N words of each of
                                           the app's ten languages (FrequencyWords, OpenSubtitles 2018/
                                           2016, CC BY-SA 4.0, used only to choose the rows) plus every
                                           single character and byte, at 8 or 4 bits with a scale per row
  search/model.json                        the score's scale and bias (SigLIP: match probability =
                                           sigmoid(scale x cosine + bias)) and the input size

Every converted part is checked against the original on real photos and texts (cosine of embeddings).
"""
import json, os, struct, subprocess, sys
import numpy as np, torch, onnx, onnxsim
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bpe import BPE

work, NWORDS, BITS = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
here = os.path.dirname(os.path.abspath(__file__))
assets = os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets')
conv = os.path.join(work, 'conv'); os.makedirs(conv, exist_ok=True)
RID = 'google/siglip2-base-patch32-256'
from transformers import AutoModel
from huggingface_hub import hf_hub_download
m = AutoModel.from_pretrained(RID).eval()
v = m.vision_model; tm = m.text_model; head = v.head
D = v.config.hidden_size; H = head.attention.num_heads; dh = D // H

class Vision(torch.nn.Module):
    def __init__(s):
        super().__init__()
        s.v = v
        W = head.attention.in_proj_weight; b = head.attention.in_proj_bias
        s.q = torch.nn.Parameter((head.probe[0, 0] @ W[:D].T + b[:D]) / dh ** 0.5, requires_grad=False)
        s.Wk = torch.nn.Parameter(W[D:2 * D].T.contiguous(), requires_grad=False); s.bk = torch.nn.Parameter(b[D:2 * D], requires_grad=False)
        s.Wv = torch.nn.Parameter(W[2 * D:].T.contiguous(), requires_grad=False); s.bv = torch.nn.Parameter(b[2 * D:], requires_grad=False)
        blk = torch.zeros(D, H)
        for h in range(H): blk[h * dh:(h + 1) * dh, h] = 1
        s.blk = torch.nn.Parameter(blk, requires_grad=False)
    def forward(s, x):
        hs = s.v.post_layernorm(s.v.encoder(s.v.embeddings(x)).last_hidden_state)
        k = torch.matmul(hs, s.Wk) + s.bk; val = torch.matmul(hs, s.Wv) + s.bv
        w = torch.matmul(torch.softmax(torch.matmul(k * s.q, s.blk), dim=1), s.blk.T)
        a = head.attention.out_proj((w * val).sum(1, keepdim=True))
        a = a + head.mlp(head.layernorm(a))
        return torch.nn.functional.normalize(a[:, 0], dim=-1)

class Text(torch.nn.Module):
    def __init__(s):
        super().__init__(); s.t = tm
        s.pos = torch.nn.Parameter(tm.embeddings.position_embedding.weight[:64][None].clone(), requires_grad=False)
    def forward(s, e):
        h = s.t.encoder(inputs_embeds=e + s.pos).last_hidden_state
        h = s.t.final_layer_norm(h)[:, -1:]
        return torch.nn.functional.normalize(s.t.head(h)[:, 0], dim=-1)

def export(mod, x, name):
    path = os.path.join(conv, name + '.onnx')
    torch.onnx.export(mod, (x,), path, input_names=['input'], output_names=['embedding'], opset_version=17, dynamo=False)
    s, ok = onnxsim.simplify(onnx.load(path)); assert ok; onnx.save(s, path)
    np.save(os.path.join(conv, 'calibration_image_sample_data_20x128x128x3_float32.npy'),
            np.random.default_rng(0).uniform(0, 1, (20, 128, 128, 3)).astype(np.float32))
    out = os.path.join(conv, 'tf_' + name)
    subprocess.run([os.path.join(work, 'venv312', 'bin', 'onnx2tf'), '-i', path, '-o', out, '-odrqt', '-nuo', '-n'],
                   check=True, cwd=conv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return os.path.join(out, name + '_dynamic_range_quant.tflite')

from ai_edge_litert.interpreter import Interpreter
def run_tflite(path, x):
    it = Interpreter(model_path=path); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]['index'], x); it.invoke()
    e = it.get_tensor(it.get_output_details()[0]['index'])[0]; return e / np.linalg.norm(e)

# Image tower.
vt = export(Vision().eval(), torch.randn(1, 3, 256, 256), 'search_image')
from PIL import Image
photos = json.load(open(os.path.join(work, 'oi-search.json')))['photos'][:50]
cos = []
for p in photos:
    im = Image.open(os.path.join(work, p['file'])).convert('RGB').resize((256, 256), Image.BILINEAR)
    x = (np.asarray(im, np.float32) / 255 - 0.5) / 0.5
    with torch.no_grad(): ref = Vision()(torch.tensor(x.transpose(2, 0, 1)[None]))[0].numpy()
    cos.append(float(run_tflite(vt, x[None]) @ ref))
print(f'image tower: 8-bit weights against the original on 50 photos: cosine min {min(cos):.4f}, median {np.median(cos):.4f}')

# Vocabulary: the tokens of the most common words of each language.
TOK = hf_hub_download(RID, 'tokenizer.json')
bpe = BPE(TOK); V = bpe.vocab
LANGS = ['en', 'pt_br', 'es', 'fr', 'de', 'it', 'ru', 'zh_cn', 'ja', 'ko']
keep = {i for t, i in V.items() if len(t) == 1 or t.startswith('<0x')} | {V[t] for t in ('<pad>', '<eos>', '<bos>', '<unk>')}
for f in LANGS:
    for line in list(open(os.path.join(work, 'freq', f'{f}_50k.txt'), encoding='utf-8'))[:NWORDS]:
        w = line.split(' ')[0].lower()
        if w: keep |= {V[p] for p in bpe.pieces(w)} | {V[p] for p in bpe.pieces(' ' + w)}
keep = sorted(keep)
row = np.full(len(V), -1, np.int32); row[keep] = np.arange(len(keep))
Wt = tm.embeddings.token_embedding.weight.detach().numpy()[keep]
q = 2 ** (BITS - 1) - 1
scale = (np.abs(Wt).max(1) / q + 1e-12).astype(np.float32)
Qt = np.clip(np.round(Wt / scale[:, None]), -q - 1, q).astype(np.int8)
os.makedirs(os.path.join(assets, 'search'), exist_ok=True)
with open(os.path.join(assets, 'search', 'embeddings.bin'), 'wb') as f:
    # magic "LMTE", version 1, rows, dim, bits; per row its float scale; then the values (4-bit: two
    # per byte, low nibble first, as signed numbers -8..7).
    f.write(struct.pack('>iiiii', 0x4C4D5445, 1, len(keep), D, BITS)); f.write(scale.astype('>f4').tobytes())
    if BITS == 8: f.write(Qt.tobytes())
    else:
        u = (Qt.astype(np.int16) & 15).astype(np.uint8).reshape(-1, 2); f.write((u[:, 0] | (u[:, 1] << 4)).tobytes())
merges = []
for mg in json.load(open(TOK))['model']['merges']:
    a, b = mg if isinstance(mg, list) else mg.split(' ')
    merges.append((V[a], V[b], V[a + b]))
chars = sorted((ord(t), i) for t, i in V.items() if len(t) == 1)
with open(os.path.join(assets, 'search', 'tokenizer.bin'), 'wb') as f:
    f.write(struct.pack('>iiiii', 0x4C4D544B, 1, len(V), V['<pad>'], V['<eos>']))
    f.write(struct.pack('>i', len(chars))); f.write(np.array(chars, '>i4').tobytes())
    f.write(np.array([V[f'<0x{b:02X}>'] for b in range(256)], '>i4').tobytes())
    f.write(struct.pack('>i', len(merges))); f.write(np.array(merges, '>i4').tobytes())
    f.write(row.astype('>i4').tobytes())

# Text tower, checked through the reduced vocabulary as the app runs it.
tt = export(Text().eval(), torch.randn(1, 64, D), 'search_text')
Wd = Qt.astype(np.float32) * scale[:, None]
made = {}
for a, b, c in merges: made.setdefault(c, (a, b))
def app_rows(text):
    out = []
    def add(t):
        if row[t] >= 0 or t not in made: out.append(row[t] if row[t] >= 0 else row[V['<pad>']]); return
        add(made[t][0]); add(made[t][1])
    for p in bpe.pieces(text.lower()): add(V[p])
    out = out[:63] + [row[V['<eos>']]]
    return out + [row[V['<pad>']]] * (64 - len(out))
Q = json.load(open(os.path.join(work, 'queries.json')))
texts = ['dog', 'a dog on the beach', 'birthday cake'] + [q for lang in Q.values() for q in list(lang.values())[:5]]
cos = []
for t in texts:
    x = Wd[app_rows(t)][None].astype(np.float32)
    with torch.no_grad(): ref = Text()(torch.tensor(x))[0].numpy()
    cos.append(float(run_tflite(tt, x) @ ref))
print(f'text tower: 8-bit weights against the original on {len(texts)} texts: cosine min {min(cos):.4f}, median {np.median(cos):.4f}')

import shutil
shutil.copy(vt, os.path.join(assets, 'models', 'search_image_siglip2_b32.tflite'))
shutil.copy(tt, os.path.join(assets, 'models', 'search_text_siglip2_b32.tflite'))
json.dump({'model': 'SigLIP 2 B/32 (google/siglip2-base-patch32-256)', 'image_size': 256,
           'logit_scale': float(m.logit_scale.exp()), 'logit_bias': float(m.logit_bias), 'rows': len(keep), 'bits': BITS},
          open(os.path.join(assets, 'search', 'model.json'), 'w'), indent=1)
for n in ('models/search_image_siglip2_b32.tflite', 'models/search_text_siglip2_b32.tflite', 'search/tokenizer.bin', 'search/embeddings.bin'):
    print(n, os.path.getsize(os.path.join(assets, n)) // 1000, 'kB')
