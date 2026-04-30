from __future__ import annotations

import unittest

from ci_surfaces import ALL_CODEQL_LANGUAGES, classify_path, summarize_paths


class CiSurfaceTests(unittest.TestCase):
    def test_docs_only_summary(self) -> None:
        summary = summarize_paths(["README.md", "docs/validation-lab.md"])

        self.assertTrue(summary.is_docs_only)
        self.assertFalse(summary.is_codeql_critical)
        self.assertEqual(summary.codeql_languages, ())

    def test_rust_surface(self) -> None:
        summary = summarize_paths(["engine/physics/src/lib.rs"])

        self.assertIn("rust", summary.surfaces)
        self.assertEqual(summary.codeql_languages, ("rust",))

    def test_android_surface_selects_java_kotlin(self) -> None:
        summary = summarize_paths(["clients/android/app/src/main/java/com/example/Stage.kt"])

        self.assertIn("android", summary.surfaces)
        self.assertEqual(summary.codeql_languages, ("java-kotlin",))

    def test_proto_boundary_selects_rust_and_java_kotlin(self) -> None:
        summary = summarize_paths(["proto/runtime.proto"])

        self.assertIn("generated_boundary", summary.surfaces)
        self.assertEqual(summary.codeql_languages, ("java-kotlin", "rust"))

    def test_codeql_policy_is_critical(self) -> None:
        summary = summarize_paths([".github/codeql/codeql-config.yml"])

        self.assertTrue(summary.is_codeql_critical)
        self.assertEqual(summary.codeql_languages, ALL_CODEQL_LANGUAGES)

    def test_product_invariant_pack_is_critical(self) -> None:
        summary = summarize_paths(
            [
                ".github/codeql/packs/solar-python-product-invariants/queries/"
                "ValidationPlannerMissingRuntimeCpuTruthLane.ql"
            ]
        )

        self.assertTrue(summary.is_codeql_critical)
        self.assertEqual(summary.codeql_languages, ALL_CODEQL_LANGUAGES)

    def test_workflow_is_critical(self) -> None:
        summary = summarize_paths([".github/workflows/codeql.yml"])

        self.assertTrue(summary.is_codeql_critical)
        self.assertIn("actions", summary.surfaces)

    def test_action_metadata_is_actions_surface(self) -> None:
        self.assertIn("actions", classify_path(".github/actions/install-sccache/action.yml"))


if __name__ == "__main__":
    unittest.main()
