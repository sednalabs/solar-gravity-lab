# Stage-first Rust runtime

The stage-first Android client has one world: the authoritative Rust runtime.
There is no local Kotlin simulation mode and no build switch that can replace
the Rust world with a second implementation.

## Ownership contract

- `engine/` owns bodies, physics, history, observer state, commands, scene
  taxonomy, scene extraction, and hardware truth.
- `engine/ffi` exposes a versioned C ABI with opaque session and packet handles.
- the native C++ stage adapter owns camera interaction, picking, renderer-local
  level of detail, Vulkan resources, and frame submission.
- GLSL owns GPU projection and shading only.
- Kotlin and Compose own Android lifecycle, controls, accessibility, and visual
  chrome. They do not integrate bodies or rebuild a second scene authority.

The native stage consumes the Rust packet directly. Kotlin may decode bounded
packet metadata for search, labels, accessibility, and status presentation, but
that decoded view is not simulation truth.

Comets are an explicit Rust domain body class with the same non-source-mass
policy as other catalogued small bodies. The stable scene packet carries their
renderer taxonomy directly; neither Kotlin nor C++ infers a comet from its ID.

## Stage visibility contract

The rendered world remains the dominant surface in collapsed and expanded
states. The default controls provide playback, camera help, explicit fewer/more
tracer actions, and a route to the expanded command deck without requiring a
system-edge horizontal gesture.

Camera interaction is body-aware:

- tap selects;
- double-tap selects, follows, and frames the body for close orbit;
- one-finger drag orbits the current focus;
- pinch zooms down to a radius-derived inspection limit;
- two-finger translation pans and intentionally detaches follow when the user
  moves away from the target;
- Home returns to the scenario view.

Tracer presentation has three monotonic levels:

- `Hidden` removes tracer and trail presentation;
- `Focused only` shows the selected source, or a deterministic reduced set when
  nothing is selected;
- `More` shows the complete exported tracer and trail layer.

These are renderer controls. They do not change the authoritative bodies,
integration policy, force calculation, history, or clock.

## Build and validation behavior

All stage-first debug, Android-test, prerelease, and release APKs package
`libsolarlab_v2.so`. There is no alternate-world build flag and no local-stage
fallback.

Use `android_validation_mode=stage-first-runtime` for hosted Android proof. Use
the reusable `interactive-android-build` artifact from the exact validated
commit for native computer-use acceptance. A visual claim still requires native
Android observation; compilation and semantic tags alone are not visual proof.

## Native renderer boundary

The native Vulkan host now lives under `render/android-vulkan/` and is consumed
by the Android shell as `:android-vulkan-renderer`. Its Kotlin package and JNI
exports use the canonical `com.sednalabs.solarlab.render.vulkan` namespace.
The legacy `feature-lab` module is not on the production dependency path.

The next measured renderer work is:

- extend the typed Rust scene contract with renderer-facing celestial
  appearance, orientation, ring, and comet facts;
- render recognizable scale-aware planets, moons, rings, and comets without
  changing the authoritative world;
- add device-profiled thermal and battery scheduling policy;
- measure whether far-tracer aggregation materially improves sustained device
  behavior before choosing another compaction path; and
- preserve scalar-oracle physics proof as new Arm64 kernels become active.
