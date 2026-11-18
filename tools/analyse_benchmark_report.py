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
    # Resample complete ABBA blocks, never correlated samples within a block.
    # This descriptive interval cannot remove systematic thermal/ordering bias.
    if len(values) < 5:
        return None
    # Statistical resampling deliberately uses a reproducible PRNG. It creates
    # no credentials/nonces and does not implement any integrity/security check.
    random_source = random.Random(242)
    medians = [statistics.median(random_source.choices(values, k=len(values))) for _ in range(4000)]
    return [quantile(medians, .025), quantile(medians, .975)]


def environmental_reasons(samples: list[dict[str, Any]]) -> list[str]:
    reasons = []
    environments = [sample.get(key, {}) for sample in samples for key in ("environment_before", "environment_after")]
    statuses = [env.get("thermal_status") for env in environments]
    if any(isinstance(status, int) and status >= 3 for status in statuses):
        reasons.append("severe_or_higher_thermal_status")
    if any(status is None or status == -1 for status in statuses):
        reasons.append("thermal_status_unavailable")
    plugged = {env.get("battery_plugged") for env in environments if env.get("battery_plugged") is not None}
    if len(plugged) > 1:
        reasons.append("charging_state_changed")
    if len({sample.get("placement", "system") for sample in samples}) != 1:
        reasons.append("placement_changed")
    return reasons


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


def observation_summary(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Keep observational telemetry separate from controlled kernel comparisons."""
    snapshots = [r["data"] for r in records if r.get("event") == "observatory_snapshot"]
    renderers = [r["data"] for r in records if r.get("event") == "observatory_renderer"]
    environments: dict[int, dict[str, Any]] = {}
    for record in records:
        data = record.get("data", {})
        candidates = [data] if record.get("event") == "environment" else [data.get(key, {}) for key in ("environment", "environment_before", "environment_after")]
        for env in candidates:
            if isinstance(env, dict) and isinstance(env.get("monotonic_ns"), int):
                environments[env["monotonic_ns"]] = env
    ordered = [environments[key] for key in sorted(environments)]
    thermal = Counter(str(env.get("thermal_status", "unavailable")) for env in ordered)
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
            "battery_plugged_values": sorted(plugged), "battery_energy": energy_result,
            "gpu_last_completed_sample": timing_summary(frames), "gpu_pregraphics_sample": timing_summary(pregraphics),
            "gpu_sample_counter_resets": resets, "ui_vsync_interval": timing_summary(ui_intervals),
            "timing_scope": "GPU callbacks sample the last completed timestamp query and may skip frames; UI callbacks are separate. Neither population is a complete presented-frame trace."}


def analyse(files: dict[str, bytes], warnings: list[str] | None = None) -> dict[str, Any]:
    warnings = list(warnings or [])
    manifest = json.loads(files["manifest.json"])
    records, journal_warnings = read_journal(files["events.ndjson"])
    warnings.extend(journal_warnings)
    if not any(record.get("event") == "session_end" for record in records):
        warnings.append("No session_end record: this is an interrupted or still-running session.")
    samples = [r["data"] for r in records if r.get("event") == "sample" and isinstance(r.get("data"), dict)]
    thermal_paused_blocks = {
        (str(r["data"].get("pair", "unknown")), r["data"].get("round", 0), r["data"].get("block", 0))
        for r in records if r.get("event") == "paired_block" and r.get("data", {}).get("crossed_thermal_pause")
    }
    blocks: dict[tuple[str, int, int], list[dict[str, Any]]] = defaultdict(list)
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for sample in samples:
        if sample.get("warmup") or str(sample.get("order", "")).startswith("warmup"):
            continue
        pair = str(sample.get("pair", "unknown"))
        groups[pair].append(sample)
        blocks[(pair, sample.get("round", 0), sample.get("block", 0))].append(sample)
    comparisons = []
    for pair, pair_samples in sorted(groups.items()):
        changes = []
        raw_changes = []
        exclusions: Counter[str] = Counter()
        for (block_pair, _round, _block), block_samples in blocks.items():
            if block_pair != pair:
                continue
            if [sample.get("order") for sample in block_samples] != ABBA:
                exclusions["incomplete_or_duplicate_abba"] += 1
                continue
            if any(sample.get("status") != "PASS" or sample.get("native", {}).get("status") != "PASS" for sample in block_samples):
                exclusions["failed_or_skipped_correctness_check"] += 1
                continue
            times = [sample.get("elapsed_ns") for sample in block_samples]
            if not all(finite_positive(value) for value in times):
                exclusions["missing_zero_or_nonfinite_kernel_time"] += 1
                continue
            if [sample.get("role") for sample in block_samples] != ["A", "B", "B", "A"]:
                exclusions["inconsistent_abba_roles"] += 1
                continue
            if block_samples[0].get("case_id") != block_samples[3].get("case_id") or block_samples[1].get("case_id") != block_samples[2].get("case_id"):
                exclusions["case_changed_within_block"] += 1
                continue
            if len({(sample.get("fixture_id"), sample.get("steps"), sample.get("backend")) for sample in block_samples}) != 1:
                exclusions["workload_changed_within_block"] += 1
                continue
            ratio = math.sqrt((times[1] / times[0]) * (times[2] / times[3]))
            change = (ratio - 1.0) * 100.0
            raw_changes.append(change)
            reasons = environmental_reasons(block_samples)
            if (block_pair, _round, _block) in thermal_paused_blocks:
                reasons.append("crossed_thermal_pause")
            if reasons:
                exclusions.update(reasons)
            else:
                changes.append(change)
        baseline = [s["elapsed_ns"] for s in pair_samples if s.get("role") == "A" and s.get("status") == "PASS" and finite_positive(s.get("elapsed_ns"))]
        candidate = [s["elapsed_ns"] for s in pair_samples if s.get("role") == "B" and s.get("status") == "PASS" and finite_positive(s.get("elapsed_ns"))]
        maximum_errors: dict[str, float] = {}
        policies = set()
        comparison_kinds = set()
        for sample in pair_samples:
            native = sample.get("native", {})
            if sample.get("role") == "B":
                if native.get("comparison_kind"):
                    comparison_kinds.add(str(native["comparison_kind"]))
                if native.get("numerical_policy"):
                    policies.add(str(native["numerical_policy"]))
                for key, value in error_metrics(native).items():
                    maximum_errors[key] = max(value, maximum_errors.get(key, 0.0))
        interval = bootstrap_interval(changes)
        median_change = statistics.median(changes) if changes else None
        statuses = Counter(str(sample.get("status", "INVALID")) for sample in pair_samples)
        self_control = {s.get("case_id") for s in pair_samples if s.get("role") == "A"} == {s.get("case_id") for s in pair_samples if s.get("role") == "B"}
        if statuses.get("FAIL", 0) or any(s.get("native", {}).get("status") == "FAIL" for s in pair_samples):
            decision = "correctness_failure_requires_investigation"
        elif self_control:
            decision = "repeatability_control"
        elif "different_force_model_cost" in comparison_kinds:
            decision = "different_physics_cost_only"
        elif "accuracy_cost_experiment" in comparison_kinds:
            decision = "accuracy_cost_tradeoff_requires_domain_review"
        elif len(changes) < 5:
            decision = "insufficient_clean_blocks"
        elif interval and interval[1] < -1.0 and median_change is not None and median_change <= -5.0:
            decision = "promising_candidate_repeat_on_device"
        elif interval and interval[0] > 1.0:
            decision = "candidate_slower_in_this_workload"
        else:
            decision = "no_clear_practical_gain"
        comparisons.append({
            "pair": pair, "status_counts": dict(statuses), "baseline": timing_summary(baseline),
            "candidate": timing_summary(candidate), "complete_valid_blocks": len(raw_changes),
            "clean_blocks": len(changes), "excluded_reasons": dict(exclusions),
            "block_change_percent": changes, "all_valid_block_change_percent": raw_changes,
            "median_change_percent": median_change, "bootstrap_95_percent_interval": interval,
            "maximum_reported_error_metrics": maximum_errors, "numerical_policies": sorted(policies),
            "comparison_kinds": sorted(comparison_kinds),
            "decision": decision,
        })
    admission = [r["data"] for r in records if r.get("event") == "case_admission"]
    identity = {name: hashlib.sha256(files[name]).hexdigest() for name in ("manifest.json", "device.json", "catalogue.json") if name in files}
    return {"schema_version": 1, "manifest": manifest, "source_file_sha256": identity,
            "warnings": warnings, "sample_count": len(samples), "case_admission": admission,
            "comparisons": comparisons, "observations": observation_summary(records), "promotion": "none",
            "limitations": [
                "Timing scope is whatever each native result declares; compare matched scopes only.",
                "Bootstrap intervals resample ABBA blocks and do not remove systematic device drift.",
                "Quick plans are exploratory; five or more clean blocks are needed for a candidate decision.",
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
    lines += ["", "| Matched workload | Clean blocks | Median time change | Decision |", "|---|---:|---:|---|"]
    for comparison in report["comparisons"]:
        change = comparison["median_change_percent"]
        label = comparison["pair"].replace("|", "\\|").replace("\n", " ")
        value = "unavailable" if change is None else f"{change:+.2f}%"
        lines.append(f"| {label} | {comparison['clean_blocks']} | {value} | {comparison['decision']} |")
    lines += ["", "A negative change means the candidate took less time. Failed, skipped, incomplete and thermally confounded blocks remain in JSON and are excluded from clean comparisons.", "", "## Interpretation limits", ""]
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
