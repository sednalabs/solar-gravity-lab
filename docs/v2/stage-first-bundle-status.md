# Stage-first delivery status

The recovery sequence has converged into one product architecture:

- one Rust-authoritative simulation world;
- one native Vulkan stage;
- a multiscale orbital camera with camera-relative packets;
- native picking, tracer LOD, compute compaction, and pipeline caching; and
- a thin Kotlin lifecycle, control, and accessibility shell.

The old split-world parity milestone is closed. Current work should extend Rust
world commands or stable scene metadata, not add managed simulation behavior.

Use `validation-lab` with `android_validation_mode=stage-first-runtime`, then
install the reusable exact-head interactive artifact for native visual
acceptance.
