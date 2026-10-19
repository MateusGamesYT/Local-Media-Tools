"""Compares the app's readers with Apache Tika (Apache POI inside) on the same files.

  python3 compare.py <list of files> <harness results .tsv> <harness text dir> <tika text dir> <corpus root> [--worst N]

With --alpha only words of three or more letters count (no numbers).

For each file both read, the words of each text (lower case, letters and digits) are compared as
multisets: recall = share of Tika's words the app also shows, precision = share of the app's words
Tika also has. Reported per format: files, how many the app opened, how many Tika opened, mean and
median recall and precision, and the share of files with recall >= 0.9 / 0.99.
"""
import collections, os, re, statistics, sys

WORD = re.compile(r"\w+", re.UNICODE)
ALPHA = "--alpha" in sys.argv


def words(t):
    ws = (w.lower() for w in WORD.findall(t))
    if ALPHA:
        # Words only (3+ letters): numbers from embedded charts and data aren't what a reader shows.
        ws = (w for w in ws if len(w) >= 3 and not any(c.isdigit() for c in w))
    return collections.Counter(ws)


def main():
    lst, tsv, ours, tika, root = sys.argv[1:6]
    worst = int(sys.argv[sys.argv.index("--worst") + 1]) if "--worst" in sys.argv else 0
    files = [l.rstrip("\n") for l in open(lst) if l.strip()]
    index = {f: i for i, f in enumerate(files)}
    by = collections.defaultdict(list)
    opened = collections.Counter(); tika_ok = collections.Counter(); total = collections.Counter()
    bad = collections.defaultdict(list)
    for line in open(tsv, encoding="utf-8", errors="replace"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 3:
            continue
        path, fmt, status = p[0], p[1], p[2]
        if fmt in ("?", "none", "PDF"):
            continue
        total[fmt] += 1
        rel = os.path.relpath(path, root)
        tp = os.path.join(tika, rel + ".txt")
        t = open(tp, encoding="utf-8", errors="replace").read() if os.path.exists(tp) else None
        tw = words(t) if t else collections.Counter()
        if sum(tw.values()) >= 5:
            tika_ok[fmt] += 1
        if status != "ok":
            continue
        opened[fmt] += 1
        if sum(tw.values()) < 5:
            continue
        ow = words(open(os.path.join(ours, f"{index[path]}.txt"), encoding="utf-8", errors="replace").read())
        common = sum((ow & tw).values())
        rec = common / sum(tw.values())
        prec = common / sum(ow.values()) if ow else 0.0
        by[fmt].append((rec, prec, path))
        bad[fmt].append((rec, prec, path))
    print(f"{'format':9} {'files':>5} {'opened':>6} {'tika':>5} {'compared':>8} {'recall mean/median':>19} {'precision mean/median':>22} {'rec>=.9':>8} {'rec>=.99':>8}")
    for fmt in sorted(total):
        r = by.get(fmt, [])
        if r:
            rec = [x[0] for x in r]; pr = [x[1] for x in r]
            print(f"{fmt:9} {total[fmt]:5} {opened[fmt]:6} {tika_ok[fmt]:5} {len(r):8} {statistics.mean(rec):9.3f} / {statistics.median(rec):.3f} {statistics.mean(pr):12.3f} / {statistics.median(pr):.3f} {sum(1 for x in rec if x >= 0.9) / len(r):8.2f} {sum(1 for x in rec if x >= 0.99) / len(r):8.2f}")
        else:
            print(f"{fmt:9} {total[fmt]:5} {opened[fmt]:6} {tika_ok[fmt]:5} {0:8}")
    if worst:
        for fmt in sorted(bad):
            print(f"\n-- {fmt}: lowest recall")
            for rec, prec, path in sorted(bad[fmt])[:worst]:
                print(f"  {rec:.3f} {prec:.3f} {path}")


if __name__ == "__main__":
    main()
