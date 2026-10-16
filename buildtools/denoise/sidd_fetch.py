#!/usr/bin/env python3
"""sidd_fetch.py <out dir> [scenes per camera]: real smartphone noise with clean references.

SIDD (Abdelhamed, Lin, Brown, CVPR 2018; MIT licence), the Medium sRGB set's copy on Hugging Face
(cdtrejo/sidd-medium-lmdb, one noisy photo and its ground truth per scene). For each chosen scene the
pair is downloaded, four 512 px crops are kept (the same places in both, fixed seed) and the full
photos are deleted. Scenes are spread over the five phones (Samsung S6, Google Pixel, iPhone 7,
Nexus 6, LG G4) and their ISO settings; scenes.tsv lists them (camera, ISO, light)."""
import io, json, os, random, sys, urllib.request
from PIL import Image

out = sys.argv[1]; per_camera = int(sys.argv[2]) if len(sys.argv) > 2 else 10
os.makedirs(out, exist_ok=True)
BASE = 'https://huggingface.co/datasets/cdtrejo/sidd-medium-lmdb/resolve/main/raw/train'
tree = json.load(urllib.request.urlopen('https://huggingface.co/api/datasets/cdtrejo/sidd-medium-lmdb/tree/main/raw/train'))
scenes = sorted(x['path'].split('/')[-1] for x in tree)
by_cam = {}
for s in scenes: by_cam.setdefault(s.split('_')[2], []).append(s)
rnd = random.Random(11)
chosen = []
for cam, lst in sorted(by_cam.items()):
    # Spread over ISO: sort by ISO and take evenly spaced scenes.
    lst = sorted(lst, key=lambda s: int(s.split('_')[3]))
    step = max(1, len(lst) / per_camera)
    chosen += [lst[int(i * step)] for i in range(min(per_camera, len(lst)))]
rows = []
for s in chosen:
    n = s.split('_')[0]
    imgs = {}
    for kind in ('GT', 'NOISY'):
        data = urllib.request.urlopen(f'{BASE}/{s}/{n}_{kind}_SRGB_010.PNG').read()
        imgs[kind] = Image.open(io.BytesIO(data)).convert('RGB')
    w, h = imgs['GT'].size
    for k in range(4):
        x = rnd.randrange(0, w - 512); y = rnd.randrange(0, h - 512)
        for kind, im in imgs.items():
            im.crop((x, y, x + 512, y + 512)).save(os.path.join(out, f'{n}_{k}_{kind.lower()}.png'))
    _, _, cam, iso, shutter, temp, light = s.split('_')
    rows.append(f'{n}\t{cam}\t{int(iso)}\t{light}\t{s}')
    print(s, flush=True)
open(os.path.join(out, 'scenes.tsv'), 'w').write('\n'.join(rows) + '\n')
