from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("resolve_validation_lab_plan.py")


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
) -> dict[str, str]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        plan_dir = root / "dist" / "validation-plan"
        evidence_dir = plan_dir / "prior-evidence"
        evidence_dir.mkdir(parents=True)
        primary_path = plan_dir / "primary-files.txt"
        latest_path = plan_dir / "latest-files.txt"
        evidence_path = evidence_dir / "validation-summary.json"
        primary_path.write_text("\n".join(primary_files) + ("\n" if primary_files else ""), encoding="utf-8")
        latest_values = primary_files if latest_files is None else latest_files
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
