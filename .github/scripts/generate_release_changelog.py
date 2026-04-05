#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


def run_git(args: list[str], cwd: Path = Path(".")) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        check=True,
        text=True,
        capture_output=True,
    )
    return result.stdout.strip()


def split_subsystem(path: str) -> str:
    if not path:
        return "misc"

    parts = path.split("/")
    if len(parts) >= 2 and parts[0] in {"clients", "engine", "render", "proto", ".github"}:
        return f"{parts[0]}/{parts[1]}"

    return parts[0]


def extract_pr_number(text: str) -> int | None:
    for pattern in (
        r"#(\d+)",
        r"Merge pull request #(\d+)",
        r"PR #(\d+)",
    ):
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if match:
            return int(match.group(1))
    return None


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int
    prerelease_line: str | None
    prerelease_index: int | None

    @property
    def core(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"

    @property
    def is_prerelease(self) -> bool:
        return self.prerelease_line is not None and self.prerelease_index is not None


def parse_prerelease(version_text: str) -> Version:
    prerelease_match = re.fullmatch(
        r"v?(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)-(?P<line>.+)\.(?P<index>\d+)",
        version_text,
    )
    if prerelease_match:
        return Version(
            major=int(prerelease_match.group("major")),
            minor=int(prerelease_match.group("minor")),
            patch=int(prerelease_match.group("patch")),
            prerelease_line=prerelease_match.group("line"),
            prerelease_index=int(prerelease_match.group("index")),
        )

    stable_match = re.fullmatch(
        r"v?(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)",
        version_text,
    )
    if stable_match:
        return Version(
            major=int(stable_match.group("major")),
            minor=int(stable_match.group("minor")),
            patch=int(stable_match.group("patch")),
            prerelease_line=None,
            prerelease_index=None,
        )

    raise ValueError(
        "Version must be a stable semver like '1.2.3' or a prerelease with explicit numeric "
        f"segment like '1.2.3-alpha.4': '{version_text}'."
    )


def resolve_previous_tag(version: Version, tags: Iterable[str]) -> str:
    if version.is_prerelease:
        candidates: list[tuple[int, str]] = []
        assert version.prerelease_line is not None
        assert version.prerelease_index is not None
        tag_pattern = re.compile(
            rf"^v{re.escape(version.core)}-{re.escape(version.prerelease_line)}\.(\d+)$"
        )

        for tag in tags:
            match = tag_pattern.match(tag)
            if not match:
                continue
            idx = int(match.group(1))
            if idx < version.prerelease_index:
                candidates.append((idx, tag))

        if not candidates:
            raise ValueError(
                f"No prior prerelease tag found for line '{version.core}-{version.prerelease_line}'. "
                "Baseline resolution is ambiguous because this is the first tag in this prerelease line."
            )

        candidates.sort(key=lambda item: item[0], reverse=True)
        return candidates[0][1]

    candidates: list[tuple[tuple[int, int, int], str]] = []
    for tag in tags:
        match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", tag)
        if not match:
            continue
        semver = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        if semver < (version.major, version.minor, version.patch):
            candidates.append((semver, tag))

    if not candidates:
        raise ValueError(
            f"No prior stable tag found before '{version.core}'. "
            "Baseline resolution is ambiguous because this appears to be the first stable tag."
        )

    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1]


def collect_commits(base_ref: str, head_ref: str) -> list[dict[str, str]]:
    log_format = "%H\x01%h\x01%s\x01%P"
    log_output = run_git(
        [
            "log",
            f"--format={log_format}",
            "--reverse",
            f"{base_ref}..{head_ref}",
        ]
    )

    if not log_output:
        return []

    raw_commits = log_output.splitlines()
    commits: list[dict[str, str]] = []

    for line in raw_commits:
        parts = line.split("\x01", maxsplit=3)
        if len(parts) < 4:
            raise ValueError(f"Unexpected git log output for line: {line!r}")
        full_sha, short_sha, subject, parents = parts
        body = run_git(["log", "-1", "--pretty=%B", "--no-color", full_sha])
        commit_paths = run_git(
            ["show", "--pretty=", "--name-only", "--first-parent", full_sha]
        ).splitlines()
        commit_paths = [path for path in commit_paths if path.strip()]

        subsystems = sorted({split_subsystem(path) for path in commit_paths})
        pr_number = extract_pr_number(f"{subject}\n{body}")

        commits.append(
            {
                "sha": full_sha,
                "short_sha": short_sha,
                "subject": subject,
                "pr_number": pr_number,
                "subsystems": subsystems,
                "paths": sorted(commit_paths),
                "changed_files_count": len(commit_paths),
                "parents": parents.split(),
            }
        )

    return commits


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def write_markdown(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    commits = payload["commits"]
    lines = [
        f"# Changelog for {payload['version']}",
        "",
        "## Range",
        f"- baseline: `{payload['baseline_tag']}`",
        f"- head: `{payload['head']}`",
        f"- commits: `{payload['commit_count']}`",
        "",
        "## Commits",
        "| SHA | PR | Subject | Subsystems | Paths |",
        "| --- | --- | --- | --- | --- |",
    ]

    for commit in commits:
        paths = ", ".join(commit["paths"]) if commit["paths"] else "(no file changes)"
        sub = ", ".join(commit["subsystems"]) if commit["subsystems"] else "misc"
        pr = f"#{commit['pr_number']}" if commit["pr_number"] is not None else ""
        lines.append(
            f"| `{commit['short_sha']}` | {pr} | {commit['subject']} | {sub} | {paths} |"
        )

    lines.append("")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate authoritative git-derived changelogs.")
    parser.add_argument("--version", required=True)
    parser.add_argument("--to", default="HEAD")
    parser.add_argument("--json-output")
    parser.add_argument("--markdown-output")
    parser.add_argument(
        "--dry-run", action="store_true", help="Only print planned baseline and commit counts."
    )

    args = parser.parse_args()

    try:
        version = parse_prerelease(args.version)
        all_tags = run_git(["tag", "--list"]).splitlines()
        baseline_tag = resolve_previous_tag(version, all_tags)
        head_ref = run_git(["rev-parse", args.to])
    except ValueError as exc:
        print(f"ERROR: {exc}", flush=True)
        return 1

    if args.dry_run:
        try:
            log_count = len(collect_commits(baseline_tag, head_ref))
        except ValueError as exc:
            print(f"ERROR: {exc}", flush=True)
            return 1
        print(
            f"DRY-RUN: version={args.version}, baseline={baseline_tag}, "
            f"to={head_ref}, commit_count={log_count}"
        )
        return 0

    if not args.json_output or not args.markdown_output:
        raise ValueError(
            "Both --json-output and --markdown-output are required unless --dry-run is used."
        )

    commit_rows = collect_commits(baseline_tag, head_ref)
    subsystem_summary: defaultdict[str, int] = defaultdict(int)
    for row in commit_rows:
        for subsystem in row["subsystems"]:
            subsystem_summary[subsystem] += 1

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "version": args.version,
        "baseline_tag": baseline_tag,
        "head": head_ref,
        "commit_count": len(commit_rows),
        "subsystem_summary": dict(sorted(subsystem_summary.items())),
        "commits": commit_rows,
    }

    write_json(Path(args.json_output), payload)
    write_markdown(Path(args.markdown_output), payload)

    print(f"Generated {len(commit_rows)} changelog entries from {baseline_tag}..{head_ref}")
    print(f"json={args.json_output}")
    print(f"markdown={args.markdown_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
