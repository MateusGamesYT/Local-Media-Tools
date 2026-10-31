# Test files for the metadata viewer

Made for these tests by `buildtools/meta/make_fixtures.sh`; no photos of people, no third-party
content except where a line says so. The names and places in them are invented.

| File | How it was made | Licence |
|---|---|---|
| photo.jpg, photo.png | ffmpeg's built-in test pattern (`testsrc`), tags written by ExifTool 12.76 (camera, owner, place, time, keywords) | made for this project |
| clip.mp4, clip.mkv | ffmpeg test pattern and tone, with a title and a place (QuickTime key, 3GPP location box, Matroska tag) | made for this project |
| song.mp3, song.flac, song.opus, song.wav | ffmpeg tone, with tags and (MP3, FLAC) a small cover picture made from the test pattern | made for this project |
| aes256.pdf, password.pdf | `buildtools/meta/make_pdf.py`: one-page PDFs protected with AES-256 (PDF 2.0), the first opened without a password, the second only with one | made for this project |
| restricted.pdf | `docs/boreas.odt` (NASA BOREAS report, U.S. government work, public domain; see ../docs/CREDITS.md) exported by LibreOffice 24.2 with changes forbidden (RC4 128-bit) | public domain |
| props.docx | written by the script: a one-line Word file with its properties, including the hidden ones Office adds when a document is e-mailed | made for this project |
