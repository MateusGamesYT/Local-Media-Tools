#!/bin/bash
# Compiles the app's private-details finder (vision/core) with the harness, against the desktop
# OpenCV the unit tests use (PRIVACY_WORK: where to build).
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${PRIVACY_WORK:-$R/buildtools/dl/privacy}/harness; rm -rf "$W/classes"; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
CV=$(ls $TC/test/opencv-*.jar)
KC="java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn"
K=$R/app/src/main/kotlin/com/localmediatools
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar:$CV" -d "$W/classes" $K/vision/core/PrivateText.kt $K/vision/core/PrivacyFinder.kt "$H"/*.kt
printf '#!/bin/bash\njava -Xmx3g -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar:$CV" > "$W/run.sh"
chmod +x "$W/run.sh"
