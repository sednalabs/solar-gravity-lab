"""Source-binding regressions; no network or selected-source execution."""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from verify_workflow_source import SourceError, github_commit, verify_source


SOURCE = "0123456789abcdef" * 2 + "01234567"
OTHER_SOURCE = "f" * 40


class WorkflowSourceTests(unittest.TestCase):
    def test_default_and_identical_full_sha_require_no_lookup(self) -> None:
        resolver = Mock(side_effect=AssertionError("unexpected alias lookup"))
        for requested in ("", SOURCE, SOURCE.upper()):
            with self.subTest(requested=requested):
                self.assertEqual(SOURCE, verify_source(requested, SOURCE, resolver))
        resolver.assert_not_called()

    def test_branch_and_tag_aliases_must_resolve_to_event_commit(self) -> None:
        for requested in ("release/0.2.0-alpha.3", "refs/heads/flight-preview", "v0.2.0-alpha.3"):
            with self.subTest(requested=requested):
                resolver = Mock(return_value=SOURCE)
                self.assertEqual(SOURCE, verify_source(requested, SOURCE, resolver))
                resolver.assert_called_once_with(requested)

    def test_different_full_sha_is_rejected_without_lookup(self) -> None:
        resolver = Mock(side_effect=AssertionError("unexpected alias lookup"))
        with self.assertRaisesRegex(SourceError, "Dispatch the workflow on the intended branch or tag"):
            verify_source(OTHER_SOURCE, SOURCE, resolver)
        resolver.assert_not_called()

    def test_moved_or_different_alias_is_rejected(self) -> None:
        with self.assertRaisesRegex(SourceError, "but this workflow runs at"):
            verify_source("release/0.2.0-alpha.3", SOURCE, lambda _: OTHER_SOURCE)

    def test_invalid_source_identity_fails_closed(self) -> None:
        for invalid in ("", "abc123", "g" * 40, SOURCE + "\n"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(SourceError):
                    verify_source("main", SOURCE, lambda _: invalid)
                with self.assertRaises(SourceError):
                    verify_source(SOURCE, invalid, lambda _: SOURCE)

    def test_requested_ref_is_bounded_before_lookup(self) -> None:
        resolver = Mock(side_effect=AssertionError("unexpected alias lookup"))
        for invalid in ("a" * 1025, "é" * 513, "release branch", "main\n", "main\x00", "main\x7f"):
            with self.subTest(invalid=invalid[:40]):
                with self.assertRaises(SourceError):
                    verify_source(invalid, SOURCE, resolver)
        resolver.assert_not_called()

    @patch("verify_workflow_source.subprocess.check_output", return_value=SOURCE + "\n")
    def test_alias_lookup_uses_one_encoded_api_argument_with_a_deadline(self, check_output: Mock) -> None:
        self.assertEqual(SOURCE, github_commit("sednalabs/solar-gravity-lab", "release/preview"))
        check_output.assert_called_once_with(
            ["gh", "api", "/repos/sednalabs/solar-gravity-lab/commits/release%2Fpreview", "--jq", ".sha"],
            text=True, stderr=subprocess.PIPE, timeout=30,
        )

    @patch("verify_workflow_source.subprocess.check_output")
    def test_failed_lookup_does_not_substitute_workflow_source(self, check_output: Mock) -> None:
        for failure in (subprocess.CalledProcessError(1, "gh"), subprocess.TimeoutExpired("gh", 30)):
            with self.subTest(failure=type(failure).__name__):
                check_output.side_effect = failure
                with self.assertRaisesRegex(SourceError, "could not resolve"):
                    verify_source("missing", SOURCE, lambda ref: github_commit("owner/repo", ref))

    def test_cli_mismatch_exits_before_emitting_a_verified_source(self) -> None:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("verify_workflow_source.py")),
             "--ref", OTHER_SOURCE, "--workflow-sha", SOURCE],
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("Dispatch the workflow", result.stderr)


if __name__ == "__main__":
    unittest.main()
