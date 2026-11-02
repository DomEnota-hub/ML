#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build
trap 'adb logcat -d > app/build/video-fx-logcat.txt || true' EXIT
adb logcat -c
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.domenota.medialoader.data.editor.Media3ActualVideoFxInstrumentedTest --stacktrace
