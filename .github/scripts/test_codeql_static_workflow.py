from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).parents[1] / "workflows" / "codeql.yml"

EXPECTED_MATRIX_ROWS = {
    "actions": ("none", "./.github/codeql/codeql-actions-security.yml"),
    "c-cpp": ("none", "./.github/codeql/codeql-config.yml"),
    "java-kotlin": ("manual", "./.github/codeql/codeql-config.yml"),
    "python": ("none", "./.github/codeql/codeql-python-security.yml"),
    "rust": ("none", "./.github/codeql/codeql-config.yml"),
}


def workflow_text() -> str:
    return WORKFLOW.read_text(encoding="utf-8")


def matrix_block(text: str) -> str:
    match = re.search(r"(?ms)^      matrix:\n(?P<body>.*?)(?=^    env:)", text)
    if match is None:
        raise AssertionError("CodeQL workflow must define a static analyze matrix before env")
    return match.group("body")


class CodeqlStaticWorkflowTests(unittest.TestCase):
    def test_static_matrix_covers_default_branch_categories(self) -> None:
        block = matrix_block(workflow_text())

        for language, (build_mode, config_file) in EXPECTED_MATRIX_ROWS.items():
            row_pattern = (
                rf"(?ms)- language: {re.escape(language)}\n"
                rf"\s+build-mode: {re.escape(build_mode)}\n"
                rf"\s+config_file: {re.escape(config_file)}"
            )
            self.assertRegex(block, row_pattern)

    def test_analysis_uploads_per_language_category(self) -> None:
        text = workflow_text()

        self.assertIn('category: "/language:${{ matrix.language }}"', text)

    def test_advanced_setup_still_uses_custom_config_and_manual_kotlin_build(self) -> None:
        text = workflow_text()

        self.assertIn("config-file: ${{ matrix.config_file }}", text)
        self.assertIn("build-mode: ${{ matrix.build-mode }}", text)
        self.assertIn("Build Java and Kotlin sources for CodeQL", text)
        self.assertIn("matrix.language == 'java-kotlin'", text)

    def test_dynamic_router_is_not_present(self) -> None:
        text = workflow_text()

        self.assertNotIn("CodeQL risk router", text)
        self.assertNotIn("resolve_codeql_plan", text)
        self.assertNotIn("validate_codeql_plan_contract", text)
        self.assertNotIn("needs.plan", text)
        self.assertNotIn("fromJSON(needs.plan.outputs.matrix)", text)


if __name__ == "__main__":
    unittest.main()
