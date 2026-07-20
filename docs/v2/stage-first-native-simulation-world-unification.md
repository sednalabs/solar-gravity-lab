# Native simulation world unification

## Status

The product now has one authoritative world. Android always starts the Rust
runtime, and every stage-first build binds that session directly to the native
Vulkan stage. The former managed simulation surface and its authoring-only
models have been removed from product source and validation routing.

## Durable ownership

- Rust owns active bodies, force integration, substeps, commands, checkpoints,
  history, diagnostics, observer state, provenance, and scene export.
- FFI owns stable, versioned transport with opaque handles.
- C++ owns renderer-local camera, picking, visual LOD, Vulkan resources, and
  frame submission.
- Kotlin owns Android lifecycle, controls, accessibility, labels, and sheets.
- GLSL owns projection and shading, never physical state.

The world model preserves inertial/display mass separately from gravitational
source mass. Tracers, probes, catalogued small bodies, and the explicit comet
class do not perturb the canonical system unless an explicit Rust command gives
them source mass.

## Accuracy north star

Canonical teaching scenarios remain subject to committed reference fixtures,
deterministic invariants, and scalar-oracle proof. Every visible path must be
identifiable as authoritative integration, history, bounded preview, or visual
guide. Hardware acceleration must publish the exact active backend and declared
accuracy profile; capability detection alone is not an execution claim.

## Remaining migration

The active native stage is still physically hosted by the legacy
`feature-lab` module. Move it into canonical `render/` and `clients/android/`
directories as an independent module migration. That source move must not
reintroduce a managed world or alter the stable Rust scene/FFI contracts.
