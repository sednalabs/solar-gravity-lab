#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ci_surfaces import ALL_CODEQL_LANGUAGES, summarize_paths


ADVANCED_CONFIG_FILE = "./.github/codeql/codeql-config.yml"
RUST_PR_CONFIG_FILE = "./.github/codeql/codeql-rust-pr.yml"

BUILD_MODES = {
    "actions": "none",
    "c-cpp": "none",
    "java-kotlin": "manual",
    "python": "none",
    "rust": "none",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Resolve CodeQL language routing.")
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--changed-files-json", default="")
    parser.add_argument("--expected-changed-files", default="")
    return parser.parse_args()


def parse_expected_count(value: str) -> int | None:
    value = value.strip()
    if not value:
        return None
    try:
        parsed = int(value)
    except ValueError as exc:
        raise SystemExit(f"expected changed file count must be an integer: {value}") from exc
    if parsed < 0:
        raise SystemExit("expected changed file count must be non-negative")
    return parsed


def parse_changed_files_json(value: str, expected_count: int | None) -> tuple[list[str] | None, str]:
    if not value.strip():
        return None, "trusted PR file metadata was unavailable"
    try:
        payload = json.loads(value)
    except json.JSONDecodeError as exc:
        return None, f"trusted PR file metadata was invalid JSON: {exc}"
    if not isinstance(payload, list) or not all(isinstance(item, str) for item in payload):
        return None, "trusted PR file metadata must be an array of strings"

    files = [item for item in payload if item.strip()]
    if expected_count is not None and len(set(files)) < expected_count:
        return None, f"trusted PR file metadata was incomplete: {len(set(files))} of {expected_count} files"
    return sorted(set(files)), ""


def config_file_for(language: str, event_name: str, full_scan: bool) -> str:
    if language == "rust" and event_name == "pull_request" and not full_scan:
        return RUST_PR_CONFIG_FILE
    return ADVANCED_CONFIG_FILE


def matrix_for(languages: tuple[str, ...], event_name: str, full_scan: bool) -> dict[str, list[dict[str, str]]]:
    return {
        "include": [
            {
                "language": language,
                "build-mode": BUILD_MODES[language],
                "config_file": config_file_for(language, event_name, full_scan),
            }
            for language in languages
        ]
    }


def plan(
    *,
    event_name: str,
    languages: tuple[str, ...],
    full_scan: bool,
    has_relevant_changes: bool,
    reason: str,
    surfaces: tuple[str, ...] = (),
) -> dict[str, str]:
    return {
        "matrix": json.dumps(matrix_for(languages, event_name, full_scan), separators=(",", ":")),
        "languages": ",".join(languages),
        "surfaces": ",".join(surfaces),
        "has_codeql_relevant_changes": str(has_relevant_changes).lower(),
        "run_all_languages": str(full_scan).lower(),
        "reason": reason,
    }


def full_plan(event_name: str, reason: str) -> dict[str, str]:
    return plan(
        event_name=event_name,
        languages=ALL_CODEQL_LANGUAGES,
        full_scan=True,
        has_relevant_changes=True,
        reason=reason,
        surfaces=(),
    )


def pull_request_plan(changed_files: list[str]) -> dict[str, str]:
    summary = summarize_paths(changed_files)
    surfaces = tuple(sorted(summary.surfaces))
    if summary.is_docs_only:
        return plan(
            event_name="pull_request",
            languages=(),
            full_scan=False,
            has_relevant_changes=False,
            reason="documentation-only change; CodeQL analysis not required",
            surfaces=surfaces,
        )
    if summary.is_codeql_critical:
        return plan(
            event_name="pull_request",
            languages=ALL_CODEQL_LANGUAGES,
            full_scan=True,
            has_relevant_changes=True,
            reason="critical CI, CodeQL, release, cache, or toolchain surface changed",
            surfaces=surfaces,
        )
    if not summary.codeql_languages:
        return plan(
            event_name="pull_request",
            languages=(),
            full_scan=False,
            has_relevant_changes=False,
            reason="no CodeQL-owned language surfaces changed",
            surfaces=surfaces,
        )
    return plan(
        event_name="pull_request",
        languages=summary.codeql_languages,
        full_scan=False,
        has_relevant_changes=True,
        reason=f"matched changed surfaces for {','.join(summary.codeql_languages)}",
        surfaces=surfaces,
    )


def main() -> None:
    args = parse_args()
    event_name = args.event_name.strip()
    if event_name != "pull_request":
        print(json.dumps(full_plan(event_name, f"{event_name} requires full CodeQL scan"), separators=(",", ":")))
        return

    expected_count = parse_expected_count(args.expected_changed_files)
    changed_files, error = parse_changed_files_json(args.changed_files_json, expected_count)
    if changed_files is None:
        print(json.dumps(full_plan(event_name, error), separators=(",", ":")))
        return
    print(json.dumps(pull_request_plan(changed_files), separators=(",", ":")))


if __name__ == "__main__":
    main()
