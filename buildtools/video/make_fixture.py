"""The video editor tests' real footage: one Global Lives Project clip (CC BY 3.0, fetch_clips.py),
its frames every 2 seconds (640 x 360 JPEG) and what the app's detectors find in it
(reframe_detect.py), so the tests run the app's own auto reframe on real detections and show real
frames.

    python3 make_fixture.py <clips dir> <det dir> <clip, e.g. clip02>
"""
import os, sys, shutil
import cv2

clips, det, clip = sys.argv[1:4]
here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, '..', '..', 'app', 'src', 'test', 'resources', 'video')
os.makedirs(out, exist_ok=True)
cap = cv2.VideoCapture(os.path.join(clips, clip + '.mp4')); fps = cap.get(cv2.CAP_PROP_FPS) or 25
n = 0; saved = []
while True:
    ok, f = cap.read()
    if not ok: break
    ms = int(round(n * 1000 / fps))
    if n % max(1, round(2 * fps)) == 0:
        name = f'frame_{ms:05d}.jpg'; cv2.imwrite(os.path.join(out, name), f, [cv2.IMWRITE_JPEG_QUALITY, 85]); saved.append(name)
    n += 1
shutil.copy(os.path.join(det, clip + '.found.tsv'), os.path.join(out, 'found.tsv'))
row = next(l.rstrip('\n').split('\t') for l in open(os.path.join(here, '..', 'gallery', 'meta', 'video_clips.tsv')) if l.startswith(clip + '.mp4'))
with open(os.path.join(out, 'CREDITS.tsv'), 'w') as f:
    f.write('# files\tauthor\ttitle\tsource\tlicence\tchanges\n')
    f.write(f'frame_*.jpg, found.tsv\t{row[1]}\t{row[2]}\t{row[3]}\t{row[4]}\t{row[5].replace("re-encoded H.264 1080p, 1 s keyframes, no sound", "archive.org MP4 copy (640 x 360)")}; '
            f'frames every 2 s; found.tsv: what the app\'s detectors find in every second frame\n')
print(len(saved), 'frames', n, 'in all')
