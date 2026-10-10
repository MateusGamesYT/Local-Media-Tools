#!/bin/bash
# Compiles the app's gallery core with LearnHarness.kt (a simulated user naming people round by round).
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../../.." && pwd); TC=$R/.toolchain
W=${PEOPLE_WORK:-$R/buildtools/dl/people}/harness; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
java -Xmx3g -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -classpath $TC/libs/kotlin-stdlib-2.3.21.jar -d "$W/learn-classes" $R/app/src/main/kotlin/com/localmediatools/gallery/core/*.kt "$H/LearnHarness.kt" || exit 1
printf '#!/bin/bash\njava -Xmx2g -cp %s LearnHarnessKt "$@"\n' "$W/learn-classes:$TC/libs/kotlin-stdlib-2.3.21.jar" > "$W/run_learn.sh"; chmod +x "$W/run_learn.sh"
