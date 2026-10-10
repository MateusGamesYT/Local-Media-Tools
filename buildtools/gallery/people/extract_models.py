"""Describes every face of the labelled real-people set with several face-recognition models, for
comparing them (compare_models.py). The faces are found exactly as the app finds them (extract.py:
YuNet on a copy of at most 1,600 px, small faces re-detected from the full picture) and aligned the
same way (the standard 112×112 five-point alignment, OpenCV's FaceRecognizerSF.alignCrop); each model
then describes the aligned face and its mirror image, averaged. Faces are matched to faces.json by
photo and order (and checked by box), so the verified labels apply.

    python extract_models.py <model dir> name=file.onnx … [--only N]   → <work>/models_<name>.npy
    (SFace, the app's current model, is always included as "sface"; the aligned faces are kept in
    <work>/models_crops.npy.)
    python extract_models.py <model dir> name=file.tflite … --from-crops   → the same from the kept faces
    (for converted or quantised versions of a model: .onnx or .tflite; name=file.onnx:cv runs an ONNX
    file through OpenCV's DNN module as the app does; --single also writes models_<name>_single.npy,
    the face alone without its mirror image, as face blur describes faces).
"""
import os, sys, time, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2, onnx, onnxruntime as ort
import extract
from extract import rec
from evaluate import load
from common import WORK, HERE


class TfliteModel:
    """A converted model (onnx2tf: NHWC input, the same normalisation as the ONNX model it came from)."""
    def __init__(self, path, mean=127.5, std=127.5):
        import tensorflow as tf
        self.it = tf.lite.Interpreter(model_path=path, num_threads=4); self.it.allocate_tensors()
        self.i = self.it.get_input_details()[0]; self.o = self.it.get_output_details()[0]
        self.mean, self.std = mean, std

    def describe(self, faces_bgr):
        out = []
        for f in faces_bgr:
            x = ((cv2.cvtColor(f, cv2.COLOR_BGR2RGB).astype(np.float32) - self.mean) / self.std)[None]
            self.it.set_tensor(self.i['index'], x); self.it.invoke()
            out.append(self.it.get_tensor(self.o['index']).reshape(-1))
        out = np.array(out)
        return out / np.linalg.norm(out, axis=1, keepdims=True)


class CvModel:
    """An ONNX model through OpenCV's DNN module, prepared as the app prepares faces (blobFromImages)."""
    def __init__(self, path):
        self.net = cv2.dnn.readNetFromONNX(path)

    def describe(self, faces_bgr):
        self.net.setInput(cv2.dnn.blobFromImages(faces_bgr, 1 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True))
        out = self.net.forward()
        return out / np.linalg.norm(out, axis=1, keepdims=True)


class SfaceModel:
    """The shipped SFace model through OpenCV's FaceRecognizerSF."""
    def describe(self, faces_bgr):
        out = np.array([rec.feature(f).flatten() for f in faces_bgr], np.float32)
        return out / np.linalg.norm(out, axis=1, keepdims=True)


def load_model(mdir, f):
    if f == 'sface': return SfaceModel()
    if f.endswith(':cv'): return CvModel(os.path.join(mdir, f[:-3]))
    return (TfliteModel if f.endswith('.tflite') else Model)(os.path.join(mdir, f))


def from_crops(models, single=False):
    crops = np.load(os.path.join(WORK, 'models_crops.npy'))
    for name, m in models.items():
        t0 = time.time(); rows = []; ones = []
        for k, c in enumerate(crops):
            d = m.describe([c, cv2.flip(c, 1)]); e = d[0] + d[1]
            rows.append(e / np.linalg.norm(e)); ones.append(d[0])
            if k % 500 == 0: print(name, k, f"{time.time() - t0:.0f} s", flush=True)
        np.save(os.path.join(WORK, f'models_{name}.npy'), np.array(rows, np.float32))
        if single: np.save(os.path.join(WORK, f'models_{name}_single.npy'), np.array(ones, np.float32))
        print(name, len(rows), 'faces')


class Model:
    def __init__(self, path):
        g = onnx.load(path).graph
        # InsightFace's rule: models with their own normalisation (Sub and Mul first) take 0..255.
        names = [n.name for n in g.node[:8]]
        own = any(n.startswith(('Sub', '_minus')) for n in names) and any(n.startswith(('Mul', '_mul')) for n in names)
        self.mean, self.std = (0.0, 1.0) if own else (127.5, 127.5)
        o = ort.SessionOptions(); o.intra_op_num_threads = 4
        self.s = ort.InferenceSession(path, o, providers=['CPUExecutionProvider'])
        self.inp = self.s.get_inputs()[0].name

    def describe(self, faces_bgr):
        x = np.stack([cv2.cvtColor(f, cv2.COLOR_BGR2RGB) for f in faces_bgr]).astype(np.float32)
        x = ((x - self.mean) / self.std).transpose(0, 3, 1, 2)
        out = np.concatenate([self.s.run(None, {self.inp: x[i:i + 1]})[0] for i in range(len(x))])
        return out / np.linalg.norm(out, axis=1, keepdims=True)


def main():
    mdir = sys.argv[1]
    specs = [a.split('=', 1) for a in sys.argv[2:] if '=' in a]
    only = int(sys.argv[sys.argv.index('--only') + 1]) if '--only' in sys.argv else None
    if '--from-crops' in sys.argv:
        return from_crops({name: load_model(mdir, f) for name, f in specs}, single='--single' in sys.argv)
    models = {name: Model(os.path.join(mdir, f)) for name, f in specs}
    faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
    index = {f['face']: k for k, f in enumerate(faces)}
    out = {name: np.zeros((len(faces), 0), np.float32) for name in ['sface'] + list(models)}
    got = {name: [None] * len(faces) for name in out}
    crops = []   # aligned faces of the current photo, in analyze()'s order
    kept = np.zeros((len(faces), 112, 112, 3), np.uint8)

    def describe(img, r):
        al = rec.alignCrop(img, r)
        crops.append(al)
        a = rec.feature(al).flatten(); b = rec.feature(cv2.flip(al, 1)).flatten()
        e = a / np.linalg.norm(a) + b / np.linalg.norm(b)
        return e / np.linalg.norm(e)
    extract.emb = describe   # analyze() describes faces through this

    images = sorted({f['image'] for f in faces})[:only]
    t0 = time.time(); bad = 0
    for n, im in enumerate(images):
        full = cv2.imread(os.path.join(WORK, 'img', im + '.jpg'))
        crops.clear()
        found = extract.analyze(full)
        for k, res in enumerate(found):
            i = index.get(f"{im}_{k}")
            if i is None: continue
            # The same face as in faces.json (same detector, same order): check the box.
            if np.abs(np.array(res['box']) - np.array(faces[i]['box'])).max() > 1e-3: bad += 1; continue
            got['sface'][i] = res['emb']; kept[i] = crops[k]
            pair = [crops[k], cv2.flip(crops[k], 1)]
            for name, m in models.items():
                d = m.describe(pair); e = d[0] + d[1]
                got[name][i] = e / np.linalg.norm(e)
        if n % 50 == 0: print(f"{n}/{len(images)} photos, {time.time() - t0:.0f} s", flush=True)
    for name, rows in got.items():
        ok = [r is not None for r in rows]
        dim = next(len(r) for r in rows if r is not None)
        arr = np.stack([r if r is not None else np.full(dim, np.nan, np.float32) for r in rows]).astype(np.float32)
        np.save(os.path.join(WORK, f'models_{name}.npy'), arr)
        print(name, f"{sum(ok)} of {len(rows)} faces described, {dim} numbers each")
    print('faces whose box differed from faces.json:', bad)
    json.dump([f['face'] for f in faces], open(os.path.join(WORK, 'models_faces.json'), 'w'))
    np.save(os.path.join(WORK, 'models_crops.npy'), kept)


if __name__ == '__main__':
    main()
