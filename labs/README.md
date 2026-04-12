# Validation labs

The v2 architecture expects subsystem-specific labs rather than one generic CI
lane.

Planned labs:

- conformance lab
- data lab
- render lab
- hardware lab
- client lab
- release lab

Each lab should emit machine-readable evidence and human-readable summaries tied
to the exact commit being validated.

Current runnable lab surface:

- `cargo run -p solarlab-conformance -- --pretty`

The conformance lab emits deterministic JSON for the current scientific-harness
slice:

- `major-body-orbit-telemetry`
- `added-body-repeatability`
- `collision-playback-cap`
- `arm64-kernel-equivalence`
