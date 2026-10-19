#!/bin/bash
# Downloads the real documents the readers are measured on, and Apache Tika to compare with.
#   - Apache POI's test files (Apache-2.0): Word, Excel and PowerPoint files of every age, many
#     made to break readers.
#   - govdocs1 thread 0 (digitalcorpora.org, U.S. government documents, public domain): about a
#     thousand real files as people wrote them (DOC, XLS, PPT, HTML, RTF, text, CSV, XML).
#   - Tika 2.9.2 (Apache-2.0), checked against Maven Central's SHA-1.
# Usage: buildtools/docs/fetch_corpus.sh [work dir]   (default buildtools/dl/docs, not in git)
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
W=${1:-$R/buildtools/dl/docs}
mkdir -p "$W/tools" "$W/corpus"
cd "$W"
T=https://repo1.maven.org/maven2/org/apache/tika/tika-app/2.9.2/tika-app-2.9.2.jar
if [ ! -s tools/tika-app.jar ]; then
  curl -sS -o tools/tika-app.jar.part "$T"
  [ "$(curl -sS "$T.sha1" | cut -c1-40)" = "$(sha1sum tools/tika-app.jar.part | cut -c1-40)" ] || { echo "Tika checksum mismatch"; exit 1; }
  mv tools/tika-app.jar.part tools/tika-app.jar
fi
if [ ! -d corpus/poi/test-data ]; then
  rm -rf corpus/poi
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/apache/poi.git corpus/poi
  (cd corpus/poi && git sparse-checkout set test-data/document test-data/spreadsheet test-data/slideshow)
fi
if [ ! -d corpus/govdocs ]; then
  curl -sS -o corpus/thread0.zip https://digitalcorpora.s3.amazonaws.com/corpora/files/govdocs1/threads/thread0.zip
  mkdir -p corpus/govdocs.part && (cd corpus/govdocs.part && unzip -q ../thread0.zip)
  rm corpus/thread0.zip && mv corpus/govdocs.part corpus/govdocs
fi
(find corpus/poi/test-data -type f; find corpus/govdocs -type f \( -name "*.doc" -o -name "*.xls" -o -name "*.ppt" \
  -o -name "*.txt" -o -name "*.csv" -o -name "*.html" -o -name "*.rtf" -o -name "*.xml" \)) | sort > all.lst
wc -l < all.lst
