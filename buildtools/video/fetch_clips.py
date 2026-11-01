"""Real videos of people for measuring auto reframe: the Global Lives Project recordings of
buildtools/gallery/meta/video_clips.tsv (CC BY 3.0; daily life filmed alongside one person), the same
30-second pieces, from archive.org's MP4 copies (640 x 360, 25 fps), re-encoded with a keyframe a
second as phones record, without sound.

    python3 fetch_clips.py <out dir>

ffmpeg reads only the piece it needs (HTTP range requests; the proxy from $HTTPS_PROXY).
"""
import json, os, re, subprocess, sys, urllib.request

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
here = os.path.dirname(os.path.abspath(__file__))
rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(here, '..', 'gallery', 'meta', 'video_clips.tsv')) if not l.startswith('#') and l.strip()]
env = dict(os.environ)
if env.get('HTTPS_PROXY'): env['http_proxy'] = env['HTTPS_PROXY']
for name, author, title, source, licence, changes in rows:
    dst = os.path.join(out, name)
    if os.path.exists(dst): continue
    item = source.rstrip('/').split('/')[-1]
    meta = json.load(urllib.request.urlopen(f'https://archive.org/metadata/{item}', timeout=60))
    mp4 = [f['name'] for f in meta['files'] if f['name'].lower().endswith('.mp4')]
    if not mp4: print('no mp4 for', item, file=sys.stderr); continue
    a, b = map(int, re.match(r'(\d+) s to (\d+) s', changes).groups())
    url = f'https://archive.org/download/{item}/{mp4[0]}'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-ss', str(a), '-t', str(b - a), '-i', url, '-an', '-c:v', 'libx264', '-crf', '18',
                    '-preset', 'medium', '-g', '25', '-pix_fmt', 'yuv420p', dst], check=True, env=env)
    print(name, item, a, b, flush=True)
