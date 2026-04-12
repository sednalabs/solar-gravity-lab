#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "usage: ensure_android_sdk_packages.sh <sdk-package> [<sdk-package> ...]" >&2
  exit 1
fi

phase_start="$(date +%s)"

set +o pipefail
yes | sdkmanager --licenses >/dev/null
set -o pipefail

mapfile -t installed_packages < <(sdkmanager --list_installed | awk '{print $1}')
missing_packages=()

for pkg in "$@"; do
  if printf '%s\n' "${installed_packages[@]}" | grep -Fxq "$pkg"; then
    echo "SDK package already installed: $pkg"
  else
    missing_packages+=("$pkg")
  fi
done

if [[ "${#missing_packages[@]}" -gt 0 ]]; then
  echo "Installing missing SDK packages: ${missing_packages[*]}"
  sdkmanager --install "${missing_packages[@]}"
else
  echo "All required SDK packages already installed."
fi

phase_end="$(date +%s)"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "sdk_setup_seconds=$((phase_end - phase_start))" >> "${GITHUB_OUTPUT}"
fi
