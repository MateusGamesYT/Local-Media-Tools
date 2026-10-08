#!/usr/bin/env python3
"""Reproduces app/src/main/assets/models/migan_512_fp16.tflite (the Magic Eraser model).

MI-GAN (Picsart AI Research, MIT licence: https://github.com/Picsart-AI-Research/MI-GAN) is a
small GAN made for on-device inpainting at 512×512. The TorchScript export used by IOPaint is
converted TorchScript → ONNX (opset 17) → onnxsim → onnx2tf → TFLite float16.

The converter must match the app's TFLite runtime (2.16.1), so op versions stay loadable:
    python3.12 -m venv venv && venv/bin/pip install torch==2.4.1 tensorflow==2.16.1 tf_keras==2.16.0 \
        onnx==1.16.1 onnxruntime==1.18.1 onnx2tf==1.22.3 onnxsim onnx_graphsurgeon sng4onnx psutil \
        simple_onnx_processing_tools "numpy<2" protobuf==3.20.3 ml_dtypes==0.3.2 h5py
    venv/bin/python buildtools/models/convert_migan.py

Model contract (NHWC): input [1,512,512,4] float32 = (0.5 − mask, RGB·(1 − mask)) with RGB in
[-1, 1] and mask 1 inside the hole; output [1,512,512,3] RGB in [-1, 1].
"""
import hashlib
import os
import subprocess
import sys
import tempfile
import urllib.request

URL = "https://github.com/Sanster/models/releases/download/migan/migan_traced.pt"
MD5 = "76eb3b1a71c400ee3290524f7a11b89c"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models", "migan_512_fp16.tflite")


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else OUT
    import numpy as np
    import onnx
    import onnxsim
    import tensorflow as tf
    import torch

    with tempfile.TemporaryDirectory() as tmp:
        pt = os.path.join(tmp, "migan_traced.pt")
        urllib.request.urlretrieve(URL, pt)
        if hashlib.md5(open(pt, "rb").read()).hexdigest() != MD5:
            sys.exit("checksum mismatch for migan_traced.pt")
        model = torch.jit.load(pt, map_location="cpu").eval()
        onnx_path = os.path.join(tmp, "migan.onnx")
        torch.onnx.export(model, torch.zeros(1, 4, 512, 512), onnx_path, opset_version=17,
                          input_names=["input"], output_names=["output"], do_constant_folding=True)
        simplified, ok = onnxsim.simplify(onnx.load(onnx_path))
        assert ok
        sim_path = os.path.join(tmp, "migan_sim.onnx")
        onnx.save(simplified, sim_path)
        subprocess.run([os.path.join(os.path.dirname(sys.executable), "onnx2tf"), "-i", sim_path,
                        "-o", os.path.join(tmp, "tf"), "-n"], check=True)
        tfl = os.path.join(tmp, "tf", "migan_sim_float16.tflite")

        # Parity check against PyTorch on a random image with a hole.
        rng = np.random.default_rng(0)
        img = rng.uniform(-1, 1, (512, 512, 3)).astype(np.float32)
        mask = np.zeros((512, 512), np.float32); mask[150:350, 200:330] = 1
        inp = np.concatenate([(0.5 - mask)[..., None], img * (1 - mask[..., None])], -1)
        with torch.no_grad():
            ref = model(torch.from_numpy(inp.transpose(2, 0, 1)[None].copy())).numpy()[0].transpose(1, 2, 0)
        it = tf.lite.Interpreter(model_path=tfl)
        it.allocate_tensors()
        it.set_tensor(it.get_input_details()[0]["index"], inp[None])
        it.invoke()
        out = it.get_tensor(it.get_output_details()[0]["index"])[0]
        err = float(np.abs(out - ref).mean()) * 127.5
        print(f"mean abs difference vs PyTorch: {err:.3f} / 255")
        assert err < 1.0
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(tfl, "rb") as f, open(out_path, "wb") as g:
            g.write(f.read())
    print("wrote", out_path)


if __name__ == "__main__":
    main()
