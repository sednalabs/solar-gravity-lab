#!/usr/bin/env bash
set -euo pipefail

safe_version="$1"

mkdir -p dist/startup-smoke

bash .github/scripts/android_launch_smoke.sh \
  --package "com.sednalabs.solarlab.internal" \
  --activity "com.sednalabs.solarlab.MainActivity" \
  --apk "dist/solar-gravity-lab-${safe_version}-internal-dev-preview.apk" \
  --out-dir "dist/startup-smoke"
