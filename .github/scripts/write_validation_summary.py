#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Write a structured validation summary artifact.")
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--checkout-ref", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--profile-intent", required=True)
    parser.add_argument("--profile-notes", required=True)
    parser.add_argument("--lane-set", required=True)
    parser.add_argument("--lane-summary", required=True)
    parser.add_argument("--android-test-scope", required=True)
    parser.add_argument("--android-validation-mode", required=True)
    parser.add_argument("--android-artifact-mode", required=True)
    parser.add_argument("--emulator-boot-strategy", required=True)
    parser.add_argument("--android-shell-artifacts-dir")
    parser.add_argument("--job", action="append", default=[])
    parser.add_argument("--output-json", required=True)
    parser.add_argument("--output-md", required=True)
    return parser.parse_args()


def normalize_result(raw: str) -> str:
    value = (raw or "").strip().lower()
    return value or "unknown"


def classify_status(results: list[dict[str, str]]) -> tuple[str, str | None]:
    failing = [job["name"] for job in results if job["result"] not in {"success", "skipped"}]
    if failing:
        return "action_required", failing[0]
    if any(job["result"] == "success" for job in results):
        return "success", None
    return "no_effective_lanes", None


def load_android_shell_modes(artifacts_dir: str | None) -> list[dict[str, object]]:
    if not artifacts_dir:
        return []

    root = Path(artifacts_dir)
    if not root.exists():
        return []

    results: list[dict[str, object]] = []
    for status_path in sorted(root.glob("**/status.json")):
        try:
            payload = json.loads(status_path.read_text())
        except (OSError, json.JSONDecodeError):
            continue

        validation_mode = str(payload.get("validation_mode", "")).strip()
        if not validation_mode:
            continue

        result = "success"
        exit_code = payload.get("exit_code")
        if exit_code not in (0, "0", None):
            result = "failure"

        results.append(
            {
                "validation_mode": validation_mode,
                "scope": payload.get("scope", "unknown"),
                "artifact_mode": payload.get("artifact_mode", "unknown"),
                "timeout_seconds": payload.get("timeout_seconds", "unknown"),
                "exit_code": exit_code if exit_code is not None else "unknown",
                "result": result,
            }
        )

    return results


def render_markdown(payload: dict) -> str:
    lines = [
        "## validation-lab",
        "",
        f"- ref: `{payload['validation_context']['checkout_ref']}`",
        f"- profile: `{payload['validation_context']['profile']}`",
        f"- profile intent: `{payload['validation_context']['profile_intent']}`",
        f"- lane set: `{payload['validation_context']['lane_set']}`",
        f"- lane summary: `{payload['validation_context']['lane_summary']}`",
        f"- android test scope: `{payload['validation_context']['android_test_scope']}`",
        f"- android validation mode: `{payload['validation_context']['android_validation_mode']}`",
        f"- android artifact mode: `{payload['validation_context']['android_artifact_mode']}`",
        f"- emulator boot strategy: `{payload['validation_context']['emulator_boot_strategy']}`",
        f"- overall status: `{payload['summary']['status']}`",
    ]
    if payload["summary"]["first_blocker"] is not None:
        lines.append(f"- first blocker: `{payload['summary']['first_blocker']}`")
    lines.extend(
        [
            "",
            "| Job | Result |",
            "| --- | --- |",
        ]
    )
    for job in payload["jobs"]:
        lines.append(f"| `{job['name']}` | `{job['result']}` |")
    android_shell_modes = payload["summary"].get("android_shell_modes", [])
    if android_shell_modes:
        lines.extend(
            [
                "",
                "### Android Shell Matrix",
                "",
                "| Validation mode | Result | Scope | Exit code | Timeout |",
                "| --- | --- | --- | --- | --- |",
            ]
        )
        for mode in android_shell_modes:
            lines.append(
                f"| `{mode['validation_mode']}` | `{mode['result']}` | `{mode['scope']}` | `{mode['exit_code']}` | `{mode['timeout_seconds']}` |"
            )
    return "\n".join(lines) + "\n"


def main() -> None:
    args = parse_args()

    jobs = []
    for job_arg in args.job:
        name, _, raw_result = job_arg.partition("=")
        jobs.append({"name": name, "result": normalize_result(raw_result)})

    status, first_blocker = classify_status(jobs)
    failed_jobs = [job["name"] for job in jobs if job["result"] not in {"success", "skipped"}]
    android_shell_modes = load_android_shell_modes(args.android_shell_artifacts_dir)
    if not first_blocker:
        failing_modes = [mode["validation_mode"] for mode in android_shell_modes if mode["result"] != "success"]
        if failing_modes:
            first_blocker = f"android-shell:{failing_modes[0]}"
            status = "action_required"

    payload = {
        "schema_version": 1,
        "workflow": args.workflow,
        "run": {
            "id": args.run_id,
            "attempt": args.run_attempt,
            "url": args.run_url,
            "repository": args.repository,
        },
        "validation_context": {
            "checkout_ref": args.checkout_ref,
            "profile": args.profile,
            "profile_intent": args.profile_intent,
            "profile_notes": args.profile_notes,
            "lane_set": args.lane_set,
            "lane_summary": args.lane_summary,
            "android_test_scope": args.android_test_scope,
            "android_validation_mode": args.android_validation_mode,
            "android_artifact_mode": args.android_artifact_mode,
            "emulator_boot_strategy": args.emulator_boot_strategy,
        },
        "jobs": jobs,
        "summary": {
            "status": status,
            "first_blocker": first_blocker,
            "failed_jobs": failed_jobs,
            "failed_job_count": len(failed_jobs),
            "successful_job_count": sum(1 for job in jobs if job["result"] == "success"),
            "skipped_job_count": sum(1 for job in jobs if job["result"] == "skipped"),
            "failure_structure": "job_results_only",
            "android_shell_modes": android_shell_modes,
        },
        "frontier_blockers": [
            {
                "kind": "job_failure",
                "job": job_name,
                "recommended_follow_up": f"Inspect the failing `{job_name}` lane artifacts and logs.",
            }
            for job_name in failed_jobs
        ]
        + [
            {
                "kind": "android_shell_mode_failure",
                "job": f"android-shell:{mode['validation_mode']}",
                "recommended_follow_up": f"Rerun or inspect the `{mode['validation_mode']}` android-shell artifact bundle.",
            }
            for mode in android_shell_modes
            if mode["result"] != "success"
        ],
    }

    output_json = Path(args.output_json)
    output_md = Path(args.output_md)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    output_md.write_text(render_markdown(payload))


if __name__ == "__main__":
    main()
