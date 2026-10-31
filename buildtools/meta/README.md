# Measuring the metadata viewer

The readers in `app/src/main/kotlin/com/localmediatools/meta/core` are plain Kotlin, so they run on a
desktop JVM. These scripts run them on real files and compare every field with ExifTool (12.76), the
reference program for metadata.

| Script | What it does |
|---|---|
| `harness/build.sh` | Compiles the readers with `Harness.kt`; `run.sh < list` reads each file named on standard input and prints its fields as JSON lines. |
| `compare.py` | For each tag ExifTool shows (`exiftool -j -a -G1 -n`), whether the app shows the same name and the same value; per group, with `--phone` for the kinds of files people keep on phones, `--file NAME` for one file's differences, `--missing N` / `--wrong N` for the commonest gaps. Camera makers' own notes and ExifTool's derived values are listed apart: the app doesn't decode maker notes. |
| `make_fixtures.sh` | The test files in `app/src/test/resources/meta`, with `make_pdf.py` for the AES-256 PDFs. |

Results (1.12.0):

| Files | Count | Names found | Values equal |
|---|---|---|---|
| ExifTool's own test files, phone kinds (photos, RAW, videos, sound, documents) | 81 | 87.8 % | 86.0 % |
| Real documents (govdocs1 and Apache POI: Word, Excel, PowerPoint, PDF, RTF, HTML, OpenDocument) | 2,393 | 97.1 % | 96.1 % |
| Videos and sound made by ffmpeg (MP4, MOV, MKV, WebM, AVI, MP3, M4A, FLAC, Ogg, Opus, WAV) | 18 | 96.1 % | 95.5 % |
| The real-people test photos | 94 | 98.9 % | 98.9 % |

ExifTool's test files are made to cover odd corners (several metadata kinds stuffed into one file,
old camera formats), so they score lowest. What the app leaves out on purpose: the camera makers'
own notes (each maker's private format), technical tables (strip offsets, colour lookup tables, ICC
profiles inside PDFs) and a few rare containers (CIFF, FlashPix images, SPIFF).
