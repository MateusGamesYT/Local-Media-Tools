# Audio core: checks against reference tools

The app's audio code in `app/src/main/kotlin/com/localmediatools/audio/core` (loudness meter,
resampler, FLAC and MP3 encoders) is plain Kotlin. These scripts compile it on the desktop and
compare it with FFmpeg, `flac`, LAME and shine. Nothing here ships in the app.

- `harness/build.sh` compiles the core with `harness/Harness.kt` into `buildtools/dl/audio/harness`
  (`run.sh resample|flac|mp3|loudness …`).
- `mp3_tables.py` writes `Mp3Tables.kt` (filter bank window, Huffman tables, band widths) from the
  standard's tables as transcribed in FFmpeg's source.
- `mp3_compare.py` encodes recordings with the app, LAME and shine at one bit rate, decodes them
  with FFmpeg (`-err_detect explode`: any bitstream error fails) and compares SNR, segmental SNR,
  noise per critical band, and a noise-to-mask ratio (NMR, after Brandenburg and Johnston) with the
  share of frames where noise rises above the mask.
- `preecho.py` measures pre-echo: the noise in the 15 ms before sudden attacks, against what the
  original had there.

Test recordings come from librosa's example data (`buildtools/dl/audio/src/librosa-data`, each with
its licence file): CC BY / CC0 music, speech, birdsong, whale song and drums.

## Results (October 2026)

- Loudness (EBU R128): integrated loudness within 0.1 LU of FFmpeg's `ebur128` filter.
- Resampler: 109–116 dB SNR on test tones, 123 dB alias rejection.
- FLAC: decodes bit-identically (`flac -t` passes); files a little smaller than `flac -5`.
- MP3 at 128 kbps, mean NMR in dB (lower is better), app / LAME: Lets Go Fishin' 5.9 / 6.4,
  Hungarian Dance No. 5 1.0 / 2.8, robin −2.9 / −2.8, Vibe Ace −6.1 / −1.6, Sugar Plum Fairy
  −1.4 / 3.3, humpback −6.6 / −3.0, trumpet −9.8 / −5.1, drum loop −15.1 / −7.4. At 192 kbps the app
  is ahead on 7 of 8 (behind on the robin), at 64 kbps about even.
- MP3 pre-echo (noise before attacks, 90th percentile; lower is better), app / LAME: accelerating
  snare −16.5 / −14.1 dB, drum loop −26.3 / −15.1 dB.

These are measurements, not listening tests.

What changed the MP3 results most: switching to short blocks at attacks; spending bits the bit
reservoir would otherwise throw away; and an outer quantisation loop that refines the bands with the
worst noise-to-mask ratio first and keeps the result with the lowest worst-band ratio (refining
every band over its mask at once mostly shifts the global gain, so the noise stops following the
masking when bits run short).
