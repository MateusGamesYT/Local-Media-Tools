"""Stream Open Images image metadata (stdin CSV) and keep the fields needed for a people test set."""
import csv, sys, gzip
r = csv.reader(sys.stdin)
head = next(r)
ix = [head.index(c) for c in ('ImageID', 'Subset', 'OriginalLandingURL', 'License', 'Author', 'Title', 'Rotation')]
with gzip.open(sys.argv[1], 'wt', newline='') as f:
    w = csv.writer(f)
    w.writerow(['ImageID', 'Subset', 'Landing', 'License', 'Author', 'Title', 'Rotation'])
    n = 0
    for row in r:
        if len(row) < len(head): continue
        w.writerow([row[i] for i in ix]); n += 1
print('rows', n)
