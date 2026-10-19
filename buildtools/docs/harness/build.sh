#!/bin/bash
# Compiles the app's document readers with the harness (DOCS_WORK: where to build).
set -e
H=$(cd "$(dirname "$0")" && pwd); R=$(cd "$H/../../.." && pwd); TC=$R/.toolchain
W=${DOCS_WORK:-$R/buildtools/dl/docs}/harness; rm -rf "$W/classes"; mkdir -p "$W"
CP=$(ls $TC/kotlinc/*.jar | tr '\n' ':')
KC="java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn"
K=$R/app/src/main/kotlin/com/localmediatools
$KC -classpath "$TC/libs/kotlin-stdlib-2.3.21.jar" -d "$W/classes" $K/docs/core/*.kt $K/i18n/*.kt "$H"/*.kt
printf '#!/bin/bash\njava -Xmx3g -cp %s HarnessKt "$@"\n' "$W/classes:$TC/libs/kotlin-stdlib-2.3.21.jar" > "$W/run.sh"
chmod +x "$W/run.sh"
