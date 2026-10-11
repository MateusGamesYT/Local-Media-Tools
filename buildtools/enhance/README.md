# Auto enhance: how its settings are chosen and measured

`app/src/main/kotlin/com/localmediatools/vision/core/AutoEnhance.kt` picks the photo editor's
sliders for a photo (exposure, brightness, contrast, highlights, shadows, warmth, tint, vibrance,
saturation). Since 1.9.0 the choice is learned from expert retouching instead of hand-written rules
(the rules stay for documents, which the expert set has none of).

## The photos

MIT-Adobe FiveK (Bychkovsky et al., CVPR 2011): 5,000 photos, each retouched by five experts; expert
C's versions are the usual reference. The copy on Hugging Face (`logasja/mit-adobe-fivek`, config `c`)
has the as-shot photo and expert C's retouch. Nothing from it is in the app or this repository.

- `fetch.sh` downloads the shards one at a time and `fivek_extract.py` turns each into pairs: both photos
  at 512 px on the long side, in sRGB, the as-shot photo turned to the expert's orientation.
- Training: 4 shards of the `train` split; testing: 2 shards of the `test` split (photographs the
  model never saw).

## Fitting

1. `harness/build.sh`, then `run.sh fit <pairs> <prefixes> <yunet.onnx> out.csv` — through the app's
   own Kotlin code: for every photo, what `AutoEnhance.measure` sees (tones, colour, faces found by
   the app's YuNet), and the slider values that bring the app's `ColorPipeline` closest to the
   expert (coordinate descent on the mean CIEDE2000 difference, at 160 px).
2. `fit_model.py train.csv…` — ridge regression from those measurements (and the scene) to the
   sliders, the strength of the regularisation chosen by 5-fold cross-validation; it prints the
   `MODEL` table for `AutoEnhance.kt`.
3. `run.sh restat <pairs> <prefixes> <yunet.onnx> in.csv out.csv` redoes only the measurements of an
   earlier fit (after `AutoEnhance.measure` learned to measure more), keeping the fitted sliders.
4. `run.sh eval <pairs> <test prefixes> <yunet.onnx>` — the unchanged photo, 1.8's rules and the
   model, each compared with the expert on the test photos.

The scene comes from FiveK's labels in the harness (night, nature → landscape, animals, man-made
outdoors → city); in the app it comes from the on-device classifier, and faces or a dark photo
override it the same way in both.

## Results (test photos, mean CIEDE2000 to expert C; lower is closer)

154 test photos (two shards of FiveK's test split, never used for fitting); the model was fitted on
320 training photos.

| | ΔE00 to expert C | Expert's version enhanced again (how much it changes) |
|---|---|---|
| Unchanged photo | 10.82 | — |
| 1.8 rules | 10.88 | 7.59 |
| Learned model (1.9) | **9.14** | **3.35** |

By scene (ΔE00; unchanged / 1.8 rules / model): portrait (35) 11.23 / 11.28 / **8.65**, landscape
(32) 10.05 / 10.52 / **8.52**, animal (7) 12.31 / 11.10 / **10.82**, city (37) 10.20 / 9.70 / **8.64**,
night (8) 10.32 / 9.69 / **9.28**, other (35) 11.56 / 12.27 / **10.35**. On average the 1.8 rules took
photos slightly further from the expert than leaving them alone; the model brings 105 of the 154
closer than the rules did (by more than 0.25) and 40 further.

**Leaving good photos alone.** FiveK's as-shot photos are flat; phone photos usually come out of the
camera already finished. Fitted on the as-shot photos alone, the model also changed finished photos
a lot. So the experts' versions are added to the fitting as photos that need no change (harness
`finished`, every slider 0), with half the weight of the real pairs. The weight trades the two
columns (measured before the skin-colour feature below): 0 → 8.89 / 5.71, 0.25 → 9.08 / 3.99,
0.5 → 9.22 / 3.27, 1 → 9.44 / 2.55 — every setting beats 1.8 on both; 0.5 is the middle of the range.

**White balance.** Warmth and tint are predicted only from the measured colour of the light (near-
neutral mid-tones), the skin colour of the faces and the scene; other measurements (saturation,
darkness) go with casts in these photos without causing them, and let a strongly tinted photo fool
the model (9.21 / 3.26 with this restriction, 9.22 / 3.27 without). The experts did *not* warm photos
whose neutral tones measure blue (mostly sky, snow and water, where the cool light is real), but skin
should look warm: with the faces' skin colour as a feature (`Stats.faceWarm`), a portrait with a cold
cast is warmed (VisionCoreTest's darkened, blue-tinted portrait) and portraits come closer to the
expert (8.88 → 8.65).

Not measured here: documents (they keep the 1.8 rules), food and plants (scored as a general photo
and as a landscape), and photos from a real phone camera rather than FiveK's DSLRs.
