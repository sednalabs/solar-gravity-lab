# Engine workspace

This Rust workspace is the canonical long-lived Solar Gravity Lab core and the
primary implementation line for the runtime + FFI seam.

Kotlin/Android legacy references now live under `../legacy/` and on the
archived branch `legacy/kotlin-main-20260405`.

The workspace boundary strategy is explicit:

- `runtime` is authoritative for mutable state and command semantics.
- `scene` owns the renderer-independent snapshot contract.
- `ffi` owns process-boundary representation (C ABI + JNI bridge) and never owns simulation decisions.
- `domain`, `history`, `physics`, `hardware`, and `data` crates provide stable types, invariants, and policies consumed by `runtime` and projected by `ffi`.

Seam-level behavior expectations:

- Ownership boundary:
  - `runtime` owns `WorldRuntime`, branch trees, command logs, checkpoints, and snapshots.
  - `ffi` owns opaque integer handles and packet handles for exported scene packets.
  - Runtime references should never leak as raw pointers; only IDs and value objects cross boundaries.
- Command/refresh boundary:
  - Commands are applied in one place (`runtime` command application path).
  - Consumers that need latest view state after a command are expected to call refresh/snapshot, not rely on out-of-band mutation side channels.
  - Body spawn commands preserve both `mass_kg` and `source_mass_kg`.
    `mass_kg` is a display/inertial teaching value; `source_mass_kg` is the
    gravitational source used by the authoritative solver.
- Snapshot/render extraction boundary:
  - Snapshot summary types are intentionally compact for shell UX and diagnostics.
  - Render extraction (`render_scene`) is a pure projection into the `scene` contract, then copied/exported through `ffi` packet buffers.
- Handle lifetime boundary:
  - Handle `0` is invalid in this API contract.
  - Session handles: create, use, then destroy.
  - Packet handles: export, read buffer views, then release explicitly.
- FFI ABI boundary:
  - `SOLARLAB_V2_ABI_VERSION` is currently `11`.
  - ABI 10 added explicit spawn-body source mass through
    `SlSessionCommand.body_source_mass_kg`; negative or non-finite values fall
    back to the runtime's class-based default for compatibility.
  - ABI 11 adds the explicit `SL_BODY_CLASS_COMET` domain class and the stable
    renderer-facing `SlSceneBodyKind` field to `SlVulkanBodyInstance`; clients
    must use the packet stride supplied by the ABI view and decode the new
    144-byte body layout.

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
