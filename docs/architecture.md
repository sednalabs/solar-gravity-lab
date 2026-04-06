# Architecture

Solar Gravity Lab `main` is the Rust-owned platform line.

The canonical product path on this branch is:

- `engine/` for authoritative runtime, history, scene extraction, data, and hardware crates
- `proto/solarlab/v2/` for versioned cross-language contracts
- `engine/ffi/` for the C ABI and opaque-handle boundary
- `clients/android/` for the Android shell over the Rust runtime
- `render/vulkan-adapter/` for the first backend adapter crate

The root-level Kotlin modules (`app`, `core-*`, `feature-lab`, `render-core`) are
retained only as legacy/reference material. They are no longer the canonical
architecture or validation target on this branch.

## Canonical boundaries

### Runtime ownership

`engine/runtime` owns:

- session and branch state
- command application
- checkpoints and history records
- snapshot publication
- scene extraction inputs

All external integrations should treat the runtime as the single source of truth
for mutable world semantics.

### Contract ownership

`proto/solarlab/v2` defines the versioned schema surface for:

- runtime/session contracts
- diagnostics and hardware reporting
- render-scene export
- data/update package contracts

`engine/ffi` maps those runtime concepts into a stable C ABI using:

- opaque session handles
- explicit render-packet handles
- borrowed buffer views
- explicit release calls for exported packet memory

### Dual-GPU policy ownership

When `OpenCL` is selected on canonical main, the intended split is explicit:

- `Vulkan` owns realtime rendering and in-frame packet compaction work.
- `OpenCL` owns long-horizon tracer integration and forecast/path sampling assists.
- CPU remains authoritative for checkpoint publication and canonical world state.

Interop is policy-driven, not implicit. The runtime/hardware surface should expose
the active workload map plus an explicit error-budget policy so clients and
telemetry can report what is actually active.

### Client ownership

`clients/android` is a shell, not an authority layer. It owns:

- Android lifecycle and Compose shell state
- JNI transport over the FFI surface
- host rendering of exported scene packets
- control dispatch into the runtime command surface

The Android client must not grow its own simulation rules.

## Current maturity

The architecture is ahead of the product surface today.

What is already real:

- the Rust workspace structure and ADR chain
- the versioned protobuf and FFI seams
- session creation, refresh, command application, and render-packet export
- a working Android shell that can bind a runtime session and render exported packets

What is intentionally still early:

- the runtime is still a bring-up slice rather than a full parity replacement for the
  old Kotlin product line
- physics implementation is not yet a deep authoritative solver surface
- scene extraction is still bodies-first; richer tracer, trail, and light history
  surfaces remain thin
- the Android host currently renders exported packets in a software packet-render
  path even though the exported scene contract is Vulkan-shaped

That means this branch is strategically correct, but still in the phase where one
real end-to-end vertical slice matters more than adding more abstract surface area.

## Operational repo truth

On canonical `main`:

- prerelease packaging targets `clients/android`
- validation should prove the Rust workspace and the Android shell
- the root Gradle settings should not imply that the legacy Kotlin app is the
  shipping or validated app on this branch

If you need the design rationale behind this layout, read the ADR chain first and
then the reset-era architecture docs:

1. `docs/adr/0001-rust-owned-core.md`
2. `docs/adr/0002-versioned-protobuf-contracts.md`
3. `docs/adr/0003-c-abi-and-opaque-handles.md`
4. `docs/adr/0004-offline-first-data-and-updates.md`
5. `docs/adr/0005-render-scene-and-backend-adapters.md`
6. `docs/v2/architecture.md`
7. `docs/v2/roadmap.md`
