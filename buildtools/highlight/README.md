# Your own song in the highlight video: how it was measured

When you pick a song for the highlight video, the app (`highlight/core/Song.kt`) does four things:

- **Beat:** finds the song's beat, so the cuts fall on it as they do with the app's own music.
- **Start:** skips silence at the start and begins on a beat.
- **Repeats:** if the song is shorter than the video, repeats it on whole bars with a crossfade. The
  song's ending (a fade-out or a last held chord) is not repeated.
- **Level:** brings the song to the level of the app's own music (RMS 0.12; its pieces measure
  0.10–0.13). It is ducked under the clips' own sound like that music.

How the beat is found:

1. An onset envelope: rises of loudness in a low band (kick, bass), a high band (hi-hats, snares) and
   overall, over 85 ms windows every 10.7 ms.
2. Its autocorrelation over 60–200 BPM, weighted towards 120 BPM (the prior of D. Ellis, "Beat tracking
   by dynamic programming", 2007). Each tempo is scored with two, three and four beats on as well, so a
   dotted or triplet pattern isn't taken for the beat.
3. The period and phase that line the envelope's peaks up over the analysed stretch.

A song with no real loudness attacks, or no clear peak, gets no beat. The video then keeps its own
half-second rhythm.

## Tests on music of known tempo

`HighlightSongTest` uses the app's own generated music (120, 92 and 76 BPM, three pieces each). All
nine get their tempo: seven exactly, two at double (76 → 152, whose beats include the true ones). Each
starts within 10 ms of a beat.

The same test checks four more things:

- leading silence is skipped;
- a 9-second song repeats under a 25-second video on whole bars, without gaps, at the target level;
- a held chord gets no beat;
- the level the app's own music has.

## Real songs

`song_eval.py` runs the app's code (through `harness/run.sh`) on real songs from the Free Music
Archive (`fma_tracks.py`: Creative Commons licences per track, many genres, 30-second clips). Nobody
has labelled these songs' tempos, so two independent, widely used estimators are the reference:
librosa's beat tracker and Essentia's RhythmExtractor2013. Where they agree, their tempo is taken as
right.

The confidence threshold (0.08) was chosen on the first 200 clips of shard 0 and checked on the next
200:

| Songs where the references agree | Exact | Double or half | No beat found | Other |
|---|---|---|---|---|
| Chosen on (200 songs; 137 agree), at 0.12 | 105 | 5 | 12 | 15 |
| **Checked on (200 other songs; 135 agree), at 0.08** | **108 (80 %)** | 13 (10 %) | 6 (4 %) | 8 (6 %) |

Against Essentia alone on the checking songs, librosa matches its tempo in 122 of 200 and the app in 114.
The app gives fewer other tempos (30 against librosa's 65) because it reports no beat for 30 hard
songs (Experimental, Noise, Collage). Then the cuts keep their own pace rather than following a wrong
beat.

## Commands

```
HIGHLIGHT_WORK=$W buildtools/highlight/harness/build.sh
python3 buildtools/highlight/fma_tracks.py $W/fma 0
python3 buildtools/highlight/song_eval.py $W/harness/run.sh $W/fma $W/work
```
(`song_eval.py` reads `credits.tsv`; trim it to the clips you want. It needs librosa and Essentia.)
