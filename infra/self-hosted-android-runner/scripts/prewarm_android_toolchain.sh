#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${GITHUB_RUNNER_ENV_FILE:-${ROOT_DIR}/env/runner.env}"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT in ${ENV_FILE} or env}"
: "${ANDROID_NDK_VERSION:?Set ANDROID_NDK_VERSION in ${ENV_FILE} or env}"
: "${ANDROID_PLATFORM_PACKAGE:?Set ANDROID_PLATFORM_PACKAGE in ${ENV_FILE} or env}"
: "${ANDROID_BUILD_TOOLS_PACKAGE:?Set ANDROID_BUILD_TOOLS_PACKAGE in ${ENV_FILE} or env}"
: "${ANDROID_SYSTEM_IMAGE_PACKAGE:?Set ANDROID_SYSTEM_IMAGE_PACKAGE in ${ENV_FILE} or env}"
: "${ANDROID_CMAKE_PACKAGE:?Set ANDROID_CMAKE_PACKAGE in ${ENV_FILE} or env}"
: "${ANDROID_AVD_NAME:?Set ANDROID_AVD_NAME in ${ENV_FILE} or env}"

export ANDROID_SDK_ROOT
export ANDROID_HOME="${ANDROID_SDK_ROOT}"

yes | sdkmanager --licenses >/dev/null
sdkmanager \
  "platform-tools" \
  "${ANDROID_PLATFORM_PACKAGE}" \
  "${ANDROID_BUILD_TOOLS_PACKAGE}" \
  "emulator" \
  "${ANDROID_SYSTEM_IMAGE_PACKAGE}" \
  "${ANDROID_CMAKE_PACKAGE}" \
  "ndk;${ANDROID_NDK_VERSION}"

rustup target add aarch64-linux-android x86_64-linux-android

if ! command -v cargo-ndk >/dev/null 2>&1; then
  cargo install cargo-ndk --locked
fi

if ! command -v sccache >/dev/null 2>&1; then
  cargo install sccache --locked
fi

mkdir -p "${HOME}/.android/avd"

if [[ ! -d "${HOME}/.android/avd/${ANDROID_AVD_NAME}.avd" ]]; then
  echo "no" | avdmanager create avd \
    --name "${ANDROID_AVD_NAME}" \
    --package "${ANDROID_SYSTEM_IMAGE_PACKAGE}" \
    --device "pixel_7"
fi

echo "Prewarmed Android toolchain, Rust Android targets, cargo-ndk, sccache, and AVD ${ANDROID_AVD_NAME}"
