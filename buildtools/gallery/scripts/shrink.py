"""Decode JPEGs at reduced scale (like Android's inSampleSize) and save at <=1024 px."""
import sys, os
from PIL import Image
src, dst, part, parts = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
os.makedirs(dst, exist_ok=True)
files = sorted(f for f in os.listdir(src) if f.endswith('.jpg'))[part::parts]
for f in files:
    o = os.path.join(dst, f)
    if os.path.exists(o): continue
    p = os.path.join(src, f)
    if os.path.getsize(p) < 3000: continue
    try:
        im = Image.open(p); im.draft('RGB', (1024, 1024)); im = im.convert('RGB')
        s = 1024 / max(im.size)
        if s < 1: im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.BILINEAR)
        im.save(o, quality=94)
    except Exception as e:
        print('bad', f, e)
