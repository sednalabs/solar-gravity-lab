# Android client shell

The v2 Android client is a native shell over the Rust runtime.

Design constraints:

- package namespace: `com.sednalabs.solarlab`
- Jetpack Compose UI
- unidirectional app state
- no authoritative simulation logic in the Android layer
- Rust runtime accessed through the `engine/ffi` ABI
- render host implemented as an Android adapter over the shared `RenderScene`
  backend contract

This directory will eventually replace the current v1 `app` and `feature-lab`
Android stack on the v2 branch.

## Runtime seam responsibilities

The Kotlin side is a host shell only. It owns:

- `RuntimeFacade` orchestration contract (`SolarLabApp`, `MainActivity`)
- Surface rendering and layout (`VulkanPacketRenderSurfaceView`)
- JNI/ABI transport lifecycle and packet lease management (`RuntimeBridge`, `RenderHostAdapter`)

The Rust side owns:

- Simulation state updates
- Snapshot generation
- Vulkan packet encoding and export contract

## Ownership and ownership boundaries

- The Android shell must not alter simulation logic; it only reacts to `RuntimeSignal`.
- `RuntimeBridge` is the only place that knows about session handles and runtime ABI details.
- `RenderHostAdapter` is the only owner of packet lease objects in Android, and it is responsible for releasing them before session teardown.
- `BridgeBackedRuntimeFacade` is the only translator from runtime signal stream to immutable `ShellUiState`.

## JNI and lease invariants

- A non-zero session handle is required before packet export can run.
- Packet leases are single-owner host objects (`PacketLease`) and are released with `close()`.
- Lease release must happen before `destroySession`; this avoids native ByteBuffer liveness bugs.
- `RuntimeBridge` serialises handle reads with `stateLock` to avoid races between refresh/command/connect events.

## Compose shell shape

The `SolarLabApp` Compose tree intentionally isolates rendering behind a dedicated `AndroidView` host.
This keeps:

- declarative layout/state in Compose
- imperative drawing in a `SurfaceView` adapter

without mixing host-canvas threading concerns into app shell state logic.
