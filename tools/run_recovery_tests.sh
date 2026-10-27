#!/usr/bin/env bash
set -euo pipefail
mkdir -p process-recovery
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_case() {
  local recovery_case="$1"
  adb shell am instrument -w -r -e phase9_process 1 -e class "studio.retro.ai.PhaseNineProcessTest#$recovery_case" studio.retro.ai.test/androidx.test.runner.AndroidJUnitRunner | tee "process-recovery/$recovery_case.log"
  python3 tools/check_instrumentation_result.py "process-recovery/$recovery_case.log"
}
run_case prepareCheckpoint
adb shell am force-stop studio.retro.ai
if adb shell pidof studio.retro.ai; then
  echo "The app process still exists after force-stop."
  exit 1
fi
run_case verifyCheckpointAndExport
adb pull /sdcard/Android/data/studio.retro.ai/files/diagnostics process-recovery/
