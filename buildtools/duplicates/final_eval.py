"""The final check, through the app's own code: exports the simulated camera roll (features as the app
caches them, grey thumbnails for the feature check), runs 1.6.0's Duplicates.kt and the new one
(harness/build.sh first), and scores both — on all runs and on each half of them.

    python final_eval.py features.npz extra.npz
"""
import os, struct, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from library import load, score
from evaluate import quantized
from rules import thumb
from common import WORK


def export(lib, out_dir):
    f = lib['f']
    n = len(lib['ids'])
    os.makedirs(os.path.join(out_dir, 'thumbs'), exist_ok=True)
    emb = f['emb'].astype(np.float32)
    with open(os.path.join(out_dir, 'photos.bin'), 'wb') as o:
        o.write(struct.pack('>i', n))
        for i in range(n):
            w, h = (int(x) for x in f['sizes'][i])
            t = lib['taken'][i]
            o.write(struct.pack('>iiiqqf', i, w, h, int(np.int64(np.uint64(f['dhash'][i]).astype(np.int64))),
                                -(1 << 63) if np.isnan(t) else int(t), float(f['contrast'][i])))
            o.write(np.asarray(f['g16'][i], '>f4').tobytes())
            e = emb[i]
            scale = float(np.abs(e).max() / 127)
            q = np.clip(np.round(e / scale), -127, 127).astype(np.int8)
            o.write(struct.pack('>if', len(q), scale)); o.write(q.tobytes())
    # Grey thumbnails of the photos the feature check may be asked about.
    sim = quantized(emb) @ quantized(emb).T
    need = set(np.nonzero((np.triu(sim, 1) >= 0.7).any(axis=0) | (np.triu(sim, 1) >= 0.7).any(axis=1))[0].tolist())
    for i in need:
        p = os.path.join(out_dir, 'thumbs', f'{i}.raw')
        if os.path.exists(p): continue
        pid, _, variant = lib['ids'][i].partition(':')
        g = thumb(os.path.join(WORK, 'img', pid + '.jpg'), variant or None)
        with open(p, 'wb') as o:
            o.write(struct.pack('>ii', g.shape[1], g.shape[0])); o.write(np.ascontiguousarray(g, np.uint8).tobytes())
    print(f'exported {n} photos, {len(need)} thumbnails')


def pictures(lib, path):
    """Pairs the new rules treat as copies of one picture (one is suggested for the trash), by truth."""
    from collections import Counter
    from library import truth
    c = Counter()
    for line in open(path):
        for p in line.split('/'):
            m = [int(x) for x in p.split()]
            for a in range(len(m)):
                for b in range(a + 1, len(m)): c[truth(lib, m[a], m[b])] += 1
    print(f"  treated as copies of one picture: copy {c['copy']} pairs, same moment {c['same']}, related {c['related']}, different {c['different']}")


def read_groups(path):
    return [[int(x) for x in line.split()[1:]] for line in open(path) if line.strip()]


def main():
    lib = load(sys.argv[1], extra_path=sys.argv[2])
    out = os.path.join(WORK, 'harness_io')
    export(lib, out)
    h = os.path.join(WORK, 'harness')
    subprocess.run([os.path.join(h, 'run_old.sh'), os.path.join(out, 'photos.bin'), os.path.join(out, 'old.txt')], check=True)
    subprocess.run([os.path.join(h, 'run.sh'), os.path.join(out, 'photos.bin'), os.path.join(out, 'thumbs'), os.path.join(out, 'new.txt')], check=True)
    old, new = read_groups(os.path.join(out, 'old.txt')), read_groups(os.path.join(out, 'new.txt'))
    print('All runs:')
    score(lib, old, "1.6.0 (app's code)")
    _, _, bad = score(lib, new, "1.7.0 (app's code)")
    pictures(lib, os.path.join(out, 'new.txt.pictures'))
    for g in bad[:10]: print('  unrelated photos together:', [lib['ids'][i] for i in g])
    for half in (0, 1):
        keep = lambda g: [i for i in g if lib['moment'].get(i, (half, ''))[0] % 2 == half]
        print(f'Half {"AB"[half]} of the runs (all unrelated photos and copies):')
        score(lib, [x for x in (keep(g) for g in old) if len(x) > 1], '1.6.0')
        score(lib, [x for x in (keep(g) for g in new) if len(x) > 1], '1.7.0')


main()
