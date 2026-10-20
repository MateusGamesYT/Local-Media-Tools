# Measuring the file reader

The readers in `app/src/main/kotlin/com/localmediatools/docs/core` (Word, Excel, PowerPoint,
OpenDocument, RTF, EPUB, HTML, CSV, text) are plain Kotlin, so they run on a desktop JVM. These scripts
run them on about 2,000 real files and compare what they show with two independent programs.

| Script | What it does |
|---|---|
| `fetch_corpus.sh` | Downloads the corpus into `buildtools/dl/docs` (not in git): Apache POI's test files (Word, Excel and PowerPoint files of every age, many of them made to break readers; Apache-2.0) and thread 0 of govdocs1 (about a thousand files from U.S. government web servers: DOC, XLS, PPT, HTML, RTF, text, CSV, XML), plus Apache Tika 2.9.2, checked against Maven Central's SHA-1. |
| `tika_refs.sh` | Tika's text of every file: the reference for the text comparison. |
| `lo_refs.sh convert` | The government documents re-saved by LibreOffice as DOCX, ODT, XLSX, ODS, PPTX and ODP (45 of each), so the newer formats are measured on real content too. |
| `lo_refs.sh csv` | Every sheet of every spreadsheet the readers opened, as LibreOffice shows its cells (US English), for the cell-by-cell check. |
| `harness/build.sh` | Compiles the app's readers with `Harness.kt`; `run.sh <out dir> - < list` reads each file (60 s limit per file) and writes its text and, for workbooks, every cell. |
| `compare.py` | Per format: files opened, and how many of Tika's words the app shows (recall) and how many of the app's words Tika has (precision); `--alpha` counts words of three or more letters, `--worst N` lists the lowest files. |
| `missing.py` | The words one file's two readings don't share. |
| `cells.py` | Every cell LibreOffice shows against the app's text for the same sheet, row and column; differences in formula cells (LibreOffice recalculates them, so TODAY() and friends change) and in General numbers (LibreOffice shows up to 15 digits, Excel and the app at most 11 characters) are counted apart. |
| `make_fixtures.sh` | The test documents in `app/src/test/resources/docs`, with their credits. |

Tika's text isn't the truth either: it also reads embedded objects, chart data, notes and handout
masters, document properties and picture file names, which a reader doesn't show on the page. So
precision is the stricter number (the app showing text that isn't in the file would be a bug), and a
low recall is a pointer to look at a file, not a verdict. Every gap looked at is described in the
README's "What's new in 1.11.0".
