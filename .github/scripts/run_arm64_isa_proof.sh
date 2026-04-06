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

supports = {
    "neon": any(t in tokens for t in ("asimd", "neon")),
    "fma": any(t in tokens for t in ("fma", "fhm", "asimdfhm")),
    "sve2": "sve2" in tokens,
    "sme": ("sme" in tokens) or ("sme2" in tokens),
}

payload = {
    "runner_arch": "arm64",
    "cpuinfo_tokens_count": len(tokens),
    "capabilities": supports,
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
  echo "physics-tests: solarlab-physics --lib"
  echo "runtime-tests: solarlab-runtime telemetry_report"
} > dist/arm64-isa-proof/summary.txt
