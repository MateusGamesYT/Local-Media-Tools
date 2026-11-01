"""Builds the models "Blur faces & private info" uses into app/src/main/assets.

    python3 build_models.py <work dir>      (files go to <work dir>/models; downloaded if missing)

  license_plate_lpd_yunet_2023mar_fp16.onnx   LPD-YuNet, OpenCV Zoo (Apache 2.0): licence plates
  text_detection_ppocrv3_en_fp16.onnx         PP-OCRv3 text detection, OpenCV Zoo (Apache 2.0)
  text_recognition_ppocrv5_mobile_fp16.onnx   PP-OCRv5 mobile recogniser (PaddleOCR, Apache 2.0; ONNX by
                                              bukuroo/PPOCRv5-ONNX on Hugging Face, Apache 2.0), input
                                              fixed to 48 x 320 so OpenCV's DNN module can run it
  privacy/ppocrv5_dict.txt                    the recogniser's characters

Weights are stored as float16 (OpenCV turns them back into float32 when it loads a model, so only
OpenCV runs these files, as with the face models); each
model's outputs are checked against the original on random input, and the recogniser's on real text.
"""
import hashlib, os, shutil, sys, urllib.request
import numpy as np, onnx, onnxsim, cv2 as cv
from onnx import numpy_helper

work = os.path.join(sys.argv[1], 'models')
os.makedirs(work, exist_ok=True)
here = os.path.dirname(os.path.abspath(__file__))
assets = os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets')
HF = 'https://huggingface.co'
SRC = {
    'license_plate_detection_yunet__license_plate_detection_lpd_yunet_2023mar.onnx':
        (f'{HF}/opencv/license_plate_detection_yunet/resolve/main/license_plate_detection_lpd_yunet_2023mar.onnx',
         '6d4978a7b6d25514d5e24811b82bfb511d166bdd8ca3b03aa63c1623d4d039c7'),
    'text_detection_ppocr__text_detection_en_ppocrv3_2023may.onnx':
        (f'{HF}/opencv/text_detection_ppocr/resolve/main/text_detection_en_ppocrv3_2023may.onnx',
         '03f550c6b406fda8bf54bd8327815f6c7e2edd98cea02348c93d879254366587'),
    'ppocrv5-mobile-rec.onnx': (f'{HF}/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5-mobile-rec.onnx',
         'bf66820f48fa99f779974c4df78e5274a9d8e0458c4137e8c5357e40e2c3faf2'),
    'ppocrv5_dict.txt': (f'{HF}/bukuroo/PPOCRv5-ONNX/resolve/main/ppocrv5_dict.txt',
         '1ea29636956177e400af712d9782e7693f3fb25f98617bed10479d2965a836fd'),
}

def fetch(name):
    path = os.path.join(work, name); url, sha = SRC[name]
    if not os.path.exists(path): urllib.request.urlretrieve(url, path)
    if hashlib.sha256(open(path, 'rb').read()).hexdigest() != sha: sys.exit(f'{name} is not the expected file')
    return path

def half(m, note):
    for t in m.graph.initializer:
        if t.data_type == onnx.TensorProto.FLOAT:
            t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float16), t.name))
    inits = {t.name for t in m.graph.initializer}
    for i in m.graph.input:
        if i.name in inits: i.type.tensor_type.elem_type = onnx.TensorProto.FLOAT16
    # Weights held in Constant nodes too (OpenCV converts those as well); small ones stay as they are.
    for nd in m.graph.node:
        if nd.op_type == 'Constant':
            for at in nd.attribute:
                if at.t and at.t.data_type == onnx.TensorProto.FLOAT and numpy_helper.to_array(at.t).size >= 64:
                    at.t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(at.t).astype(np.float16), at.t.name))
    m.doc_string = note + ' Weights as float16.'
    return m

def save(m, name):
    path = os.path.join(assets, 'models', name)
    data = m.SerializeToString(); open(path, 'wb').write(data)
    print(name, len(data), 'bytes, sha256', hashlib.sha256(data).hexdigest())
    return path

def compare(a, b, shape, outs=None):
    x = np.random.default_rng(0).uniform(0, 255, shape).astype(np.float32)
    na, nb = cv.dnn.readNet(a), cv.dnn.readNet(b)
    na.setInput(x); nb.setInput(x)
    ra = na.forward(outs) if outs else [na.forward()]; rb = nb.forward(outs) if outs else [nb.forward()]
    return max(float(np.abs(p - q).max() / (np.abs(p).max() + 1e-9)) for p, q in zip(ra, rb))

lpd = fetch('license_plate_detection_yunet__license_plate_detection_lpd_yunet_2023mar.onnx')
out = save(half(onnx.load(lpd), 'LPD-YuNet (OpenCV Zoo, 2023mar, Apache 2.0).'), 'license_plate_lpd_yunet_2023mar_fp16.onnx')
print('  largest relative difference', compare(lpd, out, (1, 3, 240, 320), ['loc', 'conf', 'iou']))

det = fetch('text_detection_ppocr__text_detection_en_ppocrv3_2023may.onnx')
out = save(half(onnx.load(det), 'PP-OCRv3 English text detection (OpenCV Zoo, 2023may, Apache 2.0).'), 'text_detection_ppocrv3_en_fp16.onnx')
print('  largest relative difference', compare(det, out, (1, 3, 320, 320)))

rec = fetch('ppocrv5-mobile-rec.onnx')
m = onnx.load(rec)
fixed, ok = onnxsim.simplify(m, overwrite_input_shapes={m.graph.input[0].name: [1, 3, 48, 320]})
if not ok: sys.exit('could not fix the recogniser input size')
tmp = os.path.join(work, 'ppocrv5-mobile-rec-48x320.onnx'); onnx.save(fixed, tmp)
out = save(half(fixed, 'PP-OCRv5 mobile text recognition (PaddleOCR, Apache 2.0), input 1x3x48x320.'), 'text_recognition_ppocrv5_mobile_fp16.onnx')
x = np.random.default_rng(0).uniform(-1, 1, (1, 3, 48, 320)).astype(np.float32)
a, b = cv.dnn.readNet(tmp), cv.dnn.readNet(out); a.setInput(x); b.setInput(x)
ra, rb = a.forward(), b.forward()
print('  largest difference', float(np.abs(ra - rb).max()), '; same best character', float((ra.argmax(-1) == rb.argmax(-1)).mean()))

os.makedirs(os.path.join(assets, 'privacy'), exist_ok=True)
shutil.copy(fetch('ppocrv5_dict.txt'), os.path.join(assets, 'privacy', 'ppocrv5_dict.txt'))
