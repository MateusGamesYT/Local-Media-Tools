"""Time per face (one aligned 112×112 face, no mirror) of each face-recognition model on this CPU, with
1 and 4 threads, through ONNX Runtime and through OpenCV's DNN module (what the app runs SFace with),
or for a .tflite file through TensorFlow Lite (XNNPACK, as the app runs its other models).

    python time_models.py <model dir> name=file.onnx|file.tflite …
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
        if f.endswith('.tflite'):
            import tensorflow as tf
            for th in (1, 4):
                it = tf.lite.Interpreter(model_path=path, num_threads=th); it.allocate_tensors()
                i = it.get_input_details()[0]; xi = x.transpose(0, 2, 3, 1).copy()
                def run():
                    it.set_tensor(i['index'], xi); it.invoke()
                row.append(f"TFLite {th} thread{'s' if th > 1 else ''} {bench(run):7.1f} ms")
            print(' | '.join(row), flush=True)
            continue
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
