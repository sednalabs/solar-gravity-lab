#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

PRODUCT_PREFIXES = (
    "clients/android",
    "feature-lab",
    "engine/",
    "render-core",
    "render/",
    "core-model",
    "core-simulation",
    "proto/",
)

AUTOMATION_PREFIXES = (
    ".github/",
    "infra/",
)

DOC_PREFIXES = (
    "docs/",
    "README.md",
    "AGENTS.md",
)

AREA_RULES: tuple[tuple[str, str], ...] = (
    ("clients/android/app/src/androidTest", "Android validation"),
    ("clients/android/app/src/test", "Android validation"),
    ("clients/android/app/src/main", "Android app"),
    ("clients/android", "Android app"),
    ("feature-lab/src/main/cpp", "Native Vulkan renderer"),
    ("feature-lab/src/main/java", "Android render bridge"),
    ("feature-lab", "Feature lab"),
    ("engine/runtime", "Engine runtime"),
    ("engine/physics", "Physics engine"),
    ("engine/hardware", "Hardware profiling"),
    ("engine/ffi", "Native bridge"),
    ("engine/history", "History engine"),
    ("engine/scene", "Scene model"),
    ("render-core", "Render core"),
    ("render/", "Render pipeline"),
    ("core-simulation", "Simulation core"),
    ("core-model", "Core model"),
    ("proto/", "Protocol/schema"),
    (".github/workflows", "CI and release automation"),
    (".github/actions", "CI and build tooling"),
    (".github/scripts", "CI and release automation"),
    (".github/", "CI and build tooling"),
    ("infra/", "Infrastructure"),
    ("docs/", "Documentation"),
    ("README.md", "Documentation"),
    ("AGENTS.md", "Documentation"),
)

BREAKING_PATTERNS = (
    r"\bbreaking\b",
    r"\bdrop\b",
    r"\bremove\b",
    r"\brename\b",
    r"\bmigrate\b",
    r"\bincompatible\b",
)

CONVENTIONAL_SUBJECT_RE = re.compile(
    r"^(?P<kind>[a-z]+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$",
    flags=re.IGNORECASE,
)


@dataclass(frozen=True)
class CommitRow:
    sha: str
    short_sha: str
    subject: str
    changed_files_count: int
    paths: tuple[str, ...]


def normalize_subject(subject: str) -> str:
    text = subject.strip()
    merge_match = re.match(r"^Merge pull request #\d+ from .+?\n?(.*)$", text, flags=re.IGNORECASE)
    if merge_match and merge_match.group(1).strip():
        text = merge_match.group(1).strip()

    conventional = CONVENTIONAL_SUBJECT_RE.match(text)
    if conventional:
        scope = conventional.group("scope")
        desc = conventional.group("desc").strip()
        if scope:
            desc = f"{scope}: {desc}"
        text = desc

    text = text.replace("jni", "JNI").replace("api", "API").replace("ci", "CI")
    if text:
        text = text[0].upper() + text[1:]
    return text


def path_matches(path: str, prefixes: Iterable[str]) -> bool:
    return any(path == prefix or path.startswith(prefix.rstrip("/") + "/") for prefix in prefixes)


def classify_commit(paths: tuple[str, ...]) -> str:
    if not paths:
        return "misc"

    has_product = any(path_matches(path, PRODUCT_PREFIXES) for path in paths)
    has_automation = any(path_matches(path, AUTOMATION_PREFIXES) for path in paths)
    has_docs = any(path_matches(path, DOC_PREFIXES) for path in paths)
    non_docs_paths = [path for path in paths if not path_matches(path, DOC_PREFIXES)]

    if has_product:
        return "product"
    if has_automation and not any(path_matches(path, PRODUCT_PREFIXES) for path in non_docs_paths):
        return "automation"
    if has_docs and not non_docs_paths:
        return "docs"
    return "misc"


def commit_areas(paths: tuple[str, ...]) -> list[str]:
    resolved: list[str] = []
    for path in paths:
        for prefix, label in AREA_RULES:
            if path == prefix or path.startswith(prefix):
                resolved.append(label)
                break
        else:
            resolved.append("Miscellaneous")
    counts = Counter(resolved)
    return [label for label, _ in counts.most_common()]


def summarize_focus(subsystem_summary: dict[str, int]) -> str:
    area_counts: Counter[str] = Counter()
    for subsystem, count in subsystem_summary.items():
        label = "Miscellaneous"
        for prefix, area in AREA_RULES:
            if subsystem == prefix or subsystem.startswith(prefix):
                label = area
                break
        area_counts[label] += int(count)

    dominant = [label for label, _ in area_counts.most_common(3) if label != "Documentation"]
    if not dominant:
        dominant = [label for label, _ in area_counts.most_common(3)]
    if not dominant:
        return "This release does not contain enough subsystem data to generate a focus summary."

    if len(dominant) == 1:
        focus = dominant[0]
    elif len(dominant) == 2:
        focus = f"{dominant[0]} and {dominant[1]}"
    else:
        focus = f"{dominant[0]}, {dominant[1]}, and {dominant[2]}"

    return f"This release is primarily concentrated in {focus}."


def render_highlights(commits: list[CommitRow]) -> list[str]:
    area_counts: Counter[str] = Counter()
    for commit in commits:
        for area in commit_areas(commit.paths)[:2]:
            if area != "Documentation":
                area_counts[area] += 1

    highlights: list[str] = []
    for area, count in area_counts.most_common(4):
        if area == "Miscellaneous":
            continue
        noun = "commit" if count == 1 else "commits"
        highlights.append(f"{area} appears in {count} substantive {noun}.")
    return highlights


def render_commit_bullets(commits: list[CommitRow], repo: str, limit: int) -> list[str]:
    ranked = sorted(
        commits,
        key=lambda item: (item.changed_files_count, len(item.paths)),
        reverse=True,
    )
    bullets: list[str] = []
    seen_subjects: set[str] = set()
    for commit in ranked:
        subject = normalize_subject(commit.subject)
        if subject in seen_subjects:
            continue
        seen_subjects.add(subject)
        bullets.append(
            f"- {subject} ([`{commit.short_sha}`](https://github.com/{repo}/commit/{commit.sha}))"
        )
        if len(bullets) >= limit:
            break
    return bullets


def detect_breaking_changes(commits: list[CommitRow]) -> list[str]:
    rows: list[str] = []
    for commit in commits:
        lowered = commit.subject.lower()
        if any(re.search(pattern, lowered) for pattern in BREAKING_PATTERNS):
            rows.append(normalize_subject(commit.subject))
    return rows


def parse_inventory(path: Path) -> dict:
    payload = json.loads(path.read_text(encoding="utf-8"))
    required_keys = {"version", "baseline_tag", "head", "commit_count", "subsystem_summary", "commits"}
    missing = required_keys - payload.keys()
    if missing:
        missing_text = ", ".join(sorted(missing))
        raise ValueError(f"Inventory file is missing required keys: {missing_text}")
    return payload


def to_commit_rows(payload: dict) -> list[CommitRow]:
    rows: list[CommitRow] = []
    for row in payload["commits"]:
        rows.append(
            CommitRow(
                sha=row["sha"],
                short_sha=row["short_sha"],
                subject=row["subject"],
                changed_files_count=int(row.get("changed_files_count", 0)),
                paths=tuple(row.get("paths", [])),
            )
        )
    return rows


def build_markdown(payload: dict, repo: str) -> str:
    commits = to_commit_rows(payload)
    product_commits = [commit for commit in commits if classify_commit(commit.paths) == "product"]
    automation_commits = [commit for commit in commits if classify_commit(commit.paths) == "automation"]
    docs_commits = [commit for commit in commits if classify_commit(commit.paths) == "docs"]
    breaking_changes = detect_breaking_changes(commits)

    lines: list[str] = []
    lines.append("## Overview")
    lines.append(summarize_focus(payload["subsystem_summary"]))
    lines.append("")
    lines.append("## Highlights")
    highlight_rows = render_highlights(product_commits or commits)
    if highlight_rows:
        lines.extend(f"- {row}" for row in highlight_rows)
    else:
        lines.append("- No substantive highlights could be inferred from the git inventory.")
    lines.append("")

    if breaking_changes:
        lines.append("## Breaking or migration-sensitive changes")
        for row in breaking_changes[:5]:
            lines.append(f"- {row}")
        lines.append("")

    lines.append("## Product and runtime changes")
    product_rows = render_commit_bullets(product_commits, repo=repo, limit=7)
    if product_rows:
        lines.extend(product_rows)
    else:
        lines.append("- No product/runtime changes were inferred from this release inventory.")
    lines.append("")

    lines.append("## Developer platform and CI changes")
    automation_rows = render_commit_bullets(automation_commits, repo=repo, limit=5)
    if automation_rows:
        lines.extend(automation_rows)
    else:
        lines.append("- No CI or build-automation changes were inferred from this release inventory.")
    lines.append("")

    if docs_commits:
        lines.append("## Documentation")
        lines.extend(render_commit_bullets(docs_commits, repo=repo, limit=4))
        lines.append("")

    lines.append("## Release range")
    lines.append(f"- Baseline: `{payload['baseline_tag']}`")
    lines.append(f"- Head: [`{payload['head'][:12]}`](https://github.com/{repo}/commit/{payload['head']})")
    lines.append(f"- Commits in range: `{payload['commit_count']}`")
    lines.append("")
    lines.append("## Full inventory")
    lines.append(
        "The release assets include the authoritative `changelog-*.json` and `changelog-*.md` "
        "inventory files for the raw commit and path-level detail."
    )
    lines.append("")

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Render human-facing release notes from changelog inventory JSON.")
    parser.add_argument("--inventory", required=True, help="Path to changelog inventory JSON.")
    parser.add_argument("--repo", required=True, help="Repository name in owner/name form.")
    parser.add_argument("--output", required=True, help="Path to write the rendered markdown.")
    args = parser.parse_args()

    payload = parse_inventory(Path(args.inventory))
    markdown = build_markdown(payload, repo=args.repo)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(markdown.rstrip() + "\n", encoding="utf-8")
    print(f"Rendered release notes to {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
