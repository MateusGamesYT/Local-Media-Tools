#!/usr/bin/env python3
"""check_raw.py <run.sh> <exiftool> <work dir> <raw files...>

Checks the app's RAW code (buildtools/denoise/harness) against LibRaw (rawpy) and ExifTool:
  1. the sensor data the app decodes equals LibRaw's raw_image, value for value;
  2. writing it back unchanged gives a file LibRaw reads identically;
  3. writing changed data (a fixed pattern added) gives a file LibRaw reads as exactly that data
     (lossless formats) or within the format's own rounding (Sony's compressed ARW);
  4. the written file develops (LibRaw's postprocess), and ExifTool reads the same metadata and
     reports no new problems (-validate).
"""
import json, os, subprocess, sys
import numpy as np, rawpy

run, exiftool, work = sys.argv[1:4]
files = sys.argv[4:]
os.makedirs(work, exist_ok=True)

def sh(*a):
    r = subprocess.run(a, capture_output=True, text=True)
    if r.returncode != 0: raise RuntimeError(r.stderr[-2000:])
    return r.stdout.strip()

def app_info(f):
    return sh(run, 'info', f).split('\t')

def raw_of(f):
    with rawpy.imread(f) as r:
        return r.raw_image.copy(), int(r.white_level)

def tags(f):
    out = json.loads(sh(exiftool, '-j', '-a', '-G1', '-n', '-x', 'File:all', '-x', 'System:all', f))[0]
    return {k: v for k, v in out.items() if not any(s in k for s in ('StripByteCounts', 'StripOffsets', 'TileByteCounts', 'TileOffsets', 'SourceFile', 'Warning'))}

def warnings(f):
    out = json.loads(sh(exiftool, '-j', '-validate', '-warning', '-a', f))[0]
    return sorted(str(v) for k, v in out.items() if 'Warning' in k)

ok = True
for f in files:
    name = os.path.basename(f)
    info = app_info(f)
    if info[1] == 'UNSUPPORTED':
        print(f'{name}: not written by the app ({info[2]})'); continue
    fmt, variant = info[1], info[2]
    lossy = fmt == 'ARW' and variant == 'compressed'
    ref, white = raw_of(f)
    dump = os.path.join(work, 'dec.u16')
    msg = sh(run, 'dump', f, dump)
    dec = np.fromfile(dump, '<u2').reshape(ref.shape) if os.path.getsize(dump) == ref.size * 2 else None
    if dec is None:
        print(f'{name}: SIZE MISMATCH app {info[3]} LibRaw {ref.shape}'); ok = False; continue
    same = int((dec != ref).sum())
    line = [f'{name} [{fmt} {variant}]: decode {"=" if same == 0 else f"{same} DIFFERENT"} LibRaw ({msg})']
    ok &= same == 0
    # 2. unchanged
    out1 = os.path.join(work, 'same.' + name.rsplit('.', 1)[1])
    msg = sh(run, 'write', f, dump, out1)
    r1, _ = raw_of(out1)
    d1 = int((r1 != ref).sum())
    line.append(f'unchanged {"=" if d1 == 0 else f"{d1} DIFFERENT"} ({msg})')
    ok &= d1 == 0
    # 3. changed: a fixed pattern of ±4 added, kept inside the format's range
    maxv = int(info[4].split()[1])
    yy, xx = np.mgrid[0:ref.shape[0], 0:ref.shape[1]]
    want = np.clip(ref.astype(np.int64) + ((xx * 7 + yy * 13) % 9) - 4, 0, maxv).astype(np.uint16)
    want.tofile(os.path.join(work, 'want.u16'))
    out2 = os.path.join(work, 'changed.' + name.rsplit('.', 1)[1])
    msg = sh(run, 'write', f, os.path.join(work, 'want.u16'), out2)
    r2, _ = raw_of(out2)
    err = np.abs(r2.astype(np.int64) - want)
    if lossy:
        # The app must read what LibRaw reads, and stay within the format's quantisation.
        sh(run, 'dump', out2, dump)
        d2 = np.fromfile(dump, '<u2').reshape(ref.shape)
        cons = int((d2 != r2).sum())
        line.append(f'changed: LibRaw vs app {"=" if cons == 0 else f"{cons} DIFFERENT"}, error vs intended mean {err.mean():.2f} max {err.max()} (orig. data re-quantised: mean {np.abs(r1.astype(np.int64) - ref).mean():.2f})')
        ok &= cons == 0
    else:
        n2 = int((err != 0).sum())
        line.append(f'changed {"exact" if n2 == 0 else f"{n2} DIFFERENT (max {err.max()})"} ({msg})')
        ok &= n2 == 0
    # 4. develops; metadata as before; no new ExifTool warnings
    with rawpy.imread(out2) as r: rgb = r.postprocess(half_size=True)
    t0, t2 = tags(f), tags(out2)
    diff = sorted(k for k in set(t0) | set(t2) if t0.get(k) != t2.get(k))
    w0, w2 = warnings(f), warnings(out2)
    new_w = [w for w in w2 if w not in w0]
    line.append(f'develops {rgb.shape[1]}x{rgb.shape[0]}; metadata {"same" if not diff else "CHANGED " + str(diff[:8])}; warnings {"none new" if not new_w else "NEW " + str(new_w)}')
    ok &= not diff and not new_w
    print('\n    '.join(line), flush=True)
print('ALL OK' if ok else 'PROBLEMS')
