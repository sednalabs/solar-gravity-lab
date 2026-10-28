from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_android_capability_census as census


class CensusInputBoundaryTests(unittest.TestCase):
    def test_device_serial_does_not_become_a_shell_argument(self) -> None:
        serial = "192.0.2.1:5555"
        with patch.object(census.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = "features: fp asimd"
            self.assertEqual(census.adb_command(serial, "cat_cpuinfo", required=True), "features: fp asimd")

        self.assertEqual(run.call_args.args[0], ["adb", "shell", "cat", "/proc/cpuinfo"])
        self.assertEqual(run.call_args.kwargs["env"]["ANDROID_SERIAL"], serial)

    def test_unrecognized_command_and_malformed_serial_are_rejected(self) -> None:
        with patch.object(census.subprocess, "run") as run:
            with self.assertRaises(census.CommandFailure):
                census.adb_command("device", "cat_cpuinfo; id")
            with self.assertRaises(census.CommandFailure):
                census.validate_adb_serial("device;id")
            run.assert_not_called()

    def test_absolute_summary_path_is_supported(self) -> None:
        payload = {
            "schema_version": "test",
            "capture_surface": "test",
            "device_label": "test",
            "cpu": {"detected_features": [], "uncataloged_detected_tokens": []},
            "runtime_truth": {
                "candidate_kernel_paths": [],
                "eligible_candidate_kernel_paths": [],
                "blocked_candidate_kernel_paths": [],
                "reserved_feature_claims": [],
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "summary.txt"
            census.write_summary(str(output), payload)
            self.assertIn("capability-census: collected", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
