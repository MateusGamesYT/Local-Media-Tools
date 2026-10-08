# Gallery recognition: how the shipped files were made

The gallery's recognition files in `app/src/main/assets/` come from these scripts:

| File | What it is |
|---|---|
| `models/efficientdet_lite2_int8.tflite` | EfficientDet-Lite2 (COCO, 90 classes), MediaPipe's int8 export, unchanged |
| `models/scene_effnetv2_b3_21k.tflite` | EfficientNetV2-B3 pre-trained on ImageNet-21k (Google AutoML), feature backbone only; weights stored as int8 per output channel, all maths in float |
| `gallery/scene_head.bin` | the 21,843-class output layer in 4 bits (scale per 64 inputs), plus linear "probes" for scenes ImageNet-21k has no class for |
| `gallery/categories.tsv` | the searchable categories: detector classes, classifier classes (WordNet subtrees), probe, and a calibrated threshold for each |

Data (not in git): Open Images V7 human-verified image-level labels and images (validation split
for calibration, a slice of the train split for the probes), ImageNet-21k synset list, WordNet 3.0.
Put them in `buildtools/dl/{oi,in21k,wn}`; scripts read and write `buildtools/gallery/{models,meta,out,data}`.
Python 3.12 with TensorFlow 2.16.1 (the app's TFLite runtime version), NumPy, SciPy and Pillow.

## Steps

1. `convert_21k.py` — builds EfficientNetV2-B3-21k from the AutoML checkpoint (EMA weights), exports
   the backbone to TFLite (float16 weights) and the head to `.npy`. `int8_weights.py` then stores the
   convolution weights as int8 per output channel (they still go through DEQUANTIZE, so TFLite and
   XNNPACK compute in float). `anchors.py` / `meta.py` read the detector's anchors and labels.
2. `oi_pick.py`, `oi_train_pick.py`, `shrink.py` — choose and downscale the evaluation and probe images.
3. `det_run.py`, `feat_run.py` — run the detector (letterboxed, like the app) and the backbone
   (224/256 centre crop, like the app) over the images.
4. `probes.py` — logistic-regression probes on train-split features (L2 chosen by 3-fold CV).
5. `oi_cats.py` (with `HEAD_ASSET=…/scene_head.bin` to score with the shipped 4-bit head) — per photo
   and category: detector score, summed classifier probability over the category's WordNet classes.
6. `oi_report.py <scores> 0.9|0.8 <probes>` — for each category, the thresholds (one per source,
   combined with OR) with the best F0.5 at the target precision on verified labels, and 2-fold
   cross-validated precision and recall so the reported numbers aren't fitted to themselves.
7. `final_select.py` — which categories ship (see its docstring), and the table in
   `docs/gallery-calibration.md`.
8. `build_assets.py` writes the four files; `golden.py` writes the JVM test's reference scores
   (`app/src/test/resources/gallery/scene_golden.bin`), which the app's Kotlin code must reproduce.

Checks used to choose the formats (`tag_agree.py`, `head_4bit.py`, `head_prune_eval.py`): on 2,000
validation photos the int8-weight backbone gives the same tags as float32 for 95.5% of photos
(full int8 with quantized activations: 91.4%), so the thresholds were calibrated on the int8-weight
model's own outputs. The 4-bit head keeps the full softmax; pruning classes saved little and moved
more tags.

`app_parity.py` reproduces the app's own preprocessing (decode at up to 1,600 px, its crop and
letterbox arithmetic) and checks the final tags against the calibration path. With 1.4.0's resizing
(2×2 box halvings plus plain bilinear) 93.0 % of 2,000 photos got identical tags; since 1.4.1 the app
resizes exactly like Pillow (`PilResample`, checked bit for bit by a JVM test) and all 2,000 do.
Not covered: photos larger than 1,600 px are first decoded smaller by Android itself.

People grouping thresholds were tuned with `face_embed.py` / `face_analyze.py` / `cluster_proto.py`
on the BIWI Kinect Head Pose database (not redistributable; only the measured results are used):
the same YuNet + SFace pipeline as the app, clustering several hundred faces of 16 people.
`lbp_eval.py` measured the classical fallback descriptor.
