#!/bin/bash
# Compiles the app's cut-out (vision/core/Matting.kt) with Harness.kt.
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${MATTING_WORK:-$R/buildtools/dl/matting}/harness; rm -rf "$W/classes"; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
OCV=$(ls $TC/test/opencv-*.jar)
KC="java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn"
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" -d "$W/classes" "$R/app/src/main/kotlin/com/localmediatools/vision/core/Matting.kt" "$H/Harness.kt"
printf '#!/bin/bash\njava -Xmx6g -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" > "$W/run.sh"
chmod +x "$W/run.sh"
