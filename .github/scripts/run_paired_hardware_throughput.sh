#!/usr/bin/env bash
set -euo pipefail

# Same native host, no privileged frequency/thermal/placement changes.
baseline_sha="${1:?immutable baseline commit required}"
output="${2:-dist/hardware-throughput}"
candidate_root="$(pwd)"
mkdir -p "$output"
output="$(realpath "$output")"
workspace="$(mktemp -d "${RUNNER_TEMP:-/tmp}/solarlab-paired-throughput.XXXXXX")"
cleanup() {
  git -C "$candidate_root" worktree remove --force "$workspace/baseline" 2>/dev/null || true
  rm -rf "$workspace"
}
trap cleanup EXIT
git rev-parse HEAD > "$output/candidate-sha.txt"
git rev-parse "${baseline_sha}^{commit}" > "$output/baseline-sha.txt"
git worktree add --detach "$workspace/baseline" "$baseline_sha"
rustc -Vv > "$output/compiler.txt"
lscpu --json > "$output/cpu.json"
python3 - <<'PY' > "$output/affinity.json"
import json, os
print(json.dumps({"allowed_cpus": sorted(os.sched_getaffinity(0)), "surface": "hosted-cpu"}))
PY
for source_root in "$workspace/baseline" "$candidate_root"; do
  cargo generate-lockfile --manifest-path "$source_root/Cargo.toml"
done
cp "$workspace/baseline/Cargo.lock" "$output/baseline-Cargo.lock"
cp "$candidate_root/Cargo.lock" "$output/candidate-Cargo.lock"
if ! cmp -s "$output/baseline-Cargo.lock" "$output/candidate-Cargo.lock"; then
  echo "Baseline and candidate resolved different dependencies; review before attributing timing to the kernel." >&2
  exit 1
fi
for variant in baseline candidate; do
  source_root="$candidate_root"
  if [[ "$variant" == baseline ]]; then source_root="$workspace/baseline"; fi
  CARGO_TARGET_DIR="$workspace/build-$variant" cargo build --locked --release \
    --manifest-path "$source_root/Cargo.toml" -p solarlab-physics --example throughput
  cp "$workspace/build-$variant/release/examples/throughput" "$workspace/$variant-throughput"
  sha256sum "$workspace/$variant-throughput" > "$output/$variant-binary.sha256"
done
workloads=canonical-mixed-365,sparse-1024,lab-3,lab-probe-4,dense-128,dense-192,dense-256
single_cpu="$(python3 -c 'import os; print(min(os.sched_getaffinity(0)))')"
for affinity in full one-cpu; do
  sequence=(baseline candidate candidate baseline)
  baseline_round=0
  candidate_round=0
  for variant in "${sequence[@]}"; do
    if [[ "$variant" == baseline ]]; then
      round="$baseline_round"; baseline_round=$((baseline_round + 1))
    else
      round="$candidate_round"; candidate_round=$((candidate_round + 1))
    fi
    command=("$workspace/$variant-throughput" --samples 11 --steps 64 --workloads "$workloads")
    if [[ "$affinity" == one-cpu ]]; then command=(taskset -c "$single_cpu" "${command[@]}"); fi
    "${command[@]}" | tee "$output/$affinity-$variant-$round.ndjson"
  done
done
python3 .github/scripts/compare_hardware_throughput.py "$output"
