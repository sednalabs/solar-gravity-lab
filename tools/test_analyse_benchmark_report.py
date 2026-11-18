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


if __name__ == "__main__":
    unittest.main()
