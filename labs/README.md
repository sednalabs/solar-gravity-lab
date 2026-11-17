# Validation labs

The v2 architecture expects subsystem-specific labs rather than one generic CI
lane.

Architecture-level lab families (not all are separate runnable crates today):

- conformance lab
- data lab
- render lab
- hardware lab
- client lab
- release lab

Each lab should emit machine-readable evidence and human-readable summaries tied
to the exact commit being validated.

Current independently runnable conformance surface:

- `cargo run -p solarlab-conformance -- --pretty`

Additional Android runtime/native rendering, ARM64 ISA, CI security and
artifact-provenance checks run through
[`validation-lab`](../docs/validation-lab.md) and the
[prerelease workflow](../docs/release-channels.md); their success must
be tied to the tested commit, not inferred from this local command.

The conformance lab emits deterministic JSON for the current scientific-harness
slice:

- `major-body-orbit-telemetry`
- `added-body-repeatability`
- `collision-playback-cap`
- `arm64-kernel-equivalence`
- `physics-accuracy-telemetry`
- `one-year-earth-orbit-stability`
- `host-relative-playback-policy`
