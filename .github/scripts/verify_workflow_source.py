#!/usr/bin/env python3
"""Bind an optional source alias to this workflow's immutable event commit.

Run from an event-SHA checkout before build scripts or local actions. A separate
source input is a consistency check, never a second checkout authority.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections.abc import Callable
from urllib.parse import quote


FULL_SHA = re.compile(r"[0-9a-fA-F]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
MAX_REF_BYTES = 1024


class SourceError(ValueError):
    pass


def verify_source(requested_ref: str, workflow_sha: str,
                  resolve_ref: Callable[[str], str]) -> str:
    if not FULL_SHA.fullmatch(workflow_sha):
        raise SourceError("Workflow source must be a full 40-character commit SHA")
    workflow_sha = workflow_sha.lower()
    if not requested_ref:
        return workflow_sha
    if len(requested_ref.encode("utf-8")) > MAX_REF_BYTES or any(
        character.isspace() or ord(character) < 32 or ord(character) == 127
        for character in requested_ref
    ):
        raise SourceError("Requested source must be one ref of at most 1024 bytes without whitespace or control characters")
    resolved_sha = requested_ref if FULL_SHA.fullmatch(requested_ref) else resolve_ref(requested_ref)
    if not FULL_SHA.fullmatch(resolved_sha):
        raise SourceError("Requested source did not resolve to a full commit SHA")
    if resolved_sha.lower() != workflow_sha:
        raise SourceError(
            f"Requested source resolves to {resolved_sha.lower()}, but this workflow runs at {workflow_sha}. "
            "Dispatch the workflow on the intended branch or tag; ref may only identify that same commit."
        )
    return workflow_sha


def github_commit(repository: str, requested_ref: str) -> str:
    if not REPOSITORY.fullmatch(repository):
        raise SourceError("GITHUB_REPOSITORY must identify an owner/repository")
    endpoint = f"/repos/{repository}/commits/{quote(requested_ref, safe='')}"
    try:
        return subprocess.check_output(
            ["gh", "api", endpoint, "--jq", ".sha"],
            text=True, stderr=subprocess.PIPE, timeout=30,
        ).strip()
    except (OSError, subprocess.SubprocessError) as exc:
        raise SourceError("GitHub could not resolve the requested source; refusing to build a different commit") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="")
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    try:
        source = verify_source(args.ref, args.workflow_sha,
                               lambda ref: github_commit(args.repository, ref))
    except SourceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(source)
    return 0


if __name__ == "__main__":
    sys.exit(main())
