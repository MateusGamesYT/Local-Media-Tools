"""Time per face (one aligned 112×112 face, no mirror) of each face-recognition model on this CPU, with
1 and 4 threads, through ONNX Runtime and through OpenCV's DNN module (what the app runs on the phone).

    python time_models.py <model dir> name=file.onnx …
"""
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, cv2, onnxruntime as ort


def bench(f, n=30):
    f(); f()
    t = time.perf_counter()
    for _ in range(n): f()
    return (time.perf_counter() - t) / n * 1000


def main():
    mdir = sys.argv[1]
    x = np.random.default_rng(0).uniform(-1, 1, (1, 3, 112, 112)).astype(np.float32)
    for spec in sys.argv[2:]:
        name, f = spec.split('=', 1); path = os.path.join(mdir, f)
        row = [f"{name:6s} {os.path.getsize(path) / 1e6:6.1f} MB"]
        for th in (1, 4):
            o = ort.SessionOptions(); o.intra_op_num_threads = th
            s = ort.InferenceSession(path, o, providers=['CPUExecutionProvider']); inp = s.get_inputs()[0].name
            row.append(f"onnxruntime {th} thread{'s' if th > 1 else ''} {bench(lambda: s.run(None, {inp: x})):7.1f} ms")
        try:
            net = cv2.dnn.readNetFromONNX(path)
            for th in (1, 4):
                cv2.setNumThreads(th)
                def run():
                    net.setInput(x); net.forward()
                row.append(f"OpenCV {th} {bench(run):7.1f} ms")
        except Exception as e:
            row.append(f"OpenCV: can't load ({str(e).splitlines()[0][:60]})")
        print(' | '.join(row), flush=True)


if __name__ == '__main__':
    main()
