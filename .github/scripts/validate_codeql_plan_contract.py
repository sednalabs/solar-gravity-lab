#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from ci_surfaces import ALL_CODEQL_LANGUAGES


EXPECTED_BUILD_MODES = {
    "actions": "none",
    "c-cpp": "none",
    "java-kotlin": "manual",
    "python": "none",
    "rust": "none",
}

EXPECTED_CONFIG_FILES = {
    "actions": "./.github/codeql/codeql-actions-security.yml",
    "c-cpp": "./.github/codeql/codeql-config.yml",
    "java-kotlin": "./.github/codeql/codeql-config.yml",
    "python": "./.github/codeql/codeql-python-security.yml",
    "rust": "./.github/codeql/codeql-config.yml",
}


class ContractError(ValueError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate the emitted CodeQL plan contract.")
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--matrix-json", required=True)
    parser.add_argument("--languages", required=True)
    parser.add_argument("--has-codeql-relevant-changes", required=True)
    parser.add_argument("--run-all-languages", required=True)
    parser.add_argument("--reason", default="")
    return parser.parse_args()


def parse_bool(name: str, value: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise ContractError(f"{name} must be 'true' or 'false', got {value!r}")


def parse_languages(value: str) -> tuple[str, ...]:
    return tuple(language for language in (item.strip() for item in value.split(",")) if language)


def parse_matrix_rows(value: str) -> list[dict[str, str]]:
    try:
        payload = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ContractError(f"matrix must be valid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise ContractError("matrix must be a JSON object")
    include = payload.get("include")
    if not isinstance(include, list):
        raise ContractError("matrix.include must be a list")

    rows: list[dict[str, str]] = []
    for index, row in enumerate(include):
        if not isinstance(row, dict):
            raise ContractError(f"matrix.include[{index}] must be an object")
        normalized: dict[str, str] = {}
        for key in ("language", "build-mode", "config_file"):
            value = row.get(key)
            if not isinstance(value, str) or not value.strip():
                raise ContractError(f"matrix.include[{index}].{key} must be a non-empty string")
            normalized[key] = value.strip()
        rows.append(normalized)
    return rows


def expect_exact_default_categories(languages: tuple[str, ...], context: str, errors: list[str]) -> None:
    if languages != ALL_CODEQL_LANGUAGES:
        errors.append(
            f"{context} must scan all default CodeQL categories in order: "
            f"{','.join(ALL_CODEQL_LANGUAGES)}"
        )


def validate_plan_contract(
    *,
    event_name: str,
    matrix_json: str,
    languages: str,
    has_codeql_relevant_changes: str,
    run_all_languages: str,
    reason: str = "",
) -> None:
    has_relevant_changes = parse_bool("has_codeql_relevant_changes", has_codeql_relevant_changes)
    full_scan = parse_bool("run_all_languages", run_all_languages)
    output_languages = parse_languages(languages)
    rows = parse_matrix_rows(matrix_json)
    row_languages = tuple(row["language"] for row in rows)
    errors: list[str] = []

    if len(row_languages) != len(set(row_languages)):
        errors.append("matrix.include must not contain duplicate languages")
    if row_languages != output_languages:
        errors.append("languages output must exactly match matrix.include language order")

    unknown_languages = sorted(set(row_languages) - set(ALL_CODEQL_LANGUAGES))
    if unknown_languages:
        errors.append(f"matrix contains unsupported CodeQL languages: {','.join(unknown_languages)}")

    for row in rows:
        language = row["language"]
        expected_build_mode = EXPECTED_BUILD_MODES.get(language)
        expected_config_file = EXPECTED_CONFIG_FILES.get(language)
        if expected_build_mode is not None and row["build-mode"] != expected_build_mode:
            errors.append(f"{language} build-mode must be {expected_build_mode!r}")
        if expected_config_file is not None and row["config_file"] != expected_config_file:
            errors.append(f"{language} config_file must be {expected_config_file!r}")

    if has_relevant_changes:
        if not row_languages:
            errors.append("CodeQL-relevant changes require a non-empty analysis matrix")
        if not full_scan:
            errors.append("CodeQL-relevant changes must set run_all_languages=true")
    else:
        if full_scan:
            errors.append("non-relevant changes must not set run_all_languages=true")
        if row_languages:
            errors.append("non-relevant changes must not emit analysis matrix rows")

    if full_scan:
        expect_exact_default_categories(row_languages, "full CodeQL plan", errors)

    if event_name == "pull_request" and has_relevant_changes:
        expect_exact_default_categories(
            row_languages,
            "pull_request CodeQL-relevant plan",
            errors,
        )
    elif event_name != "pull_request":
        if not has_relevant_changes:
            errors.append(f"{event_name} CodeQL plan must mark changes as relevant")
        if not full_scan:
            errors.append(f"{event_name} CodeQL plan must use a full scan")
        expect_exact_default_categories(row_languages, f"{event_name} CodeQL plan", errors)

    if errors:
        reason_detail = f"\nreason: {reason}" if reason else ""
        raise ContractError("CodeQL plan contract failed:\n- " + "\n- ".join(errors) + reason_detail)


def main() -> None:
    args = parse_args()
    validate_plan_contract(
        event_name=args.event_name.strip(),
        matrix_json=args.matrix_json,
        languages=args.languages,
        has_codeql_relevant_changes=args.has_codeql_relevant_changes,
        run_all_languages=args.run_all_languages,
        reason=args.reason,
    )
    print("CodeQL plan contract ok")


if __name__ == "__main__":
    main()
