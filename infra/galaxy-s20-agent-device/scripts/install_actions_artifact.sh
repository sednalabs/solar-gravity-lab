#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install_actions_artifact.sh --apk APK --package PACKAGE --activity ACTIVITY --out-dir DIR [--serial SERIAL]

Install a built APK onto the Galaxy developer device, launch it, and emit a
small summary bundle under the output directory.
EOF
}

serial=""
apk_path=""
package_id=""
activity_name=""
out_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="$2"
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

if [[ -z "${apk_path}" || -z "${package_id}" || -z "${activity_name}" || -z "${out_dir}" ]]; then
  usage >&2
  exit 1
fi

adb_args=()
if [[ -n "${serial}" ]]; then
  adb_args=(-s "${serial}")
fi

mkdir -p "${out_dir}"

adb "${adb_args[@]}" wait-for-device
adb "${adb_args[@]}" install -r "${apk_path}" > "${out_dir}/install.txt"
adb "${adb_args[@]}" shell am force-stop "${package_id}" >/dev/null 2>&1 || true
adb "${adb_args[@]}" shell am start -W -n "${package_id}/${activity_name}" > "${out_dir}/launch.txt"
adb "${adb_args[@]}" shell pidof "${package_id}" > "${out_dir}/pidof.txt" || true

python3 - <<'PY' "${out_dir}/install-summary.json" "${apk_path}" "${package_id}" "${activity_name}"
import json
import pathlib
import sys

output_path = pathlib.Path(sys.argv[1])
payload = {
    "schema_version": 1,
    "apk_path": sys.argv[2],
    "package_id": sys.argv[3],
    "activity_name": sys.argv[4],
    "artifacts": {
        "install": "install.txt",
        "launch": "launch.txt",
        "pidof": "pidof.txt",
    },
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
