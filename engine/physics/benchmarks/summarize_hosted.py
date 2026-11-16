#!/usr/bin/env python3
"""Reproduce a readable table from preserved hosted throughput evidence."""
import argparse
import json
from pathlib import Path


def render(data):
    lines = [
        f"# Hosted CPU measurements — run {data['run_id']}",
        "",
        f"[Validation run]({data['run_url']}); source `{data['source_sha']}`.",
        "",
        "Microseconds per complete working-copy + 64-step authoritative advance + "
        "invariant operation. Each backend uses two warmups, batches calibrated to "
        "at least 20 ms, and 11 measured samples. Speedup is same-host scalar median "
        "divided by selected-path median; values below 1 indicate a slowdown.",
        "",
        data["scope"],
    ]
    for arch, host in data["hosts"].items():
        cpu = {item["field"]: item["data"] for item in host["cpu"]["lscpu"]}
        environment = host["full"]["environment"]
        rows = host["full"]["measurements"]
        single = {
            row["workload"]: row for row in host.get("one_cpu", {}).get("measurements", [])
            if row["requested"] != "ReferenceScalar"
        }
        lines += [
            "", f"## {arch}: {cpu['Model name:']}", "",
            f"Full affinity exposes {environment['available_workers']} workers. "
            f"Artifact `{host['artifact_id']}`; archive SHA-256 "
            f"`{host['archive_sha256']}`.", "",
            "| Workload | Bodies / sources | Useful pairs | Scalar median µs | "
            "Selected median / p90 µs | Selected workers | Speedup | One-CPU selected median µs |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for row in rows:
            if row["requested"] == "ReferenceScalar":
                continue
            scalar = next(item for item in rows if item["workload"] == row["workload"]
                          and item["requested"] == "ReferenceScalar")
            n, s = row["bodies"], row["sources"]
            pinned = single.get(row["workload"])
            pinned_text = f"{pinned['median_us']:,.3f}" if pinned else "—"
            lines.append(
                f"| {row['workload']} | {n} / {s} | {s*n-s*(s+1)//2:,} | "
                f"{scalar['median_us']:,.3f} | {row['median_us']:,.3f} / "
                f"{row['p90_us']:,.3f} | {row['active_workers']} | "
                f"{scalar['median_us']/row['median_us']:.3f}× | {pinned_text} |"
            )
        lines += ["", "Actual full-affinity paths:", ""]
        for row in rows:
            if row["requested"] != "ReferenceScalar":
                lines.append(f"- {row['workload']}: `{row['path']}`.")
        max_error = max(row["max_abs_oracle_error"] for row in rows)
        max_relative = max(row["max_relative_oracle_error"] for row in rows)
        lines += ["", f"Largest full-affinity scalar-oracle errors: "
                  f"absolute component `{max_error:.12g}`, relative `{max_relative:.12g}`. "
                  "Absolute components include both metres and metres per second."]
        if "one_cpu" in host:
            lines += ["", f"One-CPU affinity: `{host['one_cpu']['affinity']['cpus']}`. "
                      "The preserved JSON includes its separate scalar measurements, "
                      "actual selected paths, complete feature list and errors. "
                      f"Both runs use binary SHA-256 `{host['shared_binary_sha256']}`."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    print(render(json.loads(args.evidence.read_text())), end="")
