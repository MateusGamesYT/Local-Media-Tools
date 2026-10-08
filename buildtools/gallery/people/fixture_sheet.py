import sys, os
from PIL import Image, ImageDraw
D = sys.argv[1]; rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(D, 'faces.tsv')) if not l.startswith('#')]
files = sorted({r[0] for r in rows})
T = 200; cols = 10
sheet = Image.new('RGB', (cols * T, ((len(files) + cols - 1) // cols) * (T + 26)), 'white'); d = ImageDraw.Draw(sheet)
for i, fn in enumerate(files):
    im = Image.open(os.path.join(D, fn)).convert('RGB'); W, H = im.size; s = T / max(W, H)
    im = im.resize((max(1, int(W * s)), max(1, int(H * s))))
    x0, y0 = (i % cols) * T, (i // cols) * (T + 26)
    sheet.paste(im, (x0, y0)); lab = ''
    for r in rows:
        if r[0] != fn: continue
        x, y, w, h = map(float, r[2:6])
        col = 'lime' if r[9] else 'red'
        d.rectangle([x0 + x * im.size[0], y0 + y * im.size[1], x0 + (x + w) * im.size[0], y0 + (y + h) * im.size[1]], outline=col, width=2)
        if r[9]: lab = f"{r[9].split()[0][:9]} {r[10]} {r[11][:14]}"
    d.text((x0 + 2, y0 + T + 2), lab, fill='black'); d.text((x0 + 2, y0 + T + 13), fn[:16], fill='gray')
sheet.save(sys.argv[2])
