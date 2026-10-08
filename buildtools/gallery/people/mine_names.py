import csv, gzip, re, collections, sys, json
pat = re.compile(r"\b([A-Z][a-z]+(?:[ -][A-Z]\.)?(?: [A-Z][a-z]+(?:-[A-Z][a-z]+)?){1,2})\b")
imgs = collections.defaultdict(set); authors = collections.defaultdict(set)
lic = collections.Counter()
with gzip.open(sys.argv[1], 'rt', newline='') as f:
    r = csv.reader(f); next(r)
    for row in r:
        iid, sub, land, license, author, title, rot = row
        lic[license] += 1
        if not title: continue
        for m in set(pat.findall(title)):
            imgs[m].add(iid); authors[m].add(author)
print(lic.most_common(5))
rows = [(n, len(imgs[n]), len(authors[n])) for n in imgs if len(imgs[n]) >= 25 and len(authors[n]) >= 3]
rows.sort(key=lambda x: -x[1])
json.dump(rows, open(sys.argv[2], 'w'))
print(len(rows))
for n, c, a in rows[:600]: print(f'{c:6d} {a:4d}  {n}')
