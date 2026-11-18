#!/usr/bin/env python3
"""Compare paired same-host CPU measurements; never infer device acceptance."""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

EXPECTED_WORKLOADS = {
    "canonical-mixed-365", "sparse-1024", "lab-3", "lab-probe-4",
    "dense-128", "dense-192", "dense-256",
}


def read_measurements(path: Path) -> tuple[dict, dict]:
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    environments = [row for row in rows if row["kind"] == "environment"]
    if len(environments) != 1:
        raise ValueError(f"{path}: expected one environment record")
    measurements = {}
    for row in rows:
        if row["kind"] != "measurement":
            continue
        key = (row["workload"], row["requested"])
        if key in measurements:
            raise ValueError(f"{path}: duplicate measurement {key}")
        for metric in ("median_us", "p90_us"):
            if not 0 < float(row[metric]) < float("inf"):
                raise ValueError(f"{path}: invalid {metric} for {key}")
        if row["p90_us"] < row["median_us"]:
            raise ValueError(f"{path}: p90 below median for {key}")
        if not (0 <= row["max_relative_oracle_error"] <= 1.0e-10
                and 0 <= row["max_abs_oracle_error"] <= 0.02):
            raise ValueError(f"{path}: scalar oracle failure for {key}")
        if not all(isinstance(row[field], int) and not isinstance(row[field], bool)
                   for field in ("bodies", "sources", "active_workers")):
            raise ValueError(f"{path}: noninteger workload/worker counts for {key}")
        if not 0 <= row["sources"] <= row["bodies"] or row["active_workers"] < 1:
            raise ValueError(f"{path}: invalid workload/worker counts for {key}")
        if row["requested"] == "SimdArm64" and not row["path"].startswith("simd.arm64."):
            raise ValueError(f"{path}: requested native Arm path fell back for {key}")
        if row["requested"] == "ReferenceScalar" and row["path"] != "scalar.reference":
            raise ValueError(f"{path}: scalar control did not execute scalar for {key}")
        measurements[key] = row
    if not measurements:
        raise ValueError(f"{path}: no measurements")
    return environments[0], measurements


def compare(directory: Path) -> dict:
    comparisons = []
    fixture_identity = None
    measurement_policy = None
    for affinity in ("full", "one-cpu"):
        runs = {}
        reference_environment = None
        reference_keys = None
        for variant in ("baseline", "candidate"):
            runs[variant] = []
            for repetition in (0, 1):
                env, rows = read_measurements(directory / f"{affinity}-{variant}-{repetition}.ndjson")
                signature = {key: env[key] for key in ("arch", "available_workers", "steps", "samples")}
                policy = {key: env[key] for key in ("arch", "steps", "samples")}
                if measurement_policy is None:
                    measurement_policy = policy
                if policy != measurement_policy or env["steps"] not in (1, 64) or env["samples"] != 11:
                    raise ValueError(f"{affinity}: inconsistent or unexpected measurement policy")
                host_backend = {"aarch64": "SimdArm64", "x86_64": "SimdX64"}.get(env["arch"])
                expected_keys = {(workload, backend) for workload in EXPECTED_WORKLOADS
                                 for backend in ("ReferenceScalar", host_backend)}
                if host_backend is None or set(rows) != expected_keys:
                    raise ValueError(f"{affinity}: incomplete or unexpected workload/backend matrix")
                identity = {key: (row["bodies"], row["sources"]) for key, row in rows.items()}
                if fixture_identity is None:
                    fixture_identity = identity
                if identity != fixture_identity:
                    raise ValueError(f"{affinity}: body/source counts changed between measurements")
                if reference_environment is None:
                    reference_environment, reference_keys = signature, set(rows)
                if signature != reference_environment or set(rows) != reference_keys:
                    raise ValueError(f"{affinity}: incompatible environments or workloads")
                if affinity == "one-cpu" and (env["available_workers"] != 1 or any(row["active_workers"] != 1 for row in rows.values())):
                    raise ValueError("one-cpu: affinity was not observed by the solver")
                runs[variant].append(rows)
        for key in sorted(reference_keys):
            result = {"affinity": affinity, "workload": key[0], "backend": key[1], "environment": reference_environment}
            for variant, variant_runs in runs.items():
                result[variant] = {
                    "median_us": statistics.median(run[key]["median_us"] for run in variant_runs),
                    "median_of_p90_us": statistics.median(run[key]["p90_us"] for run in variant_runs),
                    "run_medians_us": [run[key]["median_us"] for run in variant_runs],
                    "paths": sorted({run[key]["path"] for run in variant_runs}),
                    "active_workers": sorted({run[key]["active_workers"] for run in variant_runs}),
                    "max_abs_oracle_error": max(run[key]["max_abs_oracle_error"] for run in variant_runs),
                    "max_relative_oracle_error": max(run[key]["max_relative_oracle_error"] for run in variant_runs),
                }
            result["candidate_over_baseline"] = result["candidate"]["median_us"] / result["baseline"]["median_us"]
            result["candidate_p90_over_baseline"] = result["candidate"]["median_of_p90_us"] / result["baseline"]["median_of_p90_us"]
            comparisons.append(result)
    index = {(row["affinity"], row["workload"], row["backend"]): row for row in comparisons}
    for row in comparisons:
        control = index.get((row["affinity"], row["workload"], "ReferenceScalar"))
        if control:
            row["scalar_control_ratio"] = control["candidate_over_baseline"]
            row["normalised_ratio"] = row["candidate_over_baseline"] / control["candidate_over_baseline"]
    return {
        "schema": "solarlab.paired-throughput.v1",
        "method": "Same host/compiler, baseline-candidate-candidate-baseline; median of two run medians; eleven samples and warmup per run. Ratios below one mean less elapsed time.",
        "scope": "Hosted CPU throughput and scalar-oracle agreement only. No Galaxy, GPU, frame, energy or thermal acceptance.",
        "measurement_policy": measurement_policy,
        "baseline_sha": (directory / "baseline-sha.txt").read_text().strip(),
        "candidate_sha": (directory / "candidate-sha.txt").read_text().strip(),
        "comparisons": comparisons,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = compare(args.directory)
    (args.directory / "comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = [f"# Paired CPU throughput: {result['measurement_policy']['steps']} step(s)", "", result["method"], "", result["scope"], "",
             f"Baseline: `{result['baseline_sha']}`. Candidate: `{result['candidate_sha']}`.", "",
             "| Affinity | Workload | Backend | Baseline µs | Candidate µs | Change | Scalar control ratio |",
             "|---|---|---|---:|---:|---:|---:|"]
    for row in result["comparisons"]:
        lines.append(f"| {row['affinity']} | {row['workload']} | {row['backend']} | {row['baseline']['median_us']:.3f} | {row['candidate']['median_us']:.3f} | {(row['candidate_over_baseline'] - 1) * 100:+.2f}% | {row.get('scalar_control_ratio', 1):.4f} |")
    text = "\n".join(lines) + "\n"
    (args.directory / "comparison.md").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
