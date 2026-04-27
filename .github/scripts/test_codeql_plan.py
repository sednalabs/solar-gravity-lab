from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("resolve_codeql_plan.py")


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


class CodeqlPlanTests(unittest.TestCase):
    def test_docs_only_pr_skips_analysis(self) -> None:
        plan = run_plan(["docs/validation-lab.md"])

        self.assertEqual(plan["has_codeql_relevant_changes"], "false")
        self.assertEqual(matrix_languages(plan), [])

    def test_rust_only_pr_uses_rust_fast_config(self) -> None:
        plan = run_plan(["engine/physics/src/lib.rs"])
        rows = json.loads(plan["matrix"])["include"]

        self.assertEqual(matrix_languages(plan), ["rust"])
        self.assertEqual(rows[0]["config_file"], "./.github/codeql/codeql-rust-pr.yml")
        self.assertEqual(plan["run_all_languages"], "false")

    def test_android_pr_selects_java_kotlin(self) -> None:
        plan = run_plan(["clients/android/app/src/main/java/com/example/Stage.kt"])

        self.assertEqual(matrix_languages(plan), ["java-kotlin"])

    def test_proto_boundary_selects_android_and_rust_consumers(self) -> None:
        plan = run_plan(["proto/runtime.proto"])

        self.assertEqual(matrix_languages(plan), ["java-kotlin", "rust"])

    def test_codeql_policy_change_forces_full_scan(self) -> None:
        plan = run_plan([".github/codeql/codeql-config.yml"])

        self.assertEqual(plan["run_all_languages"], "true")
        self.assertEqual(
            matrix_languages(plan),
            ["actions", "c-cpp", "java-kotlin", "python", "rust"],
        )

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
        self.assertEqual(
            matrix_languages(plan),
            ["actions", "c-cpp", "java-kotlin", "python", "rust"],
        )


if __name__ == "__main__":
    unittest.main()
