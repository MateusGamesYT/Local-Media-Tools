"""Where things are: these scripts and the verified labels live in git; downloaded photos and computed
features go to the work folder (DUP_WORK, by default buildtools/dl/duplicates)."""
import os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
WORK = os.environ.get('DUP_WORK') or os.path.join(REPO, 'buildtools', 'dl', 'duplicates')
# Open Images V7 metadata (ImageID, Subset, Landing, License, Author, Title, Rotation), as made by
# buildtools/gallery/people/strip_meta.py.
META = os.environ.get('OI_META') or os.path.join(REPO, 'buildtools', 'dl', 'people', 'oi_meta_dl.csv.gz')
EMBEDDER = os.path.join(REPO, '.toolchain', 'models', 'mobilenet_v3_small_embedder.tflite')
for d in ('img', 'sheets'): os.makedirs(os.path.join(WORK, d), exist_ok=True)
