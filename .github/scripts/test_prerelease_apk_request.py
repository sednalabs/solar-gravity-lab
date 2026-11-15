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

    def test_versioned_release_branch_resolves_identity_and_pins_checkout(self) -> None:
        outputs, _stderr = run_request(
            event_ref="refs/heads/release/0.2.0-alpha.1",
            head_message="qualified Earth-Moon lab",
        )

        self.assertEqual(outputs["release_requested"], "true")
        self.assertEqual(outputs["reason"], "release_branch_push")
        self.assertEqual(outputs["checkout_ref"], "f" * 40)
        self.assertEqual(outputs["target_sha"], "f" * 40)
        self.assertEqual(outputs["version_name"], "0.2.0-alpha.1")
        self.assertEqual(outputs["version_code"], "2001001")
        self.assertGreater(int(outputs["version_code"]), 9)  # Published 0.1.1-alpha.8 APK.
        self.assertEqual(outputs["release_channel"], "prerelease")
        self.assertEqual(outputs["build_variant"], "prerelease")
        self.assertEqual(outputs["publish_release"], "true")

    def test_auto_codes_increase_across_release_stages_and_version_fields(self) -> None:
        versions = [
            "0.0.0-alpha.0", "0.0.0-alpha.999", "0.0.0-beta.0", "0.0.0-beta.999",
            "0.0.0-rc.0", "0.0.0-rc.999", "0.0.0", "0.0.1-alpha.0",
            "0.0.99", "0.1.0-alpha.0", "0.99.99", "1.0.0-alpha.0", "20.99.99",
        ]
        codes = [
            int(run_request(event_ref=f"refs/heads/release/{version}")[0]["version_code"])
            for version in versions
        ]
        self.assertGreater(codes[0], 0)
        self.assertLessEqual(codes[-1], 2_100_000_000)
        self.assertTrue(all(a < b for a, b in zip(codes, codes[1:])), codes)

    def test_stable_release_branch_infers_stable_channel_and_release_variant(self) -> None:
        outputs, _stderr = run_request(event_ref="refs/heads/release/0.2.0")
        self.assertEqual(outputs["version_name"], "0.2.0")
        self.assertEqual(outputs["release_channel"], "stable")
        self.assertEqual(outputs["build_variant"], "release")
        self.assertEqual(outputs["version_code"], "2009000")

    def test_release_branch_trailer_cannot_override_its_version(self) -> None:
        _outputs, stderr = run_request(
            event_ref="refs/heads/release/0.2.0-alpha.1",
            head_message="SolarLab-Release: 0.1.0\n",
            expect_success=False,
        )
        self.assertIn("does not match the versioned release branch", stderr)

    def test_unversioned_release_branch_requires_explicit_trailer(self) -> None:
        _outputs, stderr = run_request(event_ref="refs/heads/release/alpha.16", expect_success=False)
        self.assertIn("release/<semver> or provide a SolarLab-Release trailer", stderr)

    def test_unversioned_release_branch_accepts_explicit_trailer(self) -> None:
        outputs, _stderr = run_request(
            event_ref="refs/heads/release/alpha.16",
            head_message="release branch update\n\nSolarLab-Release: 0.2.0-alpha.1\n",
        )

        self.assertEqual(outputs["release_requested"], "true")
        self.assertEqual(outputs["reason"], "release_branch_push")
        self.assertEqual(outputs["checkout_ref"], "f" * 40)
        self.assertEqual(outputs["version_name"], "0.2.0-alpha.1")
        self.assertEqual(outputs["version_code"], "2001001")
        self.assertEqual(outputs["release_channel"], "prerelease")
        self.assertEqual(outputs["build_variant"], "prerelease")
        self.assertEqual(outputs["publish_release"], "true")

    def test_manual_dispatch_can_derive_version_from_selected_release_ref(self) -> None:
        outputs, _stderr = run_request(
            event_name="workflow_dispatch",
            input_ref="release/0.2.0-alpha.1",
            input_release_channel="auto",
        )
        self.assertEqual(outputs["version_name"], "0.2.0-alpha.1")
        self.assertEqual(outputs["version_code"], "2001001")
        self.assertEqual(outputs["release_channel"], "prerelease")

    def test_manual_dispatch_without_version_fails_instead_of_reusing_old_release(self) -> None:
        _outputs, stderr = run_request(event_name="workflow_dispatch", expect_success=False)
        self.assertIn("version_name is required", stderr)

    def test_channel_mismatch_is_rejected_on_manual_and_push_requests(self) -> None:
        for args in [
            {"event_name": "workflow_dispatch", "input_version_name": "0.2.0-alpha.1", "input_release_channel": "stable"},
            {"event_ref": "refs/heads/release/0.2.0-alpha.1", "head_message": "SolarLab-Release-Channel: stable\n"},
        ]:
            with self.subTest(args=args):
                _outputs, stderr = run_request(**args, expect_success=False)
                self.assertIn("does not match version channel", stderr)

    def test_custom_prerelease_or_out_of_range_version_needs_explicit_code(self) -> None:
        for version in ["0.2.0-preview.1", "0.2.0-alpha.1000", "0.100.0", "21.0.0"]:
            with self.subTest(version=version):
                _outputs, stderr = run_request(event_ref=f"refs/heads/release/{version}", expect_success=False)
                self.assertIn("supply SolarLab-Version-Code", stderr)
                outputs, _stderr = run_request(
                    event_ref=f"refs/heads/release/{version}", head_message="SolarLab-Version-Code: 3000000\n",
                )
                self.assertEqual(outputs["version_code"], "3000000")

    def test_android_version_code_limit_is_checked(self) -> None:
        _outputs, stderr = run_request(
            event_ref="refs/heads/release/0.2.0-alpha.1",
            head_message="SolarLab-Version-Code: 2100000001\n",
            expect_success=False,
        )
        self.assertIn("must not exceed 2100000000", stderr)

    def test_numeric_prerelease_identifier_cannot_have_leading_zeros(self) -> None:
        _outputs, stderr = run_request(event_ref="refs/heads/release/0.2.0-alpha.01", expect_success=False)
        self.assertIn("must not have leading zeros", stderr)


if __name__ == "__main__":
    unittest.main()
