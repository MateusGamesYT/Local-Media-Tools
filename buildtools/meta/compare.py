"""Compares the app's metadata readers with ExifTool on the same files.

  python3 compare.py <harness .jsonl> <exiftool .json> [--phone] [--group G] [--missing N] [--wrong N] [--files N]

ExifTool's output is `exiftool -j -a -G1 -n` (every tag, grouped, values unconverted). For each tag
ExifTool reports, the app's fields are searched for the same name (in any group) and then for the
same value (numbers within 0.01 %, text after trimming; lists joined by ", "). Reported per group:
tags, share of names found, share of values equal. The camera makers' own groups (maker notes) and
ExifTool's derived values (Composite) are listed apart: the app reads neither on purpose.

--phone keeps only the kinds of files people have on a phone (photos and camera RAW, videos, music
and recordings, documents, archives); --files N lists the N files with the most tags not found.
"""
import collections, json, re, sys

SKIP_GROUPS = {"ExifTool", "System"}
SKIP_FILE = {"FileName", "Directory", "FileSize", "FileModifyDate", "FileAccessDate", "FileInodeChangeDate", "FilePermissions",
             "FileTypeExtension", "ExifByteOrder", "CurrentIPTCDigest", "Error", "Warning"}
MAKERS = {"Canon", "CanonCustom", "CanonRaw", "CanonVRD", "Nikon", "NikonCustom", "NikonScan", "NikonCapture", "NikonPreview", "Sony", "SonyIDC",
          "Olympus", "Panasonic", "PanasonicRaw", "FujiFilm", "Pentax", "Samsung", "Apple", "Casio", "Kodak", "Minolta", "Ricoh", "Sanyo",
          "Sigma", "JVC", "GE", "Motorola", "Leica", "Reconyx", "DJI", "FLIR", "GoPro", "Microsoft", "Google", "Qualcomm", "HP", "Kyocera",
          "Nintendo", "Phase One", "PhaseOne", "Red", "Lytro", "InfiRay", "FotoStation", "PhotoMechanic", "CasioQVCI", "MinoltaRaw",
          "SigmaRaw", "KodakIFD", "KodakBordersIFD", "PentaxJpeg", "OlympusEquipment", "OlympusCameraSettings", "OlympusImageProcessing",
          "OlympusFocusInfo", "OlympusRawDevelopment", "Olympus2", "NikonSettings", "Unknown", "MakerNotes"}


def norm(v):
    if isinstance(v, list):
        return ", ".join(norm(x) for x in v)
    if isinstance(v, bool):
        return "1" if v else "0"
    return str(v).strip()


def num(s):
    try:
        return float(s)
    except (ValueError, TypeError):
        return None


def same(a, b):
    a, b = norm(a), norm(b)
    if a == b:
        return True
    x, y = num(a), num(b)
    if x is not None and y is not None:
        return abs(x - y) <= max(1e-4 * max(abs(x), abs(y)), 1e-6)
    # Lists of numbers ("1 2 3" vs "1, 2, 3"), case and spaces.
    sa = re.split(r"[\s,]+", a.lower()); sb = re.split(r"[\s,]+", b.lower())
    if len(sa) == len(sb) and len(sa) > 1 and all((num(p) is not None and num(q) is not None and abs(num(p) - num(q)) <= 1e-4 * max(abs(num(p)), abs(num(q)), 1e-6)) or p == q for p, q in zip(sa, sb)):
        return True
    return a.lower().replace(" ", "") == b.lower().replace(" ", "")


PHONE = set("jpg jpeg png gif webp bmp psd tif tiff dng cr2 cr3 nef nrw arw orf rw2 raf pef srw heic heif avif mp4 mov m4a m4v 3gp mkv webm "
              "avi wav mp3 flac ogg opus pdf docx docm xlsx pptx doc xls ppt odt ods odp epub rtf html htm txt csv zip svg".split())


def main():
    ours_path, et_path = sys.argv[1], sys.argv[2]
    only = sys.argv[sys.argv.index("--group") + 1] if "--group" in sys.argv else None
    show_missing = int(sys.argv[sys.argv.index("--missing") + 1]) if "--missing" in sys.argv else 0
    show_wrong = int(sys.argv[sys.argv.index("--wrong") + 1]) if "--wrong" in sys.argv else 0
    show_files = int(sys.argv[sys.argv.index("--files") + 1]) if "--files" in sys.argv else 0
    one = sys.argv[sys.argv.index("--file") + 1] if "--file" in sys.argv else None
    phone = "--phone" in sys.argv
    per_file = collections.Counter()
    ours = {}
    errors = []
    for line in open(ours_path, encoding="utf-8"):
        d = json.loads(line)
        if "error" in d:
            errors.append((d["file"], d["error"]))
            continue
        ours[d["file"]] = d
    et = {d["SourceFile"]: d for d in json.load(open(et_path, encoding="utf-8"))}
    stats = collections.defaultdict(lambda: [0, 0, 0])  # tags, names found, values equal
    missing = collections.defaultdict(collections.Counter)
    wrong = collections.defaultdict(list)
    for path, e in list(et.items()):
        o = ours.get(path)
        if phone and path.rsplit(".", 1)[-1].lower() not in PHONE:
            del et[path]
            continue
        if o is None or one and path.split("/")[-1] != one:
            continue
        by_name = collections.defaultdict(list)
        for g, n, v, r, p in o["fields"]:
            by_name[n].append(r)
            by_name[n].append(v)
        for key, val in e.items():
            if key == "SourceFile" or ":" not in key:
                continue
            g, n = key.split(":", 1)
            if g in SKIP_GROUPS or (g == "File" and n in SKIP_FILE):
                continue
            if only and g != only:
                continue
            if isinstance(val, str) and val.startswith("(Binary data") or norm(val) == "":
                continue
            cat = "MakerNotes" if g in MAKERS else g
            s = stats[cat]
            s[0] += 1
            vals = by_name.get(n)
            if one and cat != "MakerNotes":
                print(("  " if vals and any(same(val, x) for x in vals) else "W " if vals else "M ") + f"{g}:{n} = {norm(val)[:100]!r}" + (f"  app={vals[0][:100]!r}" if vals and not any(same(val, x) for x in vals) else ""))
            if not vals:
                missing[cat][n] += 1
                if cat not in ("MakerNotes", "Composite"):
                    per_file[path.split("/")[-1]] += 1
                continue
            s[1] += 1
            if any(same(val, x) for x in vals):
                s[2] += 1
            else:
                wrong[cat].append((path.split("/")[-1], n, norm(val)[:80], norm(vals[0])[:80]))
    rows = sorted(stats.items(), key=lambda kv: -kv[1][0])
    core = [kv for kv in rows if kv[0] not in ("MakerNotes", "Composite")]
    tot = [sum(s[i] for _, s in core) for i in range(3)]
    if one:
        print("app fields: " + "; ".join(f"{g}:{n}={v[:60]}" for g, n, v, r, p in ours[[k for k in ours if k.endswith("/" + one)][0]]["fields"]))
        return
    print(f"files: {len(et)} (app read {len(ours)}, failed {len(errors)})")
    print(f"all groups except maker notes and Composite: {tot[0]} tags, names {100 * tot[1] / max(1, tot[0]):.1f} %, values {100 * tot[2] / max(1, tot[0]):.1f} %")
    print(f"{'group':24} {'tags':>6} {'names':>7} {'values':>7}")
    for g, s in rows:
        print(f"{g:24} {s[0]:6} {100 * s[1] / s[0]:6.1f}% {100 * s[2] / s[0]:6.1f}%")
    for f, err in errors:
        print("ERROR", f, err[:300])
    if show_files:
        print("files with the most tags not found: " + ", ".join(f"{f} {k}" for f, k in per_file.most_common(show_files)))
    if show_missing:
        for g, c in sorted(missing.items(), key=lambda kv: -sum(kv[1].values())):
            if g == "MakerNotes":
                continue
            print(f"missing in {g}: " + ", ".join(f"{n}×{k}" for n, k in c.most_common(show_missing)))
    if show_wrong:
        for g, l in wrong.items():
            if g == "MakerNotes":
                continue
            for w in l[:show_wrong]:
                print(f"wrong {g}: {w[0]} {w[1]}: exiftool={w[2]!r} app={w[3]!r}")


main()
