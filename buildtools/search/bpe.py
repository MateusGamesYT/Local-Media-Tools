"""SigLIP 2's tokenizer (Gemma's byte-fallback BPE), written the way the app's Kotlin code does it,
so the two can be compared token for token, and with a reduced vocabulary.

Text: spaces become "▁"; the whole text is split into characters (a character the vocabulary lacks
becomes its UTF-8 bytes, <0xXX>); then the adjacent pair whose merge comes first in the merge list is
merged, again and again, until no pair can merge. Then <eos>, and padding to 64 with <pad>.
"""
import heapq, json

class BPE:
    def __init__(self, tokenizer_json, keep=None):
        t = json.load(open(tokenizer_json))
        m = t['model']
        vocab = m['vocab']
        if keep is not None: vocab = {k: v for k, v in vocab.items() if v in keep}
        self.vocab = vocab
        self.ranks = {}
        for r, mg in enumerate(m['merges']):
            a, b = mg if isinstance(mg, list) else mg.split(' ')
            if a in vocab and b in vocab and (a + b) in vocab and (a, b) not in self.ranks: self.ranks[(a, b)] = r
        self.pad, self.eos = vocab['<pad>'], vocab['<eos>']

    def pieces(self, text):
        s = text.replace(' ', '▁')
        sym = []
        for ch in s:
            if ch in self.vocab: sym.append(ch)
            else: sym.extend(f'<0x{b:02X}>' for b in ch.encode('utf-8'))
        while len(sym) > 1:
            best = None
            for i in range(len(sym) - 1):
                r = self.ranks.get((sym[i], sym[i + 1]))
                if r is not None and (best is None or r < best[0]): best = (r, i)
            if best is None: break
            i = best[1]; sym[i:i + 2] = [sym[i] + sym[i + 1]]
        return sym

    def ids(self, text, length=64):
        p = [self.vocab[s] for s in self.pieces(text)][:length - 1] + [self.eos]
        return p + [self.pad] * (length - len(p))

    def trace(self, text):
        """Every token the merges pass through for this text (to keep a reduced vocabulary complete)."""
        s = text.replace(' ', '▁')
        sym = []
        for ch in s:
            if ch in self.vocab: sym.append(ch)
            else: sym.extend(f'<0x{b:02X}>' for b in ch.encode('utf-8'))
        seen = set(sym)
        while len(sym) > 1:
            best = None
            for i in range(len(sym) - 1):
                r = self.ranks.get((sym[i], sym[i + 1]))
                if r is not None and (best is None or r < best[0]): best = (r, i)
            if best is None: break
            i = best[1]; sym[i:i + 2] = [sym[i] + sym[i + 1]]; seen.add(sym[i])
        return seen
