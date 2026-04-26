#!/usr/bin/env bash
set -euo pipefail

mkdir -p dist/arm64-isa-proof

python3 - <<'PY'
import json
from pathlib import Path

tokens = set()
cpuinfo_path = Path("/proc/cpuinfo")
if cpuinfo_path.exists():
    for line in cpuinfo_path.read_text().splitlines():
        if ":" not in line:
            continue
        key, values = line.split(":", 1)
        normalized = key.strip().lower()
        if normalized not in {"features", "flags"}:
            continue
        for token in values.split():
            tokens.add(token.strip().lower())

ALIASES = {
    "asimd": "neon",
    "fphp": "fp16",
    "asimdhp": "fp16",
    "asimdfhm": "fhm",
    "asimddp": "dotprod",
    "atomics": "lse",
    "crc32": "crc",
}
normalized_tokens = sorted({ALIASES.get(token, token) for token in tokens})

supports = {
    feature: feature in normalized_tokens
    for feature in (
        "neon",
        "fp",
        "fp16",
        "fhm",
        "dotprod",
        "i8mm",
        "sve",
        "sve2",
        "sme",
        "sme2",
        "lse",
        "lse2",
        "crc",
        "mops",
    )
}

payload = {
    "runner_arch": "arm64",
    "cpuinfo_tokens_count": len(tokens),
    "normalized_tokens": normalized_tokens,
    "capabilities": supports,
    "implemented_solver_paths": {
        "active_when_supported": ["simd.arm64.neon-f64-pairwise"],
        "reported_but_reserved_until_kernel_exists": ["sve", "sve2", "sme", "sme2"],
    },
}

Path("dist/arm64-isa-proof/capabilities.json").write_text(
    json.dumps(payload, indent=2) + "\n"
)
PY

# Run the full physics library suite because it now includes dedicated
# dispatch/activation + scalar-equivalence ISA proof assertions.
cargo test -p solarlab-physics --lib -- --nocapture

# Runtime telemetry should surface solver execution report fields cleanly.
cargo test -p solarlab-runtime --lib telemetry_report

# Minimal summary for CI artifact consumers.
{
  echo "arm64-isa-proof: passed"
  echo "active-arm64-solver-path: simd.arm64.neon-f64-pairwise"
  echo "reserved-arm64-extensions: sve,sve2,sme,sme2"
  echo "physics-tests: solarlab-physics --lib"
  echo "runtime-tests: solarlab-runtime telemetry_report"
} > dist/arm64-isa-proof/summary.txt
