"""Collect Open Images photos whose title names a person (excluding statues, posters, costumes...)."""
import csv, gzip, re, json, sys, collections
names = [l.strip() for l in open(sys.argv[2]) if l.strip() and not l.startswith('#')]
bad = re.compile(r"statue|poster|mural|painting|paint|wax|tussaud|mask|costume|cosplay|graffiti|tribute|memorial|cutout|cut-out|doll|billboard|tattoo|impersonat|cover|book|magazine|figure|figurine|toy|lego|cake|sculpt|bust\b|print|stencil|drawing|sketch|caricature|cartoon|banner|sign\b|t-shirt|shirt|mug|button|sticker|art\b|artwork|exhibit|museum|look-?alike|tv\b|screen|television|projection|newspaper|lookalike|replica|effigy|puppet|grave|tomb|house|home of|birthplace|street|avenue|bridge|school|library|airport|station|plaque|stamp|coin|bill\b|tribute|fan\b|fans\b", re.I)
want = {n: re.compile(r"\b" + re.escape(n) + r"\b") for n in names}
out = collections.defaultdict(list)
with gzip.open(sys.argv[1], 'rt', newline='') as f:
    r = csv.reader(f); next(r)
    for iid, sub, land, lic, author, title, rot in r:
        if not title or bad.search(title): continue
        for n, p in want.items():
            if p.search(title): out[n].append(dict(id=iid, subset=sub, landing=land, author=author, title=title, rot=rot))
json.dump(out, open(sys.argv[3], 'w'))
for n in names:
    rows = out.get(n, [])
    print(f"{n}: {len(rows)} photos, {len(set(r['author'] for r in rows))} authors")
