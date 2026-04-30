from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("resolve_codeql_plan.py")
ALL_CODEQL_LANGUAGES = ["actions", "c-cpp", "java-kotlin", "python", "rust"]


def run_plan(
    files: list[str] | str,
    *,
    event_name: str = "pull_request",
    expected_changed_files: str | None = None,
) -> dict[str, str]:
    if isinstance(files, str):
        changed_files_json = files
    else:
        changed_files_json = json.dumps(files)
    command = [
        sys.executable,
        str(SCRIPT),
        "--event-name",
        event_name,
        "--changed-files-json",
        changed_files_json,
    ]
    if expected_changed_files is not None:
        command.extend(["--expected-changed-files", expected_changed_files])
    proc = subprocess.run(command, check=True, text=True, capture_output=True)
    return json.loads(proc.stdout)


def matrix_languages(plan: dict[str, str]) -> list[str]:
    return [row["language"] for row in json.loads(plan["matrix"])["include"]]


def matrix_rows(plan: dict[str, str]) -> list[dict[str, str]]:
    return json.loads(plan["matrix"])["include"]


def matrix_config_file(plan: dict[str, str], language: str) -> str:
    return next(row["config_file"] for row in matrix_rows(plan) if row["language"] == language)


class CodeqlPlanTests(unittest.TestCase):
    def test_docs_only_pr_skips_analysis(self) -> None:
        plan = run_plan(["docs/validation-lab.md"])

        self.assertEqual(plan["has_codeql_relevant_changes"], "false")
        self.assertEqual(matrix_languages(plan), [])

    def test_rust_only_pr_uses_full_category_parity(self) -> None:
        plan = run_plan(["engine/physics/src/lib.rs"])

        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(matrix_config_file(plan, "rust"), "./.github/codeql/codeql-config.yml")
        self.assertEqual(plan["run_all_languages"], "true")
        self.assertIn("category parity", plan["reason"])

    def test_android_pr_uses_full_category_parity(self) -> None:
        plan = run_plan(["clients/android/app/src/main/java/com/example/Stage.kt"])

        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(plan["run_all_languages"], "true")
        self.assertIn("category parity", plan["reason"])

    def test_actions_pr_uses_custom_actions_security_config(self) -> None:
        plan = run_plan([".github/workflows/prerelease-apk.yml"])

        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(matrix_config_file(plan, "actions"), "./.github/codeql/codeql-actions-security.yml")

    def test_python_pr_uses_full_category_parity_with_custom_python_security_config(self) -> None:
        plan = run_plan([".github/scripts/write_validation_summary.py"])

        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(matrix_config_file(plan, "python"), "./.github/codeql/codeql-python-security.yml")
        self.assertEqual(plan["run_all_languages"], "true")

    def test_proto_boundary_uses_full_category_parity(self) -> None:
        plan = run_plan(["proto/runtime.proto"])

        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(plan["run_all_languages"], "true")

    def test_codeql_policy_change_forces_full_scan(self) -> None:
        plan = run_plan([".github/codeql/codeql-config.yml"])

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)
        self.assertEqual(matrix_config_file(plan, "actions"), "./.github/codeql/codeql-actions-security.yml")
        self.assertEqual(matrix_config_file(plan, "python"), "./.github/codeql/codeql-python-security.yml")

    def test_product_invariant_pack_change_forces_full_scan(self) -> None:
        plan = run_plan(
            [
                ".github/codeql/packs/solar-actions-product-invariants/queries/"
                "ValidationLabMissingStageFirstProofSurface.ql"
            ]
        )

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)

    def test_incomplete_pr_metadata_forces_full_scan(self) -> None:
        plan = run_plan(["engine/physics/src/lib.rs"], expected_changed_files="2")

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertIn("incomplete", plan["reason"])

    def test_malformed_pr_metadata_forces_full_scan(self) -> None:
        plan = run_plan("{not-json")

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertIn("invalid JSON", plan["reason"])

    def test_push_forces_full_scan(self) -> None:
        plan = run_plan([], event_name="push")

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertCountEqual(matrix_languages(plan), ALL_CODEQL_LANGUAGES)


if __name__ == "__main__":
    unittest.main()
