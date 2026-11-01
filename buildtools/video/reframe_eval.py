"""How well auto reframe keeps the subject in the picture, on real videos of people.

    python3 reframe_eval.py <det dir> <out.json> [aspect=0.5625]

Clips: Global Lives Project recordings (CC BY 3.0; fetch_clips.py), 640 x 360, 30 s, daily life filmed
alongside one person. reframe_detect.py found what the app's detectors see; the app's own reframe
(Kotlin, harness/) plans the crop from that.

Reference: torchvision's Faster R-CNN v2 (independent of the app's models), 5 times a second, between
the app's samples. People are joined into tracks (overlap from frame to frame); the subject is the
track that is there most and biggest. For each reference frame with the subject in it:
  - covered: the share of the subject's box inside the crop (1 = all of it);
  - in frame: 90 % or more of it inside;
  - head in frame: 90 % or more of the subject's head inside (reframe_heads.py: Keypoint R-CNN, also
    independent of the app; a person close to the camera can be wider than a portrait picture, and
    then the head is what has to stay in);
  - someone's head in frame: at least one person's head (any of them) 90 % or more inside. In group
    scenes "the subject" is a matter of choice (the app may follow someone else than the person on
    screen the most and biggest); this says whether the picture shows a person rather than a wall.
Camera: how fast the crop moves (share of the picture's width per second) and how often it turns
back (reversals per minute; jitter shows as many).

Compared: the centre crop (what a phone does without reframing), following each sample without
smoothing, and the app's planner with its settings. Settings are chosen on half the clips (even
numbers) and scored on the other half.
"""
import json, os, subprocess, sys
import numpy as np

det, out = sys.argv[1:3]
aspect = float(sys.argv[3]) if len(sys.argv) > 3 else 9 / 16
H = os.path.join(det, '..', 'harness', 'run.sh')
clips = sorted(f[:-8] for f in os.listdir(det) if f.endswith('.ref.tsv'))

def reference(clip):
    rows = [l.split('\t') for l in open(os.path.join(det, clip + '.ref.tsv'))]
    by = {}
    for r in rows: by.setdefault(int(r[0]), []).append(np.array([float(x) for x in r[1:5]]))
    times = sorted(by)
    tracks = []  # each: {t: box}
    def iou(a, b):
        ix = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])); iy = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
        i = ix * iy; u = a[2] * a[3] + b[2] * b[3] - i
        return i / u if u > 0 else 0
    for t in times:
        used = set()
        for tr in tracks:
            last_t = max(tr); last = tr[last_t]
            if t - last_t > 1200: continue
            best, bi = 0.3, None
            for k, b in enumerate(by[t]):
                if k in used: continue
                v = iou(last, b)
                if v > best: best, bi = v, k
            if bi is not None: tr[t] = by[t][bi]; used.add(bi)
        for k, b in enumerate(by[t]):
            if k not in used: tracks.append({t: b})
    if not tracks: return {}, []
    main = max(tracks, key=lambda tr: sum(np.sqrt(b[2] * b[3]) for b in tr.values()))
    # The subject's head where the keypoint reference saw it (its person matching the subject's box).
    heads = {}; anyhead = {}
    hp = os.path.join(det, clip + '.heads.tsv')
    if os.path.exists(hp):
        hb = {}
        for l in open(hp):
            r = l.split('\t'); hb.setdefault(int(r[0]), []).append([float(x) for x in r[1:9]])
        def clipped(h):
            x0, y0 = max(0, h[0]), max(0, h[1]); x1, y1 = min(1, h[0] + h[2]), min(1, h[1] + h[3])
            return np.array([x0, y0, x1 - x0, y1 - y0]) if x1 > x0 and y1 > y0 else None
        for t, rs in hb.items():
            hs = [b for b in (clipped(r[4:]) for r in rs) if b is not None]
            if hs: anyhead[t] = hs
        for t, b in main.items():
            best, head = 0.5, None
            for r in hb.get(t, []):
                v = iou(b, np.array(r[:4]))
                if v > best: best, head = v, r[4:]
            if head is not None:
                x0, y0 = max(0, head[0]), max(0, head[1]); x1, y1 = min(1, head[0] + head[2]), min(1, head[1] + head[3])
                if x1 > x0 and y1 > y0: heads[t] = np.array([x0, y0, x1 - x0, y1 - y0])
    return main, times, heads, anyhead

def crops(clip, args):
    f = os.path.join(det, '..', f'crops_{os.getpid()}.tsv')
    subprocess.run([H, det, clip, f] + args, check=True, stderr=subprocess.DEVNULL)
    c = {}
    for l in open(f):
        r = l.split('\t'); c[int(r[0])] = np.array([float(x) for x in r[1:5]])
    return c

def centre_crop(times):
    w = min(1, aspect / (16 / 9)); return {t: np.array([(1 - w) / 2, 0, w, 1]) for t in times}

def inside(b, c):
    ix = max(0, min(b[0] + b[2], c[0] + c[2]) - max(b[0], c[0])); iy = max(0, min(b[1] + b[3], c[1] + c[3]) - max(b[1], c[1]))
    return ix * iy / max(1e-9, b[2] * b[3])

def score(subject, crop, heads, anyhead):
    def at(t): return crop[min(crop, key=lambda k: abs(k - t))]
    cov = [inside(b, at(t)) for t, b in subject.items()]
    hin = [inside(b, at(t)) >= 0.9 for t, b in heads.items()]
    ain = [any(inside(b, at(t)) >= 0.9 for b in hs) for t, hs in anyhead.items()]
    ts = sorted(crop); cx = np.array([crop[t][0] + crop[t][2] / 2 for t in ts]); dt = np.diff(ts) / 1000
    v = np.diff(cx) / np.maximum(dt, 1e-3)
    moving = np.abs(v) > 0.01
    sign = np.sign(v[moving]); rev = int((np.diff(sign) != 0).sum()) if len(sign) > 1 else 0
    dur = (ts[-1] - ts[0]) / 60000
    return dict(covered=float(np.mean(cov)) if cov else None, in_frame=float(np.mean(np.array(cov) >= 0.9)) if cov else None,
                head_in=float(np.mean(hin)) if hin else 0.0, nh=len(hin),
                any_head=float(np.mean(ain)) if ain else 0.0, na=len(ain),
                speed=float(np.mean(np.abs(v))), reversals_per_min=rev / max(dur, 1e-6), n=len(cov))

def run(clipset, method):
    res = []
    for clip in clipset:
        subject, times, heads, anyhead = refs[clip]
        if not subject: continue
        crop = centre_crop(times) if method == 'centre' else crops(clip, method)
        res.append(score(subject, crop, heads, anyhead))
    keys = ['covered', 'in_frame', 'head_in', 'any_head', 'speed', 'reversals_per_min']
    w = np.array([r['n'] for r in res], float); wh = np.array([r['nh'] for r in res], float); wa = np.array([r['na'] for r in res], float)
    def avg(k, wt):
        if not res or (wt is not None and wt.sum() == 0): return float('nan')
        return float(np.average([r[k] for r in res], weights=wt))
    out = {k: avg(k, w if k in ('covered', 'in_frame') else wh if k == 'head_in' else wa if k == 'any_head' else None) for k in keys}
    out['heads'] = int(wh.sum())
    return out

only = [c for c in sys.argv[4:]]  # optionally: just these clips (a quick look while references are made)
if only: clips = [c for c in clips if c in only]
refs = {c: reference(c) for c in clips}
A = f'aspect={aspect}'
tune = [c for c in clips if int(c[4:]) % 2 == 0]; test = [c for c in clips if int(c[4:]) % 2 == 1]
grid = [[A, 'rate=5', f'speed={s}', f'accel={a}', f'margin={m}', f'heads={h}', f'centre={c}']
        for s in (0.1, 0.3, 0.6, 1.2) for a in (0.1, 0.4, 1.6) for m in (0.04, 0.08) for h in (0, 1) for c in (0, 0.25, 0.5)]
# Body and head in the picture count alike; a camera that moves a lot or keeps turning back costs.
def objective(m): return 0.5 * m['in_frame'] + 0.5 * m['head_in'] - 0.02 * m['reversals_per_min'] - 0.3 * m['speed']
results = {'clips': clips, 'aspect': aspect, 'subjects': {c: len(refs[c][0]) for c in clips}}
for name, half in (('tune', tune), ('test', test)):
    results[name] = {'centre crop': run(half, 'centre'), 'follow, no smoothing': run(half, [A, 'rate=5', 'speed=0', 'accel=0', 'margin=0.08', 'heads=0']),
                     'app defaults, heads=0': run(half, [A, 'rate=5', 'heads=0']), 'app defaults, heads=1': run(half, [A, 'rate=5', 'heads=1'])}
if os.environ.get('QUICK'): grid = [[A, 'rate=5', 'heads=1']]
scored = [(objective(run(tune, g)), g) for g in grid]
best = max(scored, key=lambda x: x[0])[1]
results['best'] = best
results['tune']['app (best on these)'] = run(tune, best)
results['test']['app (chosen on the other half)'] = run(test, best)
results['test']['app (defaults)'] = run(test, [A, 'rate=5'])
results['test']['app (defaults), zoom 1'] = run(test, [A, 'rate=5', 'zoom=1'])
# Each clip with the defaults (where it goes wrong).
results['per_clip'] = {}
for c in clips:
    subject, times, heads, anyhead = refs[c]
    if subject: results['per_clip'][c] = score(subject, crops(c, [A, 'rate=5']), heads, anyhead)
json.dump(results, open(out, 'w'), indent=1)
for name in ('tune', 'test'):
    print(f'== {name} clips ({len(tune if name == "tune" else test)})')
    for k, m in results[name].items():
        print(f'  {k:32s} subject covered {m["covered"]:.3f}, in frame {m["in_frame"]:.3f}, head in {m["head_in"]:.3f} ({m["heads"]}), someone\'s head {m["any_head"]:.3f}; camera {m["speed"]:.3f} widths/s, {m["reversals_per_min"]:.1f} reversals/min')
print('chosen:', ' '.join(best))
for c, m in results['per_clip'].items():
    print(f'  {c}: covered {m["covered"]:.3f}, in frame {m["in_frame"]:.3f}, head in {m["head_in"]:.3f} ({m["nh"]}), someone\'s head {m["any_head"]:.3f} ({m["na"]}), {m["n"]} reference frames')
