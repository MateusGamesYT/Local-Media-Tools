"""Builds the app's face-recognition models from InsightFace's v0.7 release:
  - r50 (1.9.0, the Gallery's people): ResNet-50 "w600k_r50" from the buffalo_l package, converted to
    TensorFlow Lite with 8-bit weights (onnx2tf 1.22.3 and TensorFlow 2.16.1, the app's TFLite runtime;
    dynamic-range quantisation): 44 MB, and about 90 MB of memory when it runs where OpenCV needs 370 MB
    (790 MB with its Winograd convolutions) for the same model; it groups the real-people set as the
    original does (people/README, "Stronger face models");
  - mbf (1.8.0, face blur): MobileFaceNet "w600k_mbf" from the buffalo_s package, its weights stored as
    16-bit floats for OpenCV's DNN module (which turns them back into 32-bit floats when it loads the
    model; the embeddings agree with the original's to a cosine of 0.9999 or better).

The weights are InsightFace's, for non-commercial research purposes only (trained on WebFace600K);
the app credits them under Settings → Open-source licences (assets/licenses/InsightFace-models.txt).

    python3 face_model.py r50|mbf [buffalo_l.zip|buffalo_s.zip]   → app/src/main/assets/models/<file>
    python3 face_model.py --check r50|mbf    checks a model as the app runs it (r50: TensorFlow Lite,
        mbf: the installed OpenCV): the app's self-check pattern against the embedding FaceModel.kt
        expects and, for the Gallery's model, the faces of the test photos against the embeddings
        stored in faces.tsv
Needs numpy and onnx (r50: also onnx2tf and TensorFlow; --check: numpy, OpenCV and for r50 TensorFlow);
the zip is downloaded when not given.
"""
import hashlib, io, os, re, shutil, subprocess, sys, tempfile, urllib.request, zipfile
import numpy as np

RELEASE = 'https://github.com/deepinsight/insightface/releases/download/v0.7/'
MODELS = {
    'r50': dict(zip='buffalo_l.zip', member='w600k_r50.onnx', out='face_recognition_r50_w600k_int8.tflite',
                sha256='4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43', gallery=True),
    'mbf': dict(zip='buffalo_s.zip', member='w600k_mbf.onnx', out='face_recognition_mbf_w600k_fp16.onnx',
                sha256='9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f', gallery=False),
}
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models')
KOTLIN = os.path.join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'localmediatools', 'vision', 'core', 'FaceModel.kt')


def describer(name):
    """The model's embedding of an aligned 112×112 BGR face, face and mirror averaged, as the app computes it."""
    import cv2
    path = os.path.join(ASSETS, MODELS[name]['out'])
    if path.endswith('.tflite'):
        import tensorflow as tf
        it = tf.lite.Interpreter(model_path=path, num_threads=4); it.allocate_tensors()
        i = it.get_input_details()[0]['index']; o = it.get_output_details()[0]['index']
        def one(img):   # FaceInput.nhwc: RGB, (x − 127.5) / 127.5, one face at a time
            it.set_tensor(i, ((cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) - 127.5) / 127.5)[None]); it.invoke()
            e = it.get_tensor(o).reshape(-1).astype(np.float64)
            return e / np.linalg.norm(e)
        def describe(al):
            e = one(al) + one(cv2.flip(al, 1))
            return e / np.linalg.norm(e)
        return describe
    net = cv2.dnn.readNetFromONNX(path)
    def describe(al):
        net.setInput(cv2.dnn.blobFromImages([al, cv2.flip(al, 1)], 1 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True))
        o = net.forward(); o /= np.linalg.norm(o, axis=1, keepdims=True); e = o[0] + o[1]
        return e / np.linalg.norm(e)
    return describe


def check(name):
    import base64, cv2
    spec = MODELS[name]
    describe = describer(name)
    y, x, c = np.mgrid[0:112, 0:112, 0:3]
    pattern = (((x * 7 + y * 13 + c * 50) ^ (x * y)) & 255).astype(np.uint8)   # FaceModel.checkPattern
    kt = open(KOTLIN).read()
    m = re.search(r'\b' + name.upper() + r'\(\s*"' + re.escape(spec['out']) + r'",\s*"([A-Za-z0-9+/=]+)"', kt)
    if m is None: return print(name, 'has no check embedding in FaceModel.kt; its embedding of the pattern (16-bit, base64):\n' +
                               base64.b64encode(describe(pattern).astype('<f2').tobytes()).decode())
    ref = np.frombuffer(base64.b64decode(m.group(1)), '<f2').astype(np.float32)
    print('OpenCV', cv2.__version__, '|', name, 'self-check cosine (the app needs 0.99):', round(float(describe(pattern) @ ref / np.linalg.norm(ref)), 6), flush=True)
    if not spec['gallery']: return
    det = cv2.FaceDetectorYN.create(os.path.join(ASSETS, 'face_detection_yunet_2023mar.onnx'), '', (320, 320), 0.62, 0.3, 500)
    dst = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]])
    def matrix(r):   # FaceAlign.matrix
        src = np.array(r[4:14], np.float64).reshape(5, 2); sm = src.mean(0); dm = np.array([56.0262, 71.9008])
        s = src - sm; d = dst - dm; n = (s ** 2).sum(); a = (s * d).sum() / n; b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / n
        mt = np.array([[a, -b, 0], [b, a, 0]]); mt[:, 2] = dm - mt[:, :2] @ sm
        return mt
    people = os.path.join(ROOT, 'app', 'src', 'test', 'resources', 'people')
    cos = []; found = {}
    for r in (l.rstrip('\n').split('\t') for l in open(os.path.join(people, 'faces.tsv')) if not l.startswith('#')):
        if r[0] not in found:
            img = cv2.imread(os.path.join(people, r[0])); det.setInputSize((img.shape[1], img.shape[0])); _, f = det.detect(img)
            f = sorted([q for q in (f if f is not None else []) if q[14] >= 0.62 and q[2] >= 12 and q[3] >= 12], key=lambda q: -q[2] * q[3])
            found[r[0]] = (img, f)
        img, f = found[r[0]]
        e = describe(cv2.warpAffine(img, matrix(f[int(r[1])]), (112, 112), flags=cv2.INTER_LINEAR))
        cos.append(float(e @ np.frombuffer(base64.b64decode(r[12]), '<f4')))
    print(len(cos), 'test faces: lowest cosine to the stored embeddings', round(min(cos), 6))


def build(name, zip_path):
    import onnx
    from onnx import numpy_helper
    spec = MODELS[name]
    data = open(zip_path, 'rb').read() if zip_path else urllib.request.urlopen(RELEASE + spec['zip']).read()
    raw = zipfile.ZipFile(io.BytesIO(data)).read(spec['member'])
    if hashlib.sha256(raw).hexdigest() != spec['sha256']: sys.exit(spec['member'] + ' is not the expected file')
    path = os.path.join(ASSETS, spec['out'])
    if spec['out'].endswith('.tflite'):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, spec['member']); open(src, 'wb').write(raw)
            # onnx2tf compares each converted operation with ONNX Runtime on sample pictures it downloads
            # (no longer published); any pictures do for that, and they don't change the model: the same
            # outputs to the last bit with its own and with these.
            np.save(os.path.join(tmp, 'calibration_image_sample_data_20x128x128x3_float32.npy'),
                    np.random.default_rng(0).uniform(0, 1, (20, 128, 128, 3)).astype(np.float32))
            subprocess.run(['onnx2tf', '-i', src, '-o', os.path.join(tmp, 'tf'), '-ois', 'input.1:1,3,112,112', '-odrqt', '-n'],
                           check=True, stdout=subprocess.DEVNULL, cwd=tmp)
            shutil.copy(os.path.join(tmp, 'tf', spec['member'][:-5] + '_dynamic_range_quant.tflite'), path)
        out = open(path, 'rb').read()
        return print(path, len(out), 'bytes, sha256', hashlib.sha256(out).hexdigest())
    m = onnx.load_from_string(raw)
    for t in m.graph.initializer:
        if t.data_type == onnx.TensorProto.FLOAT:
            t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float16), t.name))
    inits = {t.name for t in m.graph.initializer}
    for i in m.graph.input:
        if i.name in inits: i.type.tensor_type.elem_type = onnx.TensorProto.FLOAT16
    m.doc_string = f"InsightFace {spec['member'][:-5]} ({spec['zip'][:-4]}, v0.7), weights as float16. Non-commercial research use only."
    out = m.SerializeToString()
    open(path, 'wb').write(out)
    print(path, len(out), 'bytes, sha256', hashlib.sha256(out).hexdigest())


def main():
    args = [a for a in sys.argv[1:] if a != '--check']
    if not args or args[0] not in MODELS: sys.exit(__doc__)
    if '--check' in sys.argv: return check(args[0])
    build(args[0], args[1] if len(args) > 1 else None)


if __name__ == '__main__':
    main()
