# Solar Gravity Lab

A native Kotlin Android Studio project scaffold for a physically serious solar-system sandbox, now redrafted around a **hybrid renderer architecture** with a **Vulkan-first path** and a production fallback path.

The public project name is **Solar Gravity Lab**. Some internal package names and older handoff notes still use the shorter `SolarLab` identifier.

This pass gives you:

- a multi-module Android Studio project
- pure Kotlin/JVM math, model, simulation, and render-scene modules
- a leapfrog / velocity-Verlet style N-body simulation core
- massive-body vs tracer-body dynamics
- merge collisions with momentum conservation
- barycenter recentering
- deterministic epoch-tagged starter states for the major planets
- a plug-in cartesian seed-bundle path for future DE / Horizons drop-in data
- synthetic asteroid-belt and Oort-shell population generators
- a `SolarSystemRenderHostView` that prefers Vulkan and falls back to OpenGL ES when Vulkan is unavailable or fails to initialise
- a native Vulkan renderer with SPIR-V graphics pipelines, a JNI bridge, swapchain creation, render pass / framebuffer creation, command-buffer recording, and compute-driven medium/far tracer compaction
- an OpenGL ES fallback renderer that now consumes the same packed render-scene model as the Vulkan path

## Modules

- `app`  
  Thin Android entry point and activity wiring.

- `core-math`  
  `Vector3d` and low-level math helpers. Pure Kotlin/JVM.

- `core-model`  
  Domain model: body definitions, constants, orbital elements, snapshots, config. Pure Kotlin/JVM.

- `core-simulation`  
  Physics engine, diagnostics, orbital transforms, scenario generators, and planetary seed catalogue code. Pure Kotlin/JVM.

- `render-core`  
  Backend-neutral render-scene model, trail assembler, and native scene-packet packing. Pure Kotlin/JVM.

- `feature-lab`  
  Android render host, Vulkan bridge, OpenGL fallback surface, and simulation session controller.

## Renderer direction

The renderer is now intentionally split into:

- **authoritative CPU simulation** for the physically important bodies
- **backend-neutral render-scene assembly** for all renderers
- **Vulkan-first native renderer path** for future GPU-heavy tracer and instanced body rendering
- **OpenGL ES fallback renderer** so the app remains usable while the Vulkan path is being built out

The Vulkan path is not a fake stub. It now bootstraps a real instance/device/surface/swapchain/render-pass/framebuffer/command-buffer path. What it does **not** have yet is the full body / tracer draw pipeline. The scene packet and camera contract are in place for other agents to finish that cleanly.

## What is already physically grounded

- Newtonian gravity
- double-precision state
- mutual interaction for bodies marked `MASSIVE`
- passive tracer dynamics for bodies marked `TRACER`
- momentum-conserving merge collisions
- radii derived from mass + density when using `BodyFactory.sphericalBody(...)`
- barycentric recentering for seeded systems
- deterministic major-planet starter states tied to a defined epoch

## What is still approximate or intentionally unfinished

- The simulation still uses the current seed bundle / approximate starter layering rather than a bundled DE / Horizons vector set.
- The Vulkan renderer now has body / tracer graphics pipelines plus compute-driven compaction for medium/far tracers, but it still does not perform GPU-side tracer integration.
- The asteroid belt and Oort shell remain synthetic populations, not a literal object-by-object catalogue.
- Collision handling is still merge-only.

## Suggested next implementation passes

1. Have an external agent generate `app/src/main/assets/ephemeris/solarlab_horizons_seed_bundle_v1.tsv`.
2. Finish the Vulkan body / trail graphics pipelines using the existing native scene-packet contract.
3. Replace host-visible compute buffers with staged device-local buffers, then move tracer integration onto Vulkan compute.
4. Add follow-target camera, richer time controls, and object inspection overlays.
5. Add S Pen precision placement / launch-vector tooling.
6. Add DeX desktop layout mode.

## Opening the project

Open the root folder in Android Studio. The project is structured as a normal multi-module Android project.

## Validation

The pure Kotlin/JVM modules (`core-math`, `core-model`, `core-simulation`, `render-core`) are designed so external agents can build and test the core without needing to boot the Android app.

Because this environment does not include a full Android SDK/NDK toolchain, the project snapshot was assembled here and the pure Kotlin modules were compiled separately with `kotlinc`, but the full Android Gradle build was not executed here.

The standard Gradle wrapper scripts and properties are present, but `gradle/wrapper/gradle-wrapper.jar` still needs to be regenerated or restored before `./gradlew` is fully self-contained.

Remote-first validation is now intended to happen through the dispatch-only
GitHub Actions workflow documented in `docs/validation-lab.md`, so Orchard
branches can offload bootstrap work, targeted JVM slices, frontier-style next
blocker harvests, and broader Android checkpoints without burning local host
compute.

The render handoff is now a little stronger: the Vulkan path receives a camera-aware `NativeScenePacket` with authoritative bodies, three tracer LOD tiers, simplified trails, and a source revision for future native caching.


## Vulkan implementation notes

### Stream upload pass

The native renderer now consumes the packed scene as five separate Vulkan-side streams:

- authoritative billboards
- near tracer billboards
- medium cheap-point tracers
- far density-point tracers
- trails

Those streams are cached by `sourceRevision` and uploaded into separate Vulkan buffers before command recording. Medium/far tracers already use cheaper native formats, while tracer buffers are flagged as storage-capable to keep a later compute pass straightforward.

### SPIR-V pipeline pass

The native Vulkan path now goes beyond stream upload. It loads AOT-compiled SPIR-V shaders from Android assets and creates real graphics pipelines for:

- authoritative + near billboard sprites
- medium cheap points
- far density points
- trails

Camera movement is now driven through a native uniform buffer, so camera changes do not force command-buffer rerecords. The next Vulkan-native step after this pass is compute-driven tracer compaction or integration.


### Compute compaction pass

The native Vulkan path now adds compute on top of the existing graphics/descriptor/buffer path:

- medium tracer compute compaction into an indirect draw buffer
- far tracer compute compaction into an indirect draw buffer
- per-frame compute dispatch before the render pass
- compute-to-graphics barriers for indirect draw + vertex fetch
- graphics draws for medium/far now switch to `vkCmdDrawIndirect(...)` when compute compaction is active

The remaining big native step after this pass is GPU-side integration for medium/far tracers.
