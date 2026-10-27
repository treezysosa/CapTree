#!/usr/bin/env bash
set -u
test_result=0
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=studio.retro.ai.PretrainedSpeechTest || test_result=$?
mkdir -p offline-speech-device
adb pull /sdcard/Download/RetroAiValidation offline-speech-device/ || true
adb pull /sdcard/Android/data/studio.retro.ai/files/diagnostics offline-speech-device/ || true
if [ "$test_result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
for report in Path('app/build/outputs/androidTest-results').rglob('TEST*.xml'):
    print(report.read_text())
PY
  adb logcat -d -s AndroidRuntime TestRunner | tail -n 160 || true
fi
exit "$test_result"
