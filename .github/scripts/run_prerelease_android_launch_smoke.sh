#!/usr/bin/env bash
set -euo pipefail

safe_version="$1"
package_label="${2:-internal-dev-preview}"
package_id="com.sednalabs.solarlab.internal"

if [[ "$package_label" == "release" ]]; then
  package_id="com.sednalabs.solarlab"
fi

mkdir -p dist/startup-smoke

bash .github/scripts/android_launch_smoke.sh \
  --package "$package_id" \
  --activity "com.sednalabs.solarlab.MainActivity" \
  --apk "dist/solar-gravity-lab-${safe_version}-${package_label}.apk" \
  --out-dir "dist/startup-smoke"
