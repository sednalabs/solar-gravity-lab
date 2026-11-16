from __future__ import annotations

import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("ensure_android_sdk_packages.sh")
PACKAGES = ["platform-tools", "system-images;android-35;google_apis;x86_64"]
FAKE_MANAGER = r'''#!/usr/bin/env python3
import json
import os
import signal
import sys
import time
from pathlib import Path

root = Path(os.environ["FAKE_SDK_ROOT"])
config = json.loads((root / "config.json").read_text())
state_path = root / "state.json"
state = json.loads(state_path.read_text()) if state_path.exists() else {
    "installed": config.get("installed", []), "calls": [], "counts": {}}
arguments = sys.argv[1:]
command = arguments[0]
state["calls"].append(arguments)
count = state["counts"][command] = state["counts"].get(command, 0) + 1
state_path.write_text(json.dumps(state))

if command == config.get("hang_command"):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    print("Fake SDK manager is blocked", flush=True)
    while True:
        time.sleep(60)
failure_start = config.get("failure_start", 1)
if command == config.get("fail_command") and failure_start <= count < failure_start + config.get("fail_count", 0):
    print("Fake SDK manager transient/final failure", file=sys.stderr, flush=True)
    sys.exit(7)
if command == "--list_installed":
    print("Installed packages:")
    print("  Path | Version | Description | Location")
    print("  ------- | ------- | ------- | -------")
    for package in state["installed"]:
        print(f"  {package} | 1 | Fake package | location")
elif command == "--licenses":
    assert sys.stdin.readline().strip() == "y"
    print("All SDK package licenses accepted.")
elif command == "--install":
    print("Fake download/extraction progress", flush=True)
    if not config.get("omit_install"):
        state["installed"] = list(dict.fromkeys(state["installed"] + arguments[1:]))
        state_path.write_text(json.dumps(state))
else:
    sys.exit(9)
'''


class EnsureAndroidSdkPackagesTests(unittest.TestCase):
    def run_setup(self, config: dict, *, environment: dict | None = None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config.json").write_text(json.dumps(config))
            manager = root / "sdkmanager"
            manager.write_text(FAKE_MANAGER)
            manager.chmod(0o755)
            github_output = root / "github-output"
            env = os.environ.copy()
            env.update({
                "PATH": f"{root}:{env['PATH']}",
                "FAKE_SDK_ROOT": str(root),
                "GITHUB_OUTPUT": str(github_output),
                "SOLARLAB_SDK_LIST_TIMEOUT_SECONDS": "2",
                "SOLARLAB_SDK_LICENSE_TIMEOUT_SECONDS": "2",
                "SOLARLAB_SDK_INSTALL_TIMEOUT_SECONDS": "2",
                "SOLARLAB_SDK_RETRY_DELAY_SECONDS": "0",
                "SOLARLAB_SDK_KILL_GRACE_SECONDS": "0.1",
            })
            env.update(environment or {})
            started = time.monotonic()
            result = subprocess.run(["bash", str(SCRIPT), *PACKAGES], env=env,
                                    capture_output=True, text=True, timeout=10)
            elapsed = time.monotonic() - started
            state = json.loads((root / "state.json").read_text())
            output = github_output.read_text() if github_output.exists() else ""
            return result, state, output, elapsed

    def test_all_installed_skips_license_and_install(self):
        result, state, output, _ = self.run_setup({"installed": PACKAGES})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([["--list_installed"]], state["calls"])
        self.assertIn("skipping license and install operations", result.stdout)
        self.assertRegex(output, r"^sdk_setup_seconds=\d+\n$")

    def test_missing_packages_are_installed_and_verified(self):
        result, state, output, _ = self.run_setup({"installed": PACKAGES[:1]})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([["--list_installed"], ["--licenses"],
                          ["--install", PACKAGES[1]], ["--list_installed"]], state["calls"])
        self.assertIn("Fake download/extraction progress", result.stdout)
        self.assertIn("verified installed", result.stdout)
        self.assertTrue(output)

    def test_transient_failures_retry_each_phase(self):
        for command in ["--list_installed", "--licenses", "--install"]:
            with self.subTest(command=command):
                result, state, _, _ = self.run_setup({
                    "installed": PACKAGES[:1], "fail_command": command, "fail_count": 1})
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(3 if command == "--list_installed" else 2,
                                 state["counts"][command])
                self.assertIn("retrying", result.stdout)
                self.assertEqual(["--list_installed"], state["calls"][-1])

    def test_final_failure_propagates_and_stops_later_phases(self):
        for command in ["--list_installed", "--licenses", "--install"]:
            with self.subTest(command=command):
                result, state, output, _ = self.run_setup({
                    "installed": PACKAGES[:1], "fail_command": command, "fail_count": 2})
                self.assertEqual(7, result.returncode)
                self.assertEqual(2, state["counts"][command])
                self.assertEqual(command, state["calls"][-1][0])
                self.assertIn("failed after 2 attempts", result.stdout)
                self.assertEqual("", output)

    def test_timeout_kills_unresponsive_command_and_fails_after_two_attempts(self):
        result, state, output, elapsed = self.run_setup(
            {"installed": PACKAGES[:1], "hang_command": "--licenses"},
            environment={"SOLARLAB_SDK_LICENSE_TIMEOUT_SECONDS": "0.5"},
        )
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, state["counts"]["--licenses"])
        self.assertNotIn("--install", state["counts"])
        self.assertIn("timed out after 0.5s", result.stdout)
        self.assertLess(elapsed, 5)
        self.assertEqual("", output)

    def test_successful_install_must_really_supply_every_requested_package(self):
        result, state, output, _ = self.run_setup({"installed": PACKAGES[:1], "omit_install": True})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["--list_installed"], state["calls"][-1])
        self.assertIn(f"requested packages still missing: {PACKAGES[1]}", result.stdout)
        self.assertEqual("", output)

    def test_final_inventory_failure_is_not_masked_by_successful_install(self):
        result, state, output, _ = self.run_setup({
            "installed": PACKAGES[:1], "fail_command": "--list_installed",
            "failure_start": 2, "fail_count": 2,
        })
        self.assertEqual(7, result.returncode)
        self.assertEqual(1, state["counts"]["--install"])
        self.assertEqual(3, state["counts"]["--list_installed"])
        self.assertIn("Verify installed SDK packages failed after 2 attempts", result.stdout)
        self.assertEqual("", output)


if __name__ == "__main__":
    unittest.main()
