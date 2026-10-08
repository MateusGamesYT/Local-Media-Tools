"""Run EfficientDet-Lite2 (MediaPipe export, raw anchors) over images; save per-image detections."""
import sys, os, json, time, numpy as np
from PIL import Image
import tensorflow as tf
model, mode, listfile, out = sys.argv[1:5]
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
anchors = np.load(os.path.join(G, 'meta/anchors.npy'))  # x, y, w, h
it = tf.lite.Interpreter(model_path=model, num_threads=4)
it.allocate_tensors()
inp = it.get_input_details()[0]
outs = it.get_output_details()
S = 448
def prep(img):
    w, h = img.size
    if mode == 'stretch':
        r = img.resize((S, S), Image.BILINEAR); sx, sy = 1.0, 1.0
        a = np.asarray(r)
    else:
        s = S / max(w, h); nw, nh = max(1, round(w*s)), max(1, round(h*s))
        r = img.resize((nw, nh), Image.BILINEAR)
        a = np.zeros((S, S, 3), np.uint8); a[:nh, :nw] = np.asarray(r)
        sx, sy = S / nw, S / nh   # normalised box coord * sx -> fraction of the image
    if inp['dtype'] == np.uint8: x = a[None]
    else: x = (a[None].astype(np.float32) - 127.0) / 128.0
    return x, sx, sy
def nms(boxes, scores, thr=0.5):
    order = np.argsort(-scores); keep = []
    while order.size:
        i = order[0]; keep.append(i)
        if order.size == 1: break
        r = order[1:]
        xx1 = np.maximum(boxes[i,0], boxes[r,0]); yy1 = np.maximum(boxes[i,1], boxes[r,1])
        xx2 = np.minimum(boxes[i,2], boxes[r,2]); yy2 = np.minimum(boxes[i,3], boxes[r,3])
        inter = np.clip(xx2-xx1, 0, None) * np.clip(yy2-yy1, 0, None)
        a = (boxes[i,2]-boxes[i,0])*(boxes[i,3]-boxes[i,1]); b = (boxes[r,2]-boxes[r,0])*(boxes[r,3]-boxes[r,1])
        order = r[inter / (a + b - inter + 1e-9) <= thr]
    return keep
names = [l.strip() for l in open(listfile)]
res = {}
t0 = time.time()
for k, path in enumerate(names):
    img = Image.open(path).convert('RGB')
    x, sx, sy = prep(img)
    it.set_tensor(inp['index'], x); it.invoke()
    o = {d['shape'][-1]: it.get_tensor(d['index'])[0] for d in outs}
    raw, sc = o[4], o[90]
    if k == 0: print('score range', sc.min(), sc.max(), 'raw', raw.min(), raw.max())
    prob = 1/(1+np.exp(-sc)) if sc.max() > 1.0 or sc.min() < 0 else sc
    yc = raw[:,0]*anchors[:,3] + anchors[:,1]; xc = raw[:,1]*anchors[:,2] + anchors[:,0]
    hh = np.exp(raw[:,2])*anchors[:,3]; ww = np.exp(raw[:,3])*anchors[:,2]
    boxes = np.stack([xc-ww/2, yc-hh/2, xc+ww/2, yc+hh/2], 1)
    boxes[:,[0,2]] *= sx; boxes[:,[1,3]] *= sy
    boxes = np.clip(boxes, 0, 1)
    dets = []
    for c in range(90):
        m = prob[:,c] > 0.15
        if not m.any(): continue
        idx = np.where(m)[0]
        for j in nms(boxes[idx], prob[idx,c]):
            i = idx[j]; dets.append([c, float(prob[i,c])] + [round(float(v),4) for v in boxes[i]])
    dets.sort(key=lambda d: -d[1])
    res[os.path.basename(path)] = dets[:60]
    if k % 200 == 0: print(k, round(time.time()-t0,1), flush=True)
json.dump(res, open(out, 'w'))
print('done', len(res), 'images', round((time.time()-t0)/len(res)*1000), 'ms/img')
