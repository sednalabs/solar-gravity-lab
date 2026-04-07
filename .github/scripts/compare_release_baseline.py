#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
import subprocess


CODE_FILE_EXTENSIONS = {
    ".c",
    ".cc",
    ".cpp",
    ".cxx",
    ".h",
    ".hh",
    ".hpp",
    ".hxx",
    ".java",
    ".kt",
    ".kts",
    ".rs",
    ".toml",
    ".xml",
    ".yml",
    ".yaml",
    ".json",
    ".ini",
    ".gradle",
    ".properties",
    ".py",
    ".js",
    ".jsx",
    ".ts",
    ".tsx",
    ".clj",
    ".sh",
    ".bat",
    ".cppm",
    ".swift",
    ".go",
    ".mm",
    ".m",
    ".rs.in",
    ".cuh",
}

NON_CODE_PREFIXES = (
    "docs/",
    ".github/",
    "README.md",
    "LICENSE",
    "NOTICE",
    "LICENSES/",
)


def run_git(args: Sequence[str], *, cwd: Path = Path(".")) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        check=True,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip()


def has_ref(ref: str) -> bool:
    try:
        run_git(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"])
        return True
    except subprocess.CalledProcessError:
        return False


def resolve_remote_ref(reference: str) -> str:
    if reference == "":
        raise RuntimeError("Baseline reference cannot be empty")
    if reference == "auto:last-release-tag":
        # Pick the most recent local/reachable release tag as the baseline.
        return run_git(["describe", "--tags", "--abbrev=0", "--match", "v*"]).strip()
    if reference.startswith("tag:"):
        tag = reference[len("tag:") :]
        if not tag:
            raise RuntimeError("tag: reference requires a non-empty tag name")
        return tag
    return reference


def resolve_remote(preferred: str, fallback: str) -> str:
    for candidate in (preferred, fallback):
        if not candidate:
            continue
        try:
            candidate = resolve_remote_ref(candidate)
        except RuntimeError:
            continue
        if has_ref(candidate):
            return candidate
    raise RuntimeError(
        "No remote base commit could be resolved.\n"
        f"  Preferred: {preferred}\n"
        f"  Fallback:  {fallback}\n"
        "Expected at least one of these refs to exist.\n"
        "Hint: provide an explicit release tag with '--remote-ref tag:<tag>', ensure tag fetch is configured, "
        "or use '--remote-ref origin/main'."
    )


def parse_numstat_value(raw: str) -> int:
    if raw == "-":
        return 0
    return int(raw)


def parse_numstat(raw: str) -> dict[str, tuple[int, int]]:
    entries: dict[str, tuple[int, int]] = {}
    for line in raw.splitlines():
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        additions = parse_numstat_value(parts[0])
        deletions = parse_numstat_value(parts[1])
        key = f"{parts[2]}->{parts[3]}" if len(parts) > 3 else parts[2]
        entries[key] = (additions, deletions)
    return entries


def split_log_entry(line: str) -> tuple[str, str, str, str, str]:
    side = line[0]
    parts = line[1:].split("\x01")
    if len(parts) != 4:
        raise ValueError(f"Unexpected git log format: {line}")
    sha, short_sha, subject, author_ts = parts
    return side, sha, short_sha, subject, author_ts


def patch_id_for_commit(sha: str) -> str:
    show_output = subprocess.run(
        ["git", "show", "--no-color", "--no-ext-diff", "--pretty=format:", sha],
        text=True,
        capture_output=True,
        check=True,
    ).stdout
    patch_output = subprocess.run(
        ["git", "patch-id", "--stable"],
        input=show_output,
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip().split()[0]
    return patch_output


def commit_metadata(raw_lines: Sequence[str], side_filter: str) -> list[dict]:
    commits: list[dict] = []
    for line in raw_lines:
        if not line:
            continue
        side, sha, short_sha, subject, author_ts = split_log_entry(line)
        if side != side_filter:
            continue
        commits.append(
            {
                "sha": sha,
                "short_sha": short_sha,
                "subject": subject,
                "author_unix_ts": int(author_ts),
                "patch_id": patch_id_for_commit(sha),
            }
        )
    return commits


def is_code_relevant_commit(commit: dict) -> bool:
    subject = commit["subject"].lower()
    return "doc" not in subject and "markdown" not in subject and "readme" not in subject


def parse_path(path: str) -> dict:
    if not path:
        return {"code": False, "reason": "empty"}
    lower = path.lower()
    if lower.startswith(NON_CODE_PREFIXES):
        return {"code": False, "reason": "non-code prefix"}
    extension = Path(path).suffix.lower()
    if extension == "":
        return {"code": False, "reason": "no extension"}
    if extension in CODE_FILE_EXTENSIONS:
        return {"code": True, "reason": "extension"}
    return {"code": False, "reason": "unsupported extension"}


def parse_diff_raw(local_ref: str, remote_ref: str) -> list[dict]:
    raw_output = run_git(
        [
            "diff",
            "--raw",
            "--find-renames",
            "--find-copies-harder",
            "--no-ext-diff",
            local_ref,
            remote_ref,
        ]
    ).splitlines()
    numstat_map = parse_numstat(
        run_git(
            [
                "diff",
                "--numstat",
                "--find-renames",
                "--find-copies-harder",
                "--no-ext-diff",
                local_ref,
                remote_ref,
            ]
        )
    )

    rows: list[dict] = []
    for line in raw_output:
        if not line.startswith(":"):
            continue
        header, *rest = line[1:].split("\t")
        parts = header.split()
        if len(parts) < 5 or len(rest) == 0:
            continue

        status = parts[4]
        old_path = rest[0]
        new_path = rest[1] if len(rest) > 1 else ""
        path_key = f"{old_path}->{new_path}" if new_path else old_path
        additions, deletions = numstat_map.get(path_key, (0, 0))
        old_blob = parts[2]
        new_blob = parts[3]
        code_class = parse_path(new_path or old_path)
        rows.append(
            {
                "status": status,
                "old_path": old_path,
                "new_path": new_path,
                "code_path": code_class["code"],
                "code_reason": code_class["reason"],
                "additions": additions,
                "deletions": deletions,
                "old_blob": old_blob,
                "new_blob": new_blob,
            }
        )
    return sorted(rows, key=lambda row: (row["status"], row["old_path"], row["new_path"]))


def write_markdown(path: Path, payload: dict, code_only: bool) -> None:
    local = payload["local"]
    remote = payload["remote"]
    status = payload["delta_status"]
    files = payload["file_deltas"]
    if code_only:
        files = [item for item in files if item["code_path"]]

    summary = [
        "# Release Baseline Drift Audit",
        "",
        f"- Local ref: `{local['resolved_ref']}`",
        f"- Remote ref: `{remote['resolved_ref']}`",
        f"- Local SHA: `{local['sha']}`",
        f"- Remote SHA: `{remote['sha']}`",
        f"- Merge base: `{payload['merge_base']}`",
        f"- Merge base date: `{payload['merge_base_datetime']}`",
        f"- Status: `{status}`",
        f"- Left-only commits: `{payload['counts']['local_only_commits']}`",
        f"- Right-only commits: `{payload['counts']['remote_only_commits']}`",
        f"- Code-only files: `{payload['counts']['code_file_changes']}`",
        f"- Total changed files: `{payload['counts']['total_file_changes']}`",
        "",
        "## Commit summary",
        "",
        "### Local-only commits",
        "",
        "| Commit | Subject | Patch ID |",
        "| --- | --- | --- |",
    ]
    for commit in payload["local_commits"]:
        if code_only and not commit["code_relevant"]:
            continue
        summary.append(
            f"| `{commit['short_sha']}` | {commit['subject']} | `{commit['patch_id']}` |"
        )

    summary.extend(
        [
            "",
            "### Remote-only commits",
            "",
            "| Commit | Subject | Patch ID |",
            "| --- | --- | --- |",
        ]
    )
    for commit in payload["remote_commits"]:
        if code_only and not commit["code_relevant"]:
            continue
        summary.append(
            f"| `{commit['short_sha']}` | {commit['subject']} | `{commit['patch_id']}` |"
        )

    summary.extend(
        [
            "",
            "## Changed files",
            "",
            "| Status | Path | Add | Delete | Code path |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for change in files[:200]:
        file_path = change["new_path"] or change["old_path"]
        summary.append(
            f"| {change['status']} | `{file_path}` | {change['additions']} | {change['deletions']} | {change['code_path']} |"
        )

    path.write_text("\n".join(summary) + "\n", encoding="utf-8")


def read_json_or_str(value: str | None) -> list[str] | None:
    if value is None:
        return None
    return [item.strip() for item in value.split(",") if item.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare origin/main to the canonical Rust release baseline by default."
    )
    parser.add_argument("--local-ref", default="origin/main")
    parser.add_argument("--remote-ref", default="auto:last-release-tag")
    parser.add_argument("--fallback-remote-ref", default="origin/main")
    parser.add_argument("--code-only", action="store_true")
    parser.add_argument("--json", default="drift_audit.json")
    parser.add_argument("--markdown", default="drift_audit.md")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--ignore-paths", default="")
    args = parser.parse_args()

    ignored_paths = set(read_json_or_str(args.ignore_paths) or [])
    local_ref = args.local_ref
    if not has_ref(local_ref):
        raise RuntimeError(f"Local ref '{local_ref}' does not resolve.")

    remote_ref = resolve_remote(args.remote_ref, args.fallback_remote_ref)
    local_sha = run_git(["rev-parse", local_ref]).strip()
    remote_sha = run_git(["rev-parse", remote_ref]).strip()
    merge_base = run_git(["merge-base", local_ref, remote_ref]).strip()
    merge_base_dt = run_git(["show", "-s", "--format=%cI", merge_base]).strip()

    commit_log = run_git(
        [
            "log",
            "--left-right",
            "--no-merges",
            "--cherry",
            f"{local_ref}...{remote_ref}",
            "--format=%H\x01%h\x01%s\x01%at",
        ]
    ).splitlines()
    # For "local_ref...remote_ref", "<" are local-only (left) and ">" are remote-only (right).
    local_commits = commit_metadata(commit_log, "<")
    remote_commits = commit_metadata(commit_log, ">")
    for commit in local_commits:
        commit["code_relevant"] = is_code_relevant_commit(commit)
    for commit in remote_commits:
        commit["code_relevant"] = is_code_relevant_commit(commit)

    local_only_patch_ids = {c["patch_id"] for c in local_commits}
    remote_only_patch_ids = {c["patch_id"] for c in remote_commits}
    patch_overlap = len(local_only_patch_ids.intersection(remote_only_patch_ids))

    file_deltas = []
    for change in parse_diff_raw(local_ref, remote_ref):
        path = change["new_path"] or change["old_path"]
        if path in ignored_paths:
            continue
        file_deltas.append(change)

    filtered_file_deltas = [item for item in file_deltas if not args.code_only or item["code_path"]]
    code_file_changes = sum(1 for item in file_deltas if item["code_path"])
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "local": {"requested_ref": local_ref, "resolved_ref": local_ref, "sha": local_sha},
        "remote": {"requested_ref": args.remote_ref, "resolved_ref": remote_ref, "sha": remote_sha},
        "merge_base": merge_base,
        "merge_base_datetime": merge_base_dt,
        "delta_status": "divergent" if local_only_patch_ids or remote_only_patch_ids else "sync",
        "counts": {
            "local_only_commits": len(local_commits),
            "remote_only_commits": len(remote_commits),
            "local_only_patch_ids": len(local_only_patch_ids),
            "remote_only_patch_ids": len(remote_only_patch_ids),
            "overlapping_patch_ids": patch_overlap,
            "total_file_changes": len(file_deltas),
            "code_file_changes": code_file_changes,
            "filtered_file_changes": len(filtered_file_deltas),
        },
        "local_commits": local_commits,
        "remote_commits": remote_commits,
        "file_deltas": file_deltas,
    }

    json_path = Path(args.json)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    write_markdown(Path(args.markdown), payload, args.code_only)

    print(f"Wrote JSON: {json_path}")
    print(f"Wrote markdown: {args.markdown}")

    if args.strict and payload["counts"]["filtered_file_changes"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
