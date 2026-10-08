import sys, numpy as np, struct
raw = open(sys.argv[1],'rb').read()
n = 37629; vec = 280
assert struct.unpack_from('<I', raw, vec)[0] == n
out = np.zeros((n,4), np.float32)
vt_seen = set()
for i in range(n):
    p = vec + 4 + 4*i
    t = p + struct.unpack_from('<I', raw, p)[0]
    so = struct.unpack_from('<i', raw, t)[0]
    vt = t - so
    vsize, tsize = struct.unpack_from('<HH', raw, vt)
    nf = (vsize - 4)//2
    offs = struct.unpack_from('<%dH' % nf, raw, vt+4)
    vt_seen.add((vt, offs))
    for f in range(min(nf,4)):
        out[i,f] = struct.unpack_from('<f', raw, t+offs[f])[0] if offs[f] else 0.0
print('vtables', vt_seen)
print(out[:12]); print(out[-5:])
np.save(sys.argv[2], out)
# decoding options near start
print(np.frombuffer(raw[200:284],'<u4'))
print(np.frombuffer(raw[200:284],'<f4'))
