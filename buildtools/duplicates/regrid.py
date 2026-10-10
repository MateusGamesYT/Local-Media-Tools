"""Recomputes only the layout values (Duplicates.grid) of an existing features file.

    python regrid.py photos.json|extra.json features.npz out.npz
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
from common import WORK
from features import thumbnail, grid
from variants import variants

f = dict(np.load(sys.argv[2]))
ids = [str(x) for x in f['ids']]
cache = {}
g16, con = [], []
for k, name in enumerate(ids):
    pid, _, variant = name.partition(':')
    img = Image.open(os.path.join(WORK, 'img', pid + '.jpg')).convert('RGB')
    if variant:
        img = dict(variants(img, None))[variant]
    v, c = grid(thumbnail(img))
    g16.append(v); con.append(c)
    if k % 500 == 0: print(k, flush=True)
f['g16'] = np.stack(g16); f['contrast'] = np.array(con)
np.savez_compressed(sys.argv[3], **f)
print('done')
