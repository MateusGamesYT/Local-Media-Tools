"""Writes the real-photo fixture for the private-info tests: app/src/test/resources/privacy.

    python3 make_fixture.py <work dir>

Seven Open Images photos (CC BY 2.0), picked from the measurement sets: three with licence plates
people marked (fetch_oi.py plates; one with a person by the car, one a street with people walking),
two adverts with phone numbers, e-mail and street addresses whose words TextOCR transcribed
(fetch_textocr.py private), and a QR code and a barcode (fetch_codes.py). Each photo is scaled down
to at most 1,280 px; nothing else is changed.

labels.tsv, one thing per line (coordinates as fractions of the photo):
  plate    x0 y0 x1 y1          a plate people marked and I could read (plates_readable.json)
  vehicle  x0 y0 x1 y1          a vehicle the app's object detector (EfficientDet-Lite2) finds
                                (score 0.3 or more), as the app passes them to the plate finder
  private  x0 y0 … x3 y3        a phone number or e-mail address (TextOCR's polygon), and its kind
  code     x0 y0 x1 y1          the code, outlined by OpenCV 4.12 and checked by eye
"""
import csv, json, os, sys
import numpy as np, cv2 as cv
from PIL import Image

work = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, '..', '..', 'app', 'src', 'test', 'resources', 'privacy')
os.makedirs(out, exist_ok=True)
PLATES = ['e6299b9e04680adb', '4148b2126f0986a4', '61d9b42cc644b269']
TEXT = ['10d38961aee4edff', '223514026649d937']
CODES = ['384229f885156319', 'f6c32a7445700f60']

plates = {r['id']: r for r in json.load(open(f'{work}/plates-validation.json'))}
readable = json.load(open(os.path.join(here, 'plates_readable.json')))['labels']
texts = {r['id']: r for r in json.load(open(f'{work}/textocr-private.json'))}
codes = {r['id']: r for r in json.load(open(f'{work}/codes.json'))}
train = {}
with open(f'{work}/oi/train-images-boxable-with-rotation.csv') as f:
    for r in csv.DictReader(f):
        if r['ImageID'] in TEXT: train[r['ImageID']] = r

from ai_edge_litert.interpreter import Interpreter
det = Interpreter(model_path=os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets', 'models', 'efficientdet_lite2_int8.tflite')); det.allocate_tensors()
anchors = []
for level in range(3, 8):
    fm = int(np.ceil(448 / 2 ** level))
    for y in range(fm):
        for x in range(fm):
            for sc in range(3):
                size = 3.0 * 2 ** (sc / 3.0) / fm
                for ar in (1.0, 2.0, 0.5): anchors.append(((x + 0.5) / fm, (y + 0.5) / fm, size * ar ** 0.5, size / ar ** 0.5))
anchors = np.array(anchors, np.float32)

def vehicles(img):
    """As PrivacyModels.vehicles: car, motorcycle, bus, truck at 0.3, merged, largest first (fractions)."""
    h, w = img.shape[:2]; s = 448 / max(h, w)
    r = cv.resize(img[:, :, ::-1], (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv.INTER_AREA)
    x = np.zeros((448, 448, 3), np.uint8); x[:r.shape[0], :r.shape[1]] = r
    det.set_tensor(det.get_input_details()[0]['index'], x[None]); det.invoke()
    o = {d['shape'][-1]: det.get_tensor(d['index'])[0] for d in det.get_output_details()}
    raw, sc = o[4], o[90]
    cw, ch = r.shape[1] / 448, r.shape[0] / 448
    yc = raw[:, 0] * anchors[:, 3] + anchors[:, 1]; xc = raw[:, 1] * anchors[:, 2] + anchors[:, 0]
    hh = np.exp(raw[:, 2]) * anchors[:, 3]; ww = np.exp(raw[:, 3]) * anchors[:, 2]
    boxes = np.clip(np.stack([(xc - ww / 2) / cw, (yc - hh / 2) / ch, (xc + ww / 2) / cw, (yc + hh / 2) / ch], 1), 0, 1)
    best = sc[:, [2, 3, 5, 7]].max(1); idx = np.where(best >= 0.3)[0]
    keep = cv.dnn.NMSBoxes([[float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])] for b in boxes[idx]], best[idx].tolist(), 0.3, 0.5)
    vs = [boxes[idx[k]] for k in np.array(keep).flatten()]
    return sorted(vs, key=lambda b: -(b[2] - b[0]) * (b[3] - b[1]))[:8]

def save(src, name):
    im = Image.open(src).convert('RGB')
    if max(im.size) > 1280:
        s = 1280 / max(im.size); im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    im.save(os.path.join(out, name), quality=90)
    return im.size

EMAIL = __import__('re').compile(r'[\w.+-]+@[\w-]+\.[\w.]+')
labels = ['# file\tkind\tdetail\tcoordinates (fractions of the photo)']
credits = ['# file\tauthor\ttitle\tsource\tlicence\tchanges']
for i in PLATES:
    r = plates[i]; name = f'{i}.jpg'; save(f'{work}/{r["file"]}', name)
    for k, g in enumerate(r['boxes']):
        if readable.get(f'{i}/{k}') == 'readable': labels.append(f'{name}\tplate\t\t{g["XMin"]:.5f} {g["YMin"]:.5f} {g["XMax"]:.5f} {g["YMax"]:.5f}')
    for v in vehicles(cv.imread(f'{work}/{r["file"]}')): labels.append(f'{name}\tvehicle\t\t' + ' '.join(f'{c:.5f}' for c in v))
    c = r['credit']; credits.append(f'{name}\t{c["author"]}\t{c["title"]}\t{c["url"]}\t{c["license"]}\tscaled down')
for i in TEXT:
    r = texts[i]; name = f'{i}.jpg'
    W, H = Image.open(f'{work}/{r["file"]}').size; save(f'{work}/{r["file"]}', name)
    for wd in r['words']:
        t = wd['text']; d = ''.join(ch for ch in t if ch.isdigit())
        kind = 'email' if EMAIL.search(t) else 'phone' if len(d) >= 8 else None
        if not kind: continue
        q = np.array(wd['points'], np.float32).reshape(-1, 2)
        labels.append(f'{name}\tprivate\t{kind}\t' + ' '.join(f'{x / W:.5f} {y / H:.5f}' for x, y in q))
    m = train[i]; credits.append(f'{name}\t{m["Author"]}\t{m["Title"]}\t{m["OriginalLandingURL"]}\t{m["License"]}\tscaled down')
qr = cv.QRCodeDetectorAruco(); bar = cv.barcode.BarcodeDetector()
for i in CODES:
    r = codes[i]; name = f'{i}.jpg'; img = cv.imread(f'{work}/{r["file"]}'); H, W = img.shape[:2]; save(f'{work}/{r["file"]}', name)
    for d in (qr, bar):
        ok, pts = d.detectMulti(img)
        if ok and pts is not None:
            for q in pts.reshape(-1, 4, 2):
                labels.append(f'{name}\tcode\t{"qr" if d is qr else "barcode"}\t{q[:, 0].min() / W:.5f} {q[:, 1].min() / H:.5f} {q[:, 0].max() / W:.5f} {q[:, 1].max() / H:.5f}')
    c = r['credit']; credits.append(f'{name}\t{c["author"]}\t{c["title"]}\t{c["url"]}\t{c["license"]}\tscaled down')
open(os.path.join(out, 'labels.tsv'), 'w').write('\n'.join(labels) + '\n')
open(os.path.join(out, 'CREDITS.tsv'), 'w').write('\n'.join(credits) + '\n')
open(os.path.join(out, 'README.txt'), 'w').write(
"""Real photos for testing what "Blur faces & private info" finds besides faces.

Seven photos from Flickr via Open Images V7, all under Creative Commons Attribution 2.0
(https://creativecommons.org/licenses/by/2.0/). Author, title and source of each photo are in
CREDITS.tsv. Changes: each photo was scaled down (at most 1,280 px). Only the photographers' own
pictures were used: no edits, manipulations, screen grabs or reposts.

Three photos of cars and buses with number plates (one with a woman by the car, one a street with
people walking), two adverts with phone numbers, e-mail and street addresses (businesses', printed
in the adverts), a QR code on a belt buckle and a barcode wristband on an arm.

labels.tsv: the plates people marked (Open Images boxes, readable ones), the vehicles the app's
object detector finds, phone numbers and e-mail addresses with TextOCR's outlines (CC BY 4.0), and
the codes, outlined by OpenCV and checked by eye.

Made by buildtools/privacy/make_fixture.py.
""")
print(len(labels) - 1, 'labels')
