"""Writes the MP3 (MPEG-1 Layer III) encoder's fixed tables from the standard (ISO/IEC 11172-3),
as transcribed in FFmpeg's libavcodec: the filter bank window (mpegaudiodsp_data.c,
ff_mpa_enwindow), the Huffman tables (mpegaudiodec_common.c: code lengths in the order that
rebuilds the standard's code words, the count1 tables, the table numbers and linbits) and the
scalefactor band widths.

    python3 mp3_tables.py <FFmpeg source dir> <out .kt>

The code words are rebuilt as FFmpeg's decoder does (ff_vlc_init_from_lengths: consecutive codes
in the listed order), so what the encoder writes is what decoders read.
"""
import re, sys

src, out = sys.argv[1], sys.argv[2]
common = open(f"{src}/libavcodec/mpegaudiodec_common.c").read()
dsp = open(f"{src}/libavcodec/mpegaudiodsp_data.c").read()


def array(text, name):
    m = re.search(re.escape(name) + r"[^=]*=\s*\{(.*?)\n\};", text, re.S)
    body = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
    return [int(x, 0) for x in re.findall(r"-?0x[0-9A-Fa-f]+|-?\d+", body)]


enwindow = array(dsp, "ff_mpa_enwindow[257]")
lens = array(common, "mpa_hufflens[]")
syms = array(common, "mpa_huffsymbols[]")
sizes = [n + 1 for n in array(common, "mpa_huff_sizes_minus_one[]")]
huff_data = array(common, "ff_mpa_huff_data[32][2]")
quad_codes = array(common, "mpa_quad_codes[2][16]")
quad_bits = array(common, "mpa_quad_bits[2][16]")
band_long = array(common, "ff_band_size_long[9][22]")
pretab = array(common, "ff_mpa_pretab[2][22]")
assert len(enwindow) == 257 and sum(sizes) == len(lens) == len(syms) and len(huff_data) == 64

# Each Huffman table: code and length for every (x, y), x and y < xlen.
XLEN = [2, 3, 3, 4, 4, 6, 6, 6, 8, 8, 8, 16, 16, 16, 16]
tables = []
at = 0
for t, n in enumerate(sizes):
    xlen = XLEN[t]
    code = [0] * (xlen * xlen); length = [0] * (xlen * xlen)
    c = 0
    for j in range(n):
        l = lens[at + j]; s = syms[at + j]
        x, y = s >> 4, s & 15
        code[x * xlen + y] = c >> (32 - l); length[x * xlen + y] = l
        c += 1 << (32 - l)
    assert c == 1 << 32 or c < 1 << 32, t
    assert all(length), f"table {t} has a pair without a code"
    tables.append((xlen, code, length))
    at += n

# The full 512-tap analysis window: the standard's C[i] = D[i] / 32, D from enwindow (Q16) with
# the signs FFmpeg's synthesis gives the second half.
D = [0.0] * 512
for i in range(257):
    v = enwindow[i]
    D[i] = v
    if i & 63: v = -v
    if i: D[512 - i] = v
C = [d / 65536.0 / 32.0 for d in D]

def ints(name, values, per=20):
    lines = []
    for k in range(0, len(values), per):
        lines.append("        " + ", ".join(str(v) for v in values[k:k + per]) + ",")
    return f"    val {name} = intArrayOf(\n" + "\n".join(lines) + "\n    )\n"

o = ["package com.localmediatools.audio.core\n",
     "/**",
     " * Fixed tables of MPEG-1 Layer III (ISO/IEC 11172-3), written by buildtools/audio/mp3_tables.py from",
     " * FFmpeg's transcription of the standard: the analysis window, the Huffman code words and lengths,",
     " * the count1 tables, each table number's Huffman table and linbits, and the scalefactor bands.",
     " */",
     "internal object Mp3Tables {",
     "    /** The analysis window C[0..511] (D/32 of the standard's synthesis window, D in units of 2^-16). */",
     ints("WINDOW_D", D, 16).replace("intArrayOf", "intArrayOf"),
     "    /** Width of each Huffman table (values 0 until xlen), per table 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 24. */",
     "    val XLEN = intArrayOf(" + ", ".join(map(str, XLEN)) + ")\n"]
for t, (xlen, code, length) in enumerate(tables):
    o.append(ints(f"CODE_{t}", code, 16))
    o.append(ints(f"LEN_{t}", length, 32))
o.append("    val CODES = arrayOf(" + ", ".join(f"CODE_{t}" for t in range(len(tables))) + ")\n")
o.append("    val LENS = arrayOf(" + ", ".join(f"LEN_{t}" for t in range(len(tables))) + ")\n")
o.append("    /** For each table number 0–31: the Huffman table (index into CODES, −1 for table 0 and the unused 4 and 14) and its linbits. */")
hd = [(huff_data[2 * i] - 1 if huff_data[2 * i] or i == 1 else -1, huff_data[2 * i + 1]) for i in range(32)]
o.append("    val TABLE = intArrayOf(" + ", ".join(str(h) for h, _ in hd) + ")\n")
o.append("    val LINBITS = intArrayOf(" + ", ".join(str(l) for _, l in hd) + ")\n")
o.append("    /** Count1 tables A and B: code and length for each quadruple v·8 + w·4 + x·2 + y. */")
o.append("    val QUAD_CODE = arrayOf(intArrayOf(" + ", ".join(map(str, quad_codes[:16])) + "), intArrayOf(" + ", ".join(map(str, quad_codes[16:])) + "))\n")
o.append("    val QUAD_LEN = arrayOf(intArrayOf(" + ", ".join(map(str, quad_bits[:16])) + "), intArrayOf(" + ", ".join(map(str, quad_bits[16:])) + "))\n")
o.append("    /** Widths of the 22 long-block scalefactor bands at 44.1, 48 and 32 kHz. */")
for name, k in (("BANDS_44100", 0), ("BANDS_48000", 1), ("BANDS_32000", 2)):
    o.append(f"    val {name} = intArrayOf(" + ", ".join(map(str, band_long[22 * k:22 * k + 22])) + ")\n")
o.append("    val PRETAB = intArrayOf(" + ", ".join(map(str, pretab[22:44])) + ")\n")
o.append("}\n")
open(out, "w").write("\n".join(o))
print("tables:", [(x, sum(1 for l in L if l)) for x, _, L in tables])
