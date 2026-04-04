# Solar Gravity Lab v2 Architecture

## Core principles

- Authoritative physics by default.
- Rust owns the long-lived engine.
- Clients are native shells over a stable FFI.
- Rendering consumes a renderer-independent scene contract.
- Data is offline-first with signed live updates.
- Hardware acceleration is optional and capability-driven.

## Top-level shape

### Engine

The Rust engine is split into:

- `domain`
- `physics`
- `runtime`
- `history`
- `scene`
- `hardware`
- `ffi`

This keeps scientific truth, runtime control, rendering, and platform concerns
from collapsing back into one large client module.

### Clients

Clients are thin, platform-native shells:

- Android first
- desktop later
- web or streaming clients only after the engine and scene contracts stabilize

### Data plane

The data plane is built around signed versioned packages and manifests. Runtime
state, diagnostics, and rendered views must always retain provenance so the user
can tell where the simulation truth came from.

### Rendering

The engine emits a `RenderScene` or `RenderSceneDelta`. Backends adapt that
scene to Vulkan, Metal, or future GPU APIs without owning scientific truth.

## Why this is a reset

The v1 code proved useful product direction and surfaced important domain
constraints, but it mixes too much app logic, runtime logic, and render/runtime
transport. v2 exists so we stop carrying those compromises into the permanent
codebase.
