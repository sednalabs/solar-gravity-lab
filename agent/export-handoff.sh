#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./agent/export-handoff.sh --base <ref-or-sha> [options]

Creates a zip-first handoff under out/agent/ containing:
  - changes.patch
  - changed-files.txt
  - diffstat.txt
  - git-status.txt
  - base.txt / head.txt
  - deleted-files.txt
  - overlay/ with changed file contents
  - manifest.json

Options:
  --base <ref-or-sha>     Required diff base.
  --name <label>          Optional handoff label. Default: handoff-<shortsha>-<utc>
  --notes-file <path>     Optional notes file copied into the handoff as NOTES.md
  --out-dir <path>        Optional output directory. Default: out/agent
USAGE
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
BASE_REF=""
HANDOFF_NAME=""
NOTES_FILE=""
OUT_DIR="out/agent"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      BASE_REF="${2:-}"
      shift 2
      ;;
    --name)
      HANDOFF_NAME="${2:-}"
      shift 2
      ;;
    --notes-file)
      NOTES_FILE="${2:-}"
      shift 2
      ;;
    --out-dir)
      OUT_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
 done

if [[ -z "$BASE_REF" ]]; then
  echo "--base is required." >&2
  usage >&2
  exit 1
fi

cd "$REPO_ROOT"
if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "This export script requires a real Git checkout." >&2
  exit 1
fi

BASE_SHA="$(git rev-parse "${BASE_REF}^{commit}")"
HEAD_SHA="$(git rev-parse HEAD)"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SHORT_HEAD="$(git rev-parse --short HEAD)"
HANDOFF_NAME="${HANDOFF_NAME:-handoff-${SHORT_HEAD}-${TIMESTAMP}}"
OUT_DIR_ABS="$(python3 - <<'PY' "$OUT_DIR"
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"
HANDOFF_DIR="${OUT_DIR_ABS}/${HANDOFF_NAME}"
ZIP_PATH="${OUT_DIR_ABS}/${HANDOFF_NAME}.zip"

mkdir -p "$HANDOFF_DIR/overlay"

if [[ -n "$NOTES_FILE" && ! -f "$NOTES_FILE" ]]; then
  echo "Notes file not found: ${NOTES_FILE}" >&2
  exit 1
fi

ALT_INDEX="$(mktemp)"
export GIT_INDEX_FILE="$ALT_INDEX"
cleanup() {
  rm -f "$ALT_INDEX"
}
trap cleanup EXIT

git read-tree HEAD
git add -A

git diff --cached --binary --find-renames "$BASE_SHA" > "$HANDOFF_DIR/changes.patch"
git diff --cached --name-status "$BASE_SHA" > "$HANDOFF_DIR/changed-files.txt"
git diff --cached --stat "$BASE_SHA" > "$HANDOFF_DIR/diffstat.txt"
git status --short > "$HANDOFF_DIR/git-status.txt"
git diff --cached --name-only --diff-filter=D "$BASE_SHA" > "$HANDOFF_DIR/deleted-files.txt"
printf '%s\n' "$BASE_SHA" > "$HANDOFF_DIR/base.txt"
printf '%s\n' "$HEAD_SHA" > "$HANDOFF_DIR/head.txt"

while IFS= read -r -d '' path; do
  mkdir -p "$HANDOFF_DIR/overlay/$(dirname -- "$path")"
  cp -a -- "$REPO_ROOT/$path" "$HANDOFF_DIR/overlay/$path"
done < <(git diff --cached --name-only -z --diff-filter=ACMRTUXB "$BASE_SHA")

if [[ -n "$NOTES_FILE" ]]; then
  cp -a -- "$NOTES_FILE" "$HANDOFF_DIR/NOTES.md"
fi

python3 - <<'PY' "$HANDOFF_DIR/manifest.json" "$HANDOFF_NAME" "$BASE_SHA" "$HEAD_SHA" "$TIMESTAMP" "$REPO_ROOT"
import json
import os
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
handoff_name = sys.argv[2]
base_sha = sys.argv[3]
head_sha = sys.argv[4]
timestamp = sys.argv[5]
repo_root = sys.argv[6]

manifest = {
    "schema_version": 1,
    "handoff_name": handoff_name,
    "created_utc": timestamp,
    "repo_root": repo_root,
    "base_sha": base_sha,
    "head_sha": head_sha,
    "files": {
        "patch": "changes.patch",
        "changed_files": "changed-files.txt",
        "diffstat": "diffstat.txt",
        "git_status": "git-status.txt",
        "deleted_files": "deleted-files.txt",
        "overlay": "overlay"
    }
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY

(
  cd "$OUT_DIR_ABS"
  zip -qry "$ZIP_PATH" "$HANDOFF_NAME"
)

echo "handoff_dir=${HANDOFF_DIR}"
echo "handoff_zip=${ZIP_PATH}"
