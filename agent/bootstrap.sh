#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./agent/bootstrap.sh [lane]

Verifies that this checkout is a real Git repository and that the local
 toolchain can support the requested lane.

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

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || MISSING_COMMANDS+=("$name")
}

warn() {
  echo "warning: $*" >&2
}

MISSING_COMMANDS=()
cd "$REPO_ROOT"

if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  cat >&2 <<'EOF_ERR'
This agent kit only supports a real Git checkout.

Use ./agent/prepare-upload-bundle.sh inside a clone and upload that result
instead of a plain GitHub source snapshot.
EOF_ERR
  exit 1
fi

GIT_TOPLEVEL="$(git rev-parse --show-toplevel)"
if [[ "$GIT_TOPLEVEL" != "$REPO_ROOT" ]]; then
  echo "Git toplevel mismatch. Expected ${REPO_ROOT}, got ${GIT_TOPLEVEL}." >&2
  exit 1
fi

[[ -f "$REPO_ROOT/Cargo.toml" ]] || { echo "Missing Cargo.toml at repo root." >&2; exit 1; }
[[ -f "$REPO_ROOT/gradlew" ]] || { echo "Missing root gradlew wrapper." >&2; exit 1; }
[[ -f "$REPO_ROOT/clients/android/app/build.gradle.kts" ]] || {
  echo "Missing canonical Android shell build file at clients/android/app/build.gradle.kts." >&2
  exit 1
}

require_command bash
require_command git
require_command python3
require_command zip
require_command unzip

case "$LANE" in
  smoke|rust-workspace|ffi-abi|runtime-scene-telemetry|full)
    require_command cargo
    require_command rustup
    ;;
 esac

case "$LANE" in
  android-shell|prerelease-apk|android-shell-smoke|full)
    require_command java
    ;;
 esac

case "$LANE" in
  android-shell-smoke)
    require_command adb
    ;;
 esac

if ((${#MISSING_COMMANDS[@]} > 0)); then
  echo "Missing required commands: ${MISSING_COMMANDS[*]}" >&2
  exit 1
fi

echo "repo_root=${REPO_ROOT}"
echo "git_head=$(git rev-parse HEAD)"
echo "lane=${LANE}"

echo "git=$(git --version)"
if command -v cargo >/dev/null 2>&1; then
  echo "cargo=$(cargo --version)"
fi
if command -v rustup >/dev/null 2>&1; then
  echo "rustup=$(rustup --version | head -n 1)"
fi
if command -v python3 >/dev/null 2>&1; then
  echo "python3=$(python3 --version 2>&1)"
fi

case "$LANE" in
  android-shell|prerelease-apk|android-shell-smoke|full)
    echo "java=$(java -version 2>&1 | head -n 1)"

    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"

    if [[ -z "$sdk_root" && ! -f "$REPO_ROOT/local.properties" && ! -f "$REPO_ROOT/clients/android/local.properties" ]]; then
      warn "No Android SDK location was found in env or local.properties. Android lanes may fail until the SDK is configured."
    fi

    if [[ -z "$ndk_root" ]]; then
      warn "No Android NDK location was declared in env. The Gradle task can still resolve an installed NDK from the SDK or local.properties if present."
    fi

    if ! command -v cargo >/dev/null 2>&1; then
      warn "cargo is not on PATH; Android native library assembly will fail."
    fi
    ;;
 esac

echo "Bootstrap checks passed."
