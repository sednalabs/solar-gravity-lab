#!/usr/bin/env bash
set -euo pipefail

mkdir -p clients/android/app/build/reports/emulator-smoke

./gradlew -p clients/android --no-daemon \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sednalabs.solarlab.StartupSmokeInstrumentationTest,com.sednalabs.solarlab.RotationContinuityInstrumentationTest,com.sednalabs.solarlab.SolarLabShellLayoutTest

adb logcat -d > clients/android/app/build/reports/emulator-smoke/logcat.txt || true
adb shell dumpsys activity activities > clients/android/app/build/reports/emulator-smoke/dumpsys_activity.txt || true
