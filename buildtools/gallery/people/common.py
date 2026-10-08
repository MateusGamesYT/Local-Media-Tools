"""Where things are: these scripts and the verified labels live in git; downloaded photos, face crops
and the extracted faces go to the work folder (PEOPLE_WORK, by default buildtools/dl/people)."""
import os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
WORK = os.environ.get('PEOPLE_WORK') or os.path.join(REPO, 'buildtools', 'dl', 'people')
MODELS = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'models')
for d in ('img', 'crops', 'harness'): os.makedirs(os.path.join(WORK, d), exist_ok=True)
