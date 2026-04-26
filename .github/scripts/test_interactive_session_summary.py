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


def base_payload(outcome_taxonomy: dict | None) -> dict:
    policy = {
        "resumeBehavior": "revalidate_required",
        "persistOnResume": False,
    }
    if outcome_taxonomy is not None:
        policy["outcomeTaxonomy"] = outcome_taxonomy

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
            "emulator_boot_strategy": "snapshot-cache",
            "session_timeout_minutes": "30",
            "keep_session_on_failure": "true",
        },
        "summary": {
            "status": "success",
            "job_result": "success",
            "artifacts_dir": "dist/interactive-session",
            "codex_provider_manifest": {
                "provider": {
                    "adapter": "android",
                    "transport": "android-emulator-mcp",
                },
                "policy": policy,
            },
        },
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

    print("interactive session summary rendering tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
