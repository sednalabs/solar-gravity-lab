#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SUMMARY_SCRIPT = ROOT / ".github" / "scripts" / "write_interactive_session_summary.py"
sys.dont_write_bytecode = True


def load_summary_module():
    spec = importlib.util.spec_from_file_location(
        "write_interactive_session_summary",
        SUMMARY_SCRIPT,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {SUMMARY_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


DEFAULT_VALUE = object()


def base_payload(
    outcome_taxonomy: dict | None,
    *,
    provider: object = DEFAULT_VALUE,
    policy: object = DEFAULT_VALUE,
    codex_bridge: dict | None = None,
    proof_validation: dict | None = None,
    active_build: dict | None = None,
) -> dict:
    if policy is DEFAULT_VALUE:
        policy = {
            "resumeBehavior": "revalidate_required",
            "persistOnResume": False,
        }
        if outcome_taxonomy is not None:
            policy["outcomeTaxonomy"] = outcome_taxonomy
    if provider is DEFAULT_VALUE:
        provider = {
            "adapter": "android",
            "transport": "android-emulator-mcp",
        }

    summary = {
        "status": "success",
        "job_result": "success",
        "artifacts_dir": "dist/interactive-session",
        "codex_provider_manifest": {
            "provider": provider,
            "policy": policy,
        },
    }
    if codex_bridge is not None:
        summary["codex_bridge"] = codex_bridge
    if proof_validation is not None:
        summary["codex_dynamic_tool_proof_validation"] = proof_validation
    if active_build is not None:
        summary["active_build"] = active_build

    return {
        "context": {
            "checkout_ref": "validation/example",
            "build_source": "current",
            "artifact_name": "app-debug",
            "build_run_id": "123",
            "build_commit_sha": "deadbeef",
            "android_emulator_mcp_ref": "provider-ref",
            "mcp_toolkit_rs_ref": "toolkit-ref",
            "android_validation_mode": "shell-v2",
            "interactive_debug_profile": "hosted-debug-lite",
            "preferred_gpu_backend": "none",
            "emulator_boot_strategy": "snapshot-cache",
            "session_timeout_minutes": "30",
            "keep_session_on_failure": "true",
        },
        "summary": summary,
    }


def main() -> int:
    module = load_summary_module()

    rendered = module.render_markdown(
        base_payload({
            "statuses": ["succeeded", "observe_degraded"],
            "retryability": ["none", "observe_then_retry"],
        }),
    )
    assert "- outcome statuses: `succeeded`, `observe_degraded`" in rendered
    assert "- retryability values: `none`, `observe_then_retry`" in rendered
    assert "- preferred GPU backend: `none`" in rendered

    rendered_empty = module.render_markdown(
        base_payload({
            "statuses": [],
            "retryability": [],
        }),
    )
    assert "- outcome statuses:" not in rendered_empty
    assert "- retryability values:" not in rendered_empty

    rendered_absent = module.render_markdown(base_payload(None))
    assert "- outcome statuses:" not in rendered_absent
    assert "- retryability values:" not in rendered_absent

    rendered_nulls = module.render_markdown(
        base_payload(None, provider=None, policy=None),
    )
    assert "- adapter: `unknown`" in rendered_nulls
    assert "- outcome statuses:" not in rendered_nulls
    assert "- retryability values:" not in rendered_nulls

    rendered_proof = module.render_markdown(
        base_payload(
            None,
            codex_bridge={
                "status": "ready",
                "mode": "native_dynamic_tools",
                "dynamic_tool_specs_status": "ready",
                "dynamic_tool_proof_status": "ready",
                "dynamic_tool_outcome_contract_proven": True,
                "dynamic_tool_outcome_success": True,
                "tool_names": ["android_observe", "android_step"],
            },
            proof_validation={
                "ok": True,
                "status": "ready",
                "tool": "android_observe",
                "response_success": True,
                "outcome_status": "succeeded",
                "outcome_retryability": "none",
                "taxonomy_source": "provider_manifest",
                "response_path": "dist/interactive-session/codex-bridge/android-observe-proof.json",
            },
        ),
    )
    assert "- dynamic-tool specs: `ready`" in rendered_proof
    assert "- dynamic-tool proof: `ready`" in rendered_proof
    assert "- dynamic-tool outcome contract proven: `true`" in rendered_proof
    assert "- dynamic-tool outcome success: `true`" in rendered_proof
    assert "### Codex Android Dynamic-Tool Proof" in rendered_proof
    assert "- response success: `true`" in rendered_proof
    assert "- outcome status: `succeeded`" in rendered_proof
    assert "- outcome retryability: `none`" in rendered_proof
    assert "- taxonomy source: `provider_manifest`" in rendered_proof

    rendered_error = module.render_markdown(
        base_payload(
            None,
            proof_validation={
                "ok": False,
                "status": "invalid",
                "tool": "android_observe",
                "error": "failed at /home/runner/work/example\n`raw stderr`",
            },
        ),
    )
    assert "- error details: `see uploaded proof-validation artifact`" in rendered_error
    assert "/home/runner/work/example" not in rendered_error
    assert "`raw stderr`" not in rendered_error

    rendered_live_access = module.render_markdown(
        base_payload(
            None,
            active_build=None,
        )
        | {
            "summary": {
                **base_payload(None)["summary"],
                "live_access": {
                    "status": "ready",
                    "human_terminal": {
                        "status": "ready",
                        "hostname": "operator.example.invalid",
                        "auth_mode": "browser",
                    },
                    "agent_mcp": {
                        "status": "ready",
                        "hostname": "mcp.example.invalid",
                        "auth_mode": "bearer",
                    },
                },
            },
        },
    )
    assert "operator.example.invalid" not in rendered_live_access
    assert "mcp.example.invalid" not in rendered_live_access
    assert "- endpoint detail: `not this GitHub summary`" in rendered_live_access

    rendered_active_build = module.render_markdown(
        base_payload(
            None,
            active_build={
                "status": "installed",
                "activated_at_iso": "2026-04-26T00:00:00Z",
                "manifest": {
                    "artifact_name": "interactive-android-build-stage-first-runtime-hosted-debug-lite",
                    "android_validation_mode": "stage-first-runtime",
                    "interactive_debug_profile": "hosted-debug-lite",
                    "preferred_gpu_backend": "vulkan",
                    "commit_sha": "deadbeef",
                    "apk_sha256": "abc123",
                },
            },
        ),
    )
    assert "### Active Build" in rendered_active_build
    assert "- validation mode: `stage-first-runtime`" in rendered_active_build
    assert "- debug profile: `hosted-debug-lite`" in rendered_active_build
    assert "- preferred GPU backend: `vulkan`" in rendered_active_build

    status = module.classify_status(
        "success",
        None,
        None,
        None,
        {"dynamic_tool_proof_status": "invalid"},
        None,
        None,
    )
    assert status == "action_required"

    status = module.classify_status(
        "success",
        None,
        None,
        None,
        {"dynamic_tool_proof_status": "validation_unavailable"},
        None,
        {"ok": None, "status": "validation_unavailable"},
    )
    assert status == "success"

    status = module.classify_status(
        "success",
        None,
        None,
        None,
        {"dynamic_tool_proof_status": "ready"},
        None,
        {
            "ok": True,
            "status": "ready",
            "response_success": False,
            "outcome_status": "provider_unavailable",
            "outcome_retryability": "operator_required",
        },
    )
    assert status == "action_required"

    status = module.classify_status(
        "success",
        None,
        None,
        None,
        None,
        None,
        {"ok": False, "status": "invalid"},
    )
    assert status == "action_required"

    print("interactive session summary rendering tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
