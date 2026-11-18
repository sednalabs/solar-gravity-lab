#!/usr/bin/env bash
set -euo pipefail
compiler="${1:?pinned glslang executable required}"
headers="${2:?Vulkan header include directory required}"
output="${3:-dist/qcom-offscreen-proof}"
mkdir -p "$output"
output="$(realpath "$output")"
git rev-parse HEAD > "$output/source.txt"
"$compiler" --version > "$output/compiler.txt"
cmake -S render/android-vulkan/probes -B "$output/host" -G Ninja \
  -DGLSLANG_VALIDATOR="$compiler" -DVulkan_INCLUDE_DIR="$headers"
cmake --build "$output/host" --parallel 4
ctest --test-dir "$output/host" --output-on-failure
sha256sum "$output/host/shaders/"*.spv > "$output/spirv-sha256.txt"
icd="$(python3 - <<'PY'
from pathlib import Path
matches = sorted(Path('/usr/share/vulkan/icd.d').glob('*lvp*.json'))
if not matches:
    raise SystemExit('Software Vulkan ICD missing; control execution is required')
print(matches[0])
PY
)"
for mode in control box-control tile-shading image-processing; do
  VK_ICD_FILENAMES="$icd" "$output/host/solarlab-qcom-offscreen-probe" \
    --mode "$mode" --shaders "$output/host/shaders" --require-validation \
    > "$output/$mode.json" 2> "$output/$mode-validation.txt"
  cat "$output/$mode.json"
done
python3 - "$output" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
lines = ['# QCOM offscreen qualification', '',
         'Software Vulkan control execution and shader/host compilation. SKIP never establishes Qualcomm extension execution or Galaxy acceptance.', '',
         '| Mode | Status | Reason |', '|---|---|---|']
for mode in ('control', 'box-control', 'tile-shading', 'image-processing'):
    result = json.loads((root / f'{mode}.json').read_text())
    if mode in ('control', 'box-control') and result['status'] != 'PASS':
        raise SystemExit(f'{mode}: a validated executable control PASS is required: {result}')
    if result['status'] not in ('PASS', 'SKIP'):
        raise SystemExit(f'{mode}: unexpected result: {result}')
    lines.append(f"| {mode} | {result['status']} | {result.get('reason', '')} |")
text = '\n'.join(lines) + '\n'
(root / 'summary.md').write_text(text)
print(text)
PY
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then cat "$output/summary.md" >> "$GITHUB_STEP_SUMMARY"; fi
