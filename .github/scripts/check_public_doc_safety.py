#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DOC_PATHS = ("README.md", "docs/**/*.md")
SUSPICIOUS_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"/home/[A-Za-z0-9_.-]+/"),
        "personal Linux home path",
    ),
    (
        re.compile(r"/Users/[A-Za-z0-9_. -]+/"),
        "personal macOS home path",
    ),
    (
        re.compile(r"[A-Za-z]:\\\\Users\\\\"),
        "personal Windows home path",
    ),
    (
        re.compile(r"~/.codex(?:/|\\b)"),
        "local Codex-only path",
    ),
    (
        re.compile(r"\\bTruthCore\\b"),
        "private placeholder name",
    ),
)


def iter_markdown_files() -> list[Path]:
    files: set[Path] = set()
    for pattern in DOC_PATHS:
        files.update(ROOT.glob(pattern))
    return sorted(path for path in files if path.is_file())


def changed_files() -> set[Path]:
    cmd = ["git", "-C", str(ROOT), "diff", "--name-only", "--cached"]
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if result.returncode == 0 and result.stdout.strip():
        return {ROOT / line.strip() for line in result.stdout.splitlines() if line.strip()}

    working_tree = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--name-only", "HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    if working_tree.returncode == 0 and working_tree.stdout.strip():
        return {ROOT / line.strip() for line in working_tree.stdout.splitlines() if line.strip()}

    changed: set[Path] = set()
    env_base = None
    if "GITHUB_BASE_REF" in __import__("os").environ:
        base_ref = __import__("os").environ["GITHUB_BASE_REF"].strip()
        if base_ref:
            env_base = f"origin/{base_ref}"

    if env_base:
        fetch = subprocess.run(
            ["git", "-C", str(ROOT), "fetch", "origin", env_base],
            capture_output=True,
            text=True,
            check=False,
        )
        diff = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--name-only", f"{env_base}...HEAD"],
            capture_output=True,
            text=True,
            check=False,
        )
        if fetch.returncode == 0 and diff.returncode == 0 and diff.stdout.strip():
            changed = {ROOT / line.strip() for line in diff.stdout.splitlines() if line.strip()}

    if changed:
        return changed

    head_range = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--name-only", "HEAD^", "HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    if head_range.returncode == 0 and head_range.stdout.strip():
        return {ROOT / line.strip() for line in head_range.stdout.splitlines() if line.strip()}

    return set(iter_markdown_files())


def is_checked_markdown(path: Path) -> bool:
    if not path.is_file():
        return False
    rel = path.relative_to(ROOT)
    if rel == Path("README.md"):
        return True
    return rel.parts and rel.parts[0] == "docs" and rel.suffix == ".md"


def main() -> int:
    failures: list[str] = []
    candidates = sorted(path for path in changed_files() if is_checked_markdown(path))

    if not candidates:
        print("No changed public markdown files to scan.")
        return 0

    for doc_path in candidates:
        relative_doc = doc_path.relative_to(ROOT)
        content = doc_path.read_text(encoding="utf-8")
        for line_no, line in enumerate(content.splitlines(), start=1):
            for pattern, description in SUSPICIOUS_PATTERNS:
                if pattern.search(line):
                    failures.append(
                        f"{relative_doc}:{line_no} -> {description}: {line.strip()}"
                    )

    print(f"Checked {len(candidates)} changed markdown files for publication-safety leaks.")

    if failures:
        print("\nSuspicious publication-safety content detected:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        print(
            "\nIf the content is genuinely intended for the public repo, rewrite it in "
            "generic current-implementation terms instead of personal or local-machine terms.",
            file=sys.stderr,
        )
        return 1

    print("No suspicious publication-safety markers found in changed markdown files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
