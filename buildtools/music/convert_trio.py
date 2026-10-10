"""Packs the decoder of Magenta's MusicVAE "trio_4bar" checkpoint (melody, bass and drums, 4 bars;
Apache-2.0 project, https://github.com/magenta/magenta-js/tree/master/music/checkpoints) into the
app's asset app/src/main/assets/models/trio_decoder.bin. Only the decoder is kept (the app samples
new music from the prior and never encodes any); weights stay as published: 8-bit, with each
tensor's minimum and step.

Format (big-endian): b"LMTV", int version=1, int count, then per tensor: short name length, name
(UTF-8), byte rank, int dims…, double min, double scale, the uint8 values (row-major, as in the
checkpoint: kernels are [inputs, outputs]).

    python convert_trio.py <checkpoint dir with weights_manifest.json> <out.bin>
"""
import json, os, struct, sys

ckpt, out = sys.argv[1], sys.argv[2]
manifest = json.load(open(os.path.join(ckpt, 'weights_manifest.json')))
blobs = {}
kept = []
with open(out, 'wb') as o:
    entries = []
    for group in manifest:
        data = b''.join(open(os.path.join(ckpt, p), 'rb').read() for p in group['paths'])
        off = 0
        for w in group['weights']:
            n = 1
            for d in w['shape']: n *= d
            q = w.get('quantization')
            assert q and q['dtype'] == 'uint8', w['name']
            raw = data[off:off + n]; off += n
            if w['name'].startswith('encoder/'): continue
            entries.append((w['name'], w['shape'], q['min'], q['scale'], raw))
    entries.sort(key=lambda e: e[0])
    o.write(b'LMTV'); o.write(struct.pack('>ii', 1, len(entries)))
    for name, shape, mn, sc, raw in entries:
        nb = name.encode()
        o.write(struct.pack('>H', len(nb))); o.write(nb)
        o.write(struct.pack('>b', len(shape)))
        for d in shape: o.write(struct.pack('>i', d))
        o.write(struct.pack('>dd', mn, sc)); o.write(raw)
        kept.append((name, shape))
for k in kept: print(k)
print('tensors', len(kept), 'bytes', os.path.getsize(out))
