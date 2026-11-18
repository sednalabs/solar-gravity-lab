#!/usr/bin/env python3
"""Confirm dense parallel timing at immutable sources; timings require manual review."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import statistics
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

from compare_hardware_throughput import compare, read_measurements

BASELINE = "54b8d2036b9a03cc4b9587be0b86113db5bbbe6a"
CANDIDATE = "dc5b1084cadf97a1f152147a0062704c3ca7dc40"
WORKLOADS = {"dense-192", "dense-256"}
SEGMENTS = (16, 64)
AFFINITIES = ("full", "one-cpu")
BLOCKS = 5


def command(args: list[str], cwd: Path, *, env: dict | None = None, capture: bool = False) -> str:
    result = subprocess.run(args, cwd=cwd, env=env, text=True, check=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else ""


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, indent=2) + "\n")


def validate_block(directory: Path, steps: int, block: int, workers: int) -> dict:
    expected_files = {f"{affinity}-{variant}-{repeat}.ndjson"
                      for affinity in AFFINITIES
                      for variant in ("baseline", "candidate") for repeat in (0, 1)}
    if {path.name for path in directory.glob("*.ndjson")} != expected_files:
        raise ValueError(f"{directory}: incomplete or extra repeat files")
    for affinity in AFFINITIES:
        expected_workers = 1 if affinity == "one-cpu" else workers
        for variant in ("baseline", "candidate"):
            for repeat in (0, 1):
                env, rows = read_measurements(directory / f"{affinity}-{variant}-{repeat}.ndjson")
                if (env["arch"] != "aarch64" or env["steps"] != steps
                        or env["available_workers"] != expected_workers or env["samples"] != 11):
                    raise ValueError("Unexpected native architecture, cohort, affinity or sample count")
                for (workload, backend), row in rows.items():
                    size = int(workload.split("-")[1])
                    if row["bodies"] != size or row["sources"] != size:
                        raise ValueError("Dense fixture body/source identity changed")
                    if not isinstance(row["repeats"], int) or isinstance(row["repeats"], bool) or row["repeats"] < 1:
                        raise ValueError("Invalid calibrated repeat count")
                    if not 0 < row["min_us"] <= row["median_us"]:
                        raise ValueError("Invalid minimum elapsed time")
                    if backend == "SimdArm64" and workload == "dense-256" and affinity == "full":
                        if row["active_workers"] <= 1 or "parallel" not in row["path"]:
                            raise ValueError("Dense parallel target did not execute multiple workers")
    result = compare(directory, workloads=WORKLOADS)
    if result["baseline_sha"] != BASELINE or result["candidate_sha"] != CANDIDATE:
        raise ValueError("Unexpected immutable source identity")
    result["block"] = block
    for row in result["comparisons"]:
        if (row["baseline"]["paths"] != row["candidate"]["paths"]
                or row["baseline"]["active_workers"] != row["candidate"]["active_workers"]):
            raise ValueError("Baseline and candidate executed different dispatch or worker counts")
        row["steps"] = steps
        row["block"] = block
        for variant in ("baseline", "candidate"):
            early, late = row[variant]["run_medians_us"]
            row[variant]["within_block_drift_percent"] = 100 * (late / early - 1)
    write_json(directory / "comparison.json", result)
    return result


def summarise(output: Path, blocks: list[dict], manifest: list[dict]) -> None:
    expected_blocks = {(steps, block) for steps in SEGMENTS for block in range(BLOCKS)}
    actual_blocks = {(item["measurement_policy"]["steps"], item["block"]) for item in blocks}
    if actual_blocks != expected_blocks or len(blocks) != len(expected_blocks):
        raise ValueError("Incomplete or duplicated five-block/cohort matrix")
    expected_runs = {(steps, block, affinity, variant, repeat)
                     for steps in SEGMENTS for block in range(BLOCKS)
                     for affinity in AFFINITIES for variant in ("baseline", "candidate")
                     for repeat in (0, 1)}
    actual_runs = {(item["steps"], item["block"], item["affinity"], item["variant"], item["repeat"])
                   for item in manifest}
    if actual_runs != expected_runs or len(manifest) != len(expected_runs):
        raise ValueError("Incomplete or duplicated raw execution matrix")
    rows = [row for block in blocks for row in block["comparisons"]]
    groups = {}
    for row in rows:
        key = (row["steps"], row["affinity"], row["workload"], row["backend"])
        groups.setdefault(key, []).append(row)
    summary = []
    for (steps, affinity, workload, backend), group in sorted(groups.items()):
        if sorted(row["block"] for row in group) != list(range(BLOCKS)):
            raise ValueError("Incomplete per-workload five-block matrix")
        ratios = [row["candidate_over_baseline"] for row in group]
        p90_ratios = [row["candidate_p90_over_baseline"] for row in group]
        summary.append({
            "steps": steps, "affinity": affinity, "workload": workload, "backend": backend,
            "median_block_ratio": statistics.median(ratios),
            "block_ratios": ratios, "min_block_ratio": min(ratios), "max_block_ratio": max(ratios),
            "blocks_slower": sum(ratio > 1 for ratio in ratios),
            "median_block_p90_ratio": statistics.median(p90_ratios),
            "block_p90_ratios": p90_ratios,
            "scalar_control_ratios": [row["scalar_control_ratio"] for row in group],
            "baseline_drift_percent": [row["baseline"]["within_block_drift_percent"] for row in group],
            "candidate_drift_percent": [row["candidate"]["within_block_drift_percent"] for row in group],
        })
    result = {
        "schema": "solarlab.dense-confirmation.v1",
        "baseline_sha": BASELINE, "candidate_sha": CANDIDATE,
        "method": "Five repeated ABBA blocks per affinity and segment length; same two binaries; 11 warmed samples and calibrated >=20ms batches per execution.",
        "acceptance": "Structural, native-dispatch, affinity and scalar-oracle contracts checked. Timing judgement remains manual; no timing PASS/FAIL or statistical upper bound is inferred.",
        "scope": "Hosted Arm CPU throughput only; no Oryon, frame, energy or thermal acceptance.",
        "raw_executions": len(manifest), "summary": summary, "blocks": blocks,
    }
    write_json(output / "confirmation.json", result)
    lines = [
        "# Dense parallel confirmation", "", result["method"], "", result["acceptance"], "",
        f"Baseline: {BASELINE}. Candidate: {CANDIDATE}.", "",
        "Summary changes are medians of five block ratios; the raw ratio range and all individual block medians/p90s remain in the JSON.", "",
        "| Steps | Affinity | Workload | Backend | Median change | Block range | Slower blocks | Median p90 change |",
        "|---:|---|---|---|---:|---:|---:|---:|",
    ]
    for row in summary:
        lines.append(
            f"| {row['steps']} | {row['affinity']} | {row['workload']} | {row['backend']} | "
            f"{100 * (row['median_block_ratio'] - 1):+.3f}% | "
            f"{100 * (row['min_block_ratio'] - 1):+.3f} to {100 * (row['max_block_ratio'] - 1):+.3f}% | "
            f"{row['blocks_slower']}/{BLOCKS} | {100 * (row['median_block_p90_ratio'] - 1):+.3f}% |"
        )
    lines += ["", "## Individual native blocks", "",
              "| Steps | Affinity | Block | Workload | Baseline us | Candidate us | Change | p90 change | Scalar control | Baseline drift |",
              "|---:|---|---:|---|---:|---:|---:|---:|---:|---:|"]
    for row in rows:
        if row["backend"] != "SimdArm64":
            continue
        lines.append(
            f"| {row['steps']} | {row['affinity']} | {row['block']} | {row['workload']} | "
            f"{row['baseline']['median_us']:.3f} | {row['candidate']['median_us']:.3f} | "
            f"{100 * (row['candidate_over_baseline'] - 1):+.3f}% | "
            f"{100 * (row['candidate_p90_over_baseline'] - 1):+.3f}% | "
            f"{100 * (row['scalar_control_ratio'] - 1):+.3f}% | "
            f"{row['baseline']['within_block_drift_percent']:+.3f}% |"
        )
    report = "\n".join(lines) + "\n"
    (output / "confirmation.md").write_text(report)
    print(report, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    candidate = args.candidate_dir.resolve()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Use a fresh output directory; do not mix experiments")
    output.mkdir(parents=True, exist_ok=True)
    if command(["git", "rev-parse", "HEAD"], candidate, capture=True).strip() != CANDIDATE:
        raise ValueError("Candidate checkout is not the immutable final source")
    allowed = sorted(os.sched_getaffinity(0))
    if len(allowed) < 2:
        raise ValueError("Confirmation requires a multi-CPU allowed affinity")
    protocol_root = Path(__file__).resolve().parents[2]
    (output / "protocol-sha.txt").write_text(command(["git", "rev-parse", "HEAD"], protocol_root, capture=True))
    (output / "candidate-sha.txt").write_text(CANDIDATE + "\n")
    (output / "baseline-sha.txt").write_text(BASELINE + "\n")
    (output / "compiler.txt").write_text(command(["rustc", "-Vv"], candidate, capture=True))
    (output / "cpu.json").write_text(command(["lscpu", "--json"], candidate, capture=True))
    write_json(output / "affinity.json", {"allowed_cpus": allowed, "single_cpu": allowed[0]})
    write_json(output / "build-environment.json", {
        name: os.environ.get(name) for name in
        ("RUSTFLAGS", "CARGO_BUILD_TARGET", "CARGO_PROFILE_RELEASE_LTO", "CARGO_PROFILE_RELEASE_CODEGEN_UNITS")
    })
    manifest = []
    blocks = []
    with tempfile.TemporaryDirectory(prefix="solarlab-dense-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        workspace = Path(temporary)
        baseline = workspace / "baseline"
        command(["git", "worktree", "add", "--detach", str(baseline), BASELINE], candidate)
        try:
            roots = {"baseline": baseline, "candidate": candidate}
            for variant, root in roots.items():
                command(["cargo", "generate-lockfile", "--manifest-path", str(root / "Cargo.toml")], root)
                (output / f"{variant}-Cargo.lock").write_bytes((root / "Cargo.lock").read_bytes())
            if (output / "baseline-Cargo.lock").read_bytes() != (output / "candidate-Cargo.lock").read_bytes():
                raise ValueError("Dependency resolutions differ; cannot attribute timing to source")
            binaries = {}
            binary_metadata = {}
            for variant, root in roots.items():
                target = workspace / f"build-{variant}"
                environment = dict(os.environ, CARGO_TARGET_DIR=str(target))
                command(["cargo", "build", "--locked", "--release", "--manifest-path",
                         str(root / "Cargo.toml"), "-p", "solarlab-physics", "--example", "throughput"],
                        root, env=environment)
                binary = target / "release/examples/throughput"
                binaries[variant] = binary
                binary_metadata[variant] = {"sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                                            "size_bytes": binary.stat().st_size}
            write_json(output / "binaries.json", binary_metadata)
            for steps in SEGMENTS:
                for block in range(BLOCKS):
                    directory = output / f"steps-{steps}" / f"block-{block}"
                    directory.mkdir(parents=True)
                    (directory / "baseline-sha.txt").write_text(BASELINE + "\n")
                    (directory / "candidate-sha.txt").write_text(CANDIDATE + "\n")
                    for affinity in AFFINITIES:
                        repeat_counts = {"baseline": 0, "candidate": 0}
                        for variant in ("baseline", "candidate", "candidate", "baseline"):
                            repeat = repeat_counts[variant]
                            repeat_counts[variant] += 1
                            path = directory / f"{affinity}-{variant}-{repeat}.ndjson"
                            argv = [str(binaries[variant]), "--samples", "11", "--steps", str(steps),
                                    "--workloads", ",".join(sorted(WORKLOADS))]
                            if affinity == "one-cpu":
                                argv = ["taskset", "-c", str(allowed[0]), *argv]
                            started = datetime.now(timezone.utc).isoformat()
                            timer = time.monotonic()
                            raw = command(argv, candidate, capture=True)
                            elapsed = time.monotonic() - timer
                            path.write_text(raw)
                            record = {"steps": steps, "block": block, "affinity": affinity,
                                      "variant": variant, "repeat": repeat, "started_utc": started,
                                      "elapsed_seconds": elapsed, "file": str(path.relative_to(output))}
                            manifest.append(record)
                            with (output / "run-order.ndjson").open("a") as stream:
                                stream.write(json.dumps(record) + "\n")
                            print(json.dumps({"kind": "execution", **record}), flush=True)
                            print(raw, end="" if raw.endswith("\n") else "\n", flush=True)
                    blocks.append(validate_block(directory, steps, block, len(allowed)))
            summarise(output, blocks, manifest)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(baseline)],
                           cwd=candidate, check=False)


if __name__ == "__main__":
    main()
