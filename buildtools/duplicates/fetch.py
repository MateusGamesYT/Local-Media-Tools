"""Downloads the chosen Open Images originals (CC BY 2.0), applies their rotation and keeps them at
most 1,024 px (the finder itself works on 256–512 px thumbnails).

    python fetch.py photos.json
"""
import concurrent.futures as cf, io, json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
from common import WORK

sel = json.load(open(sys.argv[1]))
rows = [p for r in sel['runs'] for p in r['photos']] + sel['pool']


def one(r):
    out = os.path.join(WORK, 'img', r['id'] + '.jpg')
    if os.path.exists(out):
        return 'have'
    url = f"https://open-images-dataset.s3.amazonaws.com/{r['subset']}/{r['id']}.jpg"
    try:
        raw = subprocess.run(['curl', '-sS', '--fail', '--max-time', '90', url], capture_output=True, timeout=120).stdout
        img = Image.open(io.BytesIO(raw)).convert('RGB')
        rot = r.get('rot') or '0'
        rot = 0 if rot.lower() == 'nan' else int(float(rot))  # NaN: not known, used as it is
        if rot:
            img = img.rotate(-rot, expand=True)
        s = 1024 / max(img.size)
        if s < 1:
            img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
        img.save(out, quality=93)
        return 'ok'
    except Exception:
        return 'fail'


with cf.ThreadPoolExecutor(12) as ex:
    res = list(ex.map(one, rows))
print({k: res.count(k) for k in set(res)})
