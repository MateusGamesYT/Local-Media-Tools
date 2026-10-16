#!/usr/bin/env python3
"""song_eval.py <run.sh> <songs dir> <work dir>

Checks the app's beat finding for the user's own song (highlight/core/Song.kt, through
harness/run.sh) on real, freely licensed songs (<songs dir>/credits.tsv: Wikimedia Commons, CC BY,
CC BY-SA, CC0 or public domain). There are no hand-made tempo labels for them, so two independent
estimators are the reference: librosa's beat tracker (Ellis 2007) and Essentia's RhythmExtractor2013
(multifeature). Where the two agree within 4 %, that tempo is taken as right, and the app is scored
on it: exact (within 4 %), or off by a factor 2 or 1/2 (cuts then fall on every beat or every other
beat, still in time), or wrong. Songs where the references disagree are listed apart.
"""
import os, sys, subprocess
import numpy as np, librosa, essentia.standard as es

run, songs, work = sys.argv[1:4]
os.makedirs(work, exist_ok=True)
rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(songs, 'credits.tsv')) if not l.startswith('#')]
paths = []
for r in rows:
    f = os.path.join(songs, r[0])
    try:
        y, sr = librosa.load(f, sr=48000, mono=False, duration=150)
    except Exception as e:
        print('skip', r[0], e); continue
    if y.ndim == 1: y = np.stack([y, y])
    out = os.path.join(work, r[0] + '.f32')
    y.T.astype('<f4').tofile(out)
    paths.append((r, out, y))
res = subprocess.run([run, '30'] + [p for _, p, _ in paths], capture_output=True, text=True, check=True).stdout.strip().split('\n')
app = {l.split('\t')[0]: l.split('\t') for l in res}
def rel(a, b): return abs(a / b - 1)
counts = {'exact': 0, 'octave': 0, 'none': 0, 'wrong': 0}
disagree = []
print(f'{"file":12s} {"app":>7s} {"conf":>6s} {"librosa":>8s} {"essentia":>8s}  verdict   query')
for r, p, y in paths:
    mono = librosa.to_mono(y[:, :48000 * 60])
    lr = float(np.atleast_1d(librosa.beat.beat_track(y=librosa.resample(mono, orig_sr=48000, target_sr=22050), sr=22050)[0])[0])
    ess = float(es.RhythmExtractor2013(method='multifeature')(es.Resample(inputSampleRate=48000, outputSampleRate=44100)(mono.astype(np.float32)))[0])
    a = app[os.path.basename(p)]
    bpm = None if a[1] == 'none' else float(a[1])
    agree = rel(lr, ess) < 0.04
    if not agree:
        # Also agree when one is double the other: take the one nearer 120 as the beat.
        for k in (2, 0.5):
            if rel(lr, ess * k) < 0.04: agree = True
    ref = (lr + ess) / 2 if rel(lr, ess) < 0.04 else None
    if not agree:
        verdict = 'refs differ'; disagree.append(r[0])
    else:
        refs = [lr, ess]
        if bpm is None: verdict = 'none'
        elif any(rel(bpm, x) < 0.04 for x in refs): verdict = 'exact'
        elif any(rel(bpm, x * k) < 0.04 for x in refs for k in (2, 0.5)): verdict = 'octave'
        else: verdict = 'wrong'
        counts[verdict] += 1
    print(f'{r[0]:12s} {a[1]:>7s} {a[2]:>6s} {lr:8.2f} {ess:8.2f}  {verdict:9s} {r[5]}')
n = sum(counts.values())
print(f'\nsongs where the references agree: {n}; app exact {counts["exact"]}, double/half {counts["octave"]}, no beat {counts["none"]}, wrong {counts["wrong"]}')
print(f'references disagree: {len(disagree)}')
