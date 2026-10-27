#!/usr/bin/env bash
set -euo pipefail
mkdir -p upgrade-validation
# Both variants use this job's same debug key. No uninstall/clear-data is permitted.
retro_previous="${RETRO_PREVIOUS_DIR:?Set the checkout directory for v0.9}"
(cd "$retro_previous" && ./gradlew --no-daemon assembleDebug)
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
adb install -t "$retro_previous/app/build/outputs/apk/debug/app-debug.apk"
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_upgrade_case() {
  local retro_case="$1"
  adb shell am instrument -w -r -e phase10_upgrade 1 -e class "studio.retro.ai.PhaseTenUpgradeTest#$retro_case" studio.retro.ai.test/androidx.test.runner.AndroidJUnitRunner | tee "upgrade-validation/$retro_case.log"
  python3 tools/check_instrumentation_result.py "upgrade-validation/$retro_case.log"
}
run_upgrade_case prepareOldInstallation
adb shell am force-stop studio.retro.ai
adb install -r -t app/build/outputs/apk/debug/app-debug.apk | tee upgrade-validation/install-replace.log
run_upgrade_case verifyUpgradedInstallationAndExport
adb pull /sdcard/Android/data/studio.retro.ai/files/upgrade-diagnostics upgrade-validation/
