#!/usr/bin/env bash
set -euo pipefail

# A stale emulator-launcher ANR can survive test setup and cover screenshots.
# Never dismiss product ANRs or stop packages on a physical device.
report_dir="${1:?Pass the instrumentation evidence directory}"
command_timeout="${ANDROID_TEST_ADB_CAPTURE_TIMEOUT_SECONDS:-20}"
mkdir -p "${report_dir}"
exec > >(tee "${report_dir}/launcher-anr-preflight.txt") 2>&1

adb_bounded() {
  timeout --foreground "${command_timeout}s" adb "$@"
}

if [[ "$(adb_bounded shell getprop ro.kernel.qemu | tr -d '\r\n')" != "1" ]]; then
  echo "Launcher ANR cleanup skipped: device is not an emulator."
  exit 0
fi

before="${report_dir}/launcher-anr.before.txt"
after="${report_dir}/launcher-anr.after.txt"
adb_bounded shell dumpsys window windows > "${before}"
if ! grep -q 'Application Not Responding:' "${before}"; then
  echo "No stale ANR window before instrumentation."
  exit 0
fi

launcher_component="$(adb_bounded shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
launcher_package="${launcher_component%%/*}"
if [[ "${launcher_component}" != */* || ! "${launcher_package}" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]]; then
  echo "Cannot identify the HOME launcher; preserving the ANR for diagnosis." >&2
  exit 1
fi
if ! grep -Fq "Application Not Responding: ${launcher_package}}" "${before}"; then
  echo "An ANR belongs to another package; preserving it for diagnosis." >&2
  exit 1
fi

adb_bounded shell dumpsys activity lastanr > "${report_dir}/launcher-anr.lastanr.txt" || true
echo "Clearing the stale emulator launcher ANR: ${launcher_package}"
adb_bounded shell am force-stop "${launcher_package}"
for attempt in 1 2 3; do
  adb_bounded shell dumpsys window windows > "${after}"
  if ! grep -q 'Application Not Responding:' "${after}"; then
    echo "ANR window cleared before instrumentation."
    exit 0
  fi
  if [[ "${attempt}" != "3" ]]; then sleep 0.2; fi
done
echo "An ANR window remains after launcher cleanup; refusing an obscured test run." >&2
exit 1
