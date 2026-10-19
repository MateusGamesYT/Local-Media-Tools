#!/bin/bash
# LibreOffice (soffice) references, in the work dir:
#   convert  real government documents re-saved in the newer formats (corpus/converted/<ext>),
#            to measure the DOCX/ODT/XLSX/ODS/PPTX/ODP readers on real content, not only on test files;
#   csv      every sheet of each spreadsheet the harness opened, as LibreOffice shows its cells
#            (US English), for the cell-by-cell check of number and date formats (csvref/<n>).
# Usage: buildtools/docs/lo_refs.sh convert|csv [work dir]   (default buildtools/dl/docs)
R=$(cd "$(dirname "$0")/../.." && pwd)
W=${2:-$R/buildtools/dl/docs}
cd "$W" || exit 1
case "$1" in
convert)
  for pair in "doc docx" "doc odt" "xls xlsx" "xls ods" "ppt pptx" "ppt odp"; do
    set -- $pair
    mkdir -p corpus/converted/$2
    find corpus/govdocs -name "*.$1" | sort | head -45 | while read f; do
      timeout 120 soffice -env:UserInstallation=file://$PWD/lo-convert --headless --norestore --convert-to $2 --outdir corpus/converted/$2 "$f" >/dev/null 2>&1
    done
  done
  find corpus/converted -type f | sort > converted.lst ;;
csv)
  grep -P "\t(XLS|XLSX|ODS)\tok\t" "${3:-run2.tsv}" | cut -f1 | grep -v clusterfuzz | grep -v crash- > sheets.lst
  rm -rf csvref; mkdir -p csvref
  n=0
  while read f; do
    d=csvref/$n; mkdir -p $d
    LANG=en_US.UTF-8 timeout 120 soffice -env:UserInstallation=file://$PWD/lo-csv --headless --norestore \
      --convert-to 'csv:Text - txt - csv (StarCalc):44,34,76,1,,1033,false,true,true,false,false,-1' --outdir $d "$f" >/dev/null 2>&1
    echo "$n	$f"; n=$((n+1))
  done < sheets.lst > csvref.index ;;
esac
echo done
