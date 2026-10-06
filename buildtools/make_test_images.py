"""Generates soft-decoder test fixtures (input file + expected RGBA dump) with Pillow."""
import os, struct, sys
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

def base(mode="RGB", w=23, h=17):
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = ((x * 11) % 256, (y * 15) % 256, ((x + y) * 7) % 256, 255 if mode != "RGBA" else (x * 9 + y) % 256)
    return im

def save_expected(name, im):
    rgba = im.convert("RGBA")
    with open(os.path.join(out, name + ".rgba"), "wb") as f:
        f.write(struct.pack(">II", rgba.width, rgba.height))
        f.write(rgba.tobytes())

cases = []
for comp in ["raw", "tiff_lzw", "tiff_adobe_deflate", "packbits"]:
    im = base("RGB").convert("RGB")
    name = f"rgb_{comp}.tif"
    im.save(os.path.join(out, name), compression=comp)
    save_expected(name, im)
    ima = base("RGBA")
    name = f"rgba_{comp}.tif"
    ima.save(os.path.join(out, name), compression=comp)
    save_expected(name, ima)
g = base().convert("L"); g.save(os.path.join(out, "gray.tif"), compression="tiff_lzw"); save_expected("gray.tif", g)
p = base().convert("P", palette=Image.ADAPTIVE, colors=16); p.save(os.path.join(out, "pal.tif")); save_expected("pal.tif", p)
b = base().convert("1"); b.save(os.path.join(out, "bilevel.tif")); save_expected("bilevel.tif", b)
# PNM
rgb = base().convert("RGB"); rgb.save(os.path.join(out, "img.ppm")); save_expected("img.ppm", rgb)
g.save(os.path.join(out, "img.pgm")); save_expected("img.pgm", g)
with open(os.path.join(out, "ascii.ppm"), "w") as f:
    f.write("P3\n# comment\n%d %d\n255\n" % rgb.size)
    for y in range(rgb.height):
        f.write(" ".join("%d %d %d" % rgb.getpixel((x, y)) for x in range(rgb.width)) + "\n")
save_expected("ascii.ppm", rgb)
# TGA
rgb.save(os.path.join(out, "img.tga")); save_expected("img.tga", rgb)
rgb.save(os.path.join(out, "rle.tga"), compression="tga_rle"); save_expected("rle.tga", rgb)
ima = base("RGBA"); ima.save(os.path.join(out, "rgba.tga")); save_expected("rgba.tga", ima)
# QOI (hand-written encoder, spec-compliant)
def qoi(im):
    w, h = im.size
    data = bytearray(b"qoif" + struct.pack(">II", w, h) + bytes([4, 0]))
    index = [(0, 0, 0, 0)] * 64
    prev = (0, 0, 0, 255); run = 0
    pix = list(im.convert("RGBA").getdata())
    for i, px in enumerate(pix):
        if px == prev:
            run += 1
            if run == 62 or i == len(pix) - 1:
                data.append(0xC0 | (run - 1)); run = 0
            prev = px; continue
        if run: data.append(0xC0 | (run - 1)); run = 0
        h_ = (px[0] * 3 + px[1] * 5 + px[2] * 7 + px[3] * 11) % 64
        if index[h_] == px: data.append(h_)
        else:
            index[h_] = px
            if px[3] == prev[3]:
                dr = (px[0] - prev[0] + 128) % 256 - 128; dg = (px[1] - prev[1] + 128) % 256 - 128; db = (px[2] - prev[2] + 128) % 256 - 128
                dr_dg = dr - dg; db_dg = db - dg
                if -2 <= dr <= 1 and -2 <= dg <= 1 and -2 <= db <= 1:
                    data.append(0x40 | ((dr + 2) << 4) | ((dg + 2) << 2) | (db + 2))
                elif -32 <= dg <= 31 and -8 <= dr_dg <= 7 and -8 <= db_dg <= 7:
                    data += bytes([0x80 | (dg + 32), ((dr_dg + 8) << 4) | (db_dg + 8)])
                else:
                    data += bytes([0xFE, px[0], px[1], px[2]])
            else:
                data += bytes([0xFF, *px])
        prev = px
    data += bytes([0] * 7 + [1])
    return bytes(data)
ima = base("RGBA")
open(os.path.join(out, "img.qoi"), "wb").write(qoi(ima)); save_expected("img.qoi", ima)
# PSD (raw and RLE merged composite, RGB)
def packbits(row):
    res = bytearray(); i = 0
    while i < len(row):
        j = i
        while j < len(row) and j - i < 128 and row[j] == row[i]: j += 1
        if j - i >= 2:
            res += bytes([(257 - (j - i)) & 0xFF, row[i]]); i = j
        else:
            j = i
            while j < len(row) and j - i < 128 and not (j + 1 < len(row) and row[j] == row[j + 1]): j += 1
            if j == i: j = i + 1
            res += bytes([j - i - 1]) + bytes(row[i:j]); i = j
    return bytes(res)
def psd(im, rle):
    w, h = im.size
    chans = [bytes(c.tobytes()) for c in im.convert("RGB").split()]
    head = b"8BPS" + struct.pack(">H", 1) + b"\0" * 6 + struct.pack(">HIIHH", 3, h, w, 8, 3)
    body = struct.pack(">I", 0) + struct.pack(">I", 0) + struct.pack(">I", 0)
    if not rle:
        body += struct.pack(">H", 0) + b"".join(chans)
    else:
        rows = [packbits(c[y * w:(y + 1) * w]) for c in chans for y in range(h)]
        body += struct.pack(">H", 1) + b"".join(struct.pack(">H", len(r)) for r in rows) + b"".join(rows)
    return head + body
open(os.path.join(out, "raw.psd"), "wb").write(psd(rgb, False)); save_expected("raw.psd", rgb)
open(os.path.join(out, "rle.psd"), "wb").write(psd(rgb, True)); save_expected("rle.psd", rgb)
print("ok", len(os.listdir(out)))
