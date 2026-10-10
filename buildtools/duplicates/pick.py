"""Chooses the duplicate-finder test photos from Open Images (all CC BY 2.0, from Flickr).

Runs: photos one photographer took one after another, found by camera file names in the titles
(IMG_4796, IMG_4797…; numbers at most 2 apart). Some are bursts of the same moment, many are
different shots of the same outing — exactly the photos a camera roll has side by side. At most one
run per photographer, 2–6 photos each.
Pool: unrelated photos (one per photographer, none of the runs' photographers), for random pairs and
for re-saved copies.

    python pick.py runs.json [n_runs] [n_pool]
"""
import csv, gzip, json, os, random, re, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import META

CAMERA = re.compile(r'^(IMG|DSC|DSCN|DSCF|_MG|_DSC|PICT|SAM|DCP|CIMG|P[0-9A-C]?)[_ -]?0*(\d{3,6})$', re.I)


def main():
    out = sys.argv[1]
    n_runs = int(sys.argv[2]) if len(sys.argv) > 2 else 260
    n_pool = int(sys.argv[3]) if len(sys.argv) > 3 else 1500
    by_user = collections.defaultdict(list)
    others = collections.defaultdict(list)
    for r in csv.DictReader(gzip.open(META, 'rt')):
        m = re.match(r'https://www.flickr.com/photos/([^/]+)/(\d+)', r['Landing'])
        if not m:
            continue
        row = {'id': r['ImageID'], 'subset': r['Subset'], 'rot': r['Rotation'], 'author': r['Author'],
               'title': r['Title'], 'url': r['Landing'], 'license': r['License']}
        t = re.sub(r'\.(jpe?g)$', '', (r['Title'] or '').strip(), flags=re.I)
        c = CAMERA.match(t)
        if c:
            by_user[m.group(1)].append((c.group(1).upper(), int(c.group(2)), row))
        else:
            others[m.group(1)].append(row)
    rng = random.Random(7)
    runs = []
    for user, items in by_user.items():
        items.sort(key=lambda x: (x[0], x[1]))
        cur = [items[0]]
        found = []
        for a in items[1:]:
            if a[0] == cur[-1][0] and 1 <= a[1] - cur[-1][1] <= 2:
                cur.append(a)
            else:
                if len(cur) >= 2: found.append(cur)
                cur = [a]
        if len(cur) >= 2: found.append(cur)
        if found:
            run = rng.choice(found)
            if len(run) > 6:
                s = rng.randrange(len(run) - 5)
                run = run[s:s + 6]
            runs.append({'user': user, 'photos': [x[2] for x in run]})
    rng.shuffle(runs)
    runs = runs[:n_runs]
    used = {r['user'] for r in runs}
    pool_users = [u for u in others if u not in used and u not in by_user]
    rng.shuffle(pool_users)
    pool = [rng.choice(others[u]) for u in pool_users[:n_pool]]
    json.dump({'runs': runs, 'pool': pool}, open(out, 'w'), indent=0)
    print(len(runs), 'runs,', sum(len(r['photos']) for r in runs), 'photos;', len(pool), 'pool photos')


main()
