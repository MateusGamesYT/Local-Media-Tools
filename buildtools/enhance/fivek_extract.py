"""fivek_extract.py <parquet> <out dir> <prefix>: each pair as <prefix>NNN_in.png / _ex.png (sRGB, long side 512, same size),
the input turned to match the expert's orientation; labels in <prefix>meta.json."""
import sys, io, os, json, numpy as np, pyarrow.parquet as pq
from PIL import Image, ImageCms
src, out, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
f = pq.ParquetFile(src)
t = f.read_row_group(0)
srgb = ImageCms.createProfile('sRGB')
names = {c: f.schema_arrow.field(c).type for c in ('location', 'time', 'light', 'subject')}
meta = []
def small_gray(im, s=48): return np.asarray(im.convert('L').resize((s, s), Image.BILINEAR), dtype=np.float32)
for i in range(t.num_rows):
    o = Image.open(io.BytesIO(t.column('original')[i].as_py()['bytes'])).convert('RGB')
    a = Image.open(io.BytesIO(t.column('augmented')[i].as_py()['bytes']))
    icc = a.info.get('icc_profile')
    a = ImageCms.profileToProfile(a, ImageCms.ImageCmsProfile(io.BytesIO(icc)), srgb, outputMode='RGB') if icc else a.convert('RGB')
    a.thumbnail((512, 512), Image.LANCZOS)
    o.thumbnail((640, 640), Image.LANCZOS)
    # Orientation: the turn whose layout matches the expert's best (after normalising brightness).
    ga = small_gray(a); ga = (ga - ga.mean()) / (ga.std() + 1e-6)
    best = None
    for rot in (0, 90, 180, 270):
        r = o.rotate(rot, expand=True)
        if (r.width > r.height) != (a.width > a.height) and a.width != a.height: continue
        g = small_gray(r); g = (g - g.mean()) / (g.std() + 1e-6)
        score = float((g * ga).mean())
        if best is None or score > best[0]: best = (score, rot)
    o = o.rotate(best[1], expand=True).resize(a.size, Image.LANCZOS)
    name = f'{prefix}{i:03d}'
    o.save(os.path.join(out, name + '_in.png')); a.save(os.path.join(out, name + '_ex.png'))
    row = {'name': name, 'src': t.column('original')[i].as_py()['path'], 'rot': best[1], 'match': round(best[0], 3)}
    for c in ('location', 'time', 'light', 'subject'): row[c] = t.column(c)[i].as_py()
    meta.append(row)
    print(name, row['src'], best, flush=True)
json.dump(meta, open(os.path.join(out, prefix + 'meta.json'), 'w'), indent=1)
