# Architecture Notes

## Core simulation design

The engine distinguishes between:

- **Massive bodies**: mutually interact with each other.
- **Tracer bodies**: feel gravity from massive bodies, but do not perturb the rest of the system.

That gives us a scalable phone-friendly middle ground between fake toy physics and full pairwise gravity across tens of thousands of objects.

## Integrator

The engine uses a kick-drift-kick leapfrog style update because orbital systems care about long-run energy behaviour more than about single-step local prettiness.

## Coordinate system

The simulation is fully 3D even though the current renderer presents a top-down X/Y projection.

Internal units are SI:

- metres
- kilograms
- seconds

## Scenario philosophy

This pass includes:

- Sun
- 8 major planets
- selected dwarf planets
- synthetic asteroid-belt tracers
- synthetic Oort-shell tracers

The Sun-through-Neptune starter states are no longer using arbitrary phase angles. They now come from an explicit, epoch-tagged JPL-derived starter catalogue that generates cartesian state vectors at J2000 TDB. Dwarf planets are still seeded from representative orbital elements for now.

## Seed catalogue layering

The seed system is now explicitly layered:

- `CartesianSeedBundle` for authoritative bundled cartesian states
- Android asset loader in `feature-lab` that auto-loads a bundle if present
- `JplApproximateSeedCatalog` fallback for the major planets when no valid bundle is present
- representative orbital-element fallback for dwarf planets that still lack bundled vectors

The point of the abstraction is to let the simulation and rendering layers stop caring where the authoritative starter states come from. External agents only need to generate the bundle file.

## Render architecture

The renderer is now split into four layers:

1. **Authoritative simulation** (`core-simulation`)  
   Produces the physically correct snapshot.

2. **Backend-neutral render assembly** (`render-core`)  
   Converts `SimulationSnapshot` into a `RenderSceneFrame` plus a primitive-array `NativeScenePacket` suitable for JNI.

3. **Backend host** (`feature-lab`)
   `SolarSystemRenderHostView` owns Vulkan backend lifecycle forwarding and status updates.

4. **Backend implementation** (`feature-lab`)  
   - `SolarSystemVulkanSurfaceView` + native bridge for the Vulkan renderer path

## Vulkan-first migration shape

The Vulkan path now owns:

- Vulkan instance creation
- Android surface creation
- physical-device and queue-family selection
- logical-device creation
- swapchain creation
- image-view creation
- render-pass creation
- framebuffer creation
- command-pool allocation
- command-buffer recording
- explicit sync objects
- JNI scene-packet ingestion

What is intentionally left for the next pass:

- body draw pipeline
- trail draw pipeline
- GPU-instanced tracer rendering
- compute-driven tracer integration
- GPU-side camera-relative precision strategy for very large scenes

## Vulkan-only backend

The app now runs a Vulkan-only renderer path. Backend status reporting remains in place so the UI can surface unavailable-runtime and native-renderer failures without implying a fallback backend exists.

## Collision model

Current collision mode is merge-only:

- conserve total mass
- conserve linear momentum
- combine volume to derive merged radius
- keep the dominant category / role where sensible


### Hybrid render packet

`render-core` now owns a camera-aware packet build step for native backends. This is deliberate: it lets unrestricted agents keep the authoritative integrator on CPU while still pushing presentation-oriented LOD, tracer budgets, and trail simplification into a deterministic Kotlin pre-pass.
