from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("resolve_validation_lab_plan.py")
CHANGED_FILES_SCRIPT = Path(__file__).resolve().with_name("validation_changed_files.sh")


def run_plan(
    primary_files: list[str],
    latest_files: list[str] | None = None,
    *,
    event_name: str = "pull_request",
    profile: str = "",
    lane_set: str = "",
    base_sha: str = "base123",
    head_sha: str = "head123",
    pull_request_number: str = "42",
    evidence: dict | None = None,
    primary_available: bool = True,
    latest_available: bool = True,
) -> dict[str, str]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        plan_dir = root / "dist" / "validation-plan"
        evidence_dir = plan_dir / "prior-evidence"
        evidence_dir.mkdir(parents=True)
        primary_path = plan_dir / "primary-files.txt"
        latest_path = plan_dir / "latest-files.txt"
        evidence_path = evidence_dir / "validation-summary.json"
        if primary_available:
            primary_path.write_text("\n".join(primary_files) + ("\n" if primary_files else ""), encoding="utf-8")
        latest_values = primary_files if latest_files is None else latest_files
        if latest_available:
            latest_path.write_text("\n".join(latest_values) + ("\n" if latest_values else ""), encoding="utf-8")
        if evidence is not None:
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")

        command = [
            sys.executable,
            str(SCRIPT),
            "--event-name",
            event_name,
            "--checkout-ref",
            "feature/example",
            "--profile",
            profile,
            "--lane-set",
            lane_set,
            "--write-wrapper",
            "false",
            "--android-test-scope",
            "",
            "--android-validation-mode",
            "",
            "--android-artifact-mode",
            "",
            "--emulator-boot-strategy",
            "",
            "--gradle-configuration-cache",
            "",
            "--base-sha",
            base_sha,
            "--head-sha",
            head_sha,
            "--pull-request-number",
            pull_request_number,
        ]
        subprocess.run(command, check=True, text=True, capture_output=True, cwd=root)
        result_text = (plan_dir / "outputs.env").read_text(encoding="utf-8")

    return parse_github_outputs(result_text)


def parse_github_outputs(text: str) -> dict[str, str]:
    outputs: dict[str, str] = {}
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        key, marker, delimiter = line.partition("<<")
        if not marker:
            index += 1
            continue
        index += 1
        value_lines: list[str] = []
        while index < len(lines) and lines[index] != delimiter:
            value_lines.append(lines[index])
            index += 1
        outputs[key] = "\n".join(value_lines)
        index += 1
    return outputs


def successful_evidence(*, base_sha: str = "base123", head_sha: str = "head123", pr: str = "42") -> dict:
    return {
        "run": {
            "base_sha": base_sha,
            "head_sha": head_sha,
            "pull_request_number": pr,
            "url": "https://example.invalid/run",
        },
        "summary": {"status": "success"},
        "validation_context": {},
    }


class ValidationLabPlanTests(unittest.TestCase):
    RUNTIME_LANES = (
        "rust_workspace", "rust_workspace_arm64", "arm64_capability_census",
        "arm64_isa_proof", "ffi_abi", "android_unit", "android_lint",
        "android_shell", "runtime_scene_telemetry",
    )

    def test_docs_only_pr_has_no_validation_lab_runtime_lanes(self) -> None:
        outputs = run_plan(["docs/ci-cache-rollout.md"])

        self.assertEqual(outputs["effective_changed_files_source"], "primary_diff")
        self.assertEqual(outputs["rust_workspace"], "false")
        self.assertEqual(outputs["android_shell"], "false")
        self.assertEqual(outputs["route_reason"], "documentation-only change; docs-sanity owns this surface")

    def test_android_visual_change_routes_to_android_lanes_only(self) -> None:
        outputs = run_plan(["clients/android/app/src/main/java/com/example/Stage.kt"])

        self.assertEqual(outputs["rust_workspace"], "false")
        self.assertEqual(outputs["android_unit"], "true")
        self.assertEqual(outputs["android_lint"], "true")
        self.assertEqual(outputs["android_shell"], "true")
        self.assertEqual(outputs["rust_workspace_arm64"], "false")
        self.assertEqual(outputs["emulator_boot_strategy"], "snapshot-cache")
        self.assertEqual(outputs["gradle_configuration_cache"], "enabled")
        self.assertEqual(
            json.loads(outputs["android_shell_matrix"]),
            [
                {
                    "validation_mode": "stage-first-runtime",
                    "debug_stage_first_client": "true",
                    "preferred_gpu_backend": "vulkan",
                    "hosted_debug_profile": "hosted-debug-lite",
                    "gradle_configuration_cache": "enabled",
                }
            ],
        )

    def test_canonical_android_renderer_routes_to_android_lanes_only(self) -> None:
        outputs = run_plan(["render/android-vulkan/src/main/cpp/SolarLabVulkanRenderer.cpp"])

        self.assertEqual(outputs["rust_workspace"], "false")
        self.assertEqual(outputs["rust_workspace_arm64"], "false")
        self.assertEqual(outputs["runtime_scene_telemetry"], "false")
        self.assertEqual(outputs["android_unit"], "true")
        self.assertEqual(outputs["android_lint"], "true")
        self.assertEqual(outputs["android_shell"], "true")

    def test_workflow_change_forces_full_checkpoint(self) -> None:
        outputs = run_plan([".github/workflows/validation-lab.yml"])

        self.assertEqual(outputs["rust_workspace"], "true")
        self.assertEqual(outputs["rust_workspace_arm64"], "true")
        self.assertEqual(outputs["arm64_capability_census"], "true")
        self.assertEqual(outputs["ffi_abi"], "true")
        self.assertEqual(outputs["android_unit"], "true")
        self.assertEqual(outputs["android_lint"], "true")
        self.assertEqual(outputs["android_shell"], "true")
        self.assertEqual(outputs["runtime_scene_telemetry"], "true")

    def test_other_consumers_do_not_trigger_runtime_lanes(self) -> None:
        for path in (
            ".github/scripts/render_release_notes.py",
            ".github/workflows/security-sarif.yml",
            ".github/workflows/gradle-cache-service.yml",
            ".github/scripts/download_interactive_build_artifact.py",
            "infra/gradle-cache-service/Cargo.lock",
        ):
            with self.subTest(path=path):
                outputs = run_plan([path])
                self.assertTrue(all(outputs[lane] == "false" for lane in self.RUNTIME_LANES))

    def test_interactive_capture_helpers_route_to_their_consumers(self) -> None:
        for path in (
            ".github/scripts/capture_interactive_baseline.py",
            ".github/scripts/test_capture_interactive_baseline.py",
            ".github/scripts/intake_qualified_prerelease.py",
            ".github/scripts/test_intake_qualified_prerelease.py",
            ".github/scripts/validate_interactive_png.py",
            ".github/scripts/test_interactive_png.py",
            ".github/scripts/test_interactive_android_provider_wiring.py",
        ):
            with self.subTest(path=path):
                outputs = run_plan([path])
                self.assertTrue(all(outputs[lane] == "false" for lane in self.RUNTIME_LANES))

    def test_interactive_capture_helper_does_not_mask_other_routes(self) -> None:
        helper = ".github/scripts/capture_interactive_baseline.py"
        outputs = run_plan([helper, "engine/ffi/src/lib.rs"])
        for lane in ("rust_workspace", "rust_workspace_arm64", "arm64_capability_census", "arm64_isa_proof", "ffi_abi"):
            self.assertEqual(outputs[lane], "true")
        self.assertEqual(outputs["android_shell"], "false")

        for path in (".github/actions/prepare-android-sdk/action.yml", "settings.gradle.kts", ".github/scripts/unknown_interactive_helper.py"):
            with self.subTest(path=path):
                outputs = run_plan([helper, path])
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

        for options in ({"lane_set": "full"}, {"profile": "full"}, {"event_name": "push"}):
            with self.subTest(options=options):
                outputs = run_plan([helper], **options)
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

    def test_shared_or_unknown_tooling_forces_full_even_in_mixed_diff(self) -> None:
        for path in (
            ".github/scripts/resolve_validation_lab_plan.py",
            ".github/scripts/run_arm64_isa_proof.sh",
            ".github/actions/prepare-rust-android-toolchain/action.yml",
            ".github/scripts/future_unknown_helper.py",
            ".github/actions/future-action/Cargo.toml",
        ):
            with self.subTest(path=path):
                outputs = run_plan(["clients/android/app/src/main/Stage.kt", path])
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

    def test_other_consumer_does_not_mask_runtime_proof(self) -> None:
        outputs = run_plan([".github/scripts/render_release_notes.py", "engine/ffi/src/lib.rs"])
        for lane in ("rust_workspace", "rust_workspace_arm64", "arm64_capability_census", "arm64_isa_proof", "ffi_abi"):
            self.assertEqual(outputs[lane], "true")
        self.assertEqual(outputs["android_shell"], "false")

    def test_shared_roots_and_unknown_manifest_force_full(self) -> None:
        for path in (
            "Cargo.toml", "Cargo.lock", "build.gradle.kts", "settings.gradle.kts",
            "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "gradlew",
            "future/Cargo.toml", "future/Cargo.lock",
        ):
            with self.subTest(path=path):
                outputs = run_plan(["clients/android/app/src/main/Stage.kt", path])
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

    def test_unavailable_or_unproven_empty_diff_forces_full(self) -> None:
        for options in (
            {"primary_available": False},
            {},
            {"latest_available": False, "evidence": successful_evidence()},
        ):
            with self.subTest(options=options):
                outputs = run_plan([], **options)
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

    def test_helper_only_predecessor_does_not_suppress_changed_arm64_proof(self) -> None:
        for changed in ("engine/physics/src/lib.rs", "engine/hardware/src/lib.rs"):
            with self.subTest(changed=changed):
                outputs = run_plan(
                    [".github/scripts/render_release_notes.py", changed],
                    [changed],
                    evidence=successful_evidence(),
                )
                self.assertEqual(outputs["prior_evidence_reused"], "true")
                for lane in ("rust_workspace_arm64", "arm64_capability_census", "arm64_isa_proof"):
                    self.assertEqual(outputs[lane], "true")

    def test_renamed_product_file_routes_removed_surface(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args: str) -> str:
                return subprocess.run(
                    ["git", *args], cwd=root, check=True, text=True, capture_output=True
                ).stdout.strip()

            git("init")
            git("config", "user.name", "Validation test")
            git("config", "user.email", "validation@example.invalid")
            git("config", "commit.gpgsign", "false")
            git("config", "core.hooksPath", "/dev/null")
            source = root / "engine" / "ffi" / "contract.txt"
            source.parent.mkdir(parents=True)
            source.write_text("contract\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "original")
            before = git("rev-parse", "HEAD")
            (root / "docs").mkdir()
            git("mv", "engine/ffi/contract.txt", "docs/contract.txt")
            git("commit", "-m", "move")
            after = git("rev-parse", "HEAD")
            changed = subprocess.run(
                ["bash", str(CHANGED_FILES_SCRIPT), before, after], cwd=root,
                check=True, text=True, capture_output=True,
            ).stdout.splitlines()

        self.assertEqual(set(changed), {"engine/ffi/contract.txt", "docs/contract.txt"})
        outputs = run_plan(changed)
        for lane in ("rust_workspace", "rust_workspace_arm64", "arm64_isa_proof", "ffi_abi"):
            self.assertEqual(outputs[lane], "true")

    def test_explicit_full_overrides_other_consumer_route(self) -> None:
        for options in ({"lane_set": "full"}, {"profile": "full"}):
            with self.subTest(options=options):
                outputs = run_plan([".github/scripts/render_release_notes.py"], **options)
                self.assertTrue(all(outputs[lane] == "true" for lane in self.RUNTIME_LANES))

    def test_runtime_cpu_truth_lane_preserves_proof(self) -> None:
        outputs = run_plan(["engine/runtime/src/lib.rs"], lane_set="runtime-cpu-truth")
        for lane in ("arm64_isa_proof", "ffi_abi", "android_unit"):
            self.assertEqual(outputs[lane], "true")

    def test_explicit_arm64_capability_census_lane_is_runnable(self) -> None:
        outputs = run_plan(
            ["docs/android-arm64-capability-census.md"],
            lane_set="arm64-capability-census",
        )

        self.assertEqual(outputs["arm64_capability_census"], "true")
        self.assertEqual(outputs["arm64_isa_proof"], "false")
        self.assertEqual(outputs["rust_workspace_arm64"], "false")
        self.assertIn("arm64_capability_census=true", outputs["lane_summary"])

    def test_same_pr_prior_evidence_routes_from_latest_delta(self) -> None:
        outputs = run_plan(
            ["engine/runtime/src/lib.rs"],
            ["clients/android/app/src/main/java/com/example/Stage.kt"],
            evidence=successful_evidence(),
        )

        self.assertEqual(outputs["prior_evidence_reused"], "true")
        self.assertEqual(outputs["effective_changed_files_source"], "latest_delta")
        self.assertEqual(outputs["merge_checkpoint_required"], "true")
        self.assertEqual(outputs["rust_workspace"], "false")
        self.assertEqual(outputs["android_shell"], "true")

    def test_exact_same_pr_prior_evidence_skips_runtime_lanes(self) -> None:
        outputs = run_plan(
            ["engine/runtime/src/lib.rs"],
            [],
            evidence=successful_evidence(),
        )

        self.assertEqual(outputs["prior_evidence_reused"], "true")
        self.assertEqual(outputs["effective_changed_files_source"], "latest_delta")
        self.assertEqual(outputs["rust_workspace"], "false")
        self.assertEqual(outputs["android_shell"], "false")
        self.assertEqual(
            outputs["route_reason"],
            "same-PR prior evidence was reused and the latest delta has no validation-lab-owned runtime lanes",
        )

    def test_prior_evidence_must_match_pr_number(self) -> None:
        outputs = run_plan(
            ["engine/runtime/src/lib.rs"],
            ["clients/android/app/src/main/java/com/example/Stage.kt"],
            evidence=successful_evidence(pr="43"),
        )

        self.assertEqual(outputs["prior_evidence_reused"], "false")
        self.assertEqual(outputs["effective_changed_files_source"], "primary_diff")
        self.assertEqual(outputs["rust_workspace"], "true")
        self.assertEqual(outputs["android_shell"], "false")

    def test_push_checkpoint_forces_full(self) -> None:
        outputs = run_plan(["docs/ci-cache-rollout.md"], event_name="push")

        self.assertEqual(outputs["profile"], "full")
        self.assertEqual(outputs["lane_set"], "full")
        self.assertEqual(outputs["effective_changed_files_source"], "checkpoint_event")
        self.assertEqual(outputs["rust_workspace"], "true")
        self.assertEqual(outputs["android_shell"], "true")

    def test_workflow_opts_into_ready_for_review_exact_proof(self) -> None:
        workflow = Path(__file__).parents[1] / "workflows" / "validation-lab.yml"
        text = workflow.read_text(encoding="utf-8")

        self.assertIn("- ready_for_review", text)
        self.assertIn("github.event.action == 'ready_for_review'", text)
        self.assertIn("candidate_reason=\"exact ready-for-review head\"", text)
        self.assertIn("ci-proof-v1-validation-lab", text)


if __name__ == "__main__":
    unittest.main()
