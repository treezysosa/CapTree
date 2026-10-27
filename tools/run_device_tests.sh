#!/usr/bin/env bash
set -u
test_result=0
./gradlew --no-daemon connectedDebugAndroidTest || test_result=$?
mkdir -p device-screenshots
adb shell screencap -p /sdcard/retro-studio-verification.png || true
adb pull /sdcard/retro-studio-verification.png device-screenshots/ || true
exit "$test_result"
