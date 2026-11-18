#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: run_prerelease_android_launch_smoke.sh SAFE_VERSION [internal-dev-preview|release|research-benchmark]" >&2
  exit 2
fi

safe_version="$1"
package_label="${2:-internal-dev-preview}"
case "$package_label" in
  internal-dev-preview|release|research-benchmark) ;;
  *) echo "Unsupported packaged smoke variant: $package_label" >&2; exit 2 ;;
esac
apk_path="dist/solar-gravity-lab-${safe_version}-${package_label}.apk"
if [[ ! -f "$apk_path" ]]; then
  echo "Packaged smoke APK is missing: $apk_path" >&2
  exit 1
fi

apkanalyzer="$(command -v apkanalyzer || true)"
if [[ -z "$apkanalyzer" ]]; then
  sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk_root" && -x "$sdk_root/cmdline-tools/latest/bin/apkanalyzer" ]]; then
    apkanalyzer="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
  else
    echo "apkanalyzer is unavailable from the installed Android SDK." >&2
    exit 1
  fi
fi

# Read the packaged manifest, not a default package name or build-directory
# metadata. Benchmark versions intentionally have separate application IDs.
package_id="$("$apkanalyzer" manifest application-id "$apk_path")"
version_code="$("$apkanalyzer" manifest version-code "$apk_path")"
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "Packaged APK has an invalid versionCode: $version_code" >&2
  exit 1
fi
case "$package_label" in
  release) expected_package="com.sednalabs.solarlab" ;;
  internal-dev-preview) expected_package="com.sednalabs.solarlab.internal" ;;
  research-benchmark) expected_package="com.sednalabs.solarlab.benchmark${version_code}" ;;
esac
if [[ "$package_id" != "$expected_package" ]]; then
  echo "Packaged APK identity mismatch: expected $expected_package, found $package_id" >&2
  exit 1
fi

mkdir -p dist/startup-smoke
printf 'package=%s\nversion_code=%s\n' "$package_id" "$version_code" > dist/startup-smoke/apk-identity.txt

bash .github/scripts/clear_stale_launcher_anr.sh dist/startup-smoke

readiness_args=(--require-runtime-ready)
if [[ "$package_label" == "research-benchmark" ]]; then
  readiness_args=()
fi

bash .github/scripts/android_launch_smoke.sh \
  --package "$package_id" \
  --activity "com.sednalabs.solarlab.MainActivity" \
  --apk "$apk_path" \
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
