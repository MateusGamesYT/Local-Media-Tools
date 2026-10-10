"""Builds the real-photo test fixture: crops of Creative Commons (CC BY 2.0) photos from Open Images,
the faces the app's pipeline finds in them (with embeddings), who the verified ones are, and credits.
  jvm set:  8 people x 6 faces, chosen to cover headwear/glasses/make-up/expression/angles/small faces
  robo set: roles for the Robolectric fake gallery (A x10 + a small blurry A face, B x12, C x3).
The photos are chosen as in 1.5.0, with SFace (so the set stays the same); the faces written are
described as the app describes them now (1.8.0: MobileFaceNet, extract.use_mbf()), and the robo set
must group as the UI tests expect with the app's current rules (MBF below)."""
import sys, os, re, html, json, random, gzip, csv, base64, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2
import extract
from common import WORK, HERE
from evaluate import load, cluster_py
D = WORK
OUT = sys.argv[1]
NEW = {"join": 0.46, "merge": 0.65, "assign": 0.42, "low": 0.46, "margin": 0.06, "goodYaw": 1.0, "goodEye": 24, "goodScore": 0.8, "lowScore": 0.85, "link": "centroid"}
# ClusterParams.MBF (1.8.0)
MBF = {"join": 0.377, "merge": 0.50, "assign": 0.308, "low": 0.317, "margin": 0.0, "goodYaw": 0.7, "goodEye": 20, "goodScore": 0.7, "lowScore": 0.8, "link": "centroid"}
JVM = ['Taylor Swift', 'Brian Solis', 'Usain Bolt', 'Ian Somerhalder', 'Caroline Wozniacki', 'Katy Perry', 'Christian Heilmann', 'Lady Gaga']
# The UI tests' screenshots show their faces: people from entertainment, sport and technology only.
SHOWN = {'Katy Perry', 'Taylor Swift', 'Brian Solis', 'James Marsters', 'Ian Somerhalder', 'John Mayer', 'Neil Patrick Harris', 'Lady Gaga',
         'Guy Kawasaki', 'Gary Vaynerchuk', 'Caroline Wozniacki', 'Roger Federer', 'Felicia Day', 'Jimmy Wales', 'Jared Padalecki',
         'Britney Spears', 'Joi Ito', 'Amanda Palmer', 'Jensen Ackles', 'Chris Brogan', 'Justin Bieber', 'Jennifer Morrison',
         'Bruce Springsteen', 'Christian Heilmann', 'Kelly Clarkson', 'David Boreanaz', 'Serena Williams', 'Usain Bolt', 'Adam Baldwin'}
WOMEN = {'Katy Perry', 'Taylor Swift', 'Lady Gaga', 'Caroline Wozniacki', 'Felicia Day', 'Britney Spears'}
faces = load(os.path.join(WORK, 'faces.json'), os.path.join(HERE, 'labels.json'))
meta = {r['ImageID']: r for r in csv.DictReader(gzip.open(os.path.join(D, 'oi_meta_dl.csv.gz'), 'rt'))}
# Only the photographer's own pictures: no edits, manipulations, screen grabs or promotional reposts.
DOUBTFUL = re.compile(r'manip|digital|edit|screen|still|promo|wallpaper|photoshop|fan ?art|drawing|poster|cover|magazine', re.I)
BANNED_AUTHORS = {'Hollywood Branded', 'Horus Tr4n'}
by = collections.defaultdict(list)
for f in faces:
    m = meta.get(f['image'])
    if not f['label'] or m is None or DOUBTFUL.search(m['Title']) or m['Author'] in BANNED_AUTHORS: continue
    by[f['label']].append(f)

def feats(f):
    s = set(f['tags'])
    s.add('F' if f['yaw'] < 0.25 else 'T' if f['yaw'] < 0.6 else 'P')
    if f['eye'] < 24: s.add('S')
    return s

def stratified(people_faces, n, rnd, exclude=()):
    pool = [f for f in people_faces if f['image'] not in exclude]
    rnd.shuffle(pool); got = []; covered = set()
    while pool and len(got) < n:
        best = max(pool, key=lambda f: len(feats(f) - covered))
        got.append(best); covered |= feats(best); pool.remove(best)
    return got

def crop(f):
    """A window around the face (at least 560 px, 6 face widths), long side at most 800 px."""
    img = cv2.imread(os.path.join(D, 'img', f['image'] + '.jpg')); H, W = img.shape[:2]
    bx, by_, bw, bh = f['box']
    fw = bw * W; cx = (bx + bw / 2) * W; cy = (by_ + bh / 2) * H
    ww = max(6 * fw, 560); wh = ww * (3 / 4 if W >= H else 4 / 3)
    ww, wh = min(ww, W), min(wh, H)
    x0 = int(min(max(0, cx - ww / 2), W - ww)); y0 = int(min(max(0, cy - wh / 2), H - wh))
    c = img[y0:y0 + int(wh), x0:x0 + int(ww)]
    s = min(1.0, 800 / max(c.shape[:2]))
    if s < 1: c = cv2.resize(c, (round(c.shape[1] * s), round(c.shape[0] * s)), interpolation=cv2.INTER_AREA)
    # the labelled face's box inside the crop, in fractions
    box = ((bx * W - x0) / ww, (by_ * H - y0) / wh, fw / ww, bh * H / wh)
    return c, box

def iou(a, b):
    ax1, ay1, bx1, by1 = a[0] + a[2], a[1] + a[3], b[0] + b[2], b[1] + b[3]
    iw = max(0, min(ax1, bx1) - max(a[0], b[0])); ih = max(0, min(ay1, by1) - max(a[1], b[1]))
    i = iw * ih; return i / (a[2] * a[3] + b[2] * b[3] - i + 1e-9)

cache = {}
def fixture(f):
    """(jpeg bytes, faces found in the crop, index of the labelled one) or None if it's not found again."""
    if f['face'] in cache: return cache[f['face']]
    c, box = crop(f)
    ok, jpg = cv2.imencode('.jpg', c, [cv2.IMWRITE_JPEG_QUALITY, 85])
    dec = cv2.imdecode(jpg, cv2.IMREAD_COLOR)
    found = extract.analyze(dec)
    k = max(range(len(found)), key=lambda i: iou(found[i]['box'], box)) if found else -1
    r = (jpg.tobytes(), found, k) if k >= 0 and iou(found[k]['box'], box) > 0.4 else None
    cache[f['face']] = r
    return r

def pick(person, n, rnd, exclude=(), where=lambda f: True):
    cands = [f for f in by[person] if where(f)]
    out = []
    for f in stratified(cands, len(cands), rnd, exclude):
        if fixture(f) is not None: out.append(f)
        if len(out) == n: break
    return out

rnd = random.Random(0)
jvm = {p: pick(p, 6, rnd) for p in JVM}
# Robolectric roles (A x10 plus a small face of A the grouping must leave alone, B x12, C x3): the UI
# tests need a gallery that groups exactly one way, so people and photos are drawn until it does.
def robo():
    As = sorted(p for p in WOMEN if len(by[p]) >= 11 and any(f['eye'] < 16 for f in by[p]))
    Bs = sorted(p for p in SHOWN - WOMEN if len(by[p]) >= 12)
    Cs = sorted(p for p in SHOWN if len(by[p]) >= 3)
    rnd = random.Random(1)
    for attempt in range(1000):
        A, B, C = rnd.choice(As), rnd.choice(Bs), rnd.choice(Cs)
        if len({A, B, C}) < 3: continue
        small = [f for f in sorted(by[A], key=lambda f: f['score']) if f['eye'] < 16 and fixture(f) is not None]
        if not small: continue
        blurry = small[0]
        a = pick(A, 10, rnd, exclude={blurry['image']}); b = pick(B, 12, rnd); c = pick(C, 3, rnd)
        if len(a) < 10 or len(b) < 12 or len(c) < 3: continue
        if {f['image'] for f in a + [blurry]} & {f['image'] for f in b + c} or {f['image'] for f in b} & {f['image'] for f in c}: continue
        roles = [('A%d' % (i + 1), f) for i, f in enumerate(a)] + [('A-small', blurry)] + [('B%d' % (i + 1), f) for i, f in enumerate(b)] + [('C%d' % (i + 1), f) for i, f in enumerate(c)]
        recs = []
        for role, f in roles:
            jpg, found, k = fixture(f); g = found[k]
            recs.append(dict(face=role, image=f['image'], label=role[0], tags='', emb=list(g['emb']), score=g['score'], eye=g['eye'], yaw=g['yaw']))
        lab = cluster_py(recs, NEW)
        grp = collections.defaultdict(set)
        for r, l in zip(recs, lab): grp[l].add(r['face'] if r['face'] == 'A-small' else r['face'][0])
        sizes = collections.Counter(lab)
        if (all(len(v) == 1 for v in grp.values()) and sizes[lab[10]] == 1
                and sorted(sizes[g] for g in sizes if sizes[g] >= 2) == [3, 10, 12]):
            print('robo:', A, '/', B, '/', C, 'after', attempt + 1, 'draws')
            return roles
    sys.exit('no robo selection groups as the UI tests expect')
roles = robo()

described = {}
def current(f):
    """The faces of a chosen photo as the app finds and describes them now (the same faces, in the same order)."""
    jpg, found, k = fixture(f)
    if f['image'] not in described:
        sface = extract.emb; extract.use_mbf()
        try: now = extract.analyze(cv2.imdecode(np.frombuffer(jpg, np.uint8), cv2.IMREAD_COLOR))
        finally: extract.emb = sface
        assert len(now) == len(found) and all(np.allclose(a['box'], b['box']) for a, b in zip(now, found)), f['image']
        described[f['image']] = now
    return described[f['image']], k
recs = []
for role, f in roles:
    now, k = current(f); g = now[k]
    recs.append(dict(face=role, label=role[0], emb=list(g['emb']), score=g['score'], eye=g['eye'], yaw=g['yaw']))
lab = cluster_py(recs, MBF)
sizes = collections.Counter(lab)
# 1.8.0: MobileFaceNet also recognises the small, far-away face of A that SFace left on its own.
if sorted(sizes[g] for g in sizes if sizes[g] >= 2) != [3, 11, 12] or any(len({r['label'] for r, l in zip(recs, lab) if l == g}) > 1 for g in sizes):
    sys.exit('the robo set no longer groups as the UI tests expect with the current rules')

os.makedirs(OUT, exist_ok=True)
files = {}
rows = []
def emit(f, role):
    jpg, _, k = fixture(f)
    found, _ = current(f)
    fn = f['image'] + '.jpg'
    if fn not in files:
        open(os.path.join(OUT, fn), 'wb').write(jpg); files[fn] = f
        for i, g in enumerate(found):
            rows.append([fn, i, *('%.5f' % v for v in g['box']), '%.4f' % g['score'], '%.3f' % g['eye'], '%.4f' % g['yaw'],
                         f['label'] if i == k else '', f['tags'] if i == k else '', '',
                         base64.b64encode(np.asarray(g['emb'], '<f4').tobytes()).decode()])
    if role:
        for row in rows:
            if row[0] == fn and row[1] == k: row[11] = (row[11] + ',' if row[11] else '') + role
for p in JVM:
    for f in jvm[p]: emit(f, 'jvm')
for role, f in roles: emit(f, role)
with open(os.path.join(OUT, 'faces.tsv'), 'w') as o:
    o.write('# file\tface\tx\ty\tw\th\tscore\teye_px\tyaw\tperson\ttags\troles\tembedding (512 float32, little-endian, base64; MobileFaceNet)\n')
    for r in rows: o.write('\t'.join(map(str, r)) + '\n')
with open(os.path.join(OUT, 'CREDITS.tsv'), 'w') as o:
    o.write('# file\tauthor\ttitle\tsource\tlicence\tchanges\n')
    for fn, f in sorted(files.items()):
        m = meta[f['image']]
        o.write('\t'.join([fn, html.unescape(m['Author']), ' '.join(html.unescape(m['Title']).split()), m['Landing'], m['License'], 'cropped and resized']) + '\n')
tot = sum(os.path.getsize(os.path.join(OUT, fn)) for fn in files)
print(len(files), 'photos', round(tot / 1e6, 2), 'MB;', len(rows), 'faces;', sum(1 for r in rows if r[9]), 'labelled')
