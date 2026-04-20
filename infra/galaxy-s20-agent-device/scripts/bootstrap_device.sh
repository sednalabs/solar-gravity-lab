#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bootstrap_device.sh [--serial SERIAL]

Verify that the local host can talk to the rooted Galaxy developer device and
that the operator has the minimum interactive tooling installed.
EOF
}

serial=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

adb_args=()
if [[ -n "${serial}" ]]; then
  adb_args=(-s "${serial}")
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required" >&2
  exit 2
fi

if ! command -v scrcpy >/dev/null 2>&1; then
  echo "scrcpy is required for the interactive device lane" >&2
  exit 2
fi

adb "${adb_args[@]}" wait-for-device
adb "${adb_args[@]}" devices -l

root_state="$(adb "${adb_args[@]}" shell su -c id 2>/dev/null || true)"
if [[ -z "${root_state}" ]]; then
  echo "Root shell was not available through 'su -c id'" >&2
  exit 3
fi

adb "${adb_args[@]}" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb "${adb_args[@]}" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb "${adb_args[@]}" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

echo "Galaxy agent developer device bootstrap passed"
echo "root shell: ${root_state}"
echo "interactive mirror command: scrcpy ${serial:+--serial ${serial}}"
