#!/bin/bash
# Compiles the app's gallery core with Harness.kt (the app's own grouping, for evaluate.cluster_app).
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../../.." && pwd); TC=$R/.toolchain
W=${PEOPLE_WORK:-$R/buildtools/dl/people}/harness; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
java -Xmx3g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -classpath $TC/libs/kotlin-stdlib-2.3.21.jar -d "$W/classes" $R/app/src/main/kotlin/com/localmediatools/gallery/core/*.kt "$H/Harness.kt"
printf '#!/bin/bash\njava -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar" > "$W/run.sh"; chmod +x "$W/run.sh"
