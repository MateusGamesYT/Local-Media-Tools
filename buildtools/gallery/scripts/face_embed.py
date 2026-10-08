# Runs the app's face pipeline (YuNet detection, 5-point alignment, SFace embedding) over BIWI frames.
import cv2, numpy as np, os, sys, glob, json
G = sys.argv[1]
det = cv2.FaceDetectorYN.create(f"{G}/models/face_detection_yunet_2023mar.onnx", "", (320, 320), 0.6, 0.3, 500)
recs = {"int8": cv2.FaceRecognizerSF.create(f"{G}/models/face_recognition_sface_2021dec_int8.onnx", ""),
        "fp32": cv2.FaceRecognizerSF.create(f"{G}/models/face_recognition_sface_2021dec.onnx", "")}
def emb(rec, img, row, flip):
    al = rec.alignCrop(img, row)
    f = rec.feature(al).flatten().astype(np.float32)
    if flip:
        f2 = rec.feature(cv2.flip(al, 1)).flatten().astype(np.float32)
        f = f / np.linalg.norm(f) + f2 / np.linalg.norm(f2)
    return f / np.linalg.norm(f)
def quality(row, w, h):
    re, le, nose = row[4:6], row[6:8], row[8:10]
    ed = np.linalg.norm(le - re)
    mid = (le + re) / 2
    yaw = abs(nose[0] - mid[0]) / max(ed, 1e-3)
    return dict(score=float(row[14]), eye=float(ed), size=float(min(row[2], row[3])), yaw=float(yaw))
out = []
seqs = sorted(d for d in os.listdir(f"{G}/data/biwi_head_pose") if d.isdigit())
for s in seqs:
    frames = sorted(glob.glob(f"{G}/data/biwi_head_pose/{s}/frame_*_rgb.jpg"))[::8]
    for fpath in frames:
        img = cv2.imread(fpath)
        h, w = img.shape[:2]
        det.setInputSize((w, h))
        _, faces = det.detect(img)
        if faces is None or len(faces) == 0:
            out.append(dict(seq=s, frame=os.path.basename(fpath), found=False)); continue
        row = max(faces, key=lambda r: r[2] * r[3])
        rec = dict(seq=s, frame=os.path.basename(fpath), found=True, **quality(row, w, h))
        for k, r in recs.items():
            rec[k] = emb(r, img, row, False).tolist()
            rec[k + "_flip"] = emb(r, img, row, True).tolist()
        out.append(rec)
    print(s, len(frames), sum(1 for o in out if o["seq"] == s and o["found"]), flush=True)
json.dump(out, open(f"{G}/eval/biwi_faces.json", "w"))
