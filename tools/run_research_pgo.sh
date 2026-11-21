#!/usr/bin/env bash
# Host-only PGO experiment. Architecture, compiler and training must match;
# never install this host profile into an Android build.
set -euo pipefail
output="${1:-dist/research-pgo}"
mkdir -p "$output"
output="$(realpath "$output")"
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "Commit the source first so the PGO result has immutable provenance." >&2
  exit 1
fi
compiler_host="$(rustc -vV | sed -n 's/^host: //p')"
profdata="$(rustc --print sysroot)/lib/rustlib/${compiler_host}/bin/llvm-profdata"
if [[ ! -x "$profdata" ]]; then
  echo "Matching llvm-profdata unavailable. Install rustup component llvm-tools-preview." >&2
  exit 1
fi
git rev-parse HEAD > "$output/source.txt"
rustc -vV > "$output/compiler.txt"
mkdir -p "$output/raw"
CARGO_TARGET_DIR="$output/instrumented" RUSTFLAGS="${RUSTFLAGS:-} -Cprofile-generate=$output/raw" \
  cargo build --release -p solarlab-benchmarks --example research
for fixture in sparse3 canonical15 dense192 dense256; do
  for steps in 1 8 64; do
    LLVM_PROFILE_FILE="$output/raw/%m-%p.profraw" \
      "$output/instrumented/release/examples/research" --case production-selected \
      --fixture "$fixture" --steps "$steps" --repeats 11 >> "$output/training.ndjson"
  done
done
"$profdata" merge -o "$output/merged.profdata" "$output/raw/"*.profraw
CARGO_TARGET_DIR="$output/baseline" \
  cargo build --release -p solarlab-benchmarks --example research
CARGO_TARGET_DIR="$output/candidate" RUSTFLAGS="${RUSTFLAGS:-} -Cprofile-use=$output/merged.profdata -Cllvm-args=-pgo-warn-missing-function" \
  cargo build --release -p solarlab-benchmarks --example research
sha256sum "$output/baseline/release/examples/research" "$output/candidate/release/examples/research" \
  "$output/merged.profdata" > "$output/sha256.txt"
for fixture in sparse3 canonical15 dense192 dense256; do
  for steps in 1 8 64; do
    for block in 1 2 3 4 5; do
      for role in A1 B1 B2 A2; do
        variant=baseline
        if [[ "$role" == B* ]]; then variant=candidate; fi
        result="$output/${fixture}-${steps}-${block}-${role}.json"
        "$output/$variant/release/examples/research" --case production-selected \
          --fixture "$fixture" --steps "$steps" --repeats 11 > "$result"
        python3 - "$result" "$variant" "$block" "$role" <<'PY' >> "$output/paired.ndjson"
import json, sys
from pathlib import Path
record = json.loads(Path(sys.argv[1]).read_text())
record.update(compiler_variant=sys.argv[2], block=int(sys.argv[3]), order=sys.argv[4],
              scope="host-only; not an Android or S25 result")
print(json.dumps(record, allow_nan=False))
if record.get("status") != "PASS":
    raise SystemExit("PGO qualification failed correctness")
PY
      done
    done
  done
done
printf 'PGO experiment retained at %s\n' "$output"
