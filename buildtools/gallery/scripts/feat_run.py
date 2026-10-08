"""Run the 21k feature backbone over images (centre-crop / squash / full), saving 1536-d features."""
import sys, os, time, numpy as np
from PIL import Image
import tensorflow as tf
model, mode, listfile, out = sys.argv[1:5]
it = tf.lite.Interpreter(model_path=model, num_threads=4); it.allocate_tensors()
inp = it.get_input_details()[0]; o = it.get_output_details()[0]
S = 224
def crop(img):
    w, h = img.size
    if mode == 'squash': return img.resize((S, S), Image.BILINEAR)
    if mode == 'center':   # the training-time eval crop: 224/256 of the short side
        s = int(min(w, h) * S / (S + 32)); x, y = (w - s) // 2, (h - s) // 2
        return img.crop((x, y, x + s, y + s)).resize((S, S), Image.BILINEAR)
    if mode == 'short':    # full short side square
        s = min(w, h); x, y = (w - s) // 2, (h - s) // 2
        return img.crop((x, y, x + s, y + s)).resize((S, S), Image.BILINEAR)
names = [l.strip() for l in open(listfile)]
F = np.zeros((len(names), 1536), np.float32); ok = np.zeros(len(names), bool); t0 = time.time()
for k, p in enumerate(names):
    try:
        img = Image.open(p).convert('RGB')
    except Exception:
        continue
    it.set_tensor(inp['index'], np.asarray(crop(img), np.float32)[None]); it.invoke()
    F[k] = it.get_tensor(o['index'])[0]; ok[k] = True
    if k % 500 == 0: print(k, round(time.time() - t0, 1), flush=True)
np.savez_compressed(out, feats=F, ok=ok, names=np.array([os.path.basename(n) for n in names]))
print('done', len(names), round((time.time() - t0) / len(names) * 1000), 'ms/img')
