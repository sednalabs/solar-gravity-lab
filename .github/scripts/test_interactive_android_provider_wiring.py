#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "interactive-android-session.yml"
SESSION_SCRIPT = ROOT / ".github" / "scripts" / "run_interactive_android_session.sh"


def require_once(text: str, expected: str) -> None:
    count = text.count(expected)
    assert count == 1, f"expected one {expected!r}, found {count}"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    session_script = SESSION_SCRIPT.read_text(encoding="utf-8")

    require_once(workflow, "repository: sednalabs/android-computer-use-mcp")
    require_once(workflow, "persist-credentials: false")
    require_once(
        workflow,
        "default: 5aa8fa9cd4315e3d7f644647da6afbd6a28027cc",
    )
    checkout_credential_field = "".join(("to", "ken"))
    assert f"\n          {checkout_credential_field}:" not in workflow

    for expected in (
        "android_computer_use_mcp_ref:",
        "--android-computer-use-mcp-ref",
        "--android-computer-use-mcp-sha",
        "--mcp-toolkit-rs-sha",
        "INTERACTIVE_MCP_ENVIRONMENT_ID:",
        "INTERACTIVE_MCP_PROVIDER_INSTANCE_ID:",
        "INTERACTIVE_MCP_SESSION_ID:",
    ):
        assert expected in workflow, f"missing provider wiring contract: {expected}"

    for expected in (
        "ANDROID_COMPUTER_USE_MCP_ENVIRONMENT_ID",
        "ANDROID_COMPUTER_USE_MCP_PROVIDER_INSTANCE_ID",
        "ANDROID_COMPUTER_USE_MCP_SESSION_ID",
        "target/release/android-computer-use-mcp",
        "android-computer-use-mcp-artifacts",
    ):
        assert expected in session_script, f"missing runtime wiring contract: {expected}"

    print("interactive Android public-provider wiring tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
