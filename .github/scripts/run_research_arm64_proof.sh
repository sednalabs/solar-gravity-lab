#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "Research ISA proof requires a native AArch64 runner; a skipped case is not proof." >&2
  exit 1
fi

proof_dir="dist/research-arm64-proof"
mkdir -p "${proof_dir}"
git rev-parse HEAD > "${proof_dir}/source.txt"
rustc -vV > "${proof_dir}/rustc.txt"

# Check the raw reciprocal exponent/domain boundaries using the same release
# optimisation settings as the research executable, then inspect that executable.
cargo test --release -p solarlab-benchmarks --lib neon::tests -- --nocapture \
  2>&1 | tee "${proof_dir}/domain-tests.txt"
cargo build --release -p solarlab-benchmarks --example research
research_binary="$(cargo metadata --no-deps --format-version=1 | python3 -c 'import json, sys; from pathlib import Path; print(Path(json.load(sys.stdin)["target_directory"]) / "release/examples/research")')"
objdump -d -C "${research_binary}" > "${proof_dir}/research-disassembly.txt"

python3 - "${research_binary}" "${proof_dir}" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

binary, destination = Path(sys.argv[1]), Path(sys.argv[2])
source = (destination / "source.txt").read_text().strip()
assembly = (destination / "research-disassembly.txt").read_text()
functions = {}
current = None
for line in assembly.splitlines():
    header = re.match(r"^[0-9a-fA-F]+ <(.+)>:$", line)
    if header:
        current = header.group(1) if "solarlab_benchmarks::neon::" in header.group(1) else None
    elif current is not None:
        functions.setdefault(current, []).append(line)
patterns = {
    "frsqrte_fp64": r"\bfrsqrte\s+v\d+\.2d,\s*v\d+\.2d\b",
    "frsqrts_fp64": r"\bfrsqrts\s+v\d+\.2d,\s*v\d+\.2d,\s*v\d+\.2d\b",
    "sqrt_control_fp64": r"\bfsqrt\s+v\d+\.2d,\s*v\d+\.2d\b",
    "divide_control_fp64": r"\bfdiv\s+v\d+\.2d,\s*v\d+\.2d,\s*v\d+\.2d\b",
}
instruction_functions = {}
for name, lines in functions.items():
    text = "\n".join(lines).lower()
    counts = {key: len(re.findall(pattern, text)) for key, pattern in patterns.items()}
    if any(counts.values()):
        instruction_functions[name] = counts
totals = {key: sum(counts[key] for counts in instruction_functions.values()) for key in patterns}
assert all(totals.values()), f"Release NEON functions lack required FP64 instructions: {totals}"

catalogue = json.loads(subprocess.check_output([str(binary), "--catalogue"], text=True))
by_id = {case["id"]: case for case in catalogue["cases"]}
case_ids = ["neon-block-4", "neon-rsqrt-refine-2", "neon-rsqrt-refine-3"]
for case_id in case_ids:
    assert by_id[case_id]["available"], f"ISA proof cannot accept unsupported {case_id}"
fixtures = by_id["neon-block-4"]["fixtures"]
assert fixtures, "No baseline fixtures admitted"
for case_id in case_ids[1:]:
    assert by_id[case_id]["fixtures"] == fixtures, f"Unmatched fixture coverage for {case_id}"
    assert by_id[case_id]["baseline_case_id"] == "neon-block-4"
    assert by_id[case_id]["comparison_kind"] == "accuracy_cost_experiment"

steps = [1, 2, 4, 8, 16, 32, 64]
max_errors = dict.fromkeys(case_ids, 0.0)
count = 0
with (destination / "cases.ndjson").open("w") as output:
    for case_id in case_ids:
        for fixture in fixtures:
            for step_count in steps:
                command = [str(binary), "--case", case_id, "--fixture", fixture,
                           "--steps", str(step_count), "--repeats", "3"]
                completed = subprocess.run(command, capture_output=True, text=True, check=False)
                result = json.loads(completed.stdout)
                # Retain a failed result before rejecting qualification.
                output.write(json.dumps({"source": source, "result": result}) + "\n")
                output.flush()
                assert completed.returncode == 0 and result["status"] == "PASS", result
                assert result["actual_kernel"] == f"research.arm64.fp64.{case_id}", result
                assert result["correctness_checked"] and result["accuracy_pass"], result
                assert result["repeat_consistent"] and len(set(result["repeat_checksums"])) == 1, result
                assert result["reference"] == "production FP64 scalar at identical fixed steps", result
                if case_id != "neon-block-4":
                    config = result["reciprocal_sqrt_configuration"]
                    assert config["refinement_iterations"] == int(case_id.rsplit("-", 1)[1]), result
                    assert config["scalar_estimate_fallback"] is False, result
                    assert config["estimate_instruction"] == "FRSQRTE .2D", result
                    assert config["refinement_instruction"] == "FRSQRTS .2D", result
                error = result["error"]["scaled_state_error"]
                max_errors[case_id] = max(max_errors[case_id], error)
                count += 1

summary = {
    "schema_version": 1, "source": source, "status": "PASS",
    "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
    "instruction_proof_scope": "FP64 instruction presence in NEON functions of the exact executed release binary",
    "instructions": totals, "instruction_functions": instruction_functions,
    "case_ids": case_ids, "fixtures": fixtures, "steps": steps, "repeats": 3,
    "passed_case_fixture_step_combinations": count,
    "max_scaled_state_errors": max_errors,
    "acceptance_scope": "native Arm64 implementation and numerical evidence; no S25 performance or energy claim",
}
(destination / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
PY
