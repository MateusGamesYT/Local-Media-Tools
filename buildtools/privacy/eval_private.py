"""Private details in text, measured on real photos through the app's own code (harness/).

    python3 eval_private.py <work dir> private|val [--what private|text] [--sheet out.jpg]

private: TextOCR photos with a phone number or e-mail address (fetch_textocr.py private); val: 400
TextOCR photos picked at random (fetch_textocr.py 400). The harness runs the app's finder on each
photo; a labelled e-mail address or number of 8 or more digits (not a date, price or size) counts as
hidden when the found
boxes, grown as the app blurs them (PrivacyShape.MARGIN), cover at least 80 % of it. Also: boxes per
photo and what kind of detail they were taken for; --sheet draws the lines taken as private, with
what was read, for checking by eye.
"""
import argparse, collections, json, os, re, subprocess, sys
import numpy as np, cv2 as cv

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('set'); ap.add_argument('--what', default='private')
ap.add_argument('--sheet'); ap.add_argument('--no-run', action='store_true'); ap.add_argument('--margin', type=float, default=0.35); ap.add_argument('--limit', type=int, default=10**9)
a = ap.parse_args()
repo = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
rows = json.load(open(f'{a.work}/textocr-{a.set}.json'))[:a.limit]
lst = f'{a.work}/harness/{a.set}.list'; out = f'{a.work}/harness/{a.set}-{a.what}.tsv'
open(lst, 'w').write('\n'.join(os.path.abspath(f'{a.work}/{r["file"]}') for r in rows) + '\n')
if not a.no_run: subprocess.run([f'{a.work}/harness/run.sh', 'find', a.what, lst, out], check=True, cwd=repo)
found = collections.defaultdict(list)
for line in open(out):
    i, kind, det, q, text = (line.rstrip('\n').split('\t') + [''])[:5]
    found[int(i)].append((kind, det, np.array([float(v) for v in q.split(',')], np.float32).reshape(4, 2), text))

EMAIL = re.compile(r'[\w.+-]+@[\w-]+\.[\w.]+')
DATE = re.compile(r'\d{1,4}[/.\\-]+\d{1,2}[/.\\-]+\d{1,4}')
YEARS = re.compile(r'(1[5-9]|20)\d\d\s*[-–]\s*(1[5-9]|20)\d\d')
def compact_date(d):
    ok = lambda day, mon, yr: 1 <= day <= 31 and 1 <= mon <= 12 and 1900 <= yr <= 2099
    return len(d) == 8 and (ok(int(d[:2]), int(d[2:4]), int(d[4:])) or ok(int(d[2:4]), int(d[:2]), int(d[4:])) or ok(int(d[6:]), int(d[4:6]), int(d[:4])))
def personal(t):
    # E-mail addresses, and numbers of 8 or more digits that are not dates, spans of years, prices or sizes.
    if EMAIL.search(t): return 'email'
    d = ''.join(c for c in t if c.isdigit())
    if len(d) >= 8 and not DATE.search(t) and not YEARS.search(t) and not compact_date(d) and '$' not in t and 'x' not in t.lower(): return 'number'
    return None

def grown(q):
    c = q.mean(0)
    e1 = np.linalg.norm(q[1] - q[0]); e2 = np.linalg.norm(q[3] - q[0]); th = min(e1, e2)
    # Grow by the margin of the thickness on every side (as PrivacyShape.grown).
    u = (q[1] - q[0]) / max(e1, 1e-3); v = (q[3] - q[0]) / max(e2, 1e-3); m = th * a.margin
    return np.array([q[0] - u * m - v * m, q[1] + u * m - v * m, q[2] + u * m + v * m, q[3] - u * m + v * m], np.float32)

def covered(gq, quads, shape):
    x0, y0 = np.floor(gq.min(0)).astype(int); x1, y1 = np.ceil(gq.max(0)).astype(int)
    x0, y0 = max(0, x0), max(0, y0); x1, y1 = min(shape[1], x1), min(shape[0], y1)
    if x1 <= x0 or y1 <= y0: return 0.0
    gm = np.zeros((y1 - y0, x1 - x0), np.uint8); cv.fillPoly(gm, [np.round(gq - [x0, y0]).astype(np.int32)], 1)
    dm = np.zeros_like(gm)
    for q in quads: cv.fillPoly(dm, [np.round(grown(q) - [x0, y0]).astype(np.int32)], 1)
    n = gm.sum()
    return (gm & dm).sum() / n if n else 0.0

tot = collections.Counter(); hid = collections.Counter(); kinds = collections.Counter(); boxes = 0
missed = []
for i, r in enumerate(rows):
    img = cv.imread(f'{a.work}/{r["file"]}')
    quads = [f[2] for f in found[i]]; boxes += len(quads)
    for f in found[i]:
        for k in (f[1].split(',') if f[1] else ['-']): kinds[k] += 1
    for w in r['words']:
        k = personal(w['text'])
        if not k: continue
        gq = np.array(w['points'], np.float32).reshape(-1, 2)
        ok = covered(gq, quads, img.shape) >= 0.8
        tot[k] += 1; hid[k] += ok
        if not ok: missed.append((i, w['text']))
n = len(rows)
print(f'{a.set} {a.what}: ' + ', '.join(f'{k} hidden {hid[k]}/{tot[k]} = {hid[k]/max(1,tot[k]):.1%}' for k in sorted(tot))
      + f'; {boxes/n:.2f} boxes/photo; kinds ' + ' '.join(f'{k}:{v}' for k, v in kinds.most_common()))
if missed: print('missed (first 25):', '; '.join(f'{i}:{t}' for i, t in missed[:25]))

if a.sheet:
    tiles = []
    for i, r in enumerate(rows):
        if not found[i]: continue
        img = cv.imread(f'{a.work}/{r["file"]}')
        for kind, det, q, text in found[i]:
            x0, y0 = np.maximum(q.min(0).astype(int) - 4, 0); x1, y1 = q.max(0).astype(int) + 4
            crop = img[y0:y1, x0:x1]
            if crop.size == 0: continue
            s = 40 / max(1, crop.shape[0]); crop = cv.resize(crop, (max(1, min(560, int(crop.shape[1] * s))), 40))
            tile = np.full((64, 600, 3), 255, np.uint8); tile[:40, :crop.shape[1]] = crop
            cv.putText(tile, f'{i} {det}: {text}'[:80], (2, 58), cv.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 200), 1, cv.LINE_AA)
            tiles.append(tile)
    for k in range(0, len(tiles), 60):
        page = tiles[k:k + 60]
        page += [np.full((64, 600, 3), 255, np.uint8)] * ((-len(page)) % 2)
        sheet = np.vstack([np.hstack(page[j:j + 2]) for j in range(0, len(page), 2)])
        cv.imwrite(a.sheet.replace('.jpg', f'-{k // 60}.jpg'), sheet)
    print(len(tiles), 'lines on the sheet')
