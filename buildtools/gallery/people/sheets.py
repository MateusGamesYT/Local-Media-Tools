"""Contact sheets: numbered face crops per person, two people per sheet, for checking by eye."""
import json, sys, os
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import WORK as D
faces = json.load(open(sys.argv[1]))          # list of {face, person, ...} to show
out_prefix = sys.argv[2]
by = {}
for f in faces: by.setdefault(f['person'], []).append(f)
font = ImageFont.load_default()
C, T = 10, 96
def sheet(person, fs):
    rows = (len(fs) + C - 1) // C
    im = Image.new('RGB', (C * T, 22 + rows * (T + 14)), (255, 255, 255))
    d = ImageDraw.Draw(im); d.text((4, 4), f"{person} ({len(fs)})", fill=(0, 0, 0), font=font)
    for k, f in enumerate(fs):
        try: c = Image.open(os.path.join(D, 'crops', f['face'] + '.jpg')).resize((T - 4, T - 4))
        except Exception: continue
        x, y = (k % C) * T, 22 + (k // C) * (T + 14)
        im.paste(c, (x + 2, y)); d.text((x + 3, y + T - 4), str(k), fill=(200, 0, 0), font=font)
    return im
names = list(by)
for i in range(0, len(names), 2):
    ims = [sheet(n, by[n]) for n in names[i:i + 2]]
    W = sum(m.width for m in ims) + 12; H = max(m.height for m in ims)
    out = Image.new('RGB', (W, H), (90, 90, 90)); x = 0
    for m in ims: out.paste(m, (x, 0)); x += m.width + 12
    out.save(f"{out_prefix}_{i // 2:02d}.png")
json.dump({n: [f['face'] for f in by[n]] for n in names}, open(out_prefix + '_index.json', 'w'))
print(len(names), 'people')
