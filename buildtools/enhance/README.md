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

## Results (mean CIEDE2000 to expert C; lower is closer)

The model was fitted on 320 training photos. Its choices (below) were compared on 154 test photos (two
shards of FiveK's test split); the final model was then checked once on 77 more test photos that
had not been looked at before (a third shard).

| | 154 test photos: ΔE00 to expert C | change to an expert's finished version | 77 untouched photos: ΔE00 | change to finished |
|---|---|---|---|---|
| Unchanged photo | 10.82 | — | 10.53 | — |
| 1.8 rules | 10.88 | 7.59 | 11.03 | 8.40 |
| Learned model (1.9) | **8.89** | **4.89** | **9.21** | **5.59** |

The 1.8 rules took photos slightly further from the expert than leaving them alone, on both sets. The
model is closer than the rules (by more than 0.25) for 108 of the 154 photos and further for 34 (56 /
14 of the 77). By scene on the 154 (unchanged / 1.8 rules / model): portrait (35) 11.23 / 11.28 /
**8.42**, landscape (32) 10.05 / 10.52 / **8.32**, animal (7) 12.31 / 11.10 / **10.49**, city (37) 10.20 /
9.70 / **8.41**, night (8) 10.32 / 9.69 / **8.79**, other (35) 11.56 / 12.27 / **10.11**.

Brightness (mean L*, input → model / expert): dark photos (L* 20) → 26.9 / 28.4, (L* 29) → 36.4 / 36.7,
(L* 39) → 44.0 / 44.3; bright photos (L* 64) → 59.1 / 57.6. On the 77 untouched photos: L* 27 → 34.9 /
34.6, 43 → 45.9 / 46.1, 65 → 58.7 / 61.1.

**Leaving good photos alone.** FiveK's as-shot photos are flat; phone photos usually come out of the
camera already finished. Fitted on the as-shot photos alone, the model also changes finished photos a
lot. So the experts' versions are added to the fitting as photos that need no change (harness
`finished`, every slider 0), at half the weight of the real pairs. For exposure and brightness they
count only a fifth of that: they include night photos the experts kept dark, and at full weight the
model brightened dark as-shot photos clearly less than the experts did (L* 20 → 25.2 instead of 28.4).
The choices, on the 154 photos (ΔE00 / change to finished): without finished photos 8.89 / 5.71; at half
weight for everything 9.14 / 3.35; for everything but brightness 8.89 / 6.00; with a fifth for brightness
(shipped) 8.89 / 4.89; with half for brightness 8.99 / 4.03 — every setting beats 1.8 on both.

**White balance.** Warmth and tint are predicted only from the measured colour of the light (near-
neutral mid-tones), the skin colour of the faces and the scene; other measurements (saturation,
darkness) go with casts in these photos without causing them, and let a strongly tinted photo fool
the model (no loss: 9.21 / 3.26 with this restriction, 9.22 / 3.27 without, in an earlier round). The
experts did *not* warm photos whose neutral tones measure blue (mostly sky, snow and water, where the
cool light is real; best warmth for those −0.07 on average), but skin should look warm: with the
faces' skin colour as a feature (`Stats.faceWarm`) a portrait with a cold cast is warmed (VisionCoreTest's
darkened, blue-tinted portrait), and portraits came closer to the expert (8.88 → 8.65 in that round).
Squared brightness terms added nothing (9.137 → 9.130) and were left out.

Not measured here: documents (they keep the 1.8 rules), food and plants (scored as a general photo and
as a landscape), and photos from a real phone camera rather than FiveK's DSLRs.
