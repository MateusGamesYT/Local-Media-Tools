# Background music: MusicVAE trio, decoded on the phone

The highlight video's music is written on the phone, from nothing but numbers: no recordings, no
samples, no voices. Two parts:

1. **The model** — Magenta's MusicVAE "trio_4bar" (`trio_4bar_lokl_small_q1`: melody, bass and drums,
   4 bars, a strong prior so random codes give musical results; 8-bit weights). Published by the
   magenta-js project (Apache License 2.0, Google), see
   <https://github.com/magenta/magenta-js/tree/master/music/checkpoints>. Only the decoder is shipped
   (`app/src/main/assets/models/trio_decoder.bin`, 5.9 MB): the app samples codes from the prior and
   never encodes music. The app's Kotlin decoder (`music/core/TrioModel.kt`) is checked against
   magenta.js itself: on six codes it makes the same choice at all 384 steps × 3 tracks
   (`MusicTest.decodesExactlyLikeMagentaJs`, reference in `app/src/test/resources/music`).
2. **The arrangement and instruments** — `music/core/Composer.kt` samples eight 4-bar ideas, rates
   them by simple musical measures (in one key, a singable range, a bass line, a steady beat for the
   mood), keeps two, puts them in one key and arranges intro, verses, choruses and an outro to cover
   the video, with chords for a pad. `music/core/Synth.kt` plays the score with the app's own
   oscillators (plucked lead, electric piano or piano, pad, bass, a synthesized drum kit), a small
   reverb and a limiter. Instrumental by construction: there is no voice anywhere in the chain.

## Rebuilding the asset and the reference

```
# The checkpoint (weights_manifest.json + group files) from the magenta-js checkpoint bucket:
#   https://storage.googleapis.com/magentadata/js/checkpoints/music_vae/trio_4bar
python3 buildtools/music/convert_trio.py <checkpoint dir> app/src/main/assets/models/trio_decoder.bin

# Reference decodes from magenta.js (npm i @magenta/music@1.23.1 @tensorflow/tfjs@2.8.6),
# with the checkpoint served locally, e.g. python3 -m http.server --bind 127.0.0.1:
node buildtools/music/ref.js <node_modules> http://127.0.0.1:8765/trio_4bar ref.json 6
```

`ref.json` becomes `app/src/test/resources/music/magenta_reference.json`.

## Measured

On nine pieces (three moods × three seeds, 45 s each, JVM on the build machine): 96–100 % of note
time in the piece's key; peak 0.64 (−3.8 dBFS), RMS 0.09–0.13; most energy between 60 Hz and 2 kHz
(the bass carries overtones so it is heard on phone speakers). Writing a piece takes about 0.8 s and
rendering 45 s of stereo audio 0.4–0.8 s.
