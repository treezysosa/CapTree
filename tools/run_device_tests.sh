#!/usr/bin/env bash
set -u
test_result=0
./gradlew --no-daemon connectedDebugAndroidTest || test_result=$?
if [ "$test_result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
for report in Path('app/build/outputs/androidTest-results').rglob('TEST*.xml'):
    print(report.read_text())
PY
  adb logcat -d -s AndroidRuntime TestRunner | tail -n 160 || true
  mkdir -p device-screenshots
  adb pull /sdcard/Android/data/studio.retro.ai/files/diagnostics device-screenshots/ || true
fi
mkdir -p device-screenshots
adb shell screencap -p /sdcard/retro-studio-verification.png || true
adb pull /sdcard/retro-studio-verification.png device-screenshots/ || true
exit "$test_result"
