#!/usr/bin/env bash
# The APK embeds these outputs. Keep the compiler identity and byte hashes with
# the build artifact; extension compilation does not assert driver support.
set -euo pipefail
compiler="${1:?glslang executable required}"
output="${2:?output shader directory required}"
compiler_source="${3:?qualified glslang source checkout required}"
# Public Khronos Git commit, not a credential. This is an immutable toolchain pin.
expected_compiler_commit=e1b562a8bed273a02f30b59b66a5d499793cede5
actual_compiler_commit="$(git -C "$compiler_source" rev-parse HEAD)"
if [[ "$actual_compiler_commit" != "$expected_compiler_commit" ]]; then
  echo "Unexpected glslang source revision: $actual_compiler_commit" >&2
  exit 1
fi
mkdir -p "$output"
"$compiler" --version > "$output/compiler.txt"
printf '%s\n' "$actual_compiler_commit" > "$output/compiler-source.txt"
git rev-parse HEAD > "$output/source.txt"
for shader in invert_control.comp tile_invert.comp box_control.comp box_filter.comp; do
  "$compiler" -V --target-env vulkan1.3 \
    "render/android-vulkan/probes/shaders/$shader" -o "$output/$shader.spv"
done
python3 - "$output" "$compiler" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
files = sorted(root.glob("*.comp.spv"))
if len(files) != 4:
    raise SystemExit("All four QCOM/control shaders must be present")
manifest = {
    "schema_version": 1,
    "source_commit": (root / "source.txt").read_text().strip(),
    "compiler_commit": (root / "compiler-source.txt").read_text().strip(),
    "compiler_binary_sha256": hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest(),
    "compiler_version": (root / "compiler.txt").read_text().strip(),
    "shaders": [{"path": p.name, "bytes": p.stat().st_size,
                 "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files],
}
(root / "shader-provenance.json").write_text(json.dumps(manifest, indent=2) + "\n")
PY
