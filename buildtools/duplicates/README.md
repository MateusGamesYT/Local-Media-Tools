# Duplicate finder: how its rules were measured

The rules in `app/src/main/kotlin/com/localmediatools/vision/core/Duplicates.kt` are checked on real
photos: Open Images V7 photos from Flickr, all under CC BY 2.0. Scripts and the labels are here;
downloads and computed features go to `buildtools/dl/duplicates` (or `DUP_WORK`). Python 3.12 with
NumPy, Pillow, OpenCV 4.11 and TensorFlow 2.16.1 (for the app's image embedder, MobileNet-V3 small).

## The test set

1. `pick.py photos.json` — 450 **runs**: photos one photographer took one after another, found by
   camera file names in the titles (IMG_4796, IMG_4797…), at most one run per photographer; and a
   pool of 1,500 unrelated photos (one per photographer). `fetch.py photos.json` downloads them.
2. `sheets.py photos.json` — contact sheets; every run was labelled by eye (`labels.json`): which
   photos are the same moment (bursts, retakes with nearly the same framing) and whether the other
   shots are "related" (the same place or subject from another angle). 84 same-moment pairs,
   375 related pairs, 135 other pairs.
3. `pick_hard.py photos.json extra.json` — 4,500 more unrelated photos from the Open Images
   validation set (the gallery calibration's downloads), 1,905 of them with human labels for what
   fools duplicate finders: screenshots, documents and text, night and dark shots, skies, clouds,
   snow, black-and-white, patterns, close-ups.
4. `features.py` — what the app computes from each photo's thumbnail: difference hash, embedding,
   layout (`Duplicates.grid`) and contrast; plus re-saved copies of 300 pool photos (half size,
   heavy JPEG, a 3 % crop, brightened, with screenshot bars). `variants.py` makes the copies.
5. `library.py` — a simulated camera roll of all 8,466 photos with capture times: each run's shots
   3–60 s apart; unrelated photos in outings minutes apart; the hard photos in quick sequences of
   their own kind, 5–40 s apart (screenshotting several chats, photographing pages one after
   another); a fifth of the photos and half of the copies without any capture time.

## Choosing the rules

- `signals.py`, `tune.py` — how each signal is distributed per kind of pair, and how many of the
  18 million unrelated pairs a rule lets through. Alone, the embedding calls two skies, two pages
  or two night shots alike (cosine ≥ 0.82 for 15 unrelated pairs, ≥ 0.70 for 115); together with
  the layout (or matching features, `FeatureCheck.kt`) almost none pass.
- `rules.py` — the ORB feature check; `evaluate.py` — 1.6.0's rules and the new ones in Python,
  with the halves of the runs (even / odd numbers) compared.
- `harness/build.sh` + `final_eval.py` — the final numbers through the app's own Kotlin code:
  1.6.0's `Duplicates.kt` (from git) and the new one with `FeatureCheck`, on the same export.

| Through the app's code (8,466 photos) | 1.6.0 | 1.7.0 |
|---|---|---|
| Groups with unrelated photos | 4 (53 pairs, largest group 13) | **0** |
| "Related" shots grouped (other angle, same place) | 36 pairs | 16 pairs |
| Same-moment pairs found | 65 of 84 | 51 of 84 |
| Re-saved copies found (small / heavy JPEG / crop / brightened / screenshot) | 300 / 300 / 299 / 300 / 284 | 300 / 300 / 297 / 300 / 278 |
| Separate shots treated as copies of one picture (suggested for the trash by default) | every group's other photos were suggested | **0** (4 same-moment pairs before the "taken seconds apart" rule) |

Both halves of the runs show the same: no unrelated groups with the new rules, 21 + 30 same-moment
pairs (1.6.0: 27 + 38). The trade-off is deliberate: a photo grouped with an unrelated one is the
mistake that costs trust (or a photo); a missed burst only costs a little space.

`final_eval.py` also counts the pairs the new rules put in one *picture* (copies of each other:
the best copy stays, the others are suggested): 2,142 copy pairs and nothing else.

`make_fixture.py` makes the JVM test's photos (`app/src/test/resources/duplicates`, with credits).
