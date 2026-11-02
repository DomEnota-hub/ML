#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build
trap 'adb logcat -d > app/build/video-fx-logcat.txt || true; adb pull /sdcard/Android/data/com.domenota.medialoader/files/video-fx-evidence app/build/video-fx-evidence || true' EXIT
adb logcat -c
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.domenota.medialoader.data.editor.Media3ActualVideoFxInstrumentedTest --stacktrace
