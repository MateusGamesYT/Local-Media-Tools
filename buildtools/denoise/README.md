# The Denoiser: how it was built and measured

The Denoiser (app `tools/DenoiseJob.kt`) cleans photos, camera RAW files and videos, each in its own
format. Its settings are measured on real noise, never guessed. This folder holds everything used to
measure them. Downloads and work files go to a folder of your choice and are never committed.

| What | Code in the app | Measured with |
|---|---|---|
| RAW reading and writing in place | `codec/raw/` (TIFF, lossless JPEG, CR2, DNG, NEF, ARW) | `check_raw.py` |
| RAW denoising | `denoise/core/RawDenoiser.kt` | `raw_eval.py` |
| Photo denoising | `denoise/core/PhotoDenoiser.kt`, `NoiseEstimator.kt` | `photo_grid.py`, `photo_rule.py`, `clean_crops.py` |
| Video denoising | `denoise/core/VideoDenoise.kt` (GPU: `video/GlDenoiser.kt`) | `video_eval.py` |

`harness/build.sh` compiles the app's own `codec/raw` and `denoise/core` code with a small command-line
harness (`DENOISE_WORK=<dir> harness/build.sh` gives `<dir>/harness/run.sh`). The scripts drive that
harness, so they measure the code the app runs, not a copy of it. They need Python with numpy,
OpenCV, rawpy (LibRaw) and, for `check_raw.py`, ExifTool.

## Data

- **RAW files:** 18 files from [raw.pixls.us](https://raw.pixls.us) (CC0), from Canon, Nikon, Sony,
  Leica, Ricoh, Google, Samsung and Apple cameras and phones, at low ISO. Seven of the same site's
  high-ISO files supply real noise levels (ISO 3200–6400).
- **Phone noise:** [SIDD](https://www.eecs.yorku.ca/~kamel/sidd/) (Abdelhamed, Lin, Brown, CVPR 2018,
  MIT licence), Medium sRGB set. `sidd_fetch.py` takes 50 scenes (10 for each of the five phones,
  spread over their ISO settings) and keeps four 512 px crops of each noisy photo and its clean
  reference.
- **Clean photos with fine detail:** `clean_crops.py` develops the low-ISO RAW files and reduces them
  4× (averaging removes their noise), giving 54 crops. Many are full of grass, branches and bark.
- **Test fixtures:** `app/src/test/resources/raw` holds two small DNGs cut from raw.pixls.us files by
  `make_raw_fixture.py` (credits in `CREDITS.tsv`). The Pixel 4a one keeps two of the phone's own
  compressed tiles byte for byte.

## RAW files stay RAW

The sensor data is decoded, cleaned and encoded back into the same file. Everything else in the file
(previews, maker notes, EXIF) is kept byte for byte. New compressed blocks go where the old ones were
when they fit, otherwise at the end of the file with their offsets updated.

`check_raw.py` checked all 18 files against LibRaw 0.22.1 (rawpy) and ExifTool 13.10:

- **Decoding:** the app's decoded sensor data equals LibRaw's, value for value.
- **Unchanged rewrite:** the file comes back identical.
- **Changed data:** it reads back exactly. For Sony's lossy compressed ARW it reads back within the
  format's own rounding.
- **Development and metadata:** the written files develop in LibRaw, and ExifTool reads the same
  metadata with no new warnings.

Supported variants:

- CR2 (lossless JPEG, sliced);
- DNG (lossless JPEG tiles or strips, uncompressed 16-bit);
- NEF (lossless Huffman, 14-bit packed, uncompressed);
- ARW (Sony's ARW2 compression, uncompressed).

These are recognised and left as they are, with a message saying why: Canon sRAW/mRAW, lossy NEF,
linear (demosaiced) DNG, packed DNG, DNG with a linearization table, CR3, RAF, ORF and RW2.

## RAW denoising

The noise model (shot plus read noise, one line pooled over the four colour-filter sites) is measured
from the file itself. The data is variance-stabilised (generalised Anscombe transform), cleaned with
OpenCV's non-local means on the four sites at once, and transformed back with the exact unbiased
inverse. Clipped photosites are kept as they are.

`raw_eval.py` used low-ISO crops (1024², 2 per file) with Poisson-Gaussian noise added at seven real
high-ISO levels. Scores are PSNR on the linear data. Files alternate between a tuning half and a
checking half.

| Half | Noisy | Result |
|---|---|---|
| Tuning (7 files × 2 crops × 7 levels) | 41.49 dB | h = 1.2: 47.34 dB · **1.5: 47.74 dB** · 1.8: 47.34 dB · 2.1: 46.83 dB · 2.5: 46.21 dB |
| Checking (14 crops × 7 levels) | 42.05 dB | 1.5 with template 7 / search 21: 49.43 dB · **1.5 with 5 / 15: 49.52 dB** (a fifth faster) · 1.2: 48.29 dB · 1.8: 49.38 dB |

The app uses h 1.5, template 5, search 15.

Clean files are left alone. On whole low-ISO files with no noise added, the developed picture changes
by 37–59 dB (the worst 256-px tile of each file: 35–53 dB). That is mostly the files' own faint noise
being removed. No colour site's level moves by more than 0.15 of 1023, even in the deepest shadows.

## Photos

The filter is OpenCV's coloured non-local means (template 7, search 21), applied in strips with
enough context rows that strips give exactly the whole picture's result. Its strength comes from the
noise measured in the photo: the quietest tenth of its 8×8 blocks (Immerkær's estimate), in CIELAB
brightness and colour, pooled over nine 512 px tiles. Blocks touching black or white clipping are left
out.

`photo_grid.py` scored every crop at strengths 2–24 (and 28–48 for the crops that wanted the most;
that helped 5 of 58 crops by at most 0.3 dB, so 24 is the cap). `photo_rule.py` fits the rule on the
even scenes and checks it on the odd ones (noisy: 27.65 dB, best possible per crop: 35.02 dB):

| Rule | Odd scenes (not used for fitting) | Clean photos: worst change |
|---|---|---|
| Best single fixed setting (h 17) | 33.54 dB | — |
| h = 5 × noise level | 33.97 dB | **20.6 dB: grass and branches wiped out** |
| **With the fine-detail safeguard (adopted)** | 33.75 dB | 33.3 dB (5 of 54 crops below 35 dB, all fine on inspection) |

**The fine-detail safeguard.** In a photo of dense grass, the quietest blocks are detail, not noise.
The plain rule then applied strength 13–18 to clean photos and smeared them.

What gives detail away: noise has the same level across the picture, so its quietest blocks form a
tight cluster. The ratio of the 10 % to the 2 % quantile is 1.42 for pure noise, at most 1.64 for 90 %
of the noisy SIDD crops, and 1.7–4.8 for detailed clean photos. Above 1.5, the strength is scaled by
(1.5 / ratio)⁸ (`PhotoDenoiser.confidence`).

Three other tests were tried and rejected because they don't separate the two:

- gradient-based selection of weakly textured blocks (Liu et al., 2013);
- comparing energy at two scales;
- the shape and colour correlation of block statistics.

Real phone noise is spread over several pixels by the camera's processing, so on a single block it
looks just like fine detail.

## Videos

Each frame is blended with the previous result where the picture stands still, so noise averages out
over frames. Where it moves, it is smoothed within the frame by a small edge-preserving filter
instead. The motion and filter thresholds are set in units of the noise measured from three frames
(the photo estimator, safeguard included). The GPU shader follows `VideoDenoise.step` line by line.

`video_eval.py` builds 30-frame clips from SIDD's clean photos with real phone noise added: a still
scene, a slow pan, a fast pan, and an object crossing a still background. Scenes alternate between
tuning and checking. On the checking scenes (25 scenes × 4 motions):

| Setting (still, motion low/high, tolerance, spatial still) | Overall | Still | Fast pan | On the moving object |
|---|---|---|---|---|
| Noisy | 28.25 dB | 28.16 dB | 28.38 dB | 28.38 dB |
| First guess (0.25, 1.5 / 4, 2.5, 0.3) | 33.45 dB | 34.41 dB | 32.11 dB | 31.29 dB |
| Best on the tuning scenes (0.2, 2.5 / 6, 3, 0.45) | 34.61 dB | 36.21 dB | 32.63 dB | 31.05 dB |
| **Adopted (0.25, 2 / 5, 3.5, 0.5)** | 34.29 dB | 35.46 dB | 32.75 dB | 31.61 dB |

The adopted setting beats the first guess everywhere, including around moving things. The setting
that was best on the tuning scenes scores slightly higher overall but leaves more trailing behind
motion, which is the more visible fault.

## Commands

```
DENOISE_WORK=$W buildtools/denoise/harness/build.sh
python3 buildtools/denoise/check_raw.py $W/harness/run.sh <exiftool> $W/check <raw files>
python3 buildtools/denoise/raw_eval.py $W/harness/run.sh $W/raw <high-ISO dir> <low-ISO files> --half tune --settings "1.2,7,21;1.5,7,21;1.8,7,21"
python3 buildtools/denoise/sidd_fetch.py $W/sidd 10
python3 buildtools/denoise/photo_grid.py $W/sidd $W/grid.jsonl
python3 buildtools/denoise/clean_crops.py <low-ISO raw dir> $W/clean
python3 buildtools/denoise/photo_rule.py $W/sidd $W/grid.jsonl [$W/grid_ext.jsonl] $W/clean
python3 buildtools/denoise/video_eval.py $W/harness/run.sh $W/sidd $W/video check 0.25,2,5,3.5,0.5,1
python3 buildtools/denoise/make_raw_fixture.py <source.dng> <out.dng> <x> <y> <w> <h>
```
