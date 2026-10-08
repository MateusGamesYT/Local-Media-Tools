"""Pick Open Images validation images: per label, up to N verified positives and N verified negatives, plus random images."""
import csv, random, collections, sys, os
D = sys.argv[1]; N = int(sys.argv[2]); out = sys.argv[3]
names = {r[0]: r[1] for r in csv.reader(open(os.path.join(D, 'oidv6-class-descriptions.csv')))}
want = [l.strip() for l in open(sys.argv[4]) if l.strip()]
inv = collections.defaultdict(list)
for k, v in names.items(): inv[v].append(k)
mids = {m for w in want for m in inv[w]}
pos, neg, allimg = collections.defaultdict(list), collections.defaultdict(list), set()
for r in csv.DictReader(open(os.path.join(D, 'validation-annotations-human-imagelabels.csv'))):
    allimg.add(r['ImageID'])
    if r['LabelName'] in mids: (pos if r['Confidence'] == '1' else neg)[r['LabelName']].append(r['ImageID'])
random.seed(7); pick = set()
for m in mids:
    for L in (pos[m], neg[m]):
        L = sorted(set(L)); random.shuffle(L); pick.update(L[:N])
rest = sorted(allimg - pick); random.shuffle(rest); pick.update(rest[:1500])
open(out, 'w').write('\n'.join(sorted(pick)))
print(len(mids), 'labels', len(pick), 'images')
