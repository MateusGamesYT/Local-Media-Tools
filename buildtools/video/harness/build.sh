#!/bin/bash
# Compiles the app's video core with Harness.kt (the app's own auto reframe, for reframe_eval.py).
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${VIDEO_WORK:-$R/buildtools/dl/video}/harness; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
java -Xmx3g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -classpath $TC/libs/kotlin-stdlib-2.3.21.jar -d "$W/classes" $R/app/src/main/kotlin/com/localmediatools/video/core/*.kt "$H/Harness.kt"
printf '#!/bin/bash\njava -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar" > "$W/run.sh"; chmod +x "$W/run.sh"
