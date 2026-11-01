"""QR codes and barcodes found by the app (harness/), on the photos of fetch_codes.py.

    python3 eval_codes.py <work dir> <sheet.jpg>

Runs the app's code finder on each photo and draws the photos with what was found (green), for
checking by eye; prints how many photos have something found. Open Images only says that a photo
shows a code somewhere, not where, so the count of codes found needs the sheet.
"""
import json, os, subprocess, sys
import numpy as np, cv2 as cv

work, sheet = sys.argv[1], sys.argv[2]
repo = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
rows = json.load(open(f'{work}/codes.json'))
lst = f'{work}/harness/codes.list'; out = f'{work}/harness/codes.tsv'
open(lst, 'w').write('\n'.join(os.path.abspath(f'{work}/{r["file"]}') for r in rows) + '\n')
subprocess.run([f'{work}/harness/run.sh', 'find', 'codes', lst, out], check=True, cwd=repo)
found = {}
for line in open(out):
    i, kind, det, q, text = (line.rstrip('\n').split('\t') + [''])[:5]
    found.setdefault(int(i), []).append(np.array([float(v) for v in q.split(',')], np.float32).reshape(4, 2))
tiles = []
for i, r in enumerate(rows):
    img = cv.imread(f'{work}/{r["file"]}')
    for q in found.get(i, []): cv.polylines(img, [q.astype(np.int32)], True, (0, 255, 0), max(3, img.shape[1] // 200))
    s = 400 / max(img.shape[:2]); t = cv.resize(img, (int(img.shape[1] * s), int(img.shape[0] * s)))
    tile = np.full((420, 400, 3), 255, np.uint8); tile[:t.shape[0], :t.shape[1]] = t
    cv.putText(tile, f'{i} {"/".join(r["labels"])} found {len(found.get(i, []))}', (2, 414), cv.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 1)
    tiles.append(tile)
while len(tiles) % 6: tiles.append(np.full((420, 400, 3), 255, np.uint8))
cv.imwrite(sheet, np.vstack([np.hstack(tiles[k:k + 6]) for k in range(0, len(tiles), 6)]))
print(f'{len(rows)} photos, something found in {len(found)}')
