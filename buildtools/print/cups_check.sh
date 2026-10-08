#!/bin/bash
# Checks the app's printing against independent implementations from CUPS:
#  1. prints real photos (plain IPP and IPPS) to ippeveprinter, CUPS's reference IPP Everywhere printer;
#  2. converts the job it received with CUPS's own PWG raster filter (pwgtopdf) and renders the pages.
# Needs: ippeveprinter (cups-ipp-utils), cups-filters, poppler-utils, Avahi running (ippeveprinter
# registers with DNS-SD), and `build_apk.py --test-only` run once (for the compiled tests).
# Where the machine has no IPv6, a small preload library gives ippeveprinter a dummy IPv6 listener.
set -euo pipefail
R=$(cd "$(dirname "$0")/../.." && pwd); OUT=${1:-$(mktemp -d)}; PORT=${PORT:-8631}
SPOOL="$OUT/spool"; mkdir -p "$SPOOL" "$OUT/ssl"
PRELOAD=""
if ! python3 -c "import socket; socket.socket(socket.AF_INET6)" 2>/dev/null; then
  gcc -shared -fPIC -O2 -o "$OUT/noipv6.so" "$R/buildtools/print/noipv6.c" -ldl; PRELOAD="$OUT/noipv6.so"
fi
LD_PRELOAD=$PRELOAD ippeveprinter -p "$PORT" -K "$OUT/ssl" -k -d "$SPOOL" -f image/pwg-raster,image/urf,image/jpeg \
  -M EPSON -m "L3250 Series" -n localhost "Local Media Tools check" > "$OUT/ippeveprinter.log" 2>&1 &
EVE=$!; trap 'kill $EVE 2>/dev/null' EXIT
sleep 2
TC="$R/.toolchain"
CP="$R/build/test-classes:$R/app/src/test/resources:$TC/libs/kotlin-stdlib-2.3.21.jar:$(ls "$TC"/test/*.jar | tr '\n' ':')"
java -cp "$CP" -Dlmt.ippeve="ipp://127.0.0.1:$PORT/ipp/print" -Dlmt.ippeve.spool="$SPOOL" org.junit.runner.JUnitCore com.localmediatools.print.PrintCoreTest
for f in "$SPOOL"/*; do
  [ -s "$f" ] || continue
  /usr/lib/cups/filter/pwgtopdf 1 lmt check 1 "" < "$f" > "$f.pdf" 2>/dev/null
  echo "$(basename "$f"): CUPS pwgtopdf → $(pdfinfo "$f.pdf" | grep -E '^(Pages|Page size)' | tr -s ' ' | paste -sd ' ')"
  pdftoppm -r 30 -png "$f.pdf" "$f.page"
done
echo "Pages rendered by CUPS + Poppler: $SPOOL/*.page-*.png"
