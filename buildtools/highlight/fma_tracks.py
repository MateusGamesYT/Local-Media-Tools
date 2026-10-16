#!/usr/bin/env python3
"""fma_tracks.py <out dir> [shard number]

Real songs for song_eval.py: one shard of the Free Music Archive "small" set (Defferrard et al., FMA:
A Dataset for Music Analysis, ISMIR 2017; Creative Commons licences per track) from its Hugging Face
copy (benjamin-paine/free-music-archive-small): about 530 clips of 30 s from many genres. Writes the
clips and credits.tsv (file, artist, title, source, licence, genres; licence and genre names from the
dataset card). For evaluation only: the clips are not part of the repository.
"""
import ast, os, re, sys, urllib.request
import pyarrow.parquet as pq
out = sys.argv[1]; shard = int(sys.argv[2]) if len(sys.argv) > 2 else 0
os.makedirs(out, exist_ok=True)
HF = 'https://huggingface.co/datasets/benjamin-paine/free-music-archive-small'
card = urllib.request.urlopen(HF + '/raw/main/README.md', timeout=60).read().decode().split('\n')
def names(field):
    start = next(i for i, l in enumerate(card) if l.strip() == f'- name: {field}')
    found = {}
    for l in card[start + 1:]:
        m = re.match(r"\s+'(\d+)': (.*)", l)
        if m: found[int(m.group(1))] = m.group(2).strip("'")
        elif found and 'names' not in l and 'class_label' not in l and 'dtype' not in l and 'sequence' not in l: break
    return found
G, L = names('genres'), names('license')
path = os.path.join(out, f'shard{shard}.parquet')
if not os.path.exists(path):
    urllib.request.urlretrieve(f'{HF}/resolve/main/data/train-{shard:05d}-of-00015.parquet', path)
rows = pq.read_table(path).to_pylist()
with open(os.path.join(out, 'credits.tsv'), 'w') as f:
    f.write('# file\tauthor\ttitle\tsource\tlicence\tgenres\n')
    for i, r in enumerate(rows):
        a = r['audio']; name = f'fma{i:03d}' + (os.path.splitext(a.get('path') or 'x.mp3')[1] or '.mp3')
        open(os.path.join(out, name), 'wb').write(a['bytes'])
        genres = ', '.join(G.get(g, str(g)) for g in (r.get('genres') or []))
        f.write('\t'.join([name, r.get('artist') or '', r.get('title') or '', r.get('url') or '', L.get(r.get('license'), str(r.get('license'))), genres]) + '\n')
print(len(rows), 'clips')
