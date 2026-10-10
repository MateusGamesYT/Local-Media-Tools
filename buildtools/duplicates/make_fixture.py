"""Makes the duplicate finder's test photos (app/src/test/resources/duplicates): real photos from the
labelled set, at most 512 px, with credits, plus re-saved copies of two of them and what the JVM test
can't compute itself — the on-device model's embeddings — and the layout values to check against.

Chosen: three same-moment pairs and two near-identical exposures the finder groups; two pairs of
"related" shots (same place, another angle) and the four hardest look-alike unrelated pairs of the
test set (clouds, night skies, printed pages, screenshots: embeddings 0.72–0.89 alike), which it
must not group; copies (half size, heavy JPEG, with screenshot bars) of one photo and of a document.

    python make_fixture.py photos.json extra.json
"""
import io, json, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
from common import WORK, REPO
from variants import variants

OUT = os.path.join(REPO, 'app', 'src', 'test', 'resources', 'duplicates')
SAME = {39: 'court', 438: 'bridge', 279: 'cushion', 126: 'sunset'}
RELATED = {8: 'cemetery', 106: 'tank'}
UNRELATED = [('426f8f0cf5190c3d', 'clouds_a'), ('664cfa93f65c0fb8', 'clouds_b'), ('75d29f8013edf6e5', 'night_a'),
             ('e05c039a63260fdf', 'night_b'), ('9c05b436b3c1a7eb', 'document_a'), ('a6fa7ce8c34cbfe5', 'document_b'),
             ('916807fa50c81554', 'screen_a'), ('97c33c574032ab32', 'screen_b')]
COPIES = [('bridge_1', ['small', 'lowq', 'screenshot']), ('document_a', ['lowq'])]


def main():
    sel = json.load(open(sys.argv[1]))
    extra = {m['id']: m for m in json.load(open(sys.argv[2]))}
    meta = {p['id']: p for r in sel['runs'] for p in r['photos']}
    meta.update({p['id']: p for p in sel['pool']})
    meta.update(extra)
    labels = {r['run']: r for r in json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'labels.json')))['runs']}
    os.makedirs(OUT, exist_ok=True)
    chosen = []  # (file name, image id)
    for runs in (SAME, RELATED):
        for run, name in runs.items():
            for k, pid in enumerate(labels[run]['ids']):
                chosen.append((f'{name}_{k + 1}', pid))
    chosen += [(name, pid) for pid, name in UNRELATED]
    files = {}
    credits = ['# Duplicate finder test photos', '',
               'Open Images V7 photos from Flickr, all under CC BY 2.0 (https://creativecommons.org/licenses/by/2.0/).',
               'Changes: downscaled to at most 512 px and saved as JPEG (quality 90); the `copy_*` files are re-saved',
               'copies made from them (half size at JPEG quality 70, JPEG quality 35, or with dark bars like a screenshot).',
               'Made by buildtools/duplicates/make_fixture.py.', '',
               '| File | Title | Author | Source |', '|---|---|---|---|']
    for name, pid in chosen:
        img = Image.open(os.path.join(WORK, 'img', pid + '.jpg')).convert('RGB')
        s = 512 / max(img.size)
        if s < 1: img = img.resize((round(img.width * s), round(img.height * s)), Image.LANCZOS)
        img.info.pop('icc_profile', None)  # plain sRGB data: decoders that apply profiles and ones that don't agree
        img.save(os.path.join(OUT, name + '.jpg'), quality=90)
        files[name] = Image.open(os.path.join(OUT, name + '.jpg')).convert('RGB')
        m = meta[pid]
        credits.append(f"| {name}.jpg | {m.get('title') or '(untitled)'} | {m['author']} | {m['url']} |")
    for name, kinds in COPIES:
        for vname, v in variants(files[name], None):
            if vname in kinds:
                v.info.pop('icc_profile', None)
                v.save(os.path.join(OUT, f'copy_{vname}_{name}.jpg'), quality=95)
                files[f'copy_{vname}_{name}'] = Image.open(os.path.join(OUT, f'copy_{vname}_{name}.jpg')).convert('RGB')
    open(os.path.join(OUT, 'CREDITS.md'), 'w').write('\n'.join(credits) + '\n')
    # The model's embeddings (as the app computes them from a thumbnail) and the layout values.
    from features import thumbnail, embed, grid
    with open(os.path.join(OUT, 'golden.bin'), 'wb') as o:
        o.write(struct.pack('>i', len(files)))
        for name in sorted(files):
            img = files[name]
            e = embed(thumbnail(img))
            scale = float(np.abs(e).max() / 127)
            q = np.clip(np.round(e / scale), -127, 127).astype(np.int8)
            g, contrast = grid(np.asarray(img))
            nb = name.encode()
            o.write(struct.pack('>H', len(nb))); o.write(nb)
            o.write(struct.pack('>if', len(q), scale)); o.write(q.tobytes())
            o.write(np.asarray(g, '>f4').tobytes()); o.write(struct.pack('>f', contrast))
    print(len(files), 'files in', OUT)


if __name__ == '__main__':
    main()
