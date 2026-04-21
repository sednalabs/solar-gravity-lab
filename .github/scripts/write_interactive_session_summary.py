#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Write an interactive Android session summary artifact.")
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--checkout-ref", required=True)
    parser.add_argument("--build-source", required=True)
    parser.add_argument("--artifact-name", required=True)
    parser.add_argument("--build-run-id", required=True)
    parser.add_argument("--build-commit-sha", required=True)
    parser.add_argument("--android-emulator-mcp-ref", required=True)
    parser.add_argument("--mcp-toolkit-rs-ref", required=True)
    parser.add_argument("--android-validation-mode", required=True)
    parser.add_argument("--interactive-debug-profile", required=True)
    parser.add_argument("--emulator-boot-strategy", required=True)
    parser.add_argument("--session-timeout-minutes", required=True)
    parser.add_argument("--keep-session-on-failure", required=True)
    parser.add_argument("--job-result", required=True)
    parser.add_argument("--artifacts-dir", required=True)
    parser.add_argument("--output-json", required=True)
    parser.add_argument("--output-md", required=True)
    return parser.parse_args()


def load_json(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None


def classify_status(job_result: str, preflight: dict | None, live_access: dict | None, session_state: dict | None) -> str:
    if job_result != "success":
        return "action_required"
    if preflight and preflight.get("status") != "ready":
        return "action_required"
    if live_access:
        if live_access.get("status") != "ready":
            return "action_required"
        human_terminal = live_access.get("human_terminal")
        if human_terminal and human_terminal.get("status") != "ready":
            return "action_required"
        agent_mcp = live_access.get("agent_mcp")
        if agent_mcp and agent_mcp.get("status") not in {"ready", "disabled"}:
            return "action_required"
    if session_state and session_state.get("status") != "success":
        return "action_required"
    return "success"


def render_markdown(payload: dict) -> str:
    def as_flag(value: object) -> str:
        return "true" if bool(value) else "false"

    lines = [
        "## interactive-android-session",
        "",
        f"- ref: `{payload['context']['checkout_ref']}`",
        f"- build source: `{payload['context']['build_source']}`",
        f"- build artifact: `{payload['context']['artifact_name']}`",
        f"- build run id: `{payload['context']['build_run_id']}`",
        f"- build commit: `{payload['context']['build_commit_sha']}`",
        f"- android-emulator-mcp ref: `{payload['context']['android_emulator_mcp_ref']}`",
        f"- mcp-toolkit-rs ref: `{payload['context']['mcp_toolkit_rs_ref']}`",
        f"- android validation mode: `{payload['context']['android_validation_mode']}`",
        f"- interactive debug profile: `{payload['context']['interactive_debug_profile']}`",
        f"- emulator boot strategy: `{payload['context']['emulator_boot_strategy']}`",
        f"- timeout minutes: `{payload['context']['session_timeout_minutes']}`",
        f"- keep session on failure: `{payload['context']['keep_session_on_failure']}`",
        f"- overall status: `{payload['summary']['status']}`",
        f"- job result: `{payload['summary']['job_result']}`",
    ]

    preflight = payload["summary"].get("preflight")
    if preflight:
        lines.extend(
            [
                "",
                "### Preflight",
                "",
                f"- status: `{preflight.get('status', 'unknown')}`",
                f"- mcp health ok: `{as_flag(preflight.get('mcp_health_ok', False))}`",
                f"- launch smoke ok: `{as_flag(preflight.get('launch_smoke_ok', False))}`",
            ]
        )

    live_access = payload["summary"].get("live_access")
    if live_access:
        human_terminal = live_access.get("human_terminal")
        agent_mcp = live_access.get("agent_mcp")
        lines.extend(
            [
                "",
                "### Live Access",
                "",
                f"- overall status: `{live_access.get('status', 'unknown')}`",
                f"- finish sentinel: `{live_access.get('finish_sentinel', 'n/a')}`",
            ]
        )
        if human_terminal:
            lines.extend(
                [
                    "",
                    "#### Human Terminal",
                    "",
                    f"- status: `{human_terminal.get('status', 'unknown')}`",
                    f"- hostname: `{human_terminal.get('hostname', 'n/a')}`",
                    f"- auth mode: `{human_terminal.get('auth_mode', 'n/a')}`",
                ]
            )
        if agent_mcp:
            lines.extend(
                [
                    "",
                    "#### Agent MCP",
                    "",
                    f"- status: `{agent_mcp.get('status', 'unknown')}`",
                    f"- hostname: `{agent_mcp.get('hostname', 'n/a')}`",
                    f"- auth mode: `{agent_mcp.get('auth_mode', 'n/a')}`",
                ]
            )

    session_state = payload["summary"].get("session_state")
    if session_state:
        lines.extend(
            [
                "",
                "### Session State",
                "",
                f"- status: `{session_state.get('status', 'unknown')}`",
                f"- reason: `{session_state.get('reason', 'unknown')}`",
                f"- started: `{session_state.get('session_start_iso', 'n/a')}`",
                f"- ended: `{session_state.get('session_end_iso', 'n/a')}`",
            ]
        )

    openai_loop = payload["summary"].get("openai_loop")
    if openai_loop:
        lines.extend(
            [
                "",
                "### Standalone OpenAI Helper",
                "",
                f"- status: `{openai_loop.get('status', 'unknown')}`",
            ]
        )
        if openai_loop.get("reason"):
            lines.append(f"- reason: `{openai_loop.get('reason')}`")
        if openai_loop.get("helper_path"):
            lines.append(f"- helper path: `{openai_loop.get('helper_path')}`")
        if openai_loop.get("config_path"):
            lines.append(f"- config path: `{openai_loop.get('config_path')}`")
        if openai_loop.get("output_root"):
            lines.append(f"- output root: `{openai_loop.get('output_root')}`")
        if openai_loop.get("default_model"):
            lines.append(f"- default model: `{openai_loop.get('default_model')}`")

    codex_bridge = payload["summary"].get("codex_bridge")
    if codex_bridge:
        lines.extend(
            [
                "",
                "### Codex Native Android Tools",
                "",
                f"- status: `{codex_bridge.get('status', 'unknown')}`",
            ]
        )
        if codex_bridge.get("reason"):
            lines.append(f"- reason: `{codex_bridge.get('reason')}`")
        if codex_bridge.get("mode"):
            lines.append(f"- mode: `{codex_bridge.get('mode')}`")
        if codex_bridge.get("dynamic_tool_helper_path"):
            lines.append(
                f"- dynamic-tool helper path: `{codex_bridge.get('dynamic_tool_helper_path')}`"
            )
        if codex_bridge.get("dynamic_tool_command"):
            lines.append(
                f"- dynamic-tool command: `{codex_bridge.get('dynamic_tool_command')}`"
            )
        if codex_bridge.get("observe_helper_path"):
            lines.append(
                f"- observe helper path: `{codex_bridge.get('observe_helper_path')}`"
            )
        if codex_bridge.get("tool_names"):
            tools = ", ".join(f"`{tool}`" for tool in codex_bridge.get("tool_names"))
            lines.append(f"- model-callable tools: {tools}")
        if codex_bridge.get("config_path"):
            lines.append(f"- config path: `{codex_bridge.get('config_path')}`")
        if codex_bridge.get("output_root"):
            lines.append(f"- output root: `{codex_bridge.get('output_root')}`")

    active_build = payload["summary"].get("active_build")
    if active_build:
        manifest = active_build.get("manifest", {})
        lines.extend(
            [
                "",
                "### Active Build",
                "",
                f"- status: `{active_build.get('status', 'unknown')}`",
                f"- activated: `{active_build.get('activated_at_iso', 'n/a')}`",
                f"- artifact name: `{manifest.get('artifact_name', 'n/a')}`",
                f"- commit sha: `{manifest.get('commit_sha', 'n/a')}`",
                f"- apk sha256: `{manifest.get('apk_sha256', 'n/a')}`",
            ]
        )

    lines.extend(
        [
            "",
            "### Artifacts",
            "",
            f"- root: `{payload['summary']['artifacts_dir']}`",
            "- expected directories:",
            "  - `android-emulator-mcp-artifacts/`",
            "  - `preflight/`",
            "  - `startup-log/`",
            "  - `live-access/`",
            "  - `emulator-logcat/`",
            "  - `ui-dumps/`",
            "  - `screenshots/`",
            "  - `codex-bridge/`",
            "  - `codex-bridge-runs/`",
            "  - `openai-loop/`",
            "  - `openai-loop-runs/`",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> None:
    args = parse_args()
    artifacts_dir = Path(args.artifacts_dir)
    preflight = load_json(artifacts_dir / "preflight" / "preflight.json")
    live_access = load_json(artifacts_dir / "live-access" / "status.json")
    session_state = load_json(artifacts_dir / "session-state.json")
    active_build = load_json(artifacts_dir / "active-build.json")
    openai_loop = load_json(artifacts_dir / "openai-loop" / "status.json")
    codex_bridge = load_json(artifacts_dir / "codex-bridge" / "status.json")

    payload = {
        "schema_version": 1,
        "workflow": args.workflow,
        "run": {
            "id": args.run_id,
            "attempt": args.run_attempt,
            "url": args.run_url,
            "repository": args.repository,
        },
        "context": {
            "checkout_ref": args.checkout_ref,
            "build_source": args.build_source,
            "artifact_name": args.artifact_name,
            "build_run_id": args.build_run_id,
            "build_commit_sha": args.build_commit_sha,
            "android_emulator_mcp_ref": args.android_emulator_mcp_ref,
            "mcp_toolkit_rs_ref": args.mcp_toolkit_rs_ref,
            "android_validation_mode": args.android_validation_mode,
            "interactive_debug_profile": args.interactive_debug_profile,
            "emulator_boot_strategy": args.emulator_boot_strategy,
            "session_timeout_minutes": args.session_timeout_minutes,
            "keep_session_on_failure": args.keep_session_on_failure,
        },
        "summary": {
            "status": classify_status(args.job_result, preflight, live_access, session_state),
            "job_result": args.job_result,
            "artifacts_dir": str(artifacts_dir),
            "preflight": preflight,
            "live_access": live_access,
            "session_state": session_state,
            "active_build": active_build,
            "openai_loop": openai_loop,
            "codex_bridge": codex_bridge,
        },
    }

    output_json = Path(args.output_json)
    output_md = Path(args.output_md)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    output_md.write_text(render_markdown(payload))


if __name__ == "__main__":
    main()
