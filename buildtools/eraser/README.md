# Magic eraser: what was measured (1.9.0)

`evaluate.py` cuts an object-shaped hole (an AIM-500 matte: real people, animals and things) into a
real photo (60 FiveK photos at 3000 px, the size of a phone photo), fills it the way the app does
(`edit/MagicEraser.kt`, `Retouch.erase`: a window around the hole, scaled for the model, the result
scaled back) and compares the fill with what was really behind it, inside the hole. 55 holes of 3–20 %
of the photo (5 shapes didn't fit). The app's model, MI-GAN (512 px, the author's ONNX export of the
same weights the app converts to TensorFlow Lite), runs through ONNX Runtime here.

| Fill | PSNR | SSIM | Detail (1 = as much as the real photo) |
|---|---|---|---|
| MI-GAN, window 1.3 × the hole | 17.52 | 0.7365 | 0.697 |
| MI-GAN, window 1.6 × | 17.75 | 0.7403 | 0.661 |
| **MI-GAN, window 1.9 × (the app)** | 17.70 | 0.7429 | 0.640 |
| MI-GAN, window 2.5 × | 17.78 | 0.7467 | 0.618 |
| MI-GAN at 768 px (window 1.9 ×) | 17.73 | 0.7452 | 0.628 |
| MI-GAN at 1024 px (window 1.9 ×) | 17.74 | 0.7431 | 0.639 |
| LaMa (big-lama, Apache-2.0, Carve's ONNX export), 512 px | **19.60** | **0.7771** | 0.586 |

- The window around the hole barely matters: more of it is a little more accurate and a little
  blurrier. The app's 1.9 stays.
- Running MI-GAN on a bigger picture of the window adds no detail: the softness of large fills comes
  from the model, not from the scaling.
- LaMa fills large holes clearly more faithfully (+1.9 dB), but is not usable in the app yet: its
  ONNX export (Fourier transforms written as matrix products, 208 MB) is rejected by OpenCV 4.12's
  DNN module, which the app runs ONNX models with, and takes about 18 s per hole on the build
  machine's CPU through ONNX Runtime. A native TensorFlow Lite conversion would be needed to judge
  its speed on a phone.

So the eraser is unchanged in 1.9.0.

    evaluate.py <photos dir> <aim dir> <model.onnx> [--factors 1.5,1.9,2.5] [--models 512,1024] [--count 60]

(`<photos dir>`: JPEGs, e.g. FiveK's as-shot photos at 3000 px; `<aim dir>`: from
`buildtools/matting/aim_extract.py`.)
