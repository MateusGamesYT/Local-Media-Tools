# Background remover: how the cut-out is measured (1.9.0)

The subject mask comes from `app/src/main/kotlin/com/localmediatools/vision/core/Matting.kt`:
U²-Net-p (rembg, Apache-2.0) predicts a mask at 320 px, and a guided filter fits it to the photo's
edges at the app's working size (up to 1024 px). `Matting.cutout` is the whole pipeline.

## The photos

AIM-500 (Li et al., "Deep Automatic Natural Image Matting", IJCAI 2021): 500 natural photos with exact
alpha mattes — 200 animals, 100 portraits, plants, furniture, toys, fruit and transparent things — from
the copy on Hugging Face (`nobg/AIM-500`). `aim_extract.py` writes each photo at 1024 px on the long
side with its matte. Nothing from it is in the app or this repository.

## Harness

`harness/build.sh`, then `run.sh <aim dir> <u2netp.onnx> <variants> even|odd|all` runs the app's Kotlin
code on every photo and scores each variant: SAD (sum of absolute alpha errors ÷ 1000) and MSE
(× 1000), the usual matting measures. Variants: `base` (1.8), `app` (`Matting.cutout` as shipped), or
parts joined by `+`: `flip` (the model also sees the mirror image; the two masks are averaged),
`zoom` (a second look at the subject, cropped, when it is clearly smaller than the photo), `colour`
(guided filter on the colour photo instead of its brightness), `r<‰>` (filter radius as thousandths of
the short side; 1.8 used 12), `c<lo>-<hi>` (the final contrast curve), `e<ε>` (the filter's ε).

Choices were made on the even-numbered photos and checked on the odd ones.

## Results

Even photos (250, where the choices were made):

| Variant | SAD | MSE |
|---|---|---|
| base (1.8) | 54.86 | 59.76 |
| flip | 53.68 | 56.72 |
| zoom | 54.22 | 59.57 |
| flip+zoom | 51.85 | 54.78 |
| colour | 54.66 | 59.64 |
| flip+zoom+colour | 51.63 | 54.64 |
| flip+zoom, radius 6 / 24 / **36** / 48 ‰ | 52.51 / 50.83 / **50.43** / 50.60 | 55.88 / 52.87 / **51.62** / 51.03 |
| flip+zoom, curve 0.1–0.9 / 0.3–0.7 | 52.84 / 51.43 | 52.68 / 57.26 |
| flip+zoom, ε 0.0001 / 0.01 | 51.83 / 52.15 | 54.79 / 55.02 |

Shipped: flip + zoom + radius 36 ‰ (the colour filter added little for its cost). On the odd photos
(250, not used for choosing): **SAD 51.63 → 49.69 (−3.8 %), MSE 54.94 → 50.04 (−8.9 %)**. By kind
(SAD, 1.8 → 1.9): animals 49.1 → 47.6, furniture 54.8 → 49.7, plants 54.1 → 49.7, transparent things
109.7 → 103.4, fruit 16.4 → 15.4, toys 32.9 → 33.2, portraits 42.8 → 43.5.

Cost: the model runs two to four times instead of once, so a cut-out takes longer: about 1.4 s
instead of 0.5 s per photo (1024 px) on the build machine's CPU (`run.sh … app,base all 40`, which
also prints the time); not timed on a phone, where both will be slower. The wider filter costs
nothing extra (box filters).
