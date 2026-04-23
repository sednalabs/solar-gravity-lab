#!/usr/bin/env bash
set -euo pipefail

mode="${1:-disabled}"

case "${mode}" in
  disabled|"")
    ;;
  enabled)
    printf '%s\n' "--configuration-cache"
    ;;
  *)
    echo "Unsupported Gradle configuration-cache mode: ${mode}" >&2
    exit 2
    ;;
esac
