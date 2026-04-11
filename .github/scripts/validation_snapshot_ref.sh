#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  .github/scripts/validation_snapshot_ref.sh create [snapshot-name] [remote]
  .github/scripts/validation_snapshot_ref.sh delete <snapshot-ref> [remote]

Create mode:
  Creates a disposable snapshot commit from the exact current worktree state
  using a temporary index, pushes it to refs/heads/validation/snapshot-*, and
  prints shell-friendly output values.

Delete mode:
  Deletes a previously created snapshot ref from the remote.
EOF
}

sanitize_name() {
  local raw="$1"
  raw="${raw,,}"
  raw="${raw//[^a-z0-9._-]/-}"
  raw="${raw#-}"
  raw="${raw%-}"
  if [[ -z "$raw" ]]; then
    raw="snapshot"
  fi
  printf '%s' "$raw"
}

create_snapshot() {
  local requested_name="${1:-}"
  local remote="${2:-origin}"
  local timestamp
  local branch_name
  local temp_index
  local commit_message
  local tree_id
  local commit_id
  local parent_commit=""

  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  branch_name="$(sanitize_name "${requested_name:-$(basename "$(pwd)")}")"
  branch_name="validation/snapshot-${branch_name}-${timestamp}"
  temp_index="$(mktemp)"
  trap 'rm -f "$temp_index"' EXIT

  export GIT_INDEX_FILE="$temp_index"
  git read-tree --empty
  git add -A
  tree_id="$(git write-tree)"
  if git rev-parse --verify HEAD >/dev/null 2>&1; then
    parent_commit="$(git rev-parse HEAD)"
  fi

  commit_message=$(
    cat <<EOF
validation snapshot: ${branch_name}

This disposable commit captures the current worktree state for remote validation.
EOF
  )

  if [[ -n "$parent_commit" ]]; then
    commit_id="$(printf '%s\n' "$commit_message" | git commit-tree "$tree_id" -p "$parent_commit")"
  else
    commit_id="$(printf '%s\n' "$commit_message" | git commit-tree "$tree_id")"
  fi

  git push "$remote" "${commit_id}:refs/heads/${branch_name}"

  printf 'snapshot_ref=%s\n' "$branch_name"
  printf 'snapshot_commit=%s\n' "$commit_id"
  printf 'snapshot_remote=%s\n' "$remote"
}

delete_snapshot() {
  local snapshot_ref="${1:-}"
  local remote="${2:-origin}"
  if [[ -z "$snapshot_ref" ]]; then
    echo "Snapshot ref is required for delete mode." >&2
    usage >&2
    exit 2
  fi
  if [[ "$snapshot_ref" != validation/snapshot-* ]]; then
    echo "Delete mode only accepts validation/snapshot-* refs." >&2
    exit 2
  fi
  git push "$remote" ":refs/heads/${snapshot_ref}"
  printf 'deleted_snapshot_ref=%s\n' "$snapshot_ref"
  printf 'snapshot_remote=%s\n' "$remote"
}

main() {
  local action="${1:-}"
  case "$action" in
    create)
      shift || true
      create_snapshot "${1:-}" "${2:-origin}"
      ;;
    delete)
      shift || true
      delete_snapshot "${1:-}" "${2:-origin}"
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
