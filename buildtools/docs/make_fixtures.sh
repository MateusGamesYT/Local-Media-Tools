#!/bin/bash
# Builds app/src/test/resources/docs: real documents for the reader tests, with their credits.
# Needs the corpus (fetch_corpus.sh) and LibreOffice (soffice) for the re-saved copies.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
W=${1:-$R/buildtools/dl/docs}
D=$R/app/src/test/resources/docs
mkdir -p "$D"
P=$W/corpus/poi/test-data; G=$W/corpus/govdocs
cp "$G/469/469113.doc" "$D/boreas.doc"
cp "$G/723/723052.xls" "$D/sugar.xls"
cp "$G/327/327809.pdf" "$D/glnpo.pdf"
cp "$P/document/SampleDoc.docx" "$P/spreadsheet/SampleSS.xlsx" "$P/slideshow/SampleShow.pptx" "$P/slideshow/SampleShow.ppt" "$D/"
cp "$P/document/password_tika_binaryrc4.doc" "$P/spreadsheet/protected_passtika.xlsx" "$D/"
lo() { timeout 180 soffice -env:UserInstallation=file://$W/lo-fixtures --headless --norestore --convert-to "$1" --outdir "$D" "$2" >/dev/null 2>&1; }
for t in docx odt rtf epub; do lo $t "$D/boreas.doc"; done
for t in xlsx ods; do lo $t "$D/sugar.xls"; done
lo 'csv:Text - txt - csv (StarCalc):44,34,76,1,,1033,false,true,true' "$D/sugar.xls"
lo odp "$D/SampleShow.pptx"
ls -la "$D"
