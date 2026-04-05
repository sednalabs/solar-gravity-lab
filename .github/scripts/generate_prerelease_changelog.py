#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
import re


SECTION_ORDER = [
    "Features",
    "Fixes",
    "Performance",
    "Physics and Accuracy",
    "Validation and CI",
    "Documentation",
    "Maintenance",
    "Other Changes",
]


@dataclass(frozen=True)
class CommitEntry:
    sha: str
    subject: str


def git(repo_root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def commit_subjects(repo_root: Path, revision_range: str) -> list[CommitEntry]:
    output = git(repo_root, "log", "--reverse", "--format=%H%x1f%s", revision_range)
    if not output:
        return []
    commits = []
    for line in output.splitlines():
        sha, subject = line.split("\x1f", 1)
        cleaned_subject = subject.strip()
        if cleaned_subject.startswith("Merge pull request #"):
            continue
        if cleaned_subject.startswith("Merge branch "):
            continue
        lower_subject = cleaned_subject.lower()
        if lower_subject.startswith("release: prepare "):
            continue
        if lower_subject.startswith("chore(release): prepare "):
            continue
        commits.append(CommitEntry(sha=sha, subject=cleaned_subject))
    return commits


def merged_tags(repo_root: Path, ref: str) -> list[str]:
    output = git(repo_root, "tag", "--merged", ref, "--sort=-creatordate")
    return [line.strip() for line in output.splitlines() if line.strip()]


def tag_target(repo_root: Path, tag: str) -> str:
    return git(repo_root, "rev-list", "-n", "1", tag)


def previous_release_tag(repo_root: Path, ref: str) -> str | None:
    ref_name = ref.removeprefix("refs/tags/")
    target_sha = git(repo_root, "rev-list", "-n", "1", ref)
    candidates = merged_tags(repo_root, ref)
    for tag in candidates:
        if tag == ref or tag == ref_name:
            continue
        if tag_target(repo_root, tag) == target_sha:
            continue
        return tag
    return None


def initial_commit(repo_root: Path, ref: str) -> str:
    return git(repo_root, "rev-list", "--max-parents=0", ref).splitlines()[0]


def categorize(subject: str) -> str:
    lower = subject.lower()
    conventional = re.match(r"^(?P<type>[a-z]+)(?:\([^)]+\))?!?:\s+(?P<rest>.+)$", lower)
    if conventional:
        commit_type = conventional.group("type")
        if commit_type == "feat":
            return "Features"
        if commit_type == "fix":
            return "Fixes"
        if commit_type == "perf":
            return "Performance"
        if commit_type in {"physics", "sim"}:
            return "Physics and Accuracy"
        if commit_type in {"ci", "build"}:
            return "Validation and CI"
        if commit_type == "test":
            return "Validation and CI"
        if commit_type == "docs":
            return "Documentation"
        if commit_type in {"chore", "refactor", "style"}:
            return "Maintenance"

    if "physics" in lower or "tracer parity" in lower or "accuracy" in lower:
        return "Physics and Accuracy"
    if "validation" in lower or "workflow" in lower or "ci" in lower:
        return "Validation and CI"
    if "readme" in lower or "docs" in lower or "release channels" in lower:
        return "Documentation"
    return "Other Changes"


def grouped(commits: list[CommitEntry]) -> dict[str, list[CommitEntry]]:
    groups: dict[str, list[CommitEntry]] = {section: [] for section in SECTION_ORDER}
    for commit in commits:
        groups[categorize(commit.subject)].append(commit)
    return {section: entries for section, entries in groups.items() if entries}


def render_markdown(
    *,
    ref: str,
    head_sha: str,
    previous_tag: str | None,
    revision_range: str,
    commits: list[CommitEntry],
) -> str:
    header = [
        "# Internal Dev Preview Changelog",
        "",
        f"- target ref: `{ref}`",
        f"- target commit: `{head_sha}`",
        f"- previous release tag: `{previous_tag}`" if previous_tag else "- previous release tag: none",
        f"- commit range: `{revision_range}`",
        f"- commit count: `{len(commits)}`",
        "",
    ]

    if not commits:
        header.extend(
            [
                "No code or documentation commits were found in this release range.",
                "",
            ]
        )
        return "\n".join(header)

    sections = []
    for section, entries in grouped(commits).items():
        sections.append(f"## {section}")
        sections.append("")
        for commit in entries:
            sections.append(f"- {commit.subject} (`{commit.sha[:7]}`)")
        sections.append("")

    return "\n".join(header + sections).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--output", required=True)
    parser.add_argument("--json-output")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    ref = args.ref
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    head_sha = git(repo_root, "rev-parse", ref)
    previous_tag = previous_release_tag(repo_root, ref)
    if previous_tag:
        revision_spec = f"{previous_tag}..{ref}"
        revision_range = revision_spec
    else:
        first_commit = initial_commit(repo_root, ref)
        revision_spec = ref
        revision_range = f"{first_commit}..{ref} (initial baseline)"
    commits = commit_subjects(repo_root, revision_spec)

    markdown = render_markdown(
        ref=ref,
        head_sha=head_sha,
        previous_tag=previous_tag,
        revision_range=revision_range,
        commits=commits,
    )
    output_path.write_text(markdown)

    if args.json_output:
        json_path = Path(args.json_output)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(
            json.dumps(
                {
                    "target_ref": ref,
                    "target_commit": head_sha,
                    "previous_release_tag": previous_tag,
                    "commit_range": revision_range,
                    "commit_count": len(commits),
                    "groups": {
                        section: [
                            {"sha": commit.sha, "subject": commit.subject}
                            for commit in entries
                        ]
                        for section, entries in grouped(commits).items()
                    },
                },
                indent=2,
            )
            + "\n"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
