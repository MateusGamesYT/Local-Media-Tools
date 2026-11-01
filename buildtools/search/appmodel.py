"""The app's search model as the app runs it, from the files it ships (app/src/main/assets): the TFLite
image side (photo squashed to 256 x 256 with Pillow's bilinear, colours to -1..1), and the text side
through the app's tokenizer file and reduced, low-bit token table (TextTokenizer + TokenTable)."""
import json, os, struct
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter
from bpe import BPE

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')

class AppModel:
    def __init__(self, tokenizer_json, threads=4):
        self.cfg = json.load(open(f'{ASSETS}/search/model.json'))
        self.img = Interpreter(model_path=f'{ASSETS}/models/search_image_siglip2_b32.tflite', num_threads=threads); self.img.allocate_tensors()
        self.txt = Interpreter(model_path=f'{ASSETS}/models/search_text_siglip2_b32.tflite', num_threads=threads); self.txt.allocate_tensors()
        self.bpe = BPE(tokenizer_json); V = self.bpe.vocab; self.V = V
        raw = open(f'{ASSETS}/search/tokenizer.bin', 'rb').read()
        nv = struct.unpack_from('>i', raw, 8)[0]; nc = struct.unpack_from('>i', raw, 20)[0]
        o = 24 + nc * 8 + 256 * 4; nm = struct.unpack_from('>i', raw, o)[0]; o += 4
        merges = np.frombuffer(raw, '>i4', nm * 3, o).reshape(-1, 3); o += nm * 12
        self.row = np.frombuffer(raw, '>i4', nv, o)
        self.made = {}
        for a, b, c in merges: self.made.setdefault(int(c), (int(a), int(b)))
        t = open(f'{ASSETS}/search/tokens.emb', 'rb').read()
        _, _, R, D, bits = struct.unpack_from('>iiiii', t, 0)
        sc = np.frombuffer(t, '>f4', R, 20).astype(np.float32)
        if bits == 8: Q = np.frombuffer(t, np.int8, R * D, 20 + R * 4).reshape(R, D).astype(np.float32)
        else:
            u = np.frombuffer(t, np.uint8, R * D // 2, 20 + R * 4)
            lo = (u & 15).astype(np.int8); hi = (u >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
            Q = np.empty((R, D), np.float32); Q[:, 0::2] = lo; Q[:, 1::2] = hi
        self.table = Q * sc[:, None]
        self.scale, self.bias = self.cfg['logit_scale'], self.cfg['logit_bias']

    def rows(self, text):
        out = []; row = self.row; V = self.V
        def add(tk):
            if row[tk] >= 0 or tk not in self.made: out.append(row[tk] if row[tk] >= 0 else row[V['<pad>']]); return
            add(self.made[tk][0]); add(self.made[tk][1])
        for pc in self.bpe.pieces(text.lower()): add(V[pc])
        out = out[:63] + [row[V['<eos>']]]
        return out + [row[V['<pad>']]] * (64 - len(out))

    def text(self, s):
        self.txt.set_tensor(self.txt.get_input_details()[0]['index'], self.table[self.rows(s)][None].astype(np.float32)); self.txt.invoke()
        e = self.txt.get_tensor(self.txt.get_output_details()[0]['index'])[0]; return e / np.linalg.norm(e)

    def texts(self, ss): return np.stack([self.text(s) for s in ss])

    def image(self, im):
        x = (np.asarray(im.convert('RGB').resize((256, 256), Image.BILINEAR), np.float32) / 127.5 - 1)[None]
        self.img.set_tensor(self.img.get_input_details()[0]['index'], x); self.img.invoke()
        e = self.img.get_tensor(self.img.get_output_details()[0]['index'])[0]; return e / np.linalg.norm(e)

    def images(self, paths):
        out = []
        for k, p in enumerate(paths):
            out.append(self.image(Image.open(p)))
            if k % 1000 == 0: print(f'  images {k}/{len(paths)}', flush=True)
        return np.stack(out)

    def threshold_cos(self, p=0.0004): return (np.log(p / (1 - p)) - self.bias) / self.scale
