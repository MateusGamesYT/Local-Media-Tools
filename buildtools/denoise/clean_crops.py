#!/usr/bin/env python3
"""clean_crops.py <raw files dir> <out dir>

Clean photos for checking that the photo denoiser leaves detail alone: low-ISO RAW files
(raw.pixls.us, CC0) developed by LibRaw (rawpy, camera white balance) and reduced 4× (averaging
16 photosites per pixel removes their noise), three 512 px crops each (centre, top left, bottom
right). Many are full of fine detail (grass, branches, bark) — the case a noise estimator can take
for noise.
"""
import cv2, rawpy, sys, os
src, dst = sys.argv[1:3]
os.makedirs(dst, exist_ok=True)
for fn in sorted(os.listdir(src)):
    try:
        with rawpy.imread(os.path.join(src, fn)) as r: rgb = r.postprocess(use_camera_wb=True, output_bps=8)
    except Exception as e:
        print(fn, 'skipped:', e); continue
    bgr = cv2.resize(rgb[:, :, ::-1], (rgb.shape[1] // 4, rgb.shape[0] // 4), interpolation=cv2.INTER_AREA)
    H, W = bgr.shape[:2]; s = min(512, H, W)
    for i, (y, x) in enumerate([((H - s) // 2, (W - s) // 2), (0, 0), (H - s, W - s)]):
        cv2.imwrite(f'{dst}/{fn}_{i}.png', bgr[y:y + s, x:x + s])
