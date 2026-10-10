"""What the duplicate finder sees of each test photo, computed the way the app does it.

The app works on the photo library's thumbnail (ContentResolver.loadThumbnail at 256×256, which
returns the cached thumbnail sub-sampled to roughly 256–511 px), from which it takes:
  - a difference hash from a 9×8 grey copy (Bitmap.createScaledBitmap, bilinear, no averaging),
  - an image embedding: MobileNet-V3 small (MediaPipe image embedder), 224×224 squeezed, 0..1 input,
  - the picture's structure: a 16×16 grey area average (Duplicates.grid), and its contrast.
Re-saved copies of pool photos are made here too (smaller and recompressed, heavily recompressed,
slightly cropped, brightened, with screenshot bars).

    python features.py photos.json features.npz              # runs, pool and re-saved copies
    python features.py extra.json extra.npz                   # the unrelated photos of pick_hard.py
"""
import io, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2
from PIL import Image, ImageEnhance
from common import WORK, EMBEDDER
from variants import variants

def thumbnail(img):
    """Like MediaStore: a ~512 px cached thumbnail, then an integer sample size for 256×256."""
    s = 512 / max(img.size)
    t = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.BILINEAR) if s < 1 else img
    k = max(1, int(min(t.width / 256, t.height / 256)))
    if k > 1:
        t = t.reduce(k)
    return np.asarray(t.convert('RGB'))


def gray(rgb):
    return (299 * rgb[..., 0].astype(np.int32) + 587 * rgb[..., 1] + 114 * rgb[..., 2]) // 1000


def area_matrix(n_in, n_out):
    """Weights of an exact area average from n_in to n_out cells (fractional pixel coverage)."""
    W = np.zeros((n_out, n_in))
    for o in range(n_out):
        a, b = o * n_in / n_out, (o + 1) * n_in / n_out
        for x in range(int(np.floor(a)), min(n_in, int(np.ceil(b)))):
            W[o, x] = min(b, x + 1) - max(a, x)
        W[o] /= W[o].sum()
    return W


def grid(rgb):
    """Duplicates.grid: grey (0.299/0.587/0.114) area-averaged to 16×16, zero mean, unit norm; and the
    standard deviation of those 256 values (contrast, in grey levels)."""
    c = rgb.astype(np.float64)  # (as uint8, 587 * green would overflow)
    g = (299 * c[..., 0] + 587 * c[..., 1] + 114 * c[..., 2]) / 1000
    h, w = g.shape
    v = (area_matrix(h, 16) @ g @ area_matrix(w, 16).T).ravel()
    v = v - v.mean()
    norm = np.sqrt((v * v).sum())
    return (v / norm if norm > 1e-6 else np.zeros(256)).astype(np.float32), float(norm / 16)


def dhash(rgb):
    tiny = cv2.resize(rgb, (9, 8), interpolation=cv2.INTER_LINEAR)
    g = gray(tiny)
    h = 0
    bit = 0
    for y in range(8):
        for x in range(8):
            if g[y, x + 1] > g[y, x]:
                h |= 1 << bit
            bit += 1
    return h


_interp = None


def embed(rgb):
    global _interp
    if _interp is None:
        import tensorflow as tf
        _interp = tf.lite.Interpreter(model_path=EMBEDDER, num_threads=4)
        _interp.allocate_tensors()
    x = cv2.resize(rgb, (224, 224), interpolation=cv2.INTER_AREA).astype(np.float32) / 255.0
    _interp.set_tensor(_interp.get_input_details()[0]['index'], x[None])
    _interp.invoke()
    v = _interp.get_tensor(_interp.get_output_details()[0]['index'])[0].astype(np.float32)
    return v / max(np.linalg.norm(v), 1e-6)


def main():
  sel = json.load(open(sys.argv[1]))
  out = sys.argv[2]
  rng = np.random.default_rng(3)
  rows = []
  copies = set()
  if isinstance(sel, list):  # extra.json
      rows = [(m['id'], 'extra', None) for m in sel]
  else:
      for r in sel['runs']:
          for p in r['photos']:
              rows.append((p['id'], 'run', None))
      pool = [p['id'] for p in sel['pool']]
      copies = set(pool[:300])
      for pid in pool:
          rows.append((pid, 'pool', None))

  ids, kinds, of, dh, embs, g16, contrast, sizes = [], [], [], [], [], [], [], []
  def add(name, kind, origin, img):
      rgb = thumbnail(img)
      ids.append(name); kinds.append(kind); of.append(origin or '')
      dh.append(dhash(rgb)); embs.append(embed(rgb))
      v, c = grid(rgb)
      g16.append(v); contrast.append(c)
      sizes.append(img.size)

  for n, (pid, kind, _) in enumerate(rows):
      img = Image.open(os.path.join(WORK, 'img', pid + '.jpg')).convert('RGB')
      add(pid, kind, None, img)
      if pid in copies:
          for vname, v in variants(img, rng):
              add(pid + ':' + vname, 'copy', pid, v)
      if n % 200 == 0:
          print(n, len(ids), flush=True)

  np.savez_compressed(out, ids=np.array(ids), kinds=np.array(kinds), of=np.array(of),
                      dhash=np.array([np.uint64(h) for h in dh]), emb=np.stack(embs), g16=np.stack(g16), contrast=np.array(contrast),
                      sizes=np.array(sizes))
  print('done', len(ids))


if __name__ == '__main__':
    main()
