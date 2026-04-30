from __future__ import annotations

import json
import unittest

from resolve_codeql_plan import pull_request_plan
from validate_codeql_plan_contract import ContractError, validate_plan_contract


def assert_valid_contract(testcase: unittest.TestCase, plan: dict[str, str], event_name: str = "pull_request") -> None:
    try:
        validate_plan_contract(
            event_name=event_name,
            matrix_json=plan["matrix"],
            languages=plan["languages"],
            has_codeql_relevant_changes=plan["has_codeql_relevant_changes"],
            run_all_languages=plan["run_all_languages"],
            reason=plan["reason"],
        )
    except ContractError as exc:
        testcase.fail(str(exc))


class CodeqlPlanContractTests(unittest.TestCase):
    def test_docs_only_pull_request_contract(self) -> None:
        assert_valid_contract(self, pull_request_plan(["docs/validation-lab.md"]))

    def test_android_pull_request_requires_full_category_parity(self) -> None:
        assert_valid_contract(self, pull_request_plan(["clients/android/app/src/main/java/com/example/Stage.kt"]))

    def test_relevant_pull_request_rejects_partial_language_matrix(self) -> None:
        matrix = {
            "include": [
                {
                    "language": "java-kotlin",
                    "build-mode": "manual",
                    "config_file": "./.github/codeql/codeql-config.yml",
                }
            ]
        }

        with self.assertRaisesRegex(ContractError, "pull_request CodeQL-relevant plan"):
            validate_plan_contract(
                event_name="pull_request",
                matrix_json=json.dumps(matrix, separators=(",", ":")),
                languages="java-kotlin",
                has_codeql_relevant_changes="true",
                run_all_languages="true",
                reason="regression fixture",
            )

    def test_rejects_languages_output_that_does_not_match_matrix(self) -> None:
        plan = pull_request_plan(["engine/physics/src/lib.rs"])

        with self.assertRaisesRegex(ContractError, "languages output"):
            validate_plan_contract(
                event_name="pull_request",
                matrix_json=plan["matrix"],
                languages="java-kotlin",
                has_codeql_relevant_changes=plan["has_codeql_relevant_changes"],
                run_all_languages=plan["run_all_languages"],
                reason=plan["reason"],
            )

    def test_rejects_wrong_language_config_file(self) -> None:
        plan = pull_request_plan([".github/workflows/codeql.yml"])
        matrix = json.loads(plan["matrix"])
        for row in matrix["include"]:
            if row["language"] == "actions":
                row["config_file"] = "./.github/codeql/codeql-config.yml"

        with self.assertRaisesRegex(ContractError, "actions config_file"):
            validate_plan_contract(
                event_name="pull_request",
                matrix_json=json.dumps(matrix, separators=(",", ":")),
                languages=plan["languages"],
                has_codeql_relevant_changes=plan["has_codeql_relevant_changes"],
                run_all_languages=plan["run_all_languages"],
                reason=plan["reason"],
            )


if __name__ == "__main__":
    unittest.main()
