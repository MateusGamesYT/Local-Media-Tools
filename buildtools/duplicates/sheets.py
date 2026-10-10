"""Contact sheets of the runs for labelling by eye: each run is boxed and numbered, its photos
lettered a, b, c… in shooting order.

    python sheets.py photos.json [runs_per_sheet]
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont
from common import WORK

sel = json.load(open(sys.argv[1]))
per = int(sys.argv[2]) if len(sys.argv) > 2 else 14
H, W, GAP = 170, 1500, 10
font = ImageFont.load_default(size=20)
runs = [(i, r) for i, r in enumerate(sel['runs'])]
out_dir = os.path.join(WORK, 'sheets')
for s in range(0, len(runs), per):
    chunk = runs[s:s + per]
    tiles = []
    for i, r in chunk:
        ims = [Image.open(os.path.join(WORK, 'img', p['id'] + '.jpg')) for p in r['photos']]
        ims = [im.resize((max(1, round(im.width * H / im.height)), H)) for im in ims]
        w = sum(im.width for im in ims) + GAP * (len(ims) - 1) + 12
        t = Image.new('RGB', (min(w, W), H + 34), (40, 40, 40))
        d = ImageDraw.Draw(t)
        d.text((6, 4), f"#{i}", fill=(255, 220, 0), font=font)
        x = 6
        for k, im in enumerate(ims):
            t.paste(im, (x, 30))
            d.text((x + 4, 34), 'abcdef'[k], fill=(255, 255, 255), font=font, stroke_width=2, stroke_fill=(0, 0, 0))
            x += im.width + GAP
        tiles.append(t)
    rows, cur, cw = [], [], 0
    for t in tiles:
        if cur and cw + t.width + GAP > W:
            rows.append(cur); cur, cw = [], 0
        cur.append(t); cw += t.width + GAP
    if cur: rows.append(cur)
    sheet = Image.new('RGB', (W, sum(max(t.height for t in r) + GAP for r in rows)), (0, 0, 0))
    y = 0
    for r in rows:
        x = 0
        for t in r:
            sheet.paste(t, (x, y)); x += t.width + GAP
        y += max(t.height for t in r) + GAP
    sheet.save(os.path.join(out_dir, f'runs_{s // per:02d}.jpg'), quality=85)
print('sheets:', (len(runs) + per - 1) // per)
