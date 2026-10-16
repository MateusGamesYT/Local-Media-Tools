#!/usr/bin/env python3
"""video_eval.py <clips dir> <out.json>

Which frames the gallery should look at in a video (gallery/core/VideoSampling.kt), measured on real
videos of people: Global Lives Project recordings (CC BY 3.0, see <clips dir>/credits.tsv; 30 s
pieces re-encoded with a keyframe a second, as phones record).

Reference: the app's face pipeline for video frames (YuNet on the frame at most 1600 px, ResNet-50
descriptions, as people/extract.py replicates it; video frames have no full-resolution second look)
on every frame at 2 per second; the good faces (the R50 ClusterParams' isGood) grouped by the
gallery's own rule for one video (sameVideoFace 0.54) give the people in the clip, counting those
seen well in at least two of those frames. A way of picking frames finds a person when one of its
frames holds a good face of theirs (0.54 to any of their reference faces). The 1.9 way: three
moments (15, 50, 85 %), each decoded from its nearest keyframe; the new ways: VideoSampling with
several spacings. Things too: the app's tagger (scripts/app_parity.py's replica of ObjectTagger,
same models, head and calibrated categories) on the same frames; the reference is every tag seen in
at least two reference frames, and a way finds a tag when one of its frames has it (tags its frames
have that the reference doesn't are counted as unconfirmed).
"""
import os, sys, json, subprocess
import numpy as np, cv2, struct
from PIL import Image
os.environ.setdefault('PEOPLE_WORK', os.path.join(os.path.dirname(sys.argv[2]) or '.', 'people_work'))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'people'))
import extract
from ai_edge_litert.interpreter import Interpreter

clips, out = sys.argv[1:3]
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets')

# ObjectTagger as scripts/app_parity.py replicates it (Pillow's bilinear = the app's PilResample).
def to8(a): return np.clip(np.round(a), 0, 255).astype(np.uint8)
def resample(a, tw, th): return np.asarray(Image.fromarray(to8(a)).resize((tw, th), Image.BILINEAR)).astype(np.float32)
det = Interpreter(model_path=os.path.join(ASSETS, 'models/efficientdet_lite2_int8.tflite'), num_threads=4); det.allocate_tensors()
dI = det.get_input_details()[0]; dO = {d['shape'][-1]: d['index'] for d in det.get_output_details()}
scn = Interpreter(model_path=os.path.join(ASSETS, 'models/scene_effnetv2_b3_21k.tflite'), num_threads=4); scn.allocate_tensors()
sI = scn.get_input_details()[0]; sO = scn.get_output_details()[0]
raw = open(os.path.join(ASSETS, 'gallery/scene_head.bin'), 'rb').read()
_, R, D, GS, NP = struct.unpack_from('<iiiii', raw, 4); o = 24; ng = D // GS
sc = np.frombuffer(raw, '<f2', R * ng, o).astype(np.float32).reshape(R, ng); o += R * ng * 2
bias = np.frombuffer(raw, '<f4', R, o); o += R * 4
pk = np.frombuffer(raw, np.uint8, R * D // 2, o).reshape(R, D // 2); o += R * D // 2
PW = np.frombuffer(raw, '<f4', NP * D, o).reshape(NP, D); o += NP * D * 4; PB = np.frombuffer(raw, '<f4', NP, o)
lo = (pk & 15).astype(np.int8); hi = (pk >> 4).astype(np.int8); lo[lo > 7] -= 16; hi[hi > 7] -= 16
q = np.empty((R, D), np.float32); q[:, 0::2] = lo; q[:, 1::2] = hi; Wd = (q.reshape(R, ng, GS) * sc[:, :, None]).reshape(R, D)
cats = []
for line in open(os.path.join(ASSETS, 'gallery/categories.tsv')):
    if line.startswith('#') or not line.strip(): continue
    c = line.rstrip('\n').split('\t'); ints = lambda s: [int(x) for x in s.replace(',', ' ').split()]
    cats.append((c[0], ints(c[4]), float(c[5]), ints(c[7]), float(c[8]), int(c[9]), float(c[10])))
def tag(rgb):
    a = rgb; h, w = a.shape[:2]
    side = max(1, min(w, h) * 224 // (224 + 32))
    crop = a[(h - side) // 2:(h + side) // 2, (w - side) // 2:(w + side) // 2]
    scn.set_tensor(sI['index'], to8(resample(crop, 224, 224)).astype(np.float32)[None]); scn.invoke(); feat = scn.get_tensor(sO['index'])[0].copy()
    s = 448 / max(w, h); w2 = max(1, round(w * s)); h2 = max(1, round(h * s))
    box = np.zeros((448, 448, 3), np.uint8); box[:h2, :w2] = to8(resample(a, w2, h2))
    det.set_tensor(dI['index'], box[None]); det.invoke(); dmax = det.get_tensor(dO[90])[0].max(0)
    z = Wd @ feat + bias; z -= z.max(); p = np.exp(z.astype(np.float64)); p /= p.sum()
    pr = 1 / (1 + np.exp(-(PW @ feat + PB)))
    out = set()
    for k, dc, dt, rows, st, pi, pt in cats:
        if dc and dt > 0 and max(dmax[c] if dmax[c] >= 0.15 else 0 for c in dc) >= dt: out.add(k)
        if rows and st > 0 and p[rows].sum() >= st: out.add(k)
        if pi >= 0 and pt > 0 and pr[pi] >= pt: out.add(k)
    return out
SAME = 0.54; GOOD = dict(score=0.80, yaw=0.70, eye=16.0)
_it = []
def emb_r50(img, r):
    if not _it:
        it = Interpreter(model_path=os.path.join(extract.M, 'face_recognition_r50_w600k_int8.tflite'), num_threads=4); it.allocate_tensors(); _it.append(it)
    it = _it[0]; i = it.get_input_details()[0]['index']; o = it.get_output_details()[0]['index']
    al = cv2.warpAffine(img, extract.align_matrix(r), (112, 112), flags=cv2.INTER_LINEAR)
    e = np.zeros(512, np.float64)
    for view in (al, cv2.flip(al, 1)):
        it.set_tensor(i, ((cv2.cvtColor(view, cv2.COLOR_BGR2RGB).astype(np.float32) - 127.5) / 127.5)[None]); it.invoke()
        v = it.get_tensor(o).reshape(-1).astype(np.float64); e += v / np.linalg.norm(v)
    return (e / np.linalg.norm(e)).astype(np.float32)

def faces(frame):
    """GalleryFaces.analyze(engine, src = null, work) and the tags: the frame scaled to fit 1600 px (no refinement)."""
    H, W = frame.shape[:2]; s = min(1.0, 1600 / max(W, H))
    work = cv2.resize(frame, (round(W * s), round(H * s)), interpolation=cv2.INTER_AREA) if s < 1 else frame
    res = {'faces': [], 'tags': tag(cv2.cvtColor(work, cv2.COLOR_BGR2RGB))}
    for f in extract.detect(work):
        eye, yaw = extract.eyes_yaw(f)
        good = f[14] >= GOOD['score'] and yaw <= GOOD['yaw'] and eye >= GOOD['eye']
        res['faces'].append(dict(good=bool(good), emb=emb_r50(work, f) if good else None, eye=eye))
    return res

def keyframes(path):
    r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v', '-skip_frame', 'nokey', '-show_entries', 'frame=pts_time', '-of', 'csv=p=0', path], capture_output=True, text=True, check=True)
    return sorted(float(x.strip(',')) for x in r.stdout.split() if x.strip(', '))

def frame_at(cap, fps, t):
    cap.set(cv2.CAP_PROP_POS_FRAMES, round(t * fps))
    ok, f = cap.read()
    return f if ok else None

def spread(dur, spacing, mn, mx):
    if dur <= 1.5: return [0.0]
    n = int(min(mx, max(mn, dur // spacing)))
    return [dur * (i + 0.5) / n for i in range(n)]

def closest(keys, t): return min(keys, key=lambda k: abs(k - t))

rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(clips, 'credits.tsv')) if not l.startswith('#')]
WAYS = {'1.9: 3 moments': None, 'every 5 s (3-16)': (5, 3, 16), 'every 3 s (3-16)': (3, 3, 16), 'every 2 s (3-24)': (2, 3, 24), 'every 1 s (3-32)': (1, 3, 32)}
results = []
for r in rows:
    path = os.path.join(clips, r[0])
    cap = cv2.VideoCapture(path); fps = cap.get(cv2.CAP_PROP_FPS); n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)); dur = n / fps
    keys = keyframes(path)
    cache = {}
    def at(t):
        k = round(t * fps)
        if k not in cache:
            f = frame_at(cap, fps, t); cache[k] = faces(f) if f is not None else {'faces': [], 'tags': set()}
        return cache[k]
    # Reference: every frame at 2 per second.
    dense = [(t, at(t)) for t in np.arange(0, dur - 0.05, 0.5)]
    groups = []   # lists of (t, emb)
    tagcount = {}
    for t, fr in dense:
        for k in fr['tags']: tagcount[k] = tagcount.get(k, 0) + 1
    reftags = {k for k, n in tagcount.items() if n >= 2}
    for t, fr in dense:
        for f in fr['faces']:
            if not f['good']: continue
            best, bi = 0, -1
            for gi, g in enumerate(groups):
                s = max(float(f['emb'] @ e) for _, e in g)
                if s > best: best, bi = s, gi
            if best >= SAME: groups[bi].append((t, f['emb']))
            else: groups.append([(t, f['emb'])])
    people = [g for g in groups if len({t for t, _ in g}) >= 2]
    res = dict(clip=r[0], source=r[3], seconds=round(dur, 1), keyframe_s=round(float(np.median(np.diff(keys))), 2) if len(keys) > 1 else None, people=len(people), tags=sorted(reftags), ways={})
    for name, way in WAYS.items():
        if way is None: moments = [closest(keys, dur * p) for p in (0.15, 0.5, 0.85)]
        else:
            seen, moments = set(), []
            for t in spread(dur, *way):
                k = closest(keys, t)
                if k not in seen: seen.add(k); moments.append(k)
        found = set(); good = 0; tags = set()
        for t in moments:
            tags |= at(t)['tags']
            for f in at(t)['faces']:
                if not f['good']: continue
                good += 1
                for pi, g in enumerate(people):
                    if max(float(f['emb'] @ e) for _, e in g) >= SAME: found.add(pi)
        res['ways'][name] = dict(frames=len(moments), distinct=len(set(moments)), found=len(found), good_faces=good,
                                 tags_found=len(tags & reftags), tags_unconfirmed=len(tags - reftags))
    results.append(res)
    print(r[0], res['people'], len(reftags), {k: (v['frames'], v['distinct'], v['found'], v['tags_found'], v['tags_unconfirmed']) for k, v in res['ways'].items()}, flush=True)
json.dump(results, open(out, 'w'), indent=1)
total = sum(x['people'] for x in results)
print(f'\n{len(results)} clips, {total} people seen well in at least two of the reference frames')
for name in WAYS:
    f = sum(x['ways'][name]['found'] for x in results); fr = np.mean([x['ways'][name]['frames'] for x in results]); d = np.mean([x['ways'][name]['distinct'] for x in results])
    clipsall = sum(1 for x in results if x['ways'][name]['found'] == x['people'])
    tf_ = sum(x['ways'][name]['tags_found'] for x in results); tt = sum(len(x['tags']) for x in results); tu = sum(x['ways'][name]['tags_unconfirmed'] for x in results)
    print(f'{name:20s} people found {f}/{total} ({100 * f / max(total, 1):.0f} %), every person in {clipsall}/{len(results)} clips; things found {tf_}/{tt} ({100 * tf_ / max(tt, 1):.0f} %), unconfirmed {tu}; frames per clip {fr:.1f} ({d:.1f} different)')
