"""Cell-by-cell check of the spreadsheet readers against LibreOffice (cells as shown, US English).

  python3 cells.py <work dir> [--show N] [--file SUBSTRING]

Reads csvref.index (from lo_refs.sh csv), the harness results run2.tsv and the harness's
<n>.cells files in out2, and compares every non-empty cell LibreOffice shows with the text the app
shows in the same sheet, row and column. Reported per format: cells compared, share identical,
share identical after ignoring case and spaces, and the most common kinds of difference with
examples, so number and date formatting can be checked on thousands of real cells.
"""
import collections, csv, os, re, sys

csv.field_size_limit(1 << 30)


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def kind(lo, ours):
    if ours is None:
        return "missing"
    if re.fullmatch(r"-?[\d,]*\.?\d+(E[+-]?\d+)?%?", lo.replace(" ", "")):
        return "number"
    if re.search(r"\d+[/-]\d+[/-]\d+|\d+:\d+", lo):
        return "date/time"
    if re.search(r"[$€£¥]", lo):
        return "currency"
    return "text"


def main():
    w = sys.argv[1]
    show = int(sys.argv[sys.argv.index("--show") + 1]) if "--show" in sys.argv else 8
    only = sys.argv[sys.argv.index("--file") + 1] if "--file" in sys.argv else None
    results = {}
    for line in open(os.path.join(w, "run2.tsv"), encoding="utf-8", errors="replace"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3:
            results[p[0]] = p[1]
    lst = [l.rstrip("\n") for l in open(os.path.join(w, "all.lst"))]
    index = {f: i for i, f in enumerate(lst)}
    total = collections.Counter(); same = collections.Counter(); loose = collections.Counter()
    diffs = collections.defaultdict(list); kinds = collections.Counter(); per_file = []
    for line in open(os.path.join(w, "csvref.index"), encoding="utf-8"):
        n, path = line.rstrip("\n").split("\t", 1)
        if only and only not in path:
            continue
        fmt = results.get(path, "?")
        d = os.path.join(w, "csvref", n)
        if not os.path.isdir(d) or path not in index:
            continue
        cells = os.path.join(w, "out2", f"{index[path]}.cells")
        ours = collections.defaultdict(dict)
        names = []
        if os.path.exists(cells):
            for l in open(cells, encoding="utf-8", errors="replace"):
                p = l.rstrip("\n").split("\t", 4)
                if len(p) == 5:
                    if p[1] not in names:
                        names.append(p[1])
                    ours[p[1]][(int(p[2]), int(p[3]))] = p[4]
        base = os.path.splitext(os.path.basename(path))[0]
        csvs = sorted(os.listdir(d))
        ft = fs = 0
        for c in csvs:
            sheet = c[len(base) + 1:-4] if c.startswith(base + "-") else c[:-4]
            mine = ours.get(sheet)
            if mine is None:
                # File names can't hold every character a sheet name can.
                cand = [s for s in ours if re.sub(r"[^\w]", "", s) == re.sub(r"[^\w]", "", sheet)]
                mine = ours.get(cand[0]) if cand else {}
            with open(os.path.join(d, c), encoding="utf-8", errors="replace", newline="") as fh:
                for r, row in enumerate(csv.reader(fh)):
                    for col, lo in enumerate(row):
                        if not lo.strip():
                            continue
                        o = mine.get((r, col))
                        total[fmt] += 1; ft += 1
                        if o == lo or (o is not None and o.strip() == lo.strip()):
                            same[fmt] += 1; loose[fmt] += 1; fs += 1
                            continue
                        if o is not None and norm(o) == norm(lo):
                            loose[fmt] += 1
                            continue
                        k = kind(lo, o)
                        kinds[(fmt, k)] += 1
                        if len(diffs[(fmt, k)]) < 400:
                            diffs[(fmt, k)].append((path, sheet, r, col, lo, o))
        if ft:
            per_file.append((fs / ft, ft, path))
    print(f"{'format':6} {'cells':>9} {'identical':>10} {'loosely':>9}")
    for f in sorted(total):
        print(f"{f:6} {total[f]:9} {same[f] / total[f]:10.4f} {loose[f] / total[f]:9.4f}")
    print("\nDifferences by kind:")
    for (f, k), v in sorted(kinds.items(), key=lambda x: -x[1]):
        print(f"  {f:5} {k:10} {v:7}")
    for (f, k), v in sorted(kinds.items(), key=lambda x: -x[1]):
        print(f"\n== {f} {k} ({v})")
        seen = collections.Counter()
        for path, sheet, r, col, lo, o in diffs[(f, k)]:
            if seen[path] >= 2:
                continue
            seen[path] += 1
            print(f"  {os.path.basename(path)[:40]:40} {sheet[:14]:14} r{r} c{col}  LO={lo[:50]!r}  app={(o or '')[:50]!r}")
            if sum(seen.values()) >= show:
                break
    per_file.sort()
    print("\nWorst files:")
    for s, n, p in per_file[:show]:
        print(f"  {s:.3f} {n:7} {p}")


main()
