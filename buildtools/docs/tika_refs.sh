#!/bin/bash
# Tika's text of every corpus file (the reference compare.py measures against), in <work>/tika.
# Batch mode per folder first; files a batch skipped (it stops on some broken files) one by one.
# Usage: buildtools/docs/tika_refs.sh [work dir]   (default buildtools/dl/docs)
R=$(cd "$(dirname "$0")/../.." && pwd)
W=${1:-$R/buildtools/dl/docs}
cd "$W" || exit 1
for d in corpus/poi/test-data/document corpus/poi/test-data/spreadsheet corpus/poi/test-data/slideshow corpus/govdocs; do
  o=tika/${d#corpus/}; mkdir -p "$o"
  timeout 3600 java -Xmx4g -jar tools/tika-app.jar -i "$d" -o "$o" -t -numConsumers 3 > /dev/null 2>&1
done
while read f; do
  o=tika/${f#corpus/}.txt
  [ -e "$o" ] && continue
  mkdir -p "$(dirname "$o")"
  timeout 120 java -Xmx2g -jar tools/tika-app.jar -t "$f" > "$o" 2>/dev/null || [ -s "$o" ] || rm -f "$o"
done < all.lst
find tika -name '*.txt' | wc -l
