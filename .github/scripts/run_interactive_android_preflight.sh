#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run_interactive_android_preflight.sh \
  --mcp-health-url URL \
  --apk APK \
  --package PACKAGE \
  --activity ACTIVITY \
  --out-dir DIR

Proves the in-job android-computer-use-mcp health surface plus the app
install-and-launch path before handing control to the interactive session.
EOF
}

mcp_health_url=""
apk_path=""
package_id=""
activity_name=""
out_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mcp-health-url)
      mcp_health_url="$2"
      shift 2
      ;;
    --apk)
      apk_path="$2"
      shift 2
      ;;
    --package)
      package_id="$2"
      shift 2
      ;;
    --activity)
      activity_name="$2"
      shift 2
      ;;
    --out-dir)
      out_dir="$2"
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

if [[ -z "${mcp_health_url}" || -z "${apk_path}" || -z "${package_id}" || -z "${activity_name}" || -z "${out_dir}" ]]; then
  usage >&2
  exit 1
fi

mkdir -p "${out_dir}"

adb wait-for-device
adb devices -l > "${out_dir}/adb-devices.txt"
adb shell getprop ro.build.fingerprint > "${out_dir}/device-fingerprint.txt" || true

mcp_health_ok="false"
launch_smoke_ok="false"
mcp_health_file="${out_dir}/mcp-health.json"

if curl -fsSL "${mcp_health_url}" -o "${mcp_health_file}"; then
  mcp_health_ok="true"
fi

if bash .github/scripts/android_launch_smoke.sh \
  --package "${package_id}" \
  --activity "${activity_name}" \
  --apk "${apk_path}" \
  --out-dir "${out_dir}/startup-smoke"; then
  launch_smoke_ok="true"
fi

python3 - <<'PY' "${out_dir}/preflight.json" "${package_id}" "${activity_name}" "${apk_path}" "${mcp_health_url}" "${mcp_health_ok}" "${launch_smoke_ok}"
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
payload = {
    "schema_version": 1,
    "package_id": sys.argv[2],
    "activity_name": sys.argv[3],
    "apk_path": sys.argv[4],
    "mcp_health_url": sys.argv[5],
    "mcp_health_ok": sys.argv[6] == "true",
    "launch_smoke_ok": sys.argv[7] == "true",
    "status": "ready" if sys.argv[6] == "true" and sys.argv[7] == "true" else "action_required",
    "artifacts": {
        "adb_devices": "adb-devices.txt",
        "device_fingerprint": "device-fingerprint.txt",
        "mcp_health": "mcp-health.json",
        "startup_smoke_dir": "startup-smoke",
    },
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY

if [[ "${mcp_health_ok}" != "true" || "${launch_smoke_ok}" != "true" ]]; then
  echo "Interactive preflight failed: mcp_health_ok=${mcp_health_ok}, launch_smoke_ok=${launch_smoke_ok}" >&2
  exit 1
fi
