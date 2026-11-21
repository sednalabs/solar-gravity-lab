#!/usr/bin/env python3
"""Verify a phone export and reconstruct paired measurements without dependencies.

ZIP members are read without extraction. The raw journal is authoritative;
precomputed summaries never substitute for correctness or measurement evidence.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import random
import statistics
import sys
from typing import Any
import zipfile

MAX_MEMBER_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_BYTES = 768 * 1024 * 1024
ALLOWED_MEMBERS = {
    "archive-index.json", "manifest.json", "device.json", "catalogue.json",
    "research-catalogue.json", "events.ndjson", "summary.json",
}
ABBA = ["A1", "B1", "B2", "A2"]
BAAB = ["B1", "A1", "A2", "B2"]
TIMING_METRICS = ("gpu_timestamp", "jni_wrapper_elapsed_ns", "thread_cpu_elapsed_ns", "native_setup")


def load_export(path: Path) -> tuple[dict[str, bytes], list[str]]:
    warnings: list[str] = []
    if path.is_dir():
        # The CLI caller deliberately chooses the input directory. Member names
        # come from this constant allowlist, never from the report's contents.
        # Do not follow a report member symlink outside that chosen directory.
        files = {}
        total_bytes = 0
        for name in sorted(ALLOWED_MEMBERS):
            member = path / name
            if member.is_symlink():
                raise ValueError(f"Symlinked report member is not accepted: {name}")
            if not member.is_file():
                continue
            size = member.stat().st_size
            total_bytes += size
            if size > MAX_MEMBER_BYTES or total_bytes > MAX_ARCHIVE_BYTES:
                raise ValueError("Directory export exceeds the reader limit")
            files[name] = member.read_bytes()
        warnings.append("Directory input: archive integrity is unverified.")
    else:
        with zipfile.ZipFile(path) as archive:
            names = [entry.filename for entry in archive.infolist()]
            if len(names) != len(set(names)):
                raise ValueError("Duplicate ZIP member names")
            if set(names) - ALLOWED_MEMBERS:
                raise ValueError("Unexpected ZIP members; this is not a flat research export")
            if any(entry.file_size > MAX_MEMBER_BYTES for entry in archive.infolist()):
                raise ValueError("An export member exceeds the reader limit")
            if sum(entry.file_size for entry in archive.infolist()) > MAX_ARCHIVE_BYTES:
                raise ValueError("Export exceeds the reader limit")
            files = {entry.filename: archive.read(entry) for entry in archive.infolist()}
    if "events.ndjson" not in files or "manifest.json" not in files:
        raise ValueError("Export must contain manifest.json and events.ndjson")
    if "archive-index.json" in files:
        index = json.loads(files["archive-index.json"])
        if index.get("schema_version") != 1 or index.get("algorithm") != "SHA-256":
            raise ValueError("Unsupported archive index")
        entries = index.get("files", [])
        indexed = [entry["path"] for entry in entries]
        if len(indexed) != len(set(indexed)) or set(indexed) != set(files) - {"archive-index.json"}:
            raise ValueError("Archive index does not cover the exact exported file set")
        for entry in entries:
            content = files[entry["path"]]
            if len(content) != entry["bytes"] or hashlib.sha256(content).hexdigest() != entry["sha256"]:
                raise ValueError(f"Integrity failure for {entry['path']}")
    elif not path.is_dir():
        # Merely checks the user-selected input type. ZIP names are never
        # extracted or joined to a filesystem path anywhere in this reader.
        raise ValueError("ZIP export is missing its integrity index")
    return files, warnings


def read_journal(content: bytes) -> tuple[list[dict[str, Any]], list[str]]:
    warnings: list[str] = []
    lines = content.splitlines(keepends=True)
    if lines and not lines[-1].endswith(b"\n"):
        lines.pop()
        warnings.append("A partial final record was ignored; earlier records are retained.")
    records = []
    last_monotonic = -1
    for number, line in enumerate(lines, 1):
        try:
            record = json.loads(line)
        except (ValueError, UnicodeError) as error:
            raise ValueError(f"Corrupt completed journal record {number}") from error
        if not isinstance(record, dict) or record.get("schema_version") != 1:
            raise ValueError(f"Unsupported journal schema at record {number}")
        monotonic = record.get("monotonic_ns")
        if not isinstance(monotonic, int) or monotonic < last_monotonic:
            raise ValueError(f"Non-monotonic journal clock at record {number}")
        last_monotonic = monotonic
        records.append(record)
    return records, warnings


def finite_positive(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def quantile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))]


def timing_summary(values: list[float]) -> dict[str, Any]:
    return {"count": len(values), "median_ns": statistics.median(values) if values else None,
            "p90_ns": quantile(values, .90), "p95_ns": quantile(values, .95), "p99_ns": quantile(values, .99)}


def bootstrap_interval(values: list[float]) -> list[float] | None:
    # Values are log-ratios of complete resampling units. In protocol v3 a
    # unit contains both complementary blocks; never resample its eight samples
    # or its two blocks independently. Legacy fixed-order blocks stay descriptive.
    if len(values) < 5:
        return None
    # Statistical resampling deliberately uses a reproducible PRNG. It creates
    # no credentials/nonces and does not implement any integrity/security check.
    random_source = random.Random(242)
    medians = [statistics.median(random_source.choices(values, k=len(values))) for _ in range(4000)]
    return [quantile(medians, .025), quantile(medians, .975)]


def percent_from_log(value: float) -> float:
    return math.expm1(value) * 100.0


def log_effect_summary(values: list[float]) -> dict[str, Any]:
    interval = bootstrap_interval(values)
    return {
        "median_change_percent": percent_from_log(statistics.median(values)) if values else None,
        "bootstrap_95_percent_interval": [percent_from_log(value) for value in interval] if interval else None,
    }


def metric_value(sample: dict[str, Any], metric: str) -> Any:
    native = sample.get("native", {})
    if metric == "gpu_timestamp":
        return native.get("gpu_elapsed_ns") if native.get("gpu_timestamp_valid") is True else None
    if metric == "native_setup":
        return native.get("setup_ns")
    return sample.get(metric)


def metric_log_ratio(samples: list[dict[str, Any]], metric: str) -> float | None:
    values = [metric_value(sample, metric) for sample in samples]
    if not all(finite_positive(value) for value in values):
        return None
    by_role = {role: [math.log(value) for sample, value in zip(samples, values)
                      if sample.get("role") == role] for role in ("A", "B")}
    if not by_role["A"] or len(by_role["A"]) != len(by_role["B"]):
        return None
    # Roles remain semantic A/B even when B executes first. Averaging logs
    # cancels multiplicative first-position effects across complementary orders.
    return statistics.mean(by_role["B"]) - statistics.mean(by_role["A"])


def paired_metric_summary(blocks: list[list[dict[str, Any]]], metric: str,
                          units: list[list[dict[str, Any]]] | None = None) -> dict[str, Any]:
    """Retain raw block evidence; estimate v3 effects from balanced units only."""
    block_logs = [value for block in blocks if (value := metric_log_ratio(block, metric)) is not None]
    selected_units = blocks if units is None else units
    valid_units = [(unit, value) for unit in selected_units
                   if (value := metric_log_ratio(unit, metric)) is not None]
    logs = [value for _, value in valid_units]
    return {
        "clean_blocks": len(block_logs), "blocks_without_valid_metric": len(blocks) - len(block_logs),
        "statistical_unit": "fixed_order_block" if units is None else "complementary_eight_sample_unit",
        "resampling_units": len(logs), "units_without_valid_metric": len(selected_units) - len(logs),
        "baseline": timing_summary([metric_value(sample, metric) for unit, _ in valid_units
                                    for sample in unit if sample.get("role") == "A"]),
        "candidate": timing_summary([metric_value(sample, metric) for unit, _ in valid_units
                                     for sample in unit if sample.get("role") == "B"]),
        "block_change_percent": [percent_from_log(value) for value in block_logs],
        "unit_log_ratios": logs, "unit_change_percent": [percent_from_log(value) for value in logs],
        **log_effect_summary(logs),
        "interpretation": ("legacy_fixed_order_descriptive_only; systematic_position_bias_is_not_removed"
                           if units is None else
                           "complete_complementary_units; separate_clock_population; repeated_sessions_still_required"),
    }


def first_position_summary(blocks: list[list[dict[str, Any]]]) -> dict[str, Any]:
    metrics = {}
    for metric in ("elapsed_ns",) + TIMING_METRICS:
        logs = []
        for block in blocks:
            first, last = metric_value(block[0], metric), metric_value(block[-1], metric)
            if finite_positive(first) and finite_positive(last):
                logs.append(math.log(first) - math.log(last))
        metrics[metric] = {"blocks": len(logs), **log_effect_summary(logs)}
    return {
        "first_roles_observed": sorted({block[0]["role"] for block in blocks}),
        "first_vs_last_same_role": metrics,
        "interpretation": "Positive means the first invocation was slower than the same-role last invocation. "
                          "This diagnoses position-associated drift; it does not identify its cause or correct timing by subtraction.",
    }


def refresh_changed(values: list[float]) -> bool:
    # Small reporting jitter is not a mode change; 60/120 Hz transitions are.
    return bool(values) and not math.isclose(min(values), max(values), rel_tol=.005, abs_tol=.25)


def environmental_reasons(samples: list[dict[str, Any]],
                          extra_environments: list[dict[str, Any]] | None = None) -> list[str]:
    reasons = []
    environments = [sample.get(key, {}) for sample in samples for key in ("environment_before", "environment_after")]
    environments.extend(extra_environments or [])
    statuses = [env.get("thermal_status") for env in environments]
    if any(isinstance(status, int) and status >= 3 for status in statuses):
        reasons.append("severe_or_higher_thermal_status")
    if any(status is None or status == -1 for status in statuses):
        reasons.append("thermal_status_unavailable")
    plugged = {env.get("battery_plugged") for env in environments if env.get("battery_plugged") is not None}
    if len(plugged) > 1:
        reasons.append("charging_state_changed")
    refresh = [env["display_refresh_hz"] for env in environments if finite_positive(env.get("display_refresh_hz"))]
    if refresh_changed(refresh):
        reasons.append("display_refresh_changed")
    if len({sample.get("placement", "system") for sample in samples}) != 1:
        reasons.append("placement_changed")
    return reasons


def effective_repeats(sample: dict[str, Any]) -> Any:
    return sample.get("effective_repeats", sample.get("native", {}).get(
        "repeats", sample.get("native", {}).get("iterations", sample.get("repeats", 1))))


def correctness_reasons(sample: dict[str, Any]) -> list[str]:
    native = sample.get("native", {})
    reasons = []
    if sample.get("status") != "PASS" or native.get("status") != "PASS":
        reasons.append("failed_or_skipped_correctness_check")
    flags = ("correctness_checked", "accuracy_pass", "reference_accuracy_pass", "repeat_consistent")
    if any(key in native and native[key] is not True for key in flags):
        reasons.append("native_correctness_flag_not_true")
    acceptance = native.get("forecast_acceptance")
    if isinstance(acceptance, dict) and "accepted" in acceptance and acceptance["accepted"] is not True:
        reasons.append("native_forecast_acceptance_not_true")
    return reasons


def calibration_validation_reasons(entries: list[tuple[int, dict[str, Any]]],
                                   first_measurement_index: int) -> list[str]:
    if len(entries) != 1:
        return ["missing_pair_calibration" if not entries else "duplicate_pair_calibration"]
    index, calibration = entries[0]
    counts = [calibration.get(key) for key in ("fixed_repeats", "requested_repeats", "max_repeats")]
    if (calibration.get("protocol_version") != 3
            or any(type(value) is not int or value <= 0 for value in counts)
            or not counts[0] <= counts[1] <= counts[2]
            or type(calibration.get("clamp_rewarmup")) is not bool
            or calibration["clamp_rewarmup"] != (counts[0] != counts[1])
            or any(not finite_positive(calibration.get(key)) for key in (
                "target_native_batch_ns", "pilot_baseline_elapsed_ns", "pilot_candidate_elapsed_ns"))
            or any(not isinstance(calibration.get(key), str) or not calibration[key].strip()
                   for key in ("baseline_native_scope", "candidate_native_scope"))):
        return ["invalid_pair_calibration"]
    if index >= first_measurement_index:
        return ["pair_calibration_not_before_measurement"]
    return []


def sample_identity(sample: dict[str, Any]) -> dict[str, Any]:
    identity = {key: sample.get(key) for key in (
        "case_id", "fixture_id", "steps", "backend", "placement", "repeats", "elapsed_scope")}
    identity["effective_repeats"] = effective_repeats(sample)
    identity["native"] = {key: sample.get("native", {}).get(key) for key in (
        "elapsed_scope", "timing_scope", "actual_kernel", "physics_model", "comparison_kind", "numerical_policy")}
    return identity


def declared_identity_reasons(samples: list[dict[str, Any]], declarations: list[dict[str, Any]]) -> list[str]:
    for declaration in declarations:
        for sample in samples:
            role_case = "baseline_case" if sample["role"] == "A" else "candidate_case"
            if role_case in declaration and declaration[role_case] != sample.get("case_id"):
                return ["recorded_pair_case_identity_mismatch"]
            if any(key in declaration and declaration[key] != sample.get(key)
                   for key in ("fixture_id", "steps", "backend", "placement")):
                return ["recorded_pair_workload_identity_mismatch"]
            scope_key = "baseline_native_scope" if sample["role"] == "A" else "candidate_native_scope"
            if scope_key in declaration and declaration[scope_key] != sample.get("elapsed_scope"):
                return ["recorded_pair_timing_scope_mismatch"]
    return []


def block_validation_reasons(samples: list[dict[str, Any]], round_id: Any, protocol: int,
                             sample_id_counts: Counter[str], calibrated_repeats: Any) -> list[str]:
    expected = BAAB if protocol == 3 and type(round_id) is int and round_id % 2 else ABBA
    if [sample.get("order") for sample in samples] != expected:
        return ["incomplete_or_duplicate_balanced_block" if protocol == 3 else "incomplete_or_duplicate_abba"]
    correctness = sorted({reason for sample in samples for reason in correctness_reasons(sample)})
    if correctness:
        return correctness
    if not all(finite_positive(sample.get("elapsed_ns")) for sample in samples):
        return ["missing_zero_or_nonfinite_kernel_time"]
    if [sample.get("role") for sample in samples] != [order[0] for order in expected]:
        return ["inconsistent_abba_roles"]
    if any(len({sample.get("case_id") for sample in samples if sample["role"] == role}) != 1 for role in ("A", "B")):
        return ["case_changed_within_block"]
    if len({(sample.get("fixture_id"), sample.get("steps"), sample.get("backend")) for sample in samples}) != 1:
        return ["workload_changed_within_block"]
    repeats = [sample.get("repeats", 1) for sample in samples]
    effective = [effective_repeats(sample) for sample in samples]
    if (any(type(value) is not int or value <= 0 for value in repeats + effective)
            or len(set(repeats)) != 1 or len(set(effective)) != 1):
        return ["repeat_count_changed_or_invalid_within_block"]
    if repeats != effective:
        return ["requested_repeat_count_not_executed"]
    for sample in samples:
        native = sample.get("native", {})
        if any(key in native and native[key] != sample.get(key)
               for key in ("case_id", "fixture_id", "steps")):
            return ["native_workload_identity_mismatch"]
        native_repeats = native.get("repeats", native.get("iterations"))
        if native_repeats is not None and native_repeats != effective_repeats(sample):
            return ["native_repeat_count_mismatch"]
    native_scopes = [sample.get("native", {}).get("timing_scope") for sample in samples]
    if len({scope for scope in native_scopes if isinstance(scope, str)}) > 1:
        return ["native_timing_scope_changed_within_block"]
    if protocol == 3:
        if type(round_id) is not int or round_id < 0:
            return ["invalid_counterbalanced_round"]
        if any(any(not isinstance(sample.get(key), str) or not sample[key]
                   for key in ("case_id", "fixture_id", "backend"))
               or type(sample.get("steps")) is not int or sample["steps"] <= 0 for sample in samples):
            return ["incomplete_counterbalanced_workload_identity"]
        if any(not isinstance(scope, str) or not scope.strip() for scope in native_scopes):
            return ["native_timing_scope_unavailable"]
        if any(sample.get("elapsed_scope") != sample["native"].get("elapsed_scope", sample["native"]["timing_scope"])
               for sample in samples):
            return ["native_elapsed_scope_mismatch"]
        if (any(sample.get("protocol_version") != 3 or type(sample.get("counterbalanced_unit")) is not int
                or sample["counterbalanced_unit"] != round_id // 2
                or sample.get("block_order") != "".join(order[0] for order in expected)
                or sample.get("phase") != "measurement" or type(sample.get("position_in_block")) is not int
                for sample in samples)
                or [sample.get("position_in_block") for sample in samples] != list(range(4))):
            return ["counterbalanced_schedule_metadata_mismatch"]
        if any(not isinstance(sample.get("sample_id"), str) or not sample["sample_id"]
               or sample_id_counts[sample["sample_id"]] != 1 for sample in samples):
            return ["missing_or_duplicate_sample_id"]
        epochs = [sample.get("thermal_epoch") for sample in samples]
        if any(type(epoch) is not int or epoch < 0 for epoch in epochs) or len(set(epochs)) != 1:
            return ["missing_or_changed_thermal_epoch"]
        if calibrated_repeats is not None and effective[0] != calibrated_repeats:
            return ["calibrated_repeat_count_mismatch"]
    return []


def balanced_unit_reasons(blocks: list[dict[str, Any]], unit: int,
                          extra_environments: list[dict[str, Any]] | None = None) -> list[str]:
    if len(blocks) != 2 or [block["round"] for block in blocks] != [2 * unit, 2 * unit + 1]:
        return ["missing_or_duplicate_complementary_block"]
    if any(block["reasons"] for block in blocks):
        return ["invalid_or_confounded_constituent_block"]
    samples = [sample for block in blocks for sample in block["samples"]]
    if len({sample["thermal_epoch"] for sample in samples}) != 1:
        return ["thermal_epoch_changed_between_blocks"]
    if any(len({sample.get("case_id") for sample in samples if sample["role"] == role}) != 1 for role in ("A", "B")):
        return ["case_changed_between_blocks"]
    if len({(sample.get("fixture_id"), sample.get("steps"), sample.get("backend"),
             sample.get("repeats", 1), effective_repeats(sample), sample.get("elapsed_scope"),
             sample.get("native", {}).get("elapsed_scope")) for sample in samples}) != 1:
        return ["workload_batch_or_timing_scope_changed_between_blocks"]
    return environmental_reasons(samples, extra_environments)


def error_metrics(value: Any, path: str = "") -> dict[str, float]:
    result = {}
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else key
            if any(word in key.lower() for word in ("error", "drift")) and isinstance(child, (int, float)) and not isinstance(child, bool):
                if math.isfinite(child):
                    result[child_path] = abs(float(child))
            elif isinstance(child, dict):
                result.update(error_metrics(child, child_path))
    return result


def runtime_observation_summary(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Count observed work between coherent snapshots of the same bridge.

    The first lifetime counter includes work before recording began. Never count
    it as session work, bridge a missing observation, or subtract across a new
    bridge instance. These are work counts, not a CPU-time or energy estimate.
    """
    fields = ("periodic_summary_polls", "paused_duplicate_suppressions",
              "packet_export_attempts", "packet_export_failures")
    totals = Counter({field: 0 for field in fields})
    exclusions: Counter[str] = Counter()
    scopes: set[str] = set()
    observations = intervals = observed_ns = 0
    previous: tuple[str, int, dict[str, int]] | None = None
    for record in records:
        if record.get("event") != "observatory_snapshot":
            continue
        data = record.get("data")
        counters = data.get("runtime_observation") if isinstance(data, dict) else None
        if not isinstance(counters, dict):
            exclusions["counter_unavailable"] += 1
            previous = None
            continue
        scope = counters.get("scope_id")
        clock = record.get("source_monotonic_ns")
        if (type(counters.get("schema_version")) is not int or counters["schema_version"] != 1
                or not isinstance(scope, str) or not scope.strip()
                or type(clock) is not int or clock < 0
                or any(type(counters.get(field)) is not int or counters[field] < 0 for field in fields)):
            exclusions["malformed_counter_observation"] += 1
            previous = None
            continue
        values = {field: counters[field] for field in fields}
        if (values["paused_duplicate_suppressions"] > values["periodic_summary_polls"]
                or values["packet_export_failures"] > values["packet_export_attempts"]):
            exclusions["inconsistent_counter_observation"] += 1
            previous = None
            continue
        observations += 1
        scopes.add(scope)
        if previous is not None:
            old_scope, old_clock, old_values = previous
            delta = {field: values[field] - old_values[field] for field in fields}
            if scope != old_scope:
                exclusions["bridge_scope_changed"] += 1
            elif clock <= old_clock:
                exclusions["nonincreasing_observation_clock"] += 1
                # An out-of-order sample must not become the next baseline.
                previous = None
                continue
            elif any(value < 0 for value in delta.values()):
                exclusions["counter_reset_or_regression"] += 1
            elif (delta["paused_duplicate_suppressions"] > delta["periodic_summary_polls"]
                    or delta["packet_export_failures"] > delta["packet_export_attempts"]):
                exclusions["inconsistent_counter_delta"] += 1
            else:
                totals.update(delta)
                intervals += 1
                observed_ns += clock - old_clock
        previous = (scope, clock, values)
    polls = totals["periodic_summary_polls"]
    return {
        "available": intervals > 0,
        "counter_observations": observations,
        "bridge_scope_count": len(scopes),
        "accepted_intervals": intervals,
        "observed_interval_ns": observed_ns if intervals else None,
        "observed_counter_deltas": dict(totals) if intervals else None,
        "paused_exports_avoided_per_periodic_poll": (
            totals["paused_duplicate_suppressions"] / polls if intervals and polls else None),
        "excluded_intervals_or_observations": dict(exclusions),
        "scope": "deltas_between_adjacent_valid_observations_of_the_same_bridge_instance",
        "interpretation": (
            "Counts cover only accepted observation intervals. The first lifetime values and "
            "unobserved endpoints are excluded. A suppression is an avoided periodic scene export "
            "and snapshot publication, not an avoided summary poll, GPU frame, or measured energy saving. "
            "Explicit refreshes and commands can still export packets during an otherwise paused scene."),
    }


def pipeline_cache_summary(renderers: list[dict[str, Any]]) -> dict[str, Any]:
    """A callback samples lifecycle counters; never sum repeated cache observations."""
    states = [renderer["pipeline_cache_persistence"] for renderer in renderers
              if isinstance(renderer.get("pipeline_cache_persistence"), dict)]
    return {
        "available": bool(states), "observations_with_fields": len(states),
        "observed_sources": sorted({str(state["source"]) for state in states if "source" in state}),
        "observed_load_statuses": sorted({str(state["load"]) for state in states if "load" in state}),
        "observed_save_statuses": sorted({str(state["save"]) for state in states if "save" in state}),
        "latest_observed_lifecycle_counters": states[-1] if states else None,
        "interpretation": "Sampled cumulative device-lifecycle host counters, not per-callback operations. "
                          "No sum or lifecycle count is inferred: callbacks can repeat or miss recreation. "
                          "disk-supplied confirms compatible bytes were supplied, not a driver cache hit. "
                          "Nanosecond fields are CPU host intervals, not GPU or frame time.",
    }


def recorded_environments(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    environments: dict[int, dict[str, Any]] = {}
    for record in records:
        data = record.get("data", {})
        candidates = [data] if record.get("event") == "environment" else [data.get(key, {}) for key in ("environment", "environment_before", "environment_after")]
        for env in candidates:
            if isinstance(env, dict) and isinstance(env.get("monotonic_ns"), int):
                environments[env["monotonic_ns"]] = env
    return [environments[key] for key in sorted(environments)]


def observation_summary(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Keep observational telemetry separate from controlled kernel comparisons."""
    snapshots = [r["data"] for r in records if r.get("event") == "observatory_snapshot"]
    renderers = [r["data"] for r in records if r.get("event") == "observatory_renderer"]
    ordered = recorded_environments(records)
    thermal = Counter(str(env.get("thermal_status", "unavailable")) for env in ordered)
    refresh = [(env["monotonic_ns"], env["display_refresh_hz"]) for env in ordered
               if finite_positive(env.get("display_refresh_hz"))]
    refresh_transitions = [
        {"monotonic_ns": current[0], "from_hz": previous[1], "to_hz": current[1]}
        for previous, current in zip(refresh, refresh[1:]) if refresh_changed([previous[1], current[1]])]
    plugged = {env.get("battery_plugged") for env in ordered if isinstance(env.get("battery_plugged"), int)}
    energy = [(env["monotonic_ns"], env["battery_energy_counter_nwh"]) for env in ordered
              if isinstance(env.get("battery_energy_counter_nwh"), (int, float))
              and math.isfinite(env["battery_energy_counter_nwh"])]
    energy_result: dict[str, Any] = {"scope": "whole_phone_battery_terminal_including_display_validation_and_logger",
                                  "counter_observations": len(energy), "net_battery_energy_change_j": None}
    if len(energy) >= 2:
        delta_j = (energy[-1][1] - energy[0][1]) * 3.6e-6
        energy_result.update(net_battery_energy_change_j=delta_j,
                             observation_interval_ns=energy[-1][0] - energy[0][0],
                             interpretation="negative_is_net_battery_depletion; counter_resolution_and_driver_support_are_unqualified")
        if plugged == {0} and delta_j <= 0:
            energy_result["observed_unplugged_depletion_j"] = -delta_j
    frames, pregraphics = [], []
    previous_counter = None
    resets = 0
    for renderer in renderers:
        timing = renderer.get("gpu_timing") or {}
        counter = timing.get("samples")
        if not isinstance(counter, int) or counter <= 0 or counter == previous_counter:
            continue
        if previous_counter is not None and counter < previous_counter:
            resets += 1
        previous_counter = counter
        if finite_positive(timing.get("frameNs")):
            frames.append(timing["frameNs"])
        if finite_positive(timing.get("preGraphicsNs")):
            pregraphics.append(timing["preGraphicsNs"])
    ui_intervals = [value for snapshot in snapshots for value in snapshot.get("ui_frame_intervals_ns", []) if finite_positive(value)]
    scenarios = sorted({str(snapshot["snapshot"].get("scenario_id")) for snapshot in snapshots if isinstance(snapshot.get("snapshot"), dict)})
    dropped = max([0] + [int(r.get("data", {}).get("queue_dropped_events_total", 0)) for r in records]
                  + [int(r.get("data", {}).get("queue_dropped_events", 0)) for r in records])
    return {"runtime_snapshot_count": len(snapshots), "renderer_callback_count": len(renderers),
            "scenarios": scenarios, "queue_dropped_events": dropped,
            "environment_observation_count": len(ordered), "thermal_status_observation_counts": dict(thermal),
            "display_refresh": {"observations": len(refresh),
                                "observed_hz": sorted({round(value, 3) for _, value in refresh}),
                                "observed_transitions": refresh_transitions,
                                "interpretation": "Periodic cached observations, not a continuous mode trace. Default display policy does not establish a fixed refresh rate."},
            "battery_plugged_values": sorted(plugged), "battery_energy": energy_result,
            "gpu_last_completed_sample": timing_summary(frames), "gpu_pregraphics_sample": timing_summary(pregraphics),
            "gpu_sample_counter_resets": resets, "ui_vsync_interval": timing_summary(ui_intervals),
            "runtime_observation": runtime_observation_summary(records),
            "pipeline_cache_persistence": pipeline_cache_summary(renderers),
            "timing_scope": "GPU callbacks sample the last completed timestamp query and may skip frames; UI callbacks are separate. Neither population is a complete presented-frame trace."}


def analyse(files: dict[str, bytes], warnings: list[str] | None = None) -> dict[str, Any]:
    warnings = list(warnings or [])
    manifest = json.loads(files["manifest.json"])
    protocol = manifest.get("protocol_version", 1)
    records, journal_warnings = read_journal(files["events.ndjson"])
    warnings.extend(journal_warnings)
    if not any(record.get("event") == "session_end" for record in records):
        warnings.append("No session_end record: this is an interrupted or still-running session.")
    samples = [r["data"] for r in records if r.get("event") == "sample" and isinstance(r.get("data"), dict)]
    sample_id_counts = Counter(s["sample_id"] for s in samples if isinstance(s.get("sample_id"), str))
    calibrations: dict[str, list[tuple[int, dict[str, Any]]]] = defaultdict(list)
    sample_indices = {}
    sample_windows = {}
    starts = {}
    for index, record in enumerate(records):
        data = record.get("data", {})
        if record.get("event") == "pair_calibration":
            calibrations[str(data.get("pair"))].append((index, data))
        elif record.get("event") == "sample_start" and isinstance(data.get("sample_id"), str):
            starts.setdefault(data["sample_id"], record["monotonic_ns"])
        elif record.get("event") == "sample":
            sample_indices[id(data)] = index
            sample_windows[id(data)] = (starts.get(data.get("sample_id"), record["monotonic_ns"]), record["monotonic_ns"])
    environment_trace = recorded_environments(records)

    def environments_during(selected: list[dict[str, Any]]) -> list[dict[str, Any]]:
        first = min(sample_windows[id(sample)][0] for sample in selected)
        last = max(sample_windows[id(sample)][1] for sample in selected)
        return [env for env in environment_trace if first <= env["monotonic_ns"] <= last]
    thermal_paused_blocks = {
        (str(r["data"].get("pair", "unknown")), r["data"].get("round", 0), r["data"].get("block", 0))
        for r in records if r.get("event") == "paired_block" and r.get("data", {}).get("crossed_thermal_pause")
    }
    blocks: dict[tuple[str, int, int], list[dict[str, Any]]] = defaultdict(list)
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    failed_admission: Counter[str] = Counter()
    for sample in samples:
        pair = str(sample.get("pair", "unknown"))
        if (sample.get("status") == "FAIL" or sample.get("native", {}).get("status") == "FAIL"
                or any(reason != "failed_or_skipped_correctness_check" for reason in correctness_reasons(sample))):
            failed_admission[pair] += 1
        if (sample.get("warmup") or sample.get("phase") in ("warmup", "calibration")
                or str(sample.get("order", "")).startswith("warmup")):
            continue
        groups[pair].append(sample)
        blocks[(pair, sample.get("round", 0), sample.get("block", 0))].append(sample)
    # A failed pilot can retire its pair before any measured block exists.
    for pair in failed_admission:
        groups.setdefault(pair, [])
    comparisons = []
    for pair, pair_samples in sorted(groups.items()):
        calibration_entries = calibrations.get(pair, [])
        pair_contract_reasons = []
        declarations = []
        fixed_repeats = None
        if protocol == 3:
            pair_contract_reasons.extend(calibration_validation_reasons(
                calibration_entries, min((sample_indices[id(s)] for s in pair_samples), default=len(records))))
            if len(calibration_entries) == 1:
                calibration = calibration_entries[0][1]
                declarations.append(calibration)
                fixed_repeats = calibration.get("fixed_repeats")
            if "schedule" in manifest:
                schedule = manifest["schedule"]
                scheduled = [entry for entry in schedule if isinstance(entry, dict) and entry.get("pair") == pair] if isinstance(schedule, list) else []
                if len(scheduled) != 1:
                    pair_contract_reasons.append("missing_or_duplicate_manifest_pair")
                else:
                    declarations.append(scheduled[0])
        frozen_identity = {}
        contract_violations: Counter[str] = Counter(pair_contract_reasons)
        raw_changes = []
        clean_blocks = []
        block_diagnostics = []
        unit_blocks: dict[int, list[dict[str, Any]]] = defaultdict(list)
        exclusions: Counter[str] = Counter()
        for (block_pair, _round, _block), block_samples in blocks.items():
            if block_pair != pair:
                continue
            reasons = block_validation_reasons(block_samples, _round, protocol, sample_id_counts,
                                               fixed_repeats)
            if protocol == 3:
                reasons.extend(reason for reason in pair_contract_reasons if reason not in reasons)
                if not reasons:
                    declared_reasons = declared_identity_reasons(block_samples, declarations)
                    reasons.extend(declared_reasons)
                    contract_violations.update(declared_reasons)
                    if not reasons:
                        if not frozen_identity:
                            frozen_identity = {sample["role"]: sample_identity(sample) for sample in block_samples}
                        if any(sample_identity(sample) != frozen_identity[sample["role"]] for sample in block_samples):
                            reasons.append("pair_identity_changed_across_comparison")
                            contract_violations["pair_identity_changed_across_comparison"] += 1
            value = None
            if not reasons:
                value = metric_log_ratio(block_samples, "elapsed_ns")
                raw_changes.append(percent_from_log(value))
                reasons = environmental_reasons(block_samples, environments_during(block_samples))
                if (block_pair, _round, _block) in thermal_paused_blocks:
                    reasons.append("crossed_thermal_pause")
            exclusions.update(reasons)
            if not reasons:
                clean_blocks.append(block_samples)
            block_diagnostics.append({"round": _round, "block": _block,
                                      "chronological_order": [s.get("order") for s in block_samples],
                                      "sample_ids": [s.get("sample_id") for s in block_samples],
                                      "change_percent": percent_from_log(value) if value is not None else None,
                                      "excluded_reasons": reasons})
            if protocol == 3 and type(_round) is int and _round >= 0:
                unit_blocks[_round // 2].append({"round": _round, "samples": block_samples, "reasons": reasons})
        if protocol == 3 and frozen_identity and any(
                sample.get("role") not in frozen_identity
                or sample_identity(sample) != frozen_identity[sample["role"]] for sample in pair_samples):
            # Do not let a later cohort, or an invalid block carrying changed
            # metadata, silently redefine the experiment under the same pair key.
            contract_violations["pair_identity_changed_across_comparison"] = max(
                1, contract_violations["pair_identity_changed_across_comparison"])
        units = []
        unit_diagnostics = []
        unit_exclusions: Counter[str] = Counter()
        for unit, constituent_blocks in sorted(unit_blocks.items()):
            unit_samples = [sample for block in constituent_blocks for sample in block["samples"]]
            reasons = balanced_unit_reasons(constituent_blocks, unit, environments_during(unit_samples))
            unit_exclusions.update(reasons)
            value = None
            if not reasons:
                units.append(unit_samples)
                value = metric_log_ratio(unit_samples, "elapsed_ns")
            unit_diagnostics.append({"counterbalanced_unit": unit,
                                     "sample_ids": [s.get("sample_id") for s in unit_samples],
                                     "change_percent": percent_from_log(value) if value is not None else None,
                                     "excluded_reasons": reasons})
        effect = paired_metric_summary(clean_blocks, "elapsed_ns", units if protocol == 3 else None)
        passed = [s for s in pair_samples if not correctness_reasons(s)
                  and finite_positive(s.get("elapsed_ns"))]
        baseline = [s["elapsed_ns"] for s in passed if s.get("role") == "A"]
        candidate = [s["elapsed_ns"] for s in passed if s.get("role") == "B"]
        maximum_errors: dict[str, float] = {}
        policies = set()
        comparison_kinds = set()
        for sample in pair_samples:
            native = sample.get("native", {})
            if sample.get("role") == "B":
                if native.get("comparison_kind"):
                    comparison_kinds.add(str(native["comparison_kind"]))
                elif sample.get("case_id") in {"gpu.visual-fp16", "gpu.passive-fp64", "opencl.passive-fp64"}:
                    # Version-one exports predate explicit native precision
                    # metadata. Known case contracts still change precision.
                    comparison_kinds.add("accuracy_cost_experiment")
                if native.get("numerical_policy"):
                    policies.add(str(native["numerical_policy"]))
                for key, value in error_metrics(native).items():
                    maximum_errors[key] = max(value, maximum_errors.get(key, 0.0))
        interval = effect["bootstrap_95_percent_interval"]
        median_change = effect["median_change_percent"]
        statuses = Counter(str(sample.get("status", "INVALID")) for sample in pair_samples)
        self_control = {s.get("case_id") for s in pair_samples if s.get("role") == "A"} == {s.get("case_id") for s in pair_samples if s.get("role") == "B"}
        if failed_admission[pair]:
            decision = "correctness_failure_requires_investigation"
        elif protocol == 3 and contract_violations:
            decision = "measurement_contract_requires_investigation"
        elif self_control:
            decision = "repeatability_control"
        elif "different_force_model_cost" in comparison_kinds:
            decision = "different_physics_cost_only"
        elif "accuracy_cost_experiment" in comparison_kinds:
            decision = "accuracy_cost_tradeoff_requires_domain_review"
        elif protocol in (1, 2):
            decision = "legacy_fixed_order_descriptive_only"
        elif protocol != 3:
            decision = "unsupported_measurement_protocol"
        elif effect["resampling_units"] < 5:
            decision = "insufficient_clean_counterbalanced_units"
        elif interval and interval[1] < -1.0 and median_change is not None and median_change <= -5.0:
            decision = "promising_candidate_repeat_on_device"
        elif interval and interval[0] > 1.0:
            decision = "candidate_slower_in_this_workload"
        else:
            decision = "no_clear_practical_gain"
        comparisons.append({
            "pair": pair, "status_counts": dict(statuses), "baseline": timing_summary(baseline),
            "pair_calibration_records": len(calibration_entries),
            "measurement_contract_violations": dict(contract_violations), "frozen_pair_identity": frozen_identity,
            "candidate": timing_summary(candidate), "complete_valid_blocks": len(raw_changes),
            "pooled_timing_scope": "positive_PASS_measured_role_samples_including_excluded_blocks; not_the_paired_effect_estimator",
            "clean_blocks": len(clean_blocks), "excluded_reasons": dict(exclusions),
            "block_change_percent": effect["block_change_percent"], "all_valid_block_change_percent": raw_changes,
            "raw_block_diagnostics": block_diagnostics,
            "statistical_unit": effect["statistical_unit"], "resampling_units": effect["resampling_units"],
            "clean_counterbalanced_units": len(units) if protocol == 3 else 0,
            "counterbalanced_unit_diagnostics": unit_diagnostics,
            "excluded_unit_reasons": dict(unit_exclusions),
            "unit_log_ratios": effect["unit_log_ratios"], "unit_change_percent": effect["unit_change_percent"],
            "median_change_percent": median_change, "bootstrap_95_percent_interval": interval,
            "estimate_interpretation": effect["interpretation"],
            "first_position_diagnostic": first_position_summary(clean_blocks),
            "self_control": self_control,
            "maximum_reported_error_metrics": maximum_errors, "numerical_policies": sorted(policies),
            "comparison_kinds": sorted(comparison_kinds),
            "legacy_precision_classification_inferred": any(
                s.get("role") == "B" and not s.get("native", {}).get("comparison_kind")
                and s.get("case_id") in {"gpu.visual-fp16", "gpu.passive-fp64", "opencl.passive-fp64"}
                for s in pair_samples),
            "failed_samples_including_calibration_and_warmup": failed_admission[pair],
            "separate_timing_metrics": {metric: paired_metric_summary(
                clean_blocks, metric, units if protocol == 3 else None) for metric in TIMING_METRICS},
            "declared_native_timing_scopes": {
                role: sorted({str(s.get("native", {}).get("timing_scope", "unspecified"))
                              for s in pair_samples if s.get("role") == role}) for role in ("A", "B")},
            "decision": decision,
        })
    if comparisons and protocol in (1, 2):
        warnings.append("Legacy fixed-ABBA order: all effects are descriptive. A first-in-block penalty can favour B even in a self-control; block bootstrapping does not remove that bias.")
    self_controls = []
    for comparison in comparisons:
        if not comparison["self_control"]:
            continue
        metrics = {"elapsed_ns": {key: comparison[key] for key in (
            "median_change_percent", "bootstrap_95_percent_interval", "resampling_units")},
                   **comparison["separate_timing_metrics"]}
        affected = []
        for metric, result in metrics.items():
            interval = result["bootstrap_95_percent_interval"]
            value = result["median_change_percent"]
            if value is not None and abs(value) >= 5 and interval and (interval[1] < -1 or interval[0] > 1):
                affected.append(metric)
        self_controls.append({"pair": comparison["pair"], "metrics": metrics,
                              "systematic_difference_metrics": affected,
                              "interpretation": "Identical-case control; nonzero effects flag repeatability or ordering conditions. Different workloads are not interchangeable controls; no correction is subtracted."})
        if affected:
            warnings.append(f"Self-control {comparison['pair']} has a systematic difference in {', '.join(affected)}. Treat claims in those timing scopes cautiously; do not apply its magnitude to a different workload.")
    observations = observation_summary(records)
    if observations["display_refresh"]["observed_transitions"]:
        warnings.append("Display refresh transitions were observed. Blocks and complementary units spanning an observed transition are excluded; cached telemetry cannot establish fixed conditions between observations.")
    admission = [r["data"] for r in records if r.get("event") == "case_admission"]
    identity = {name: hashlib.sha256(files[name]).hexdigest() for name in ("manifest.json", "device.json", "catalogue.json") if name in files}
    return {"schema_version": 1, "manifest": manifest, "source_file_sha256": identity,
            "warnings": warnings, "sample_count": len(samples), "case_admission": admission,
            "comparisons": comparisons, "self_control_diagnostics": self_controls,
            "observations": observations, "promotion": "none",
            "limitations": [
                "Timing scope is whatever each native result declares; compare matched scopes only.",
                "GPU timestamp, JNI wall time, calling-thread CPU time and native setup time are separate metrics. A host timing change does not establish a GPU execution change.",
                "Protocol v3 resamples complete complementary ABBA/BAAB units in log-ratio space. Legacy fixed-order intervals remain descriptive and cannot remove position bias.",
                "Quick plans are exploratory; five or more clean complementary units and repeated physical sessions are needed to investigate a candidate.",
                "Refresh, thermal and charging observations are cached snapshots, not evidence of continuously fixed operating conditions.",
                "Battery counters cover the whole device, including validation/reporting/display work.",
                "A correctness PASS covers the recorded fixture and tolerance, not general scientific validity.",
                "No runtime dispatch profile or scientific default is automatically changed.",
            ]}


def markdown(report: dict[str, Any]) -> str:
    lines = ["# Solar Gravity Lab device research", "", f"Recorded samples: **{report['sample_count']}**. Production policy changes: **none**.", ""]
    for warning in report["warnings"]:
        lines.append(f"- {warning}")
    observations = report["observations"]
    if observations["runtime_snapshot_count"] or observations["renderer_callback_count"]:
        gpu = observations["gpu_last_completed_sample"]
        gpu_label = "unavailable" if gpu["median_ns"] is None else f"{gpu['median_ns'] / 1e6:.3f} ms"
        lines += ["", "## Observatory recording", "",
                  f"Runtime snapshots: **{observations['runtime_snapshot_count']}**; renderer callbacks: **{observations['renderer_callback_count']}**; dropped queue events: **{observations['queue_dropped_events']}**.", "",
                  f"Unique observed GPU timestamp samples: **{gpu['count']}**; median sampled GPU frame time: **{gpu_label}**.", "",
                  observations["timing_scope"], ""]
        runtime = observations["runtime_observation"]
        if runtime["available"]:
            counts = runtime["observed_counter_deltas"]
            lines += [
                f"Across **{runtime['accepted_intervals']}** valid observation intervals: "
                f"**{counts['periodic_summary_polls']}** periodic summary polls, "
                f"**{counts['paused_duplicate_suppressions']}** paused duplicate exports avoided, "
                f"**{counts['packet_export_attempts']}** packet export attempts and "
                f"**{counts['packet_export_failures']}** export failures.", "",
                runtime["interpretation"], "",
            ]
        else:
            lines += ["Runtime work counters: unavailable or insufficient observations for a valid delta.", ""]
        cache = observations["pipeline_cache_persistence"]
        if cache["available"]:
            latest = cache["latest_observed_lifecycle_counters"]
            lines += [
                f"Pipeline cache sources observed: **{', '.join(cache['observed_sources']) or 'unavailable'}**. "
                f"Latest load status: **{latest.get('load', 'unavailable')}**; "
                f"latest save status: **{latest.get('save', 'unavailable')}**.", "",
                cache["interpretation"], "",
            ]
    lines += ["", "| Matched workload | Clean blocks | Resampling units | Median time change | Decision |", "|---|---:|---:|---:|---|"]
    for comparison in report["comparisons"]:
        change = comparison["median_change_percent"]
        label = comparison["pair"].replace("|", "\\|").replace("\n", " ")
        value = "unavailable" if change is None else f"{change:+.2f}%"
        lines.append(f"| {label} | {comparison['clean_blocks']} | {comparison['resampling_units']} | {value} | {comparison['decision']} |")
    gpu_comparisons = [comparison for comparison in report["comparisons"]
                       if comparison["separate_timing_metrics"]["gpu_timestamp"]["resampling_units"]]
    if gpu_comparisons:
        lines += ["", "## Native GPU query intervals", "",
                  "These queries measure the native GPU interval independently of host submission/completion latency. They are not whole-frame or energy measurements.", "",
                  "| Matched workload | Valid resampling units | Baseline GPU interval | Candidate GPU interval | Paired GPU change |",
                  "|---|---:|---:|---:|---:|"]
        for comparison in gpu_comparisons:
            metric = comparison["separate_timing_metrics"]["gpu_timestamp"]
            label = comparison["pair"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {label} | {metric['resampling_units']} | {metric['baseline']['median_ns'] / 1000:.3f} µs | "
                         f"{metric['candidate']['median_ns'] / 1000:.3f} µs | {metric['median_change_percent']:+.2f}% |")
    controls = [comparison for comparison in report["comparisons"] if comparison["self_control"]]
    if controls:
        lines += ["", "## Repeatability and first-position diagnostics", "",
                  "Each row compares a case with itself. The first/last column compares the first invocation with the same-role last invocation within each block; positive means the first was slower. No control-derived correction is subtracted from another workload.", "",
                  "| Identical-case control | Native paired change | Native first/last change | GPU paired change |",
                  "|---|---:|---:|---:|"]
        for comparison in controls:
            first = comparison["first_position_diagnostic"]["first_vs_last_same_role"]["elapsed_ns"]
            values = [comparison["median_change_percent"], first["median_change_percent"],
                      comparison["separate_timing_metrics"]["gpu_timestamp"]["median_change_percent"]]
            labels = ["unavailable" if value is None else f"{value:+.2f}%" for value in values]
            label = comparison["pair"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {label} | {' | '.join(labels)} |")
    lines += ["", "A negative change means the candidate took less time. Protocol v3 estimates use complete eight-sample complementary units; legacy estimates describe fixed-order blocks. Raw blocks, excluded units, and first-position diagnostics for every timing scope remain in JSON.", "", "## Interpretation limits", ""]
    lines.extend(f"- {limitation}" for limitation in report["limitations"])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="Exported ZIP or recovered session directory")
    parser.add_argument("--output", type=Path, help="Write JSON and Markdown using this filename stem")
    args = parser.parse_args()
    try:
        files, warnings = load_export(args.report)
        report = analyse(files, warnings)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"Cannot analyse export: {error}", file=sys.stderr)
        return 1
    text = markdown(report)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.with_suffix(".json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        args.output.with_suffix(".md").write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
