# Solar Gravity Lab v2

This branch is the clean-room `v2` architecture reset for Solar Gravity Lab.

The long-term product shape is:

- a Rust-owned authoritative simulation/runtime core
- native client shells over that core
- offline-first scientific data with signed live updates
- a renderer-independent scene contract with backend adapters
- optional hardware fast paths behind open, portable interfaces

The existing Kotlin/Android/Vulkan code remains in this branch only as legacy
reference material while the v2 platform is built out.

## Status

What is real on this branch today:

- an ADR-backed v2 architecture record in [`docs/adr`](docs/adr)
- a versioned protobuf schema surface in [`proto/solarlab/v2`](proto/solarlab/v2)
- a Rust workspace in [`engine`](engine) with the canonical long-lived module
  boundaries
- an explicit FFI contract starting point for native client shells
- placeholder directories for future Android, data, service, and lab surfaces

What is intentionally still transitional:

- the existing `app`, `core-*`, `feature-lab`, and `render-core` modules are
  still present as v1 reference code
- the new Android shell under `clients/android` is not implemented yet
- the new render backend adapter stack is designed but not implemented yet
- the offline update services are designed but not implemented yet

## Repository layout

- [`engine/`](engine)
  Rust workspace for the new core platform.
- [`proto/`](proto)
  Versioned cross-language schemas for packages, runtime, diagnostics, and
  render-scene contracts.
- [`clients/android/`](clients/android)
  The future Android Compose shell over the Rust core.
- [`data/`](data)
  Data-pack, manifest, validation, and provenance model documentation.
- [`services/`](services)
  Optional update and content-distribution services.
- [`labs/`](labs)
  Conformance, data, render, hardware, and client validation harnesses.
- [`legacy/`](legacy)
  Documentation for the retained v1 code still present in this branch.

## Architecture reading order

1. [`docs/adr/0001-rust-owned-core.md`](docs/adr/0001-rust-owned-core.md)
2. [`docs/adr/0002-versioned-protobuf-contracts.md`](docs/adr/0002-versioned-protobuf-contracts.md)
3. [`docs/adr/0003-c-abi-and-opaque-handles.md`](docs/adr/0003-c-abi-and-opaque-handles.md)
4. [`docs/adr/0004-offline-first-data-and-updates.md`](docs/adr/0004-offline-first-data-and-updates.md)
5. [`docs/adr/0005-render-scene-and-backend-adapters.md`](docs/adr/0005-render-scene-and-backend-adapters.md)
6. [`docs/v2/architecture.md`](docs/v2/architecture.md)
7. [`docs/v2/roadmap.md`](docs/v2/roadmap.md)

## Validation

The v2 foundational validation target is currently the Rust workspace:

```bash
cargo test --workspace
```

This branch is intentionally not yet using the old Android validation surface as
its primary proof mechanism. Android, render, and data/update validation labs
will be rebuilt around the v2 boundaries as those subsystems land.
