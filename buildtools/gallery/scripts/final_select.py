"""Final category set and thresholds.

A category ships with its 90%-precision thresholds when they hold up in 2-fold cross-validation
(precision >= 0.85, recall >= 0.30), else with its 80% ones (precision >= 0.78, recall >= 0.35),
or when either is very precise but less complete (precision >= 0.88, recall >= 0.25). Everything
else is left out.

Beach and lake are judged differently: Open Images' verified negatives for them are mostly other
shores and water (a sea shore labelled "not a beach"), so a hit also counts as right when the photo
is verified to show one of the near-identical scenes listed in NEAR. To stay honest this is measured
over every photo the probe fires on (unverified ones count as wrong): the threshold is the one with
the best recall at >= 85% of all hits being verified beach/sea/shore (or lake/river/water) photos.
"""
import csv, collections, json, os, sys
import numpy as np
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(G, '..', 'dl', 'oi')
NEAR = {
    'beach': ['Beach', 'Sea', 'Coast', 'Shore', 'Ocean', 'Body of water', 'Seaside', 'Sand'],
    'lake': ['Lake', 'River', 'Pond', 'Body of water', 'Reflection', 'Reservoir', 'Loch', 'Water'],
}

a = json.load(open(os.path.join(G, 'out/thresholds_0.9.json'))); b = json.load(open(os.path.join(G, 'out/thresholds_0.8.json')))
scores = np.load(os.path.join(G, 'out/oi_scores.npz')); probes = np.load(os.path.join(G, 'out/probes.npz'))
keys = list(scores['keys']); Y = scores['Y']; pkeys = list(probes['keys']); P = probes['P']

def near_threshold(cat):
    names = {r[0]: r[1] for r in csv.reader(open(os.path.join(D, 'oidv6-class-descriptions.csv')))}
    inv = collections.defaultdict(list)
    for k, v in names.items(): inv[v].append(k)
    idx = {i: k for k, i in enumerate(scores['ids'])}
    mids = {m for l in NEAR[cat] for m in inv.get(l, [])}
    near = set()
    for r in csv.DictReader(open(os.path.join(D, 'validation-annotations-human-imagelabels.csv'))):
        if r['Confidence'] == '1' and r['LabelName'] in mids and r['ImageID'] in idx: near.add(idx[r['ImageID']])
    j = keys.index(cat); y = Y[:, j]; m = y >= 0; s = P[:, pkeys.index(cat)]
    best = None
    for t in np.round(np.arange(0.5, 0.995, 0.01), 2):
        hit = s >= t
        tp = int((hit & m & (y == 1)).sum()); fp = int((hit & m & (y == 0)).sum())
        if tp == 0: continue
        fired = np.where(hit)[0]
        near_share = sum(1 for i in fired if i in near) / len(fired)
        r = tp / max(1, int((y == 1).sum()))
        if near_share >= 0.85 and (best is None or r > best['R']):
            best = dict(src=['wn', 'probe'], t=[None, float(t)], P=tp / (tp + fp), R=r, cvP=near_share, cvR=r, fired=len(fired),
                        npos=int((y == 1).sum()), nneg=int((y == 0).sum()), note='precision counts other ' + '/'.join(NEAR[cat][1:4]).lower() + ' photos as right')
    return best

out, rows = {}, []
for k in a:
    pick = None
    ta, tb = a.get(k), b.get(k)
    if k in NEAR:
        t = near_threshold(k)
        if t: pick = ('near', t)
    elif ta and ta['npos'] >= 15 and ta['cvP'] >= 0.85 and ta['cvR'] >= 0.30: pick = ('90', ta)
    elif tb and tb['npos'] >= 15 and tb['cvP'] >= 0.78 and tb['cvR'] >= 0.35: pick = ('80', tb)
    elif tb and tb['npos'] >= 15 and tb['cvP'] >= 0.88 and tb['cvR'] >= 0.25: pick = ('80', tb)
    elif ta and ta['npos'] >= 15 and ta['cvP'] >= 0.88 and ta['cvR'] >= 0.25: pick = ('90', ta)
    if pick:
        out[k] = pick[1]; rows.append((k, pick[0], pick[1]))
    else:
        print('dropped', k, '' if not ta else f"(90: {ta['cvP']:.2f}/{ta['cvR']:.2f})", '' if not tb else f"(80: {tb['cvP']:.2f}/{tb['cvR']:.2f})")
json.dump(out, open(os.path.join(G, 'out/thresholds_final.json'), 'w'), indent=1)
with open(os.path.join(G, 'out/calibration_table.md'), 'w') as f:
    f.write('| Category | Target | Precision | Recall | Verified positives / negatives |\n|---|---|---|---|---|\n')
    for k, tgt, t in rows:
        if tgt == 'near': f.write(f"| {k} | 85% (see note) | {t['cvP']:.2f}* | {t['R']:.2f} | {t['npos']} / {t['nneg']} |\n")
        else: f.write(f"| {k} | {tgt}% | {t['cvP']:.2f} | {t['cvR']:.2f} | {t['npos']} / {t['nneg']} |\n")
for k, tgt, t in rows:
    if tgt == 'near': print(k, 'probe', t['t'][1], f"strict P {t['P']:.2f}; fires on {t['fired']} photos, {t['cvP']:.2f} of them verified look-alike scenes; R {t['R']:.2f}")
print(len(out), 'kept')
