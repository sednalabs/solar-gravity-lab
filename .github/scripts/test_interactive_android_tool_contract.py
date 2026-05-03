#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER_SCRIPT = ROOT / ".github" / "scripts" / "run_interactive_android_session.sh"
SUMMARY_SCRIPT = ROOT / ".github" / "scripts" / "write_interactive_session_summary.py"
SESSION_WORKFLOW = ROOT / ".github" / "workflows" / "interactive-android-session.yml"
QUERY_TEST_WORKFLOW = ROOT / ".github" / "workflows" / "codeql-query-tests.yml"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class InteractiveAndroidToolContractTests(unittest.TestCase):
    def test_runner_checks_dynamic_tool_specs_shape(self) -> None:
        text = read(RUNNER_SCRIPT)

        self.assertIn('codex_dynamic_tool_specs_path="${codex_bridge_dir}/tool-specs.json"', text)
        self.assertIn('node "${codex_dynamic_tools_bin}" --config "${config_path}" specs', text)
        self.assertIn("json.loads(specs_path.read_text())", text)
        self.assertIn('payload.get("tools")', text)
        self.assertIn('payload.get("tool_specs")', text)
        self.assertIn('{"android_observe", "android_step"} - names', text)
        self.assertIn("tool specs missing expected tools", text)
        self.assertIn('dynamic_tool_specs_status="invalid"', text)

    def test_runner_calls_observe_and_checks_outcome_shape(self) -> None:
        text = read(RUNNER_SCRIPT)

        self.assertIn('{"tool":"android_observe","arguments":{"scope":"screen"}}', text)
        self.assertIn('node "${codex_dynamic_tools_bin}" --config "${config_path}" call', text)
        self.assertIn('metadata.get("android")', text)
        self.assertIn('android_metadata.get("outcome")', text)
        self.assertIn('outcome.get("status")', text)
        self.assertIn('outcome.get("retryability")', text)
        self.assertIn('payload.get("success")', text)
        self.assertIn("isinstance(success, bool)", text)
        self.assertIn('"taxonomy_source": taxonomy_source', text)
        self.assertIn('"dynamic-tool proof response did not include a known Android outcome contract"', text)

    def test_runner_records_summary_consumable_status_fields(self) -> None:
        text = read(RUNNER_SCRIPT)

        for expected in (
            '"schema_version": 1',
            '"mode": "native_dynamic_tools"',
            '"dynamic_tool_command": sys.argv[2]',
            '"provider_manifest_status": sys.argv[7]',
            '"provider_manifest_validated": provider_manifest_ready',
            '"dynamic_tool_specs_status": sys.argv[9]',
            '"dynamic_tool_proof_status": sys.argv[12]',
            '"dynamic_tool_outcome_contract_proven": proof_contract_proven',
            '"dynamic_tool_outcome_success": proof_response_success',
            '"tool_names": ["android_observe", "android_step"]',
        ):
            self.assertIn(expected, text)

    def test_summary_consumes_native_tool_status_and_fails_closed(self) -> None:
        text = read(SUMMARY_SCRIPT)

        for expected in (
            'artifacts_dir / "codex-bridge" / "status.json"',
            'artifacts_dir / "codex-bridge" / "provider-manifest.json"',
            'artifacts_dir / "codex-bridge" / "provider-manifest-validation.json"',
            'artifacts_dir / "codex-bridge" / "android-observe-proof-validation.json"',
            'codex_bridge.get("provider_manifest_status") == "invalid"',
            'codex_bridge.get("dynamic_tool_proof_status") == "invalid"',
            'codex_dynamic_tool_proof_validation.get("ok") is False',
            'codex_dynamic_tool_proof_validation.get("response_success") is False',
            'codex_dynamic_tool_proof_validation.get("taxonomy_source")',
            'codex_bridge.get("tool_names")',
            '"codex_dynamic_tool_proof_validation": codex_dynamic_tool_proof_validation',
        ):
            self.assertIn(expected, text)

    def test_workflow_keeps_end_to_end_artifact_and_status_gate(self) -> None:
        text = read(SESSION_WORKFLOW)

        for expected in (
            "repository: sednalabs/android-emulator-mcp",
            "repository: GraciousGazelles/toolkits-mcp-toolkit-rs",
            "cargo build --release --locked",
            "script: bash .github/scripts/run_interactive_android_session.sh",
            "python3 .github/scripts/write_interactive_session_summary.py",
            "--output-json dist/interactive-session-summary/interactive-session-summary.json",
            "--output-md dist/interactive-session-summary/interactive-session-summary.md",
            "dist/interactive-session/**",
            "dist/interactive-session-summary/**",
            'summary.get("summary", {}).get("status")',
            'status != "success"',
        ):
            self.assertIn(expected, text)

    def test_codeql_query_tests_run_this_contract_guard(self) -> None:
        text = read(QUERY_TEST_WORKFLOW)

        self.assertIn(".github/scripts/test_interactive_android_tool_contract.py", text)
        self.assertIn("python3 .github/scripts/test_interactive_android_tool_contract.py", text)
        self.assertIn(".github/scripts/run_interactive_android_session.sh", text)
        self.assertIn(".github/scripts/write_interactive_session_summary.py", text)
        self.assertIn(".github/workflows/interactive-android-session.yml", text)


if __name__ == "__main__":
    unittest.main()
