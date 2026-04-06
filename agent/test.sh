#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./agent/test.sh [lane]

Lanes:
  smoke
  rust-workspace
  ffi-abi
  runtime-scene-telemetry
  android-shell
  prerelease-apk
  android-shell-smoke
  full
USAGE
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LANE="${1:-smoke}"

run_cmd() {
  printf '+'
  for arg in "$@"; do
    printf ' %q' "$arg"
  done
  printf '\n'
  "$@"
}

case "$LANE" in
  -h|--help|help)
    usage
    exit 0
    ;;
  smoke|rust-workspace|ffi-abi|runtime-scene-telemetry|android-shell|prerelease-apk|android-shell-smoke|full)
    ;;
  *)
    echo "Unknown lane: ${LANE}" >&2
    usage >&2
    exit 1
    ;;
 esac

cd "$REPO_ROOT"
"$REPO_ROOT/agent/bootstrap.sh" "$LANE"

case "$LANE" in
  smoke|rust-workspace)
    run_cmd cargo test --workspace
    ;;
  ffi-abi)
    run_cmd cargo test -p solarlab-ffi
    ;;
  runtime-scene-telemetry)
    run_cmd cargo test -p solarlab-runtime -p solarlab-scene -p solarlab-vulkan-adapter
    ;;
  android-shell)
    run_cmd ./gradlew -p clients/android --no-daemon :app:assembleDebug
    ;;
  prerelease-apk)
    run_cmd ./gradlew -p clients/android --no-daemon :app:assemblePrerelease
    ;;
  android-shell-smoke)
    run_cmd bash .github/scripts/run_validation_android_shell_smoke.sh
    ;;
  full)
    run_cmd cargo test --workspace
    run_cmd ./gradlew -p clients/android --no-daemon :app:assembleDebug
    ;;
 esac
