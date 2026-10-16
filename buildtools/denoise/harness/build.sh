#!/bin/bash
# Compiles the app's RAW codecs and denoisers with the harness (DENOISE_WORK: where to build).
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${DENOISE_WORK:-$R/buildtools/dl/denoise}/harness; rm -rf "$W/classes"; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
OCV=$(ls $TC/test/opencv-*.jar)
KC="java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn"
K=$R/app/src/main/kotlin/com/localmediatools
SRC="$K/codec/raw/*.kt"
[ -d "$K/denoise/core" ] && SRC="$SRC $K/denoise/core/*.kt"
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" -d "$W/classes" $SRC "$H"/*.kt
printf '#!/bin/bash\njava -Xmx6g -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" > "$W/run.sh"
chmod +x "$W/run.sh"
