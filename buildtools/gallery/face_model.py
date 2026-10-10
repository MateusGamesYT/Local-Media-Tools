"""Builds the app's face-recognition model (1.8.0): InsightFace's MobileFaceNet "w600k_mbf" from the
buffalo_s package of InsightFace's v0.7 release, with its weights stored as 16-bit floats (half the
size; OpenCV's DNN module turns them back into 32-bit floats when it loads the model, and the
embeddings agree with the original's to a cosine of 0.9999 or better, see people/compare_models.py).

The weights are InsightFace's, for non-commercial research purposes only (trained on WebFace600K);
the app credits them under Settings → About (assets/licenses/InsightFace-models.txt).

    python3 face_model.py [buffalo_s.zip]   → app/src/main/assets/models/face_recognition_mbf_w600k_fp16.onnx
    python3 face_model.py --check           checks the model with the installed OpenCV (opencv-python): the
        app's self-check pattern against the embedding FaceEngine.kt expects, and the faces of the test
        photos against the embeddings stored in faces.tsv (done with OpenCV 4.11 and 4.12, the phones' version)
Needs numpy and onnx (--check: numpy and OpenCV); the zip is downloaded when not given.
"""
import hashlib, io, os, sys, urllib.request, zipfile
import numpy as np

URL = 'https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip'
SHA256 = '9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f'   # w600k_mbf.onnx inside it
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models', 'face_recognition_mbf_w600k_fp16.onnx')


def check():
    import base64, re, cv2
    net = cv2.dnn.readNetFromONNX(OUT)
    def describe(al):
        net.setInput(cv2.dnn.blobFromImages([al, cv2.flip(al, 1)], 1 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True))
        o = net.forward(); o /= np.linalg.norm(o, axis=1, keepdims=True); e = o[0] + o[1]
        return e / np.linalg.norm(e)
    y, x, c = np.mgrid[0:112, 0:112, 0:3]
    pattern = (((x * 7 + y * 13 + c * 50) ^ (x * y)) & 255).astype(np.uint8)   # FaceEngine.checkPattern
    kt = open(os.path.join(ROOT, 'app', 'src', 'main', 'kotlin', 'com', 'localmediatools', 'vision', 'core', 'FaceEngine.kt')).read()
    ref = np.frombuffer(base64.b64decode(re.search(r'decode\(\s*"([A-Za-z0-9+/=]+)"', kt).group(1)), '<f2').astype(np.float32)
    print('OpenCV', cv2.__version__, '| self-check cosine (the app needs 0.99):', round(float(describe(pattern) @ ref / np.linalg.norm(ref)), 6))
    det = cv2.FaceDetectorYN.create(os.path.join(os.path.dirname(OUT), 'face_detection_yunet_2023mar.onnx'), '', (320, 320), 0.62, 0.3, 500)
    dst = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]])
    def matrix(r):   # FaceAlign.matrix
        src = np.array(r[4:14], np.float64).reshape(5, 2); sm = src.mean(0); dm = np.array([56.0262, 71.9008])
        s = src - sm; d = dst - dm; n = (s ** 2).sum(); a = (s * d).sum() / n; b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / n
        m = np.array([[a, -b, 0], [b, a, 0]]); m[:, 2] = dm - m[:, :2] @ sm
        return m
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


def main():
    if '--check' in sys.argv: return check()
    import onnx
    from onnx import numpy_helper
    data = open(sys.argv[1], 'rb').read() if len(sys.argv) > 1 else urllib.request.urlopen(URL).read()
    raw = zipfile.ZipFile(io.BytesIO(data)).read('w600k_mbf.onnx')
    if hashlib.sha256(raw).hexdigest() != SHA256: sys.exit('w600k_mbf.onnx is not the expected file')
    m = onnx.load_from_string(raw)
    for t in m.graph.initializer:
        if t.data_type == onnx.TensorProto.FLOAT:
            t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float16), t.name))
    inits = {t.name for t in m.graph.initializer}
    for i in m.graph.input:
        if i.name in inits: i.type.tensor_type.elem_type = onnx.TensorProto.FLOAT16
    m.doc_string = 'InsightFace w600k_mbf (buffalo_s, v0.7), weights as float16. Non-commercial research use only.'
    out = m.SerializeToString()
    open(OUT, 'wb').write(out)
    print(OUT, len(out), 'bytes, sha256', hashlib.sha256(out).hexdigest())


if __name__ == '__main__':
    main()
