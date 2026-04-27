#!/usr/bin/env bash
set -euo pipefail

mkdir -p dist/arm64-isa-proof

python3 .github/scripts/check_arm64_capability_catalog_sync.py

python3 .github/scripts/collect_android_capability_census.py \
  --output dist/arm64-isa-proof/capability-census.json \
  --summary-output dist/arm64-isa-proof/capability-census-summary.txt \
  --legacy-capabilities-output dist/arm64-isa-proof/capabilities.json \
  --surface github-hosted-arm64 \
  --device-label github-arm64-runner

# Run the full physics library suite because it now includes dedicated
# dispatch/activation + scalar-equivalence ISA proof assertions.
cargo test -p solarlab-physics --lib -- --nocapture

# Runtime telemetry should surface solver execution report fields cleanly.
cargo test -p solarlab-runtime --lib telemetry_report

# Minimal summary for CI artifact consumers.
{
  echo "arm64-isa-proof: passed"
  echo "implemented-arm64-solver-paths: simd.arm64.neon-f64-pairwise,simd.arm64.neon-f64-tiled-pairwise"
  echo "large-scene-tiled-threshold-bodies: 96"
  echo "reserved-arm64-extensions: sve,sve2,sve-i8mm,sme,sme2,dotprod,i8mm,bf16,fp16,fhm,rdm,fcma"
  echo "capability-census: dist/arm64-isa-proof/capability-census.json"
  echo "physics-tests: solarlab-physics --lib"
  echo "runtime-tests: solarlab-runtime telemetry_report"
} > dist/arm64-isa-proof/summary.txt
