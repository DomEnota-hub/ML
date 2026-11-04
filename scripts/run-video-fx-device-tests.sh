#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build
trap 'adb logcat -d > app/build/video-fx-logcat.txt || true; adb pull /sdcard/Download/ml-video-fx-evidence app/build/video-fx-evidence || true' EXIT
adb logcat -c
gradle :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=${ML_DEVICE_TEST:-com.domenota.medialoader.data.editor.Media3ActualVideoFxInstrumentedTest}" --stacktrace
