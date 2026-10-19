"""The words Tika finds in a file that the app's reader doesn't show (and the other way round).

  python3 missing.py <list> <harness text dir> <tika text dir> <corpus root> <file>
"""
import collections, os, re, sys

W = re.compile(r"\w+", re.UNICODE)
lst, ours, tika, root, f = sys.argv[1:6]
files = [l.rstrip("\n") for l in open(lst) if l.strip()]
i = files.index(f)
o = open(os.path.join(ours, f"{i}.txt"), encoding="utf-8", errors="replace").read()
t = open(os.path.join(tika, os.path.relpath(f, root) + ".txt"), encoding="utf-8", errors="replace").read()
ow = collections.Counter(w.lower() for w in W.findall(o)); tw = collections.Counter(w.lower() for w in W.findall(t))
print("ours", sum(ow.values()), "tika", sum(tw.values()))
print("missing:", " ".join(f"{w}×{n}" for w, n in (tw - ow).most_common(40)))
print("extra:", " ".join(f"{w}×{n}" for w, n in (ow - tw).most_common(25)))
print("--- ours (start)\n" + o[:600])
print("--- tika (start)\n" + t[:900])
