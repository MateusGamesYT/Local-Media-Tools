import sys, os, csv
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wn import WN
from taxonomy import CATS
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
wn = WN(); syn = [l.strip() for l in open(os.path.join(G, '../dl/in21k/imagenet21k_goog_synsets.txt'))]; S21 = {s: i for i, s in enumerate(syn)}
kids = {}
for w, hs in wn.hyper.items():
    for h in hs: kids.setdefault(h, []).append(w)
def sub(w):
    out, st = set(), [w]
    while st:
        x = st.pop()
        if x in out: continue
        out.add(x); st.extend(kids.get(x, []))
    return out
coco = [l.strip() for l in open(os.path.join(G, 'meta/labels.txt'))]
oin = {r[1] for r in csv.reader(open(os.path.join(G, '../dl/oi/oidv6-class-descriptions.csv')))}
person = sub('n00007846')
def classes(c):
    s = set()
    for w in c['wn']: s |= sub(w)
    for w in c['wn_not']: s -= sub(w)
    return sorted(S21[x] for x in s if x in S21 and x not in person)
if __name__ == '__main__':
    for c in CATS:
        bad = [d for d in c['det'] if d not in coco] + [w for w in c['wn'] + c['wn_not'] if w not in wn.words] + [o for o in c['oi'] if o not in oin]
        cl = classes(c)
        print(f"{c['key']:11s} 21k={len(cl):4d} {'BAD ' + str(bad) if bad else ''} :: " + '; '.join(wn.words[w][0] for w in c['wn'] if w in wn.words))
