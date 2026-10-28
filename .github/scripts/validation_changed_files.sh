#!/usr/bin/env bash
set -euo pipefail

# A rename must route both the removed and added surface.
git diff --no-renames --name-only "$1" "$2" --
