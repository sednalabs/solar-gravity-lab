# Engine workspace

This Rust workspace is the canonical long-lived Solar Gravity Lab core.

Crates:

- `domain`
  Stable scientific and product concepts.
- `physics`
  Integrator, force-model, solver, and collision policy surfaces.
- `history`
  Command-log and checkpoint descriptors.
- `scene`
  Renderer-independent extracted scene contracts.
- `hardware`
  Runtime capability probing and backend selection contracts.
- `runtime`
  World lifecycle, commands, checkpoints, replay, and emitted snapshots/events.
- `ffi`
  Stable C ABI boundary for native client shells.

This workspace is intentionally light on implementation today. The important
thing at this stage is that the stable architectural seams exist in code,
compile together, and can accumulate real implementation without rewriting the
entire product shape again.
