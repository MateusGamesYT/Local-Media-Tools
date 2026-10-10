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

## People: real photos (`people/`)

People grouping (`ClusterParams.SFACE` in `gallery/core/FaceClusters.kt`) is tuned and checked on
real photos of real people: Open Images V7 photos from Flickr, all under CC BY 2.0. Scripts and the
verified labels are in `people/`; downloads and intermediate files go to `buildtools/dl/people`
(or `PEOPLE_WORK`). Python 3.12 with NumPy, Pillow and OpenCV 4.11 (`opencv-python`).

1. `strip_meta.py out.csv.gz < image_ids_and_rotation.csv` — the metadata (title, author, licence,
   rotation); keep the downloadable images (validation, test, boxable train).
2. `mine_names.py` → names that recur in titles by several photographers; `names.txt` the chosen 86.
   `collect.py` → photos whose title names them (statues, posters, wax figures, costumes, look-alikes,
   screens… excluded by title); `photos.json` the 1,233 used (at most 40 a person, spread over
   photographers).
3. `fetch.py photos.json` (rotation applied, at most 2,048 px); `extract.py photos.json faces.json` —
   the app's face pipeline (`GalleryFaces`) in Python: 4,578 faces with embeddings.
4. `propose.py` + `sheets.py` → each person's face in each photo, checked by eye on contact sheets:
   `labels.json`, 802 faces of 78 people, tagged H (headwear), G (glasses), M (heavy make-up),
   E (strong expression). Excluded: wax figures, posters, impersonators, unclear faces, and one
   account reposting magazine shoots. The 3,776 other faces are kept as strangers.
5. `harness/build.sh` compiles the app's `gallery/core` with `Harness.kt`; `evaluate.py` has the
   metrics (B-cubed precision/recall over verified faces, share of each person's faces in their main
   group, per condition, strangers joining someone) and a Python port of the grouping that gives the
   same groups as the Kotlin code.
6. `search.py A|B <n>` — random search tuned on one half of the people (39), checked on the other;
   `final_eval.py '<params>'` — 1.4.1's rules vs new ones through the app's code (`ClusterParams`
   cites the results).

| Through the app's code | 1.4.1 recall / main group | 1.5.0 recall / main group | wrong faces |
|---|---|---|---|
| Half A (tuning) | 0.70 / 75 % | 0.83 / 86 % | 0 → 0 |
| Half B (held out) | 0.63 / 77 % | 0.81 / 87 % | 0 → 0 |
| All 78 people at once | 0.65 / 75 % | 0.81 / 87 % | 2 → 6 of 802 |

Findings that shaped the rules: SFace separates these people well (97 % of faces are closer to
their own person's mean face than to anyone else's); the averaged rules were the problem, because
people photographed in varied conditions agree less with each other on average. Comparing a face with
a group's mean face fixes that, but lets blurry strangers in; a gate on the detector's score (0.85)
for faces that may only join a group keeps them out about as well as the embedding's magnitude would
(which would need every photo analysed again). The mean faces of two different people reached 0.56
(merging starts at 0.65). The six wrong faces were mostly faces with dark sunglasses (and hats).

### Learning from naming (measured after 1.7.0; nothing shipped)

`learn_export.py` + `harness/LearnHarness.kt` (`harness/build_learn.sh`) play a user in the app's own
grouping code: each round the user names every person's biggest unnamed group and takes out the faces
in it that aren't them, then the library is grouped again. With the shipped rules, one round puts
88 % (half A) / 83 % (half B) of each person's faces under their name, with 1 / 0 faces of other people
and 6 / 13 strangers' faces among them; further rounds add almost nothing, because what is left are
single faces that never form a group to name (half A: 11 the detector is unsure of, 26 small or
turned, 6 clear ones). Three ways of learning from the user were tried, none worth shipping:

- **Confirmed faces as examples** (a face counted for a person when its two best matches among the
  person's confirmed faces are close enough, faces said not to be them counting against): no change
  at all. A person's mean face matches their own faces better than their two closest other faces do
  (median 0.75 against 0.70; 10th percentile 0.57 against 0.52), and none of the 38 faces below the
  mean face's threshold were closer than 0.50 to two of the person's faces.
- **A comparison learnt from the named people** (within-person whitening of the embeddings, learnt on
  one half, measured on the other): same-person pairs found at a 1 in 10,000 false-match rate
  0.386 → 0.391 (half B) and 0.651 → 0.674 (half A); at 1 in 1,000, 0.715 → 0.733 and 0.833 → 0.843.
  Too small and too uneven to be worth thresholds recalibrated on each phone.
- **Lower thresholds for named people**: at most +1 point (0.878 → 0.888 on half A) while the strangers
  in named people went from 6 to 26 or more.

What these numbers point at is the descriptor: SFace finds only 39–65 % of same-person pairs at a 1 in
10,000 false-match rate on these photos. The next step is a stronger face model measured the same way.

7. `make_fixture.py <dir>` writes the app's test photos (`app/src/test/resources/people`): crops of
   63 photos (no edits, screen grabs or promotional reposts), the faces found with embeddings, labels
   and credits. `fixture_sheet.py` draws them for checking.

Earlier (1.4.0–1.4.1) the thresholds came from `face_embed.py` / `face_analyze.py` / `cluster_proto.py`
on the BIWI Kinect Head Pose database (not redistributable; only the measured results were used):
the same YuNet + SFace pipeline, clustering several hundred faces of 16 people. `lbp_eval.py`
measured the classical fallback descriptor.
