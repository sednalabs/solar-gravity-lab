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


def observation(sequence, scope="bridge-1", polls=100, suppressed=80, exports=30, failures=2):
    return {
        "schema_version": 1, "event": "observatory_snapshot", "monotonic_ns": sequence,
        "source_monotonic_ns": sequence * 1_000_000_000,
        "data": {"runtime_observation": {
            "schema_version": 1, "scope_id": scope, "periodic_summary_polls": polls,
            "paused_duplicate_suppressions": suppressed, "packet_export_attempts": exports,
            "packet_export_failures": failures,
        }},
    }


class ResearchReportTest(unittest.TestCase):
    def test_cache_callbacks_are_not_added_as_independent_loads_or_saves(self):
        old = {"source": "disk-supplied", "load": "loaded", "save": "saved", "saveAttempts": 3,
               "loadedBytes": 4666, "pipelineCreateNs": 123456, "hits": "unmeasured"}
        restarted = {**old, "source": "empty", "load": "driver-mismatch", "saveAttempts": 1}
        rows = [{"schema_version": 1, "event": "observatory_renderer", "monotonic_ns": i,
                 "data": {"pipeline_cache_persistence": state}} for i, state in enumerate([old, old, restarted, restarted])]
        cache = analyse(rows)["observations"]["pipeline_cache_persistence"]
        self.assertEqual(cache["observed_sources"], ["disk-supplied", "empty"])
        self.assertEqual(cache["latest_observed_lifecycle_counters"]["saveAttempts"], 1)
        self.assertEqual(cache["latest_observed_lifecycle_counters"]["hits"], "unmeasured")
        self.assertNotIn("total_saves", cache)

    def test_old_export_does_not_invent_pipeline_cache_evidence(self):
        cache = analyse(journal())["observations"]["pipeline_cache_persistence"]
        self.assertFalse(cache["available"])
        self.assertIsNone(cache["latest_observed_lifecycle_counters"])

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

    def test_legacy_fp16_host_improvement_does_not_become_a_gpu_or_precision_win(self):
        rows = journal(scale=.5)
        for row in rows:
            data = row["data"]
            data["native"].update(gpu_timestamp_valid=True, gpu_elapsed_ns=10_000)
            data["jni_wrapper_elapsed_ns"] = data["elapsed_ns"] * 4
            if data["role"] == "B":
                data["case_id"] = "gpu.visual-fp16"
        comparison = analyse(rows)["comparisons"][0]
        self.assertTrue(comparison["legacy_precision_classification_inferred"])
        self.assertEqual(comparison["decision"], "accuracy_cost_tradeoff_requires_domain_review")
        self.assertAlmostEqual(comparison["median_change_percent"], -50.0)
        gpu = comparison["separate_timing_metrics"]["gpu_timestamp"]
        self.assertEqual(gpu["clean_blocks"], 5)
        self.assertEqual(gpu["median_change_percent"], 0.0)
        self.assertEqual(gpu["baseline"]["median_ns"], 10_000)

    def test_invalid_gpu_queries_are_not_host_time_substitutions(self):
        rows = journal()
        for row in rows:
            row["data"]["native"].update(gpu_timestamp_valid=True, gpu_elapsed_ns=20_000)
        rows[1]["data"]["native"]["gpu_timestamp_valid"] = False
        rows[5]["data"]["native"]["gpu_elapsed_ns"] = None
        comparison = analyse(rows)["comparisons"][0]
        gpu = comparison["separate_timing_metrics"]["gpu_timestamp"]
        self.assertEqual(comparison["clean_blocks"], 5)
        self.assertEqual(gpu["clean_blocks"], 3)
        self.assertEqual(gpu["blocks_without_valid_metric"], 2)
        self.assertIsNone(gpu["bootstrap_95_percent_interval"])

    def test_failed_calibration_vetoes_later_fast_measurements_and_survives_retirement(self):
        failed = journal(1)[1]
        failed["data"].update(warmup=True, phase="calibration", order="calibration-B", status="FAIL")
        failed["data"]["native"]["status"] = "FAIL"
        for rows in [[failed], [failed] + journal(scale=.1)]:
            for number, row in enumerate(rows):
                row["monotonic_ns"] = number
            comparison = analyse(rows)["comparisons"][0]
            self.assertEqual(comparison["decision"], "correctness_failure_requires_investigation")
            self.assertEqual(comparison["failed_samples_including_calibration_and_warmup"], 1)

    def test_repeat_mismatch_and_silent_native_clamp_exclude_blocks(self):
        for values, expected in [([1, 2, 2, 1], "repeat_count_changed_or_invalid_within_block"),
                                 ([16] * 4, "requested_repeat_count_not_executed")]:
            rows = journal(1)
            for row, repeats in zip(rows, values):
                row["data"]["repeats"] = repeats
                row["data"]["effective_repeats"] = min(repeats, 8)
            comparison = analyse(rows)["comparisons"][0]
            self.assertEqual(comparison["clean_blocks"], 0)
            self.assertEqual(comparison["excluded_reasons"][expected], 1)

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

    def test_runtime_work_excludes_lifetime_work_before_recording(self):
        rows = [observation(1), observation(2, polls=110, suppressed=90),
                observation(3, polls=120, suppressed=98, exports=32, failures=3)]
        result = analyse(rows)
        work = result["observations"]["runtime_observation"]
        self.assertTrue(work["available"])
        self.assertEqual(work["observed_counter_deltas"], {
            "periodic_summary_polls": 20, "paused_duplicate_suppressions": 18,
            "packet_export_attempts": 2, "packet_export_failures": 1,
        })
        self.assertEqual(work["observed_interval_ns"], 2_000_000_000)
        self.assertAlmostEqual(work["paused_exports_avoided_per_periodic_poll"], .9)
        self.assertIn("**18** paused duplicate exports avoided", report.markdown(result))
        self.assertEqual(result["comparisons"], [])

    def test_runtime_work_does_not_subtract_across_bridge_recreation(self):
        rows = [observation(1), observation(2, polls=110, suppressed=90),
                observation(3, scope="bridge-2", polls=2, suppressed=0, exports=2, failures=0),
                observation(4, scope="bridge-2", polls=7, suppressed=5, exports=2, failures=0)]
        work = analyse(rows)["observations"]["runtime_observation"]
        self.assertEqual(work["bridge_scope_count"], 2)
        self.assertEqual(work["accepted_intervals"], 2)
        self.assertEqual(work["observed_counter_deltas"]["paused_duplicate_suppressions"], 15)
        self.assertEqual(work["excluded_intervals_or_observations"]["bridge_scope_changed"], 1)

    def test_runtime_work_does_not_bridge_missing_or_malformed_counters(self):
        for missing in [None, {"schema_version": 1, "scope_id": "bridge-1"},
                        {**observation(1)["data"]["runtime_observation"], "periodic_summary_polls": True}]:
            rows = [observation(1), observation(2), observation(3, polls=120, suppressed=100)]
            rows[1]["data"]["runtime_observation"] = missing
            work = analyse(rows)["observations"]["runtime_observation"]
            self.assertFalse(work["available"])
            self.assertIsNone(work["observed_counter_deltas"])

    def test_runtime_work_excludes_regressions_and_impossible_deltas(self):
        rows = [observation(1), observation(2, polls=10, suppressed=5, exports=3, failures=0),
                observation(3, polls=20, suppressed=15, exports=3, failures=0)]
        work = analyse(rows)["observations"]["runtime_observation"]
        self.assertEqual(work["accepted_intervals"], 1)
        self.assertEqual(work["observed_counter_deltas"]["periodic_summary_polls"], 10)
        self.assertEqual(work["excluded_intervals_or_observations"]["counter_reset_or_regression"], 1)
        rows = [observation(1), observation(2, polls=105, suppressed=90)]
        work = analyse(rows)["observations"]["runtime_observation"]
        self.assertFalse(work["available"])
        self.assertEqual(work["excluded_intervals_or_observations"]["inconsistent_counter_delta"], 1)

    def test_runtime_work_requires_source_time_and_two_coherent_observations(self):
        for rows in [[], [observation(1)], [observation(1), observation(1, polls=110, suppressed=90)]]:
            work = analyse(rows)["observations"]["runtime_observation"]
            self.assertFalse(work["available"])
            self.assertIsNone(work["observed_counter_deltas"])
        rows = [observation(1), observation(2, polls=110, suppressed=90)]
        del rows[1]["source_monotonic_ns"]
        self.assertFalse(analyse(rows)["observations"]["runtime_observation"]["available"])

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
