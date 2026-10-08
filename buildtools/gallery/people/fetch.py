"""Download chosen Open Images originals (CC BY 2.0), apply their rotation, keep at most 2048 px."""
import json, sys, os, io, subprocess, concurrent.futures as cf
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import WORK
sel = json.load(open(sys.argv[1]))
jobs = [(p, r) for p, rows in sel.items() for r in rows]
def one(job):
    p, r = job
    out = os.path.join(WORK, 'img', r['id'] + '.jpg')
    if os.path.exists(out): return 'have'
    url = f"https://open-images-dataset.s3.amazonaws.com/{r['subset']}/{r['id']}.jpg"
    try:
        raw = subprocess.run(['curl', '-sS', '--fail', '--max-time', '90', url], capture_output=True, timeout=120).stdout
        img = Image.open(io.BytesIO(raw)).convert('RGB')
        rot = int(float(r.get('rot') or 0))
        if rot: img = img.rotate(-rot, expand=True)
        s = 2048 / max(img.size)
        if s < 1: img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
        img.save(out, quality=92)
        return 'ok'
    except Exception as e:
        return 'fail'
with cf.ThreadPoolExecutor(12) as ex:
    res = list(ex.map(one, jobs))
print({k: res.count(k) for k in set(res)})
