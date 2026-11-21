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


def calibration_record(pair="cpu:candidate/sparse3/8/system", repeats=3):
    return {"schema_version": 1, "event": "pair_calibration", "monotonic_ns": 0,
            "data": {"pair": pair, "protocol_version": 3, "fixed_repeats": repeats, "requested_repeats": repeats,
                     "max_repeats": 11, "clamp_rewarmup": False, "target_native_batch_ns": 5_000_000,
                     "pilot_baseline_elapsed_ns": 100., "pilot_candidate_elapsed_ns": 150.,
                     "baseline_native_scope": "median_per_repeat", "candidate_native_scope": "median_per_repeat"}}


def analyse(records, protocol=1, calibration=True, manifest_fields=None):
    # Valid v3 fixtures include the calibration that the live runner emits before
    # measurement. Tests of missing or malformed records opt out explicitly.
    if protocol == 3 and calibration and not any(r.get("event") == "pair_calibration" for r in records):
        records = [calibration_record()] + records
    manifest = {"schema_version": 1, "protocol_version": protocol, **(manifest_fields or {})}
    return report.analyse({"manifest.json": json.dumps(manifest).encode(),
                           "events.ndjson": b''.join((json.dumps(r) + '\n').encode() for r in records)})


def counterbalanced_journal(units=5, scale=.75, first_penalty=4.):
    rows = []
    for round_id in range(2 * units):
        order = report.ABBA if round_id % 2 == 0 else report.BAAB
        for position, (label, drift) in enumerate(zip(order, [100., 110., 120., 132.])):
            row = journal(1)[0]
            data = row["data"]
            time = drift * (scale if label[0] == "B" else 1.) * (first_penalty if position == 0 else 1.)
            data.update(protocol_version=3, counterbalanced_unit=round_id // 2,
                        block_order="ABBA" if round_id % 2 == 0 else "BAAB", position_in_block=position,
                        sample_id=f"sample/{round_id}/{label}", thermal_epoch=0, phase="measurement", warmup=False,
                        round=round_id, block=0, order=label, role=label[0], elapsed_ns=time,
                        case_id="control" if label[0] == "A" else "candidate", repeats=3, effective_repeats=3,
                        elapsed_scope="median_per_repeat", jni_wrapper_elapsed_ns=time * 2,
                        thread_cpu_elapsed_ns=time * 1.5)
            data["native"].update(repeats=3, elapsed_scope="median_per_repeat", timing_scope="integration_and_scratch_only",
                                  accuracy_pass=True, reference_accuracy_pass=True, repeat_consistent=True,
                                  correctness_checked=True, gpu_timestamp_valid=True,
                                  gpu_elapsed_ns=1000. * (.8 if label[0] == "B" else 1.))
            for key in ("environment_before", "environment_after"):
                data[key].update(monotonic_ns=len(rows), display_refresh_hz=120.)
            row["monotonic_ns"] = len(rows)
            rows.append(row)
    return rows


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
        self.assertEqual(comparison["decision"], "legacy_fixed_order_descriptive_only")

    def test_complementary_orders_cancel_first_position_cost_in_log_space(self):
        result = analyse(counterbalanced_journal(), protocol=3)
        comparison = result["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 10)
        self.assertEqual(comparison["clean_counterbalanced_units"], 5)
        self.assertEqual(comparison["resampling_units"], 5)
        self.assertEqual(comparison["statistical_unit"], "complementary_eight_sample_unit")
        self.assertAlmostEqual(comparison["median_change_percent"], -25.)
        # Individual orders produce -62.5% and +50% under this first-slot
        # penalty. Averaging those percentages would incorrectly give -6.25%.
        self.assertAlmostEqual(comparison["block_change_percent"][0], -62.5)
        self.assertAlmostEqual(comparison["block_change_percent"][1], 50.)
        for endpoint in comparison["bootstrap_95_percent_interval"]:
            self.assertAlmostEqual(endpoint, -25.)
        self.assertEqual(comparison["decision"], "promising_candidate_repeat_on_device")
        gpu = comparison["separate_timing_metrics"]["gpu_timestamp"]
        self.assertAlmostEqual(gpu["median_change_percent"], -20.)
        self.assertEqual(gpu["resampling_units"], 5)

    def test_identical_case_first_slot_penalty_is_diagnostic_not_a_speedup(self):
        rows = journal(scale=1.)
        for row in rows:
            data = row["data"]
            data["case_id"] = "same"
            if data["order"] == "A1":
                data["elapsed_ns"] *= 4
            data["native"].update(gpu_timestamp_valid=True, gpu_elapsed_ns=1000.)
        result = analyse(rows, protocol=2)
        comparison = result["comparisons"][0]
        self.assertAlmostEqual(comparison["median_change_percent"], -50.)
        first = comparison["first_position_diagnostic"]["first_vs_last_same_role"]
        self.assertGreater(first["elapsed_ns"]["median_change_percent"], 200.)
        self.assertEqual(first["gpu_timestamp"]["median_change_percent"], 0.)
        self.assertEqual(result["self_control_diagnostics"][0]["systematic_difference_metrics"], ["elapsed_ns"])
        self.assertTrue(any("Self-control" in warning for warning in result["warnings"]))
        self.assertIn("first-position diagnostics", report.markdown(result))
        balanced = counterbalanced_journal(scale=1.)
        for row in balanced:
            row["data"]["case_id"] = "same"
        corrected = analyse(balanced, protocol=3)["comparisons"][0]
        self.assertAlmostEqual(corrected["median_change_percent"], 0.)
        self.assertEqual(corrected["decision"], "repeatability_control")

    def test_unmatched_complementary_block_remains_descriptive(self):
        rows = counterbalanced_journal(units=1)[:4]
        # An optimistic summary cannot manufacture the missing four samples.
        rows.append({"schema_version": 1, "event": "counterbalanced_unit", "monotonic_ns": len(rows),
                     "data": {"pair": rows[0]["data"]["pair"], "unit": 0, "complete_counterbalanced_unit": True}})
        result = analyse(rows, protocol=3)
        comparison = result["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 1)
        self.assertEqual(comparison["resampling_units"], 0)
        self.assertIsNone(comparison["median_change_percent"])
        self.assertEqual(comparison["excluded_unit_reasons"], {"missing_or_duplicate_complementary_block": 1})
        self.assertEqual(comparison["decision"], "insufficient_clean_counterbalanced_units")
        self.assertIsNotNone(comparison["raw_block_diagnostics"][0]["change_percent"])
        self.assertIn("unavailable", report.markdown(result))

    def test_bad_complement_cannot_hide_behind_fast_valid_order(self):
        rows = counterbalanced_journal(scale=.1)
        rows[4]["data"]["native"]["status"] = "FAIL"
        comparison = analyse(rows, protocol=3)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 9)
        self.assertEqual(comparison["clean_counterbalanced_units"], 4)
        self.assertEqual(comparison["decision"], "correctness_failure_requires_investigation")

    def test_unit_rejects_changed_case_batch_epoch_and_timing_scope(self):
        for change, expected in [("case", "case_changed_between_blocks"),
                                 ("batch", "workload_batch_or_timing_scope_changed_between_blocks"),
                                 ("scope", "workload_batch_or_timing_scope_changed_between_blocks"),
                                 ("epoch", "thermal_epoch_changed_between_blocks")]:
            with self.subTest(change=change):
                rows = counterbalanced_journal()
                for row in rows[4:8]:
                    data = row["data"]
                    if change == "case" and data["role"] == "B":
                        data["case_id"] = "different-candidate"
                    elif change == "batch":
                        data.update(repeats=4, effective_repeats=4)
                        data["native"]["repeats"] = 4
                    elif change == "scope":
                        data["elapsed_scope"] = "total_of_iterations"
                        data["native"]["elapsed_scope"] = "total_of_iterations"
                    elif change == "epoch":
                        data["thermal_epoch"] = 1
                comparison = analyse(rows, protocol=3)["comparisons"][0]
                self.assertEqual(comparison["clean_blocks"], 10 if change == "epoch" else 9)
                self.assertEqual(comparison["clean_counterbalanced_units"], 4)
                self.assertEqual(comparison["excluded_unit_reasons"][expected if change == "epoch" else "invalid_or_confounded_constituent_block"], 1)

    def test_counterbalanced_schedule_metadata_and_sample_ids_are_checked(self):
        for change, expected in [("order", "counterbalanced_schedule_metadata_mismatch"),
                                 ("unit", "counterbalanced_schedule_metadata_mismatch"),
                                 ("position", "counterbalanced_schedule_metadata_mismatch"),
                                 ("id", "missing_or_duplicate_sample_id")]:
            with self.subTest(change=change):
                rows = counterbalanced_journal()
                data = rows[4]["data"]
                if change == "order": data["block_order"] = "ABBA"
                elif change == "unit": data["counterbalanced_unit"] = 9
                elif change == "position": data["position_in_block"] = 3
                else: data["sample_id"] = rows[0]["data"]["sample_id"]
                comparison = analyse(rows, protocol=3)["comparisons"][0]
                self.assertEqual(comparison["clean_counterbalanced_units"], 4)
                self.assertIn(expected, comparison["excluded_reasons"])

    def test_refresh_change_between_complements_excludes_the_unit(self):
        rows = counterbalanced_journal()
        for row in rows[4:8]:
            for key in ("environment_before", "environment_after"):
                row["data"][key]["display_refresh_hz"] = 60.
        result = analyse(rows, protocol=3)
        comparison = result["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 10)
        self.assertEqual(comparison["clean_counterbalanced_units"], 4)
        self.assertEqual(comparison["excluded_unit_reasons"]["display_refresh_changed"], 1)
        self.assertEqual(len(result["observations"]["display_refresh"]["observed_transitions"]), 2)
        self.assertTrue(any("refresh transitions" in warning for warning in result["warnings"]))

    def test_refresh_change_inside_block_is_excluded_but_jitter_is_not(self):
        rows = counterbalanced_journal()
        rows[1]["data"]["environment_after"]["display_refresh_hz"] = 60.
        rows[9]["data"]["environment_after"]["display_refresh_hz"] = 120.00001
        comparison = analyse(rows, protocol=3)["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 9)
        self.assertEqual(comparison["clean_counterbalanced_units"], 4)
        self.assertEqual(comparison["excluded_reasons"]["display_refresh_changed"], 1)

    def test_invalid_gpu_query_omits_its_entire_unit_only_from_gpu_population(self):
        rows = counterbalanced_journal()
        rows[4]["data"]["native"]["gpu_timestamp_valid"] = False
        comparison = analyse(rows, protocol=3)["comparisons"][0]
        gpu = comparison["separate_timing_metrics"]["gpu_timestamp"]
        self.assertEqual(comparison["resampling_units"], 5)
        self.assertEqual(gpu["resampling_units"], 4)
        self.assertEqual(gpu["units_without_valid_metric"], 1)
        self.assertIsNone(gpu["bootstrap_95_percent_interval"])

    def test_native_result_and_frozen_calibration_must_match_requested_work(self):
        for change, expected in [("native_count", "native_repeat_count_mismatch"),
                                 ("native_case", "native_workload_identity_mismatch"),
                                 ("missing_case", "incomplete_counterbalanced_workload_identity"),
                                 ("calibration", "calibrated_repeat_count_mismatch")]:
            with self.subTest(change=change):
                rows = counterbalanced_journal()
                if change == "native_count": rows[4]["data"]["native"]["repeats"] = 2
                elif change == "native_case": rows[4]["data"]["native"]["case_id"] = "wrong-implementation"
                elif change == "missing_case":
                    for row in rows[4:8]: row["data"].pop("case_id")
                else:
                    rows.insert(0, calibration_record(repeats=4))
                    for i, row in enumerate(rows): row["monotonic_ns"] = i
                comparison = analyse(rows, protocol=3)["comparisons"][0]
                self.assertIn(expected, comparison["excluded_reasons"])
                self.assertLess(comparison["clean_counterbalanced_units"], 5)
                self.assertNotEqual(comparison["decision"], "promising_candidate_repeat_on_device")

    def test_v3_requires_exactly_one_preceding_valid_calibration(self):
        missing = analyse(counterbalanced_journal(), protocol=3, calibration=False)["comparisons"][0]
        self.assertEqual(missing["measurement_contract_violations"], {"missing_pair_calibration": 1})
        self.assertEqual(missing["resampling_units"], 0)
        self.assertEqual(missing["decision"], "measurement_contract_requires_investigation")
        for first_count in (3, 4):
            rows = [calibration_record(repeats=first_count), calibration_record()] + counterbalanced_journal()
            duplicate = analyse(rows, protocol=3)["comparisons"][0]
            self.assertEqual(duplicate["measurement_contract_violations"], {"duplicate_pair_calibration": 1})
            self.assertEqual(duplicate["pair_calibration_records"], 2)
            self.assertEqual(duplicate["resampling_units"], 0)
        for key, value in [("fixed_repeats", True), ("pilot_candidate_elapsed_ns", 0),
                           ("baseline_native_scope", None), ("clamp_rewarmup", True)]:
            calibration = calibration_record()
            calibration["data"][key] = value
            invalid = analyse([calibration] + counterbalanced_journal(), protocol=3)["comparisons"][0]
            self.assertIn("invalid_pair_calibration", invalid["measurement_contract_violations"])
            self.assertEqual(invalid["resampling_units"], 0)
        rows = counterbalanced_journal()
        late = calibration_record()
        late["monotonic_ns"] = len(rows)
        late_result = analyse(rows + [late], protocol=3)["comparisons"][0]
        self.assertIn("pair_calibration_not_before_measurement", late_result["measurement_contract_violations"])

    def test_declared_manifest_and_calibration_identity_must_match_samples(self):
        pair = "cpu:candidate/sparse3/8/system"
        schedule = [{"pair": pair, "baseline_case": "control", "candidate_case": "wrong-case",
                     "fixture_id": "sparse3", "steps": 8, "placement": "system"}]
        mismatch = analyse(counterbalanced_journal(), protocol=3, manifest_fields={"schedule": schedule})["comparisons"][0]
        self.assertIn("recorded_pair_case_identity_mismatch", mismatch["measurement_contract_violations"])
        self.assertEqual(mismatch["resampling_units"], 0)
        for declarations in ([], schedule + schedule):
            result = analyse(counterbalanced_journal(), protocol=3,
                             manifest_fields={"schedule": declarations})["comparisons"][0]
            self.assertIn("missing_or_duplicate_manifest_pair", result["measurement_contract_violations"])
        calibration = calibration_record()
        calibration["data"]["fixture_id"] = "different-fixture"
        mismatch = analyse([calibration] + counterbalanced_journal(), protocol=3)["comparisons"][0]
        self.assertIn("recorded_pair_workload_identity_mismatch", mismatch["measurement_contract_violations"])

    def test_pass_status_cannot_override_false_native_correctness_flags(self):
        for flag in ("correctness_checked", "accuracy_pass", "reference_accuracy_pass", "repeat_consistent", "forecast_acceptance"):
            with self.subTest(flag=flag):
                rows = counterbalanced_journal()
                for row in rows:
                    row["data"]["native"][flag] = {"accepted": False} if flag == "forecast_acceptance" else False
                comparison = analyse(rows, protocol=3)["comparisons"][0]
                self.assertEqual(comparison["status_counts"], {"PASS": 40})
                self.assertEqual(comparison["resampling_units"], 0)
                self.assertEqual(comparison["failed_samples_including_calibration_and_warmup"], 40)
                self.assertEqual(comparison["decision"], "correctness_failure_requires_investigation")

    def test_native_timing_scope_must_be_coherent_despite_matching_outer_labels(self):
        rows = counterbalanced_journal()
        for row in rows:
            data = row["data"]
            data["native"]["timing_scope"] = "kernel-only" if data["role"] == "A" else "half-of-kernel"
        comparison = analyse(rows, protocol=3)["comparisons"][0]
        self.assertEqual(comparison["resampling_units"], 0)
        self.assertEqual(comparison["excluded_reasons"]["native_timing_scope_changed_within_block"], 10)
        self.assertNotEqual(comparison["decision"], "promising_candidate_repeat_on_device")

    def test_pair_identity_cannot_change_between_otherwise_valid_units(self):
        for change in ("case", "fixture", "steps", "backend", "batch", "native_scope"):
            with self.subTest(change=change):
                rows = counterbalanced_journal(units=6)
                # Five later units individually agree internally. They must not
                # become a new experiment under the first unit's pair key.
                for row in rows[8:]:
                    data = row["data"]
                    if change == "case" and data["role"] == "B": data["case_id"] = "different-candidate"
                    elif change == "fixture": data["fixture_id"] = "different-fixture"
                    elif change == "steps": data["steps"] = 64
                    elif change == "backend": data["backend"] = "hardware"
                    elif change == "batch":
                        data.update(repeats=4, effective_repeats=4)
                        data["native"]["repeats"] = 4
                    elif change == "native_scope": data["native"]["timing_scope"] = "different-kernel-boundary"
                comparison = analyse(rows, protocol=3)["comparisons"][0]
                self.assertEqual(comparison["clean_counterbalanced_units"], 1)
                self.assertIn("pair_identity_changed_across_comparison", comparison["measurement_contract_violations"])
                self.assertEqual(comparison["decision"], "measurement_contract_requires_investigation")
        rows = counterbalanced_journal(units=6)
        for row in rows[40:]:
            if row["data"]["role"] == "B": row["data"]["case_id"] = "late-different-candidate"
        comparison = analyse(rows, protocol=3)["comparisons"][0]
        self.assertEqual(comparison["clean_counterbalanced_units"], 5)
        self.assertEqual(comparison["decision"], "measurement_contract_requires_investigation")

    def test_standalone_environment_transitions_between_complements_are_not_missed(self):
        expanded = []
        for index, row in enumerate(counterbalanced_journal()):
            timestamp = len(expanded) * 10
            row["monotonic_ns"] = timestamp
            for key in ("environment_before", "environment_after"):
                row["data"][key]["monotonic_ns"] = timestamp
            expanded.append(row)
            if index == 3:
                for hz in (60., 120.):
                    timestamp = len(expanded) * 10
                    expanded.append({"schema_version": 1, "event": "environment", "monotonic_ns": timestamp,
                                     "data": {"monotonic_ns": timestamp, "thermal_status": 0,
                                              "battery_plugged": 0, "display_refresh_hz": hz}})
        result = analyse(expanded, protocol=3)
        comparison = result["comparisons"][0]
        self.assertEqual(comparison["clean_blocks"], 10)
        self.assertEqual(comparison["clean_counterbalanced_units"], 4)
        self.assertEqual(comparison["excluded_unit_reasons"]["display_refresh_changed"], 1)
        self.assertEqual(len(result["observations"]["display_refresh"]["observed_transitions"]), 2)

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
