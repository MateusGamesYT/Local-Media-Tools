#!/bin/bash
# Compiles the app's song code (highlight/core/Song.kt) with SongHarness.kt (HIGHLIGHT_WORK: where to build).
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${HIGHLIGHT_WORK:-$R/buildtools/dl/highlight}/harness; rm -rf "$W/classes"; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
java -Xmx2g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar" -d "$W/classes" "$R/app/src/main/kotlin/com/localmediatools/highlight/core/Song.kt" "$H/SongHarness.kt"
printf '#!/bin/bash\njava -Xmx2g -cp %s SongHarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar" > "$W/run.sh"
chmod +x "$W/run.sh"
