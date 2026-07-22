#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path


PROFILE_NOTES = {
    "targeted": "One active slice only; use this to prove the current canonical seam before widening.",
    "frontier": "Bounded next-blocker harvest; keep the Rust baseline and add the Android shell when appropriate.",
    "broad": "Explicit checkpoint mode; use sparingly for milestone passes rather than routine iteration.",
    "full": "Explicit checkpoint mode; use sparingly for milestone passes rather than routine iteration.",
}
PRIMARY_FILES_PATH = Path("dist/validation-plan/primary-files.txt")
LATEST_FILES_PATH = Path("dist/validation-plan/latest-files.txt")
PRIOR_EVIDENCE_PATH = Path("dist/validation-plan/prior-evidence/validation-summary.json")
OUTPUT_ENV_PATH = Path("dist/validation-plan/outputs.env")


@dataclass
class Evidence:
    reused: bool
    sha: str = ""
    run_url: str = ""
    reason: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Resolve validation-lab lanes.")
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--checkout-ref", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--lane-set", required=True)
    parser.add_argument("--write-wrapper", required=True)
    parser.add_argument("--android-test-scope", required=True)
    parser.add_argument("--android-validation-mode", required=True)
    parser.add_argument("--android-artifact-mode", required=True)
    parser.add_argument("--emulator-boot-strategy", required=True)
    parser.add_argument("--gradle-configuration-cache", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--pull-request-number", default="")
    return parser.parse_args()


def read_changed_files(changed_path: Path) -> list[str]:
    if not changed_path.exists():
        return []
    return [
        line.strip()
        for line in changed_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def path_matches(path: str, prefixes: tuple[str, ...], names: tuple[str, ...] = ()) -> bool:
    return path in names or path.startswith(prefixes)


def is_workflow_or_validation_tool(path: str) -> bool:
    return path.startswith(".github/workflows/") or path.startswith(".github/actions/") or path.startswith(
        ".github/scripts/"
    )


def is_docs_only(files: list[str]) -> bool:
    if not files:
        return False
    return all(
        path == "README.md"
        or path.startswith("docs/")
        or path in {
            ".github/workflows/docs-sanity.yml",
            ".github/scripts/check_markdown_links.py",
            ".github/scripts/check_public_doc_safety.py",
            ".github/scripts/test_interactive_android_provider_wiring.py",
            ".github/scripts/test_interactive_session_summary.py",
            ".github/scripts/write_interactive_session_summary.py",
        }
        for path in files
    )


def has_rust_surface(files: list[str]) -> bool:
    return any(
        path_matches(
            path,
            ("engine/", "proto/", "services/", "labs/", "core-math/", "core-model/", "core-simulation/"),
            ("Cargo.toml", "Cargo.lock"),
        )
        or (path.startswith("render/") and not path.startswith("render/android-vulkan/"))
        or path.endswith("/Cargo.toml")
        or path.endswith("/Cargo.lock")
        for path in files
    )


def has_runtime_scene_surface(files: list[str]) -> bool:
    return any(
        path.startswith(("engine/runtime/", "labs/parity/"))
        or (path.startswith("render/") and not path.startswith("render/android-vulkan/"))
        for path in files
    )


def has_ffi_surface(files: list[str]) -> bool:
    return any(path.startswith(("engine/ffi/", "proto/")) for path in files)


def has_arm64_surface(files: list[str]) -> bool:
    return any(
        path.startswith(("engine/", "services/", "proto/"))
        or (path.startswith("render/") and not path.startswith("render/android-vulkan/"))
        or path == ".github/scripts/run_arm64_isa_proof.sh"
        for path in files
    )


def has_android_surface(files: list[str]) -> bool:
    return any(
        path_matches(
            path,
            (
                "clients/android/",
                "render/android-vulkan/",
                "render-core/",
                "core-math/",
                "core-model/",
                "core-simulation/",
                "gradle/",
            ),
            ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"),
        )
        for path in files
    )


def has_android_shell_surface(files: list[str]) -> bool:
    return any(
        path.startswith(
            (
                "clients/android/app/src/androidTest/",
                "clients/android/app/src/main/",
                "render/android-vulkan/",
                "render-core/",
            )
        )
        or path in {"clients/android/build.gradle.kts", "clients/android/settings.gradle.kts"}
        for path in files
    )


def has_android_unit_surface(files: list[str]) -> bool:
    return any(
        path.startswith(
            (
                "clients/android/app/src/test/",
                "clients/android/app/src/main/",
                "render/android-vulkan/",
                "render-core/",
                "core-math/",
                "core-model/",
                "core-simulation/",
            )
        )
        for path in files
    )


def load_evidence(base_sha: str, head_sha: str, pull_request_number: str = "") -> Evidence:
    evidence_path = PRIOR_EVIDENCE_PATH
    if not evidence_path.exists() or evidence_path.stat().st_size == 0:
        return Evidence(False)
    try:
        payload = json.loads(evidence_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return Evidence(False)

    if payload.get("summary", {}).get("status") != "success":
        return Evidence(False)

    run = payload.get("run", {})
    validation_context = payload.get("validation_context", {})
    evidence_head = str(run.get("head_sha") or validation_context.get("head_sha") or "").strip()
    evidence_base = str(run.get("base_sha") or validation_context.get("base_sha") or "").strip()
    if evidence_head != head_sha or evidence_base != base_sha:
        return Evidence(False)
    evidence_pr = str(run.get("pull_request_number") or validation_context.get("pull_request_number") or "").strip()
    if pull_request_number and evidence_pr != pull_request_number:
        return Evidence(False)

    return Evidence(
        True,
        sha=evidence_head,
        run_url=str(run.get("url", "")).strip(),
        reason="previous validation-lab summary succeeded for the same PR, same base, and immediate prior head",
    )


def apply_lane_set(lanes: dict[str, bool], lane_set: str, profile: str) -> None:
    if lane_set == "auto":
        lanes["rust_workspace"] = True
        if profile in {"frontier", "broad", "full"}:
            lanes["android_shell"] = True
        if profile in {"broad", "full"}:
            lanes["ffi_abi"] = True
            lanes["rust_workspace_arm64"] = True
            lanes["arm64_capability_census"] = True
            lanes["arm64_isa_proof"] = True
            lanes["android_unit"] = True
            lanes["android_lint"] = True
        return

    lane_map = {
        "bootstrap": "wrapper_bootstrap",
        "rust-workspace": "rust_workspace",
        "rust-workspace-arm64": "rust_workspace_arm64",
        "arm64-capability-census": "arm64_capability_census",
        "arm64-isa-proof": "arm64_isa_proof",
        "ffi-abi": "ffi_abi",
        "android-unit": "android_unit",
        "android-lint": "android_lint",
        "android-shell": "android_shell",
        "runtime-scene-telemetry": "runtime_scene_telemetry",
    }
    if lane_set == "runtime-cpu-truth":
        lanes["arm64_isa_proof"] = True
        lanes["ffi_abi"] = True
        lanes["android_unit"] = True
        return
    if lane_set == "full":
        for lane in lanes:
            if lane != "wrapper_bootstrap":
                lanes[lane] = True
        return
    if lane_set not in lane_map:
        raise ValueError(f"Unknown lane_set: {lane_set}")
    lanes[lane_map[lane_set]] = True


def route_auto_lanes(files: list[str], profile: str, evidence_reused: bool) -> tuple[dict[str, bool], str]:
    lanes = {
        "wrapper_bootstrap": False,
        "rust_workspace": False,
        "rust_workspace_arm64": False,
        "arm64_capability_census": False,
        "arm64_isa_proof": False,
        "ffi_abi": False,
        "android_unit": False,
        "android_lint": False,
        "android_shell": False,
        "runtime_scene_telemetry": False,
    }

    if evidence_reused and not files:
        return lanes, "exact same-PR validation evidence was reused; no validation-lab-owned runtime lanes changed"

    if not files:
        lanes["rust_workspace"] = True
        return lanes, "no changed files were available, so the Rust baseline is the conservative targeted fallback"

    if is_docs_only(files):
        return lanes, "documentation-only change; docs-sanity owns this surface"

    if any(is_workflow_or_validation_tool(path) for path in files):
        for lane in lanes:
            if lane != "wrapper_bootstrap":
                lanes[lane] = True
        return lanes, "workflow or validation tooling changed, so validation-lab uses a full checkpoint"

    if has_rust_surface(files):
        lanes["rust_workspace"] = True
    if has_runtime_scene_surface(files):
        lanes["runtime_scene_telemetry"] = True
    if has_ffi_surface(files):
        lanes["ffi_abi"] = True
    if has_arm64_surface(files) and (not evidence_reused or has_runtime_scene_surface(files) or has_ffi_surface(files)):
        lanes["rust_workspace_arm64"] = True
        lanes["arm64_capability_census"] = True
        lanes["arm64_isa_proof"] = True

    if has_android_surface(files):
        lanes["android_lint"] = True
    if has_android_unit_surface(files):
        lanes["android_unit"] = True
    if has_android_shell_surface(files):
        lanes["android_shell"] = True

    if profile == "frontier":
        if lanes["rust_workspace"] or lanes["android_unit"] or lanes["android_lint"]:
            lanes["android_shell"] = lanes["android_shell"] or has_android_surface(files)
    if profile in {"broad", "full"}:
        for lane in lanes:
            if lane != "wrapper_bootstrap":
                lanes[lane] = True

    if not any(lanes.values()):
        lanes["rust_workspace"] = True
        return lanes, "change did not match a narrower route, so the Rust baseline is the conservative fallback"

    if evidence_reused:
        return lanes, "same-PR prior evidence was reused; lanes are routed from the latest commit delta"
    return lanes, "lanes are routed from the PR diff against base"


def resolve_android_shell_matrix(enabled: bool, android_validation_mode: str, profile: str, gradle_configuration_cache: str) -> str:
    if not enabled:
        return "[]"

    matrix_by_mode = {
        "shell-v2": [
            {
                "validation_mode": "shell-v2",
                "debug_stage_first_client": "false",
                "preferred_gpu_backend": "none",
                "hosted_debug_profile": "full-fidelity",
            }
        ],
        "stage-first-runtime": [
            {
                "validation_mode": "stage-first-runtime",
                "debug_stage_first_client": "true",
                "preferred_gpu_backend": "vulkan",
                "hosted_debug_profile": "hosted-debug-lite",
            }
        ],
    }
    if android_validation_mode == "auto":
        if profile == "targeted":
            rows = matrix_by_mode["stage-first-runtime"]
        elif profile == "frontier":
            rows = matrix_by_mode["shell-v2"] + matrix_by_mode["stage-first-runtime"]
        else:
            rows = matrix_by_mode["shell-v2"] + matrix_by_mode["stage-first-runtime"]
    elif android_validation_mode in matrix_by_mode:
        rows = matrix_by_mode[android_validation_mode]
    else:
        raise ValueError(f"Unknown android_validation_mode: {android_validation_mode}")

    with_cache = [dict(row, gradle_configuration_cache=gradle_configuration_cache) for row in rows]
    return json.dumps(with_cache, separators=(",", ":"))


def normalize_choice(value: str, default: str) -> str:
    return (value or "").strip() or default


def github_output_line(key: str, value: str) -> str:
    delimiter = f"__SGL_{key}_EOF__"
    while delimiter in value:
        delimiter = f"{delimiter}_"
    return f"{key}<<{delimiter}\n{value}\n{delimiter}"


def main() -> None:
    args = parse_args()

    event_name = args.event_name
    checkout_ref = normalize_choice(args.checkout_ref, "")
    profile = normalize_choice(args.profile, "targeted")
    lane_set = normalize_choice(args.lane_set, "auto")
    write_wrapper = normalize_choice(args.write_wrapper, "false")
    android_test_scope = normalize_choice(args.android_test_scope, "core")
    android_validation_mode = normalize_choice(args.android_validation_mode, "auto")
    android_artifact_mode = normalize_choice(args.android_artifact_mode, "failures-only")
    emulator_boot_strategy = normalize_choice(args.emulator_boot_strategy, "snapshot-cache" if event_name == "pull_request" else "cold")
    gradle_configuration_cache = normalize_choice(args.gradle_configuration_cache, "enabled" if event_name == "pull_request" else "disabled")

    if profile not in {"targeted", "frontier", "broad", "full"}:
        raise ValueError(f"Unknown profile: {profile}")
    if gradle_configuration_cache not in {"disabled", "enabled"}:
        raise ValueError(f"Unknown gradle_configuration_cache: {gradle_configuration_cache}")
    if emulator_boot_strategy not in {"cold", "snapshot-cache"}:
        raise ValueError(f"Unknown emulator_boot_strategy: {emulator_boot_strategy}")

    primary_files = read_changed_files(PRIMARY_FILES_PATH)
    latest_files = read_changed_files(LATEST_FILES_PATH)
    evidence = load_evidence(args.base_sha, args.head_sha, args.pull_request_number)
    effective_files = latest_files if evidence.reused else primary_files
    changed_source = "latest_delta" if evidence.reused else "primary_diff"

    checkpoint_event = event_name in {"push", "merge_group", "schedule"}
    if checkpoint_event:
        profile = "full"
        lane_set = "full"
        evidence = Evidence(False)
        effective_files = primary_files
        changed_source = "checkpoint_event"

    if lane_set == "auto" and event_name == "pull_request":
        lanes, route_reason = route_auto_lanes(effective_files, profile, evidence.reused)
    else:
        lanes = {
            "wrapper_bootstrap": False,
            "rust_workspace": False,
            "rust_workspace_arm64": False,
            "arm64_capability_census": False,
            "arm64_isa_proof": False,
            "ffi_abi": False,
            "android_unit": False,
            "android_lint": False,
            "android_shell": False,
            "runtime_scene_telemetry": False,
        }
        apply_lane_set(lanes, lane_set, profile)
        route_reason = f"explicit lane_set={lane_set}"

    if write_wrapper == "true":
        lanes["wrapper_bootstrap"] = True

    if evidence.reused and not any(lanes.values()):
        route_reason = "same-PR prior evidence was reused and the latest delta has no validation-lab-owned runtime lanes"

    profile_intent = "checkpoint" if profile in {"broad", "full"} else profile
    android_shell_matrix = resolve_android_shell_matrix(
        lanes["android_shell"],
        android_validation_mode,
        profile,
        gradle_configuration_cache,
    )
    lane_summary = (
        f"intent={profile_intent}, wrapper_bootstrap={str(lanes['wrapper_bootstrap']).lower()}, "
        f"rust_workspace={str(lanes['rust_workspace']).lower()}, "
        f"rust_workspace_arm64={str(lanes['rust_workspace_arm64']).lower()}, "
        f"arm64_capability_census={str(lanes['arm64_capability_census']).lower()}, "
        f"arm64_isa_proof={str(lanes['arm64_isa_proof']).lower()}, "
        f"ffi_abi={str(lanes['ffi_abi']).lower()}, android_unit={str(lanes['android_unit']).lower()}, "
        f"android_lint={str(lanes['android_lint']).lower()}, android_shell={str(lanes['android_shell']).lower()}, "
        f"runtime_scene_telemetry={str(lanes['runtime_scene_telemetry']).lower()}, "
        f"android_validation_mode={android_validation_mode}, gradle_configuration_cache={gradle_configuration_cache}, "
        f"changed_files_source={changed_source}, prior_evidence_reused={str(evidence.reused).lower()}"
    )

    outputs = {
        "checkout_ref": checkout_ref,
        "profile": profile,
        "lane_set": lane_set,
        "android_test_scope": android_test_scope,
        "android_validation_mode": android_validation_mode,
        "android_artifact_mode": android_artifact_mode,
        "emulator_boot_strategy": emulator_boot_strategy,
        "gradle_configuration_cache": gradle_configuration_cache,
        "profile_intent": profile_intent,
        "profile_notes": PROFILE_NOTES[profile],
        "wrapper_bootstrap": str(lanes["wrapper_bootstrap"]).lower(),
        "rust_workspace": str(lanes["rust_workspace"]).lower(),
        "rust_workspace_arm64": str(lanes["rust_workspace_arm64"]).lower(),
        "arm64_capability_census": str(lanes["arm64_capability_census"]).lower(),
        "arm64_isa_proof": str(lanes["arm64_isa_proof"]).lower(),
        "ffi_abi": str(lanes["ffi_abi"]).lower(),
        "android_unit": str(lanes["android_unit"]).lower(),
        "android_lint": str(lanes["android_lint"]).lower(),
        "android_shell": str(lanes["android_shell"]).lower(),
        "android_shell_matrix": android_shell_matrix,
        "runtime_scene_telemetry": str(lanes["runtime_scene_telemetry"]).lower(),
        "lane_summary": lane_summary,
        "base_sha": args.base_sha,
        "head_sha": args.head_sha,
        "effective_changed_files_source": changed_source,
        "prior_evidence_reused": str(evidence.reused).lower(),
        "prior_evidence_sha": evidence.sha,
        "prior_evidence_run_url": evidence.run_url,
        "prior_evidence_reason": evidence.reason,
        "merge_checkpoint_required": str(evidence.reused).lower(),
        "route_reason": route_reason,
    }

    output_lines = [github_output_line(key, value) for key, value in outputs.items()]
    OUTPUT_ENV_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_ENV_PATH.write_text("\n".join(output_lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
