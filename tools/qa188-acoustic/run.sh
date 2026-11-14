#!/usr/bin/env bash
set -eu
# KOTLIN_LIB points to a directory containing compiler-embeddable, stdlib and compiler dependencies.
: "${KOTLIN_LIB:?Set KOTLIN_LIB to the local Kotlin compiler library directory}"
root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p /tmp/qa188-audio
python3 "$root/tools/qa188-acoustic/generate-fixtures.py"
java -cp "$KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$KOTLIN_LIB/kotlin-stdlib-1.9.23.jar" -d /tmp/qa188-audio/evidence.jar \
  "$root/app/src/main/java/com/domenota/medialoader/data/library/AcousticFingerprint.kt" \
  "$root/app/src/main/java/com/domenota/medialoader/data/library/MusicDuplicatePolicy.kt" \
  "$root/tools/qa188-acoustic/Probe.kt" "$root/tools/qa188-acoustic/Benchmark.kt"
java -Xmx512m -cp "/tmp/qa188-audio/evidence.jar:$KOTLIN_LIB/*" com.domenota.medialoader.data.library.ProbeKt
java -Xmx512m -cp "/tmp/qa188-audio/evidence.jar:$KOTLIN_LIB/*" com.domenota.medialoader.data.library.BenchmarkKt
