from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("resolve_prerelease_apk_request.py")


def run_request(
    *,
    event_name: str = "push",
    event_ref: str = "refs/heads/main",
    event_sha: str = "abc123",
    event_after: str = "f" * 40,
    head_message: str = "",
    input_ref: str = "",
    input_version_name: str = "",
    input_version_code: str = "",
    input_release_channel: str = "",
    input_build_variant: str = "",
    input_publish_release: str = "false",
    expect_success: bool = True,
) -> tuple[dict[str, str], str]:
    command = [
        sys.executable,
        str(SCRIPT),
        "--event-name",
        event_name,
        "--event-ref",
        event_ref,
        "--event-sha",
        event_sha,
        "--event-after",
        event_after,
        "--head-message",
        head_message,
        "--input-ref",
        input_ref,
        "--input-version-name",
        input_version_name,
        "--input-version-code",
        input_version_code,
        "--input-release-channel",
        input_release_channel,
        "--input-build-variant",
        input_build_variant,
        "--input-publish-release",
        input_publish_release,
    ]
    result = subprocess.run(command, text=True, capture_output=True)
    if expect_success:
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return parse_outputs(result.stdout), result.stderr

    if result.returncode == 0:
        raise AssertionError("command unexpectedly succeeded")
    return {}, result.stderr


def parse_outputs(text: str) -> dict[str, str]:
    outputs: dict[str, str] = {}
    for line in text.splitlines():
        key, _, value = line.partition("=")
        outputs[key] = value
    return outputs


class PrereleaseApkRequestTests(unittest.TestCase):
    def test_main_push_without_release_trailer_is_clean_noop(self) -> None:
        outputs, _stderr = run_request(head_message="regular merge\n")

        self.assertEqual(outputs["release_requested"], "false")
        self.assertEqual(outputs["reason"], "missing_solarlab_release_trailer")
        self.assertEqual(outputs["publish_release"], "false")

    def test_main_push_release_trailer_supplies_semver_stable_inputs(self) -> None:
        outputs, _stderr = run_request(
            head_message=(
                "release: prepare phone build\n\n"
                "SolarLab-Release: 0.1.0\n"
                "SolarLab-Version-Code: 1\n"
            )
        )

        self.assertEqual(outputs["release_requested"], "true")
        self.assertEqual(outputs["reason"], "release_trailer")
        self.assertEqual(outputs["checkout_ref"], "f" * 40)
        self.assertEqual(outputs["version_name"], "0.1.0")
        self.assertEqual(outputs["version_code"], "1")
        self.assertEqual(outputs["release_channel"], "stable")
        self.assertEqual(outputs["build_variant"], "release")
        self.assertEqual(outputs["publish_release"], "true")

    def test_stable_trailer_defaults_to_release_variant(self) -> None:
        outputs, _stderr = run_request(head_message="release\n\nSolarLab-Release: 0.1.0\n")

        self.assertEqual(outputs["release_channel"], "stable")
        self.assertEqual(outputs["build_variant"], "release")

    def test_explicit_build_variant_can_keep_prerelease_package_for_alpha(self) -> None:
        outputs, _stderr = run_request(
            head_message=(
                "release\n\n"
                "SolarLab-Release: 0.1.1-alpha.1\n"
                "SolarLab-Build-Variant: prerelease\n"
            )
        )

        self.assertEqual(outputs["build_variant"], "prerelease")

    def test_explicit_build_variant_can_keep_phone_installable_package_for_stable_version(self) -> None:
        outputs, _stderr = run_request(
            head_message=(
                "release\n\n"
                "SolarLab-Release: 0.1.0\n"
                "SolarLab-Build-Variant: prerelease\n"
            )
        )

        self.assertEqual(outputs["version_name"], "0.1.0")
        self.assertEqual(outputs["release_channel"], "stable")
        self.assertEqual(outputs["build_variant"], "prerelease")

    def test_invalid_release_trailer_fails_before_heavy_work(self) -> None:
        _outputs, stderr = run_request(
            head_message="release\n\nSolarLab-Release: alpha-one\n",
            expect_success=False,
        )

        self.assertIn("SolarLab-Release must be an ordinary semver version", stderr)

    def test_invalid_version_code_trailer_fails_before_heavy_work(self) -> None:
        _outputs, stderr = run_request(
            head_message="release\n\nSolarLab-Release: 0.1.0\nSolarLab-Version-Code: 0\n",
            expect_success=False,
        )

        self.assertIn("SolarLab-Version-Code must be a positive integer", stderr)

    def test_duplicate_release_trailers_fail_before_heavy_work(self) -> None:
        _outputs, stderr = run_request(
            head_message=(
                "release\n\n"
                "SolarLab-Release: 0.1.0\n"
                "SolarLab-Release: 0.1.1-alpha.1\n"
            ),
            expect_success=False,
        )

        self.assertIn("commit has more than one SolarLab-Release trailer", stderr)

    def test_manual_dispatch_preserves_explicit_inputs(self) -> None:
        target_sha = "b" * 40
        outputs, _stderr = run_request(
            event_name="workflow_dispatch",
            event_ref="refs/heads/main",
            event_sha="a" * 40,
            input_ref=target_sha,
            input_version_name="0.1.0",
            input_version_code="1",
            input_release_channel="stable",
            input_build_variant="prerelease",
            input_publish_release="true",
        )

        self.assertEqual(outputs["release_requested"], "true")
        self.assertEqual(outputs["reason"], "workflow_dispatch")
        self.assertEqual(outputs["checkout_ref"], target_sha)
        self.assertEqual(outputs["target_sha"], target_sha)
        self.assertEqual(outputs["version_name"], "0.1.0")
        self.assertEqual(outputs["version_code"], "1")
        self.assertEqual(outputs["release_channel"], "stable")
        self.assertEqual(outputs["build_variant"], "prerelease")
        self.assertEqual(outputs["publish_release"], "true")

    def test_manual_dispatch_rejects_newline_injection_inputs(self) -> None:
        _outputs, stderr = run_request(
            event_name="workflow_dispatch",
            event_ref="refs/heads/main",
            event_sha="a" * 40,
            input_ref="main\nINJECTED=value",
            input_version_name="0.1.0",
            expect_success=False,
        )

        self.assertIn("ref must be a single-line value", stderr)

    def test_manual_dispatch_rejects_invalid_version_code(self) -> None:
        _outputs, stderr = run_request(
            event_name="workflow_dispatch",
            event_ref="refs/heads/main",
            event_sha="a" * 40,
            input_ref="main",
            input_version_name="0.1.0",
            input_version_code="zero",
            expect_success=False,
        )

        self.assertIn("SolarLab-Version-Code must be a positive integer", stderr)

    def test_release_branch_push_keeps_legacy_auto_path(self) -> None:
        outputs, _stderr = run_request(
            event_ref="refs/heads/release/alpha.16",
            head_message="release branch update",
        )

        self.assertEqual(outputs["release_requested"], "true")
        self.assertEqual(outputs["reason"], "release_branch_push")
        self.assertEqual(outputs["checkout_ref"], "release/alpha.16")
        self.assertEqual(outputs["release_channel"], "auto")
        self.assertEqual(outputs["build_variant"], "prerelease")
        self.assertEqual(outputs["publish_release"], "true")


if __name__ == "__main__":
    unittest.main()
