"""aim_extract.py <parquet> <out dir>: each AIM-500 photo (long side ≤ 1024, JPEG) and its alpha matte (PNG, same size); list.tsv."""
import sys, io, os, pyarrow.parquet as pq
from PIL import Image
src, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
t = pq.ParquetFile(src).read_row_group(0)
rows = []
for i in range(t.num_rows):
    name = t.column('image_name')[i].as_py()
    im = Image.open(io.BytesIO(t.column('image')[i].as_py()['bytes'])).convert('RGB')
    al = Image.open(io.BytesIO(t.column('mask')[i].as_py()['bytes'])).convert('L')
    if al.size != im.size: al = al.resize(im.size, Image.BILINEAR)
    s = 1024 / max(im.size)
    if s < 1:
        size = (max(1, round(im.width * s)), max(1, round(im.height * s)))
        im = im.resize(size, Image.LANCZOS); al = al.resize(size, Image.LANCZOS)
    im.save(os.path.join(out, name + '.jpg'), quality=95); al.save(os.path.join(out, name + '_a.png'))
    rows.append(f"{i}\t{name}\t{t.column('category')[i].as_py()}\t{t.column('type')[i].as_py()}")
open(os.path.join(out, 'list.tsv'), 'w').write('\n'.join(rows) + '\n')
print(len(rows))
