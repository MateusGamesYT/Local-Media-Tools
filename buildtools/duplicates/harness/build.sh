#!/bin/bash
# Compiles the app's duplicate finder with Harness.kt, and 1.6.0's (from git) with OldHarness.kt.
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${DUP_WORK:-$R/buildtools/dl/duplicates}/harness; mkdir -p "$W/old-src"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
OCV=$(ls $TC/test/opencv-*.jar)
KC="java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn"
V=$R/app/src/main/kotlin/com/localmediatools/vision/core
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" -d "$W/classes" "$V/Duplicates.kt" "$V/FeatureCheck.kt" "$V/FaceEngine.kt" "$H/Harness.kt"
git -C "$R" show 2205c95:app/src/main/kotlin/com/localmediatools/vision/core/Duplicates.kt > "$W/old-src/Duplicates.kt"
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" -d "$W/old-classes" "$W/old-src/Duplicates.kt" "$V/FaceEngine.kt" "$H/OldHarness.kt"
printf '#!/bin/bash\njava -Xmx4g -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" > "$W/run.sh"
printf '#!/bin/bash\njava -Xmx4g -cp %s OldHarnessKt "$@"\n' "$W/old-classes:$TC/libs/kotlin-stdlib-2.3.21.jar:$OCV" > "$W/run_old.sh"
chmod +x "$W/run.sh" "$W/run_old.sh"
