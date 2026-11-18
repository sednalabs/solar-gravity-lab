#!/usr/bin/env bash
set -euo pipefail

safe_version="$1"
package_label="${2:-internal-dev-preview}"
package_id="com.sednalabs.solarlab.internal"

if [[ "$package_label" == "release" ]]; then
  package_id="com.sednalabs.solarlab"
elif [[ "$package_label" == "research-benchmark" ]]; then
  package_id="com.sednalabs.solarlab.benchmark"
fi

mkdir -p dist/startup-smoke

bash .github/scripts/clear_stale_launcher_anr.sh dist/startup-smoke

readiness_args=(--require-runtime-ready)
if [[ "$package_label" == "research-benchmark" ]]; then
  readiness_args=()
fi

bash .github/scripts/android_launch_smoke.sh \
  --package "$package_id" \
  --activity "com.sednalabs.solarlab.MainActivity" \
  --apk "dist/solar-gravity-lab-${safe_version}-${package_label}.apk" \
  --out-dir "dist/startup-smoke" \
  "${readiness_args[@]}"

if [[ "$package_label" == "research-benchmark" ]]; then
  # A process alone is insufficient: prove R8 retained both research JNI seams.
  ready=false
  for _ in {1..30}; do
    adb logcat -d -v threadtime > dist/startup-smoke/benchmark-launch.log
    if grep -Eq 'SolarLabBenchmark: ready cpu_cases=[1-9][0-9]* gpu_cases=[0-9]+' dist/startup-smoke/benchmark-launch.log; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then
    echo "Benchmark JNI catalogue did not become ready" >&2
    exit 1
  fi
  adb exec-out screencap -p > dist/startup-smoke/benchmark-ready.png
fi
