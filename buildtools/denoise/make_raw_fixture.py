#!/usr/bin/env python3
"""make_raw_fixture.py <source.dng> <out.dng> <x> <y> <w> <h>

Cuts a small, valid DNG out of a real one for the app's tests (app/src/test/resources/raw):
the sensor-data IFD with its colour and level tags, and only the part of the sensor data
inside the rectangle. Tiled lossless-JPEG files keep their original compressed tiles byte for
byte (x, y, w, h must be on tile boundaries), so the test decodes data written by the camera's
own encoder; uncompressed files keep their original samples (x and y even, so the colour filter
pattern stays in phase). Opcode lists, previews, maker notes and private data are left out (they
describe the whole picture).

Check the result with rawpy (LibRaw) before adding it: it must open and develop.
"""
import struct, sys

src, out = sys.argv[1:3]
X, Y, W, H = map(int, sys.argv[3:7])
data = open(src, 'rb').read()
le = data[:2] == b'II'
E = '<' if le else '>'
SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4, 16: 8}

def ifd(off):
    n = struct.unpack_from(E + 'H', data, off)[0]
    tags = {}
    for i in range(n):
        tag, typ, cnt = struct.unpack_from(E + 'HHI', data, off + 2 + 12 * i)
        size = SIZES[typ] * cnt
        voff = off + 2 + 12 * i + 8
        if size > 4: voff = struct.unpack_from(E + 'I', data, voff)[0]
        tags[tag] = (typ, cnt, data[voff:voff + size])
    return tags, struct.unpack_from(E + 'I', data, off + 2 + 12 * n)[0]

def ints(t):
    typ, cnt, raw = t
    f = {3: 'H', 4: 'I', 16: 'Q'}[typ]
    return list(struct.unpack(E + f * cnt, raw))

ifd0, _ = ifd(struct.unpack_from(E + 'I', data, 4)[0])
raw_ifd = ifd0
if ints(ifd0.get(254, (4, 1, struct.pack(E + 'I', 1))))[0] != 0:
    for sub in ints(ifd0[330]):
        t, _ = ifd(sub)
        if ints(t.get(254, (4, 1, struct.pack(E + 'I', 1))))[0] == 0: raw_ifd = t; break
STRUCTURE = {254, 256, 257, 258, 259, 262, 273, 277, 278, 279, 284, 322, 323, 324, 325, 50719, 50720, 50829, 50830, 50831}
DROP = {330, 34665, 34853, 40965, 50740, 51008, 51009, 51022, 50741, 700, 37500, 513, 514, 51041, 51111, 51110, 50781, 33723, 34377}
tags = {k: v for k, v in ifd0.items() if k not in STRUCTURE and k not in DROP}
tags.update({k: v for k, v in raw_ifd.items() if k not in DROP and k not in (273, 279, 324, 325, 256, 257, 50719, 50720, 50829)})
comp = ints(raw_ifd[259])[0]
bits = ints(raw_ifd[258])[0]
blocks = []
if 322 in raw_ifd:  # tiled
    tw, th = ints(raw_ifd[322])[0], ints(raw_ifd[323])[0]
    assert X % tw == 0 and Y % th == 0 and W % tw == 0 and H % th == 0, 'rectangle must be on tile boundaries'
    across = (ints(raw_ifd[256])[0] + tw - 1) // tw
    offs, cnts = ints(raw_ifd[324]), ints(raw_ifd[325])
    for ty in range(Y // th, (Y + H) // th):
        for tx in range(X // tw, (X + W) // tw):
            i = ty * across + tx
            blocks.append(data[offs[i]:offs[i] + cnts[i]])
    tiled = True
else:
    assert comp == 1 and bits == 16 and X % 2 == 0 and Y % 2 == 0
    offs, cnts = ints(raw_ifd[273]), ints(raw_ifd[279])
    rps = ints(raw_ifd[278])[0] if 278 in raw_ifd else ints(raw_ifd[257])[0]
    width = ints(raw_ifd[256])[0]
    rows = []
    for y in range(Y, Y + H):
        s = y // rps; o = offs[s] + ((y - s * rps) * width + X) * 2
        rows.append(data[o:o + W * 2])
    blocks.append(b''.join(rows))
    tags[278] = (4, 1, struct.pack(E + 'I', H))
    tiled = False
tags[254] = (4, 1, struct.pack(E + 'I', 0))
tags[256] = (4, 1, struct.pack(E + 'I', W))
tags[257] = (4, 1, struct.pack(E + 'I', H))
tags[50719] = (4, 2, struct.pack(E + 'II', 0, 0))   # DefaultCropOrigin
tags[50720] = (4, 2, struct.pack(E + 'II', W, H))   # DefaultCropSize
if 50829 in raw_ifd:
    t, l, b, r = ints(raw_ifd[50829])
    assert (t, l, b, r) == (0, 0, ints(raw_ifd[257])[0], ints(raw_ifd[256])[0]), 'files with a masked border are not cut by this script'

# Layout: header, IFD, out-of-line values (the block offset and size arrays reserved first), then the sensor data.
data_tag = (324, 325) if tiled else (273, 279)
n = len(tags) + 2
ifd_off = 8
extra_off = ifd_off + 2 + 12 * n + 4
extra = bytearray()
def place(raw):
    global extra
    if (extra_off + len(extra)) % 2: extra += b'\0'
    at = extra_off + len(extra); extra += raw; return at
arrays = {}
if len(blocks) > 1:
    for t in data_tag: arrays[t] = place(b'\0' * 4 * len(blocks))
entries = {}
for tag, (typ, cnt, raw) in tags.items():
    entries[tag] = (typ, cnt, raw.ljust(4, b'\0') if len(raw) <= 4 else struct.pack(E + 'I', place(raw)))
pos = extra_off + len(extra)
if pos % 2: extra += b'\0'; pos += 1
offs, sizes = [], []
for b in blocks: offs.append(pos); sizes.append(len(b)); pos += len(b) + len(b) % 2
for t, vals in zip(data_tag, (offs, sizes)):
    packed = struct.pack(E + 'I' * len(vals), *vals)
    if len(blocks) > 1:
        at = arrays[t] - extra_off; extra[at:at + len(packed)] = packed
        entries[t] = (4, len(vals), struct.pack(E + 'I', arrays[t]))
    else:
        entries[t] = (4, 1, packed)
out_b = bytearray((b'II' if le else b'MM') + struct.pack(E + 'HI', 42, ifd_off))
out_b += struct.pack(E + 'H', n)
for tag in sorted(entries):
    typ, cnt, raw = entries[tag]
    out_b += struct.pack(E + 'HHI', tag, typ, cnt) + raw
out_b += struct.pack(E + 'I', 0) + extra
for b in blocks: out_b += b + b'\0' * (len(b) % 2)
open(out, 'wb').write(out_b)
print(out, len(out_b), 'bytes,', len(blocks), 'block(s), compression', comp)
