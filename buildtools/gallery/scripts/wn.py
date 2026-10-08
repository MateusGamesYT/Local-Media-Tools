"""Minimal WordNet noun hierarchy reader (data.noun) for ImageNet synsets."""
import os, json
D = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), 'dl/wn')
class WN:
    def __init__(self):
        self.words, self.hyper, self.gloss = {}, {}, {}
        for line in open(os.path.join(D, 'wordnet/data.noun'), encoding='latin-1'):
            if line.startswith('  '): continue
            head, _, gloss = line.partition('|')
            t = head.split()
            off = t[0]; wc = int(t[3], 16)
            ws = [t[4 + 2*i].replace('_', ' ') for i in range(wc)]
            p = 4 + 2*wc; pc = int(t[p]); p += 1
            hy = []
            for i in range(pc):
                sym, o, pos = t[p], t[p+1], t[p+2]; p += 4
                if sym in ('@', '@i') and pos == 'n': hy.append('n' + o)
            wid = 'n' + off
            self.words[wid] = ws; self.hyper[wid] = hy; self.gloss[wid] = gloss.strip()
    def ancestors(self, wid):
        seen, st = set(), [wid]
        while st:
            x = st.pop()
            for h in self.hyper.get(x, []):
                if h not in seen: seen.add(h); st.append(h)
        return seen
    def find(self, word):
        return [w for w, ws in self.words.items() if word in ws]
def imagenet():
    ci = json.load(open(os.path.join(D, 'class_index.json')))
    return [ci[str(i)] for i in range(1000)]   # [wnid, name]
if __name__ == '__main__':
    wn = WN(); cls = imagenet()
    print(len(wn.words), cls[:3])
    print(wn.find('dog')[:3], [wn.words[a][0] for a in wn.ancestors(cls[207][0])])
