"""Adversarial measurement/integrity cases, not mirrors of the implementation."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import analyse_benchmark_report as report


def journal(blocks=5, scale=.75):
    records = []
    for block in range(blocks):
        # Deliberate monotonic device-speed drift within each ABBA block cancels
        # in the geometric paired ratio, unlike a naive pooled median.
        for order, time in zip(report.ABBA, [100., 110. * scale, 120. * scale, 132.]):
            data = {"pair": "cpu:candidate/sparse3/8/system", "case_id": "control" if order.startswith("A") else "candidate",
                    "backend": "cpu", "fixture_id": "sparse3", "steps": 8, "block": block, "round": 0,
                    "order": order, "role": order[0], "status": "PASS", "elapsed_ns": time,
                    "native": {"status": "PASS", "max_abs_error": 1e-13}, "placement": "system",
                    "environment_before": {"thermal_status": 0, "battery_plugged": 0},
                    "environment_after": {"thermal_status": 0, "battery_plugged": 0}}
            records.append({"schema_version": 1, "event": "sample", "monotonic_ns": len(records), "data": data})
    return records


def analyse(records):
    return report.analyse({"manifest.json": b'{"schema_version":1}',
                           "events.ndjson": b''.join((json.dumps(r) + '\n').encode() for r in records)})


class ResearchReportTest(unittest.TestCase):
    def test_abba_handles_shared_monotonic_multiplicative_drift(self):
        comparison = analyse(journal())["comparisons"][0]
        self.assertAlmostEqual(comparison["median_change_percent"], -25.0)
        self.assertEqual(comparison["clean_blocks"], 5)
        self.assertEqual(comparison["decision"], "promising_candidate_repeat_on_device")

    def test_fast_incorrect_candidate_cannot_win(self):
        rows = journal(scale=.1)
        rows[1]["data"]["native"]["status"] = "FAIL"
        rows[1]["data"]["status"] = "FAIL"
        comparison = analyse(rows)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 4)
        self.assertEqual(comparison["decision"], "correctness_failure_requires_investigation")

    def test_thermal_and_charge_changes_excluded_but_retained(self):
        rows = journal()
        rows[1]["data"]["environment_after"]["thermal_status"] = 3
        rows[5]["data"]["environment_after"]["battery_plugged"] = 2
        comparison = analyse(rows)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 3)
        self.assertEqual(comparison["complete_valid_blocks"], 5)
        self.assertIsNone(comparison["bootstrap_95_percent_interval"])

    def test_cooldown_excluded_even_when_cached_environment_looks_cool(self):
        rows = journal()
        rows.append({"schema_version": 1, "event": "paired_block", "monotonic_ns": len(rows),
                     "data": {"pair": rows[0]["data"]["pair"], "round": 0, "block": 0, "crossed_thermal_pause": True}})
        comparison = analyse(rows)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 4)
        self.assertEqual(comparison["excluded_reasons"]["crossed_thermal_pause"], 1)

    def test_native_failure_vetoes_incorrect_wrapper_and_self_control(self):
        rows = journal()
        for row in rows:
            row["data"]["case_id"] = "control"
        rows[1]["data"]["native"]["status"] = "FAIL"
        self.assertEqual(analyse(rows)["comparisons"][0]["decision"], "correctness_failure_requires_investigation")

    def test_changed_physics_or_accuracy_policy_is_not_an_equivalent_speedup(self):
        for kind, expected in [("different_force_model_cost", "different_physics_cost_only"),
                               ("accuracy_cost_experiment", "accuracy_cost_tradeoff_requires_domain_review")]:
            rows = journal(scale=.1)
            for row in rows:
                if row["data"]["role"] == "B":
                    row["data"]["native"]["comparison_kind"] = kind
            comparison = analyse(rows)["comparisons"][0]
            self.assertEqual(comparison["clean_blocks"], 5)
            self.assertEqual(comparison["decision"], expected)

    def test_live_capture_deduplicates_gpu_counter_and_keeps_ui_separate(self):
        data = [
            ("observatory_renderer", {"gpu_timing": {"samples": 3, "frameNs": 1200}}),
            ("observatory_renderer", {"gpu_timing": {"samples": 3, "frameNs": 1200}}),
            ("observatory_renderer", {"gpu_timing": {"samples": 4, "frameNs": 1400}, "queue_dropped_events_total": 2}),
            ("observatory_snapshot", {"ui_frame_intervals_ns": [16_666_667, 33_333_334],
                                      "environment": {"monotonic_ns": 1, "thermal_status": 0, "battery_plugged": 0,
                                                      "battery_energy_counter_nwh": 10_000_000}}),
            ("observatory_snapshot", {"environment": {"monotonic_ns": 1_000_000_001, "thermal_status": 1,
                                                      "battery_plugged": 0, "battery_energy_counter_nwh": 9_000_000}}),
        ]
        result = analyse([{"schema_version": 1, "monotonic_ns": i, "event": event, "data": values}
                          for i, (event, values) in enumerate(data)])
        self.assertEqual(result["comparisons"], [])
        observations = result["observations"]
        self.assertEqual(observations["gpu_last_completed_sample"]["count"], 2)
        self.assertEqual(observations["gpu_last_completed_sample"]["median_ns"], 1300)
        self.assertEqual(observations["ui_vsync_interval"]["count"], 2)
        self.assertEqual(observations["queue_dropped_events"], 2)
        self.assertAlmostEqual(observations["battery_energy"]["observed_unplugged_depletion_j"], 3.6)

    def test_incomplete_and_duplicate_blocks_do_not_count(self):
        rows = journal(2)
        rows.pop()
        rows.insert(1, dict(rows[0]))
        for n, r in enumerate(rows): r["monotonic_ns"] = n
        comparison = analyse(rows)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 0)

    def test_torn_tail_preserves_previous_completed_record(self):
        content = json.dumps(journal(1)[0]).encode() + b'\n{"torn":'
        rows, warnings = report.read_journal(content)
        self.assertEqual(len(rows), 1)
        self.assertTrue(warnings)

    def test_changed_workload_is_not_a_speedup(self):
        rows = journal(1)
        rows[1]["data"]["steps"] = 1
        self.assertEqual(analyse(rows)["comparisons"][0]["clean_blocks"], 0)

    def test_integrity_detects_changed_raw_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.zip"
            files = {"manifest.json": b'{}', "events.ndjson": b''}
            index = {"schema_version": 1, "algorithm": "SHA-256", "files": [
                {"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                for name, content in files.items()]}
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr("manifest.json", b'{}')
                z.writestr("events.ndjson", b'{"tampered":true}\n')
                z.writestr("archive-index.json", json.dumps(index))
            with self.assertRaisesRegex(ValueError, "Integrity failure"):
                report.load_export(path)

    def test_directory_member_cannot_follow_a_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            outside = root / "outside.json"
            outside.write_text('{"outside":true}')
            directory = root / "session"
            directory.mkdir()
            (directory / "manifest.json").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "Symlinked report member"):
                report.load_export(directory)


if __name__ == "__main__":
    unittest.main()
