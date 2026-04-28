# Stage-first restoration (first recovery slice)

This change reintroduces a stage-first Android client on top of the current repository without deleting the newer Rust-authoritative shell.

## What this slice does

The current Android entrypoint can now boot a restored immersive sandbox surface that is backed by the proven feature-lab Vulkan renderer. That restored client gives the stage visual primacy again:

- full-screen render surface
- lightweight HUD overlays instead of card-heavy shell framing
- collapsed-by-default stage chrome so the rendered scene remains primary
- real Vulkan renderer instead of the packet painter becoming the main experience
- direct pinch-to-zoom, pan, tap-to-select, and follow modes
- searchable body focus and debug transport controls
- compact speed and trace controls that remain reachable without opening the
  full command deck
- catalog / ephemeris assets pulled from the shared root asset bundle

## How it is wired

- `clients/android/settings.gradle.kts` now imports the shared JVM / Android modules that already contain the renderer and simulation stack.
- `clients/android/app` depends on `feature-lab`, `render-core`, and the shared model/math modules.
- `MainActivity` can boot either:
  - the existing Rust-authoritative shell, or
  - `StageFirstSandboxApp`, which restores the immersive sandbox.

## Variant behavior

To avoid blowing up the existing debug-oriented shell workflows immediately, the restored stage-first client is enabled by default in `prerelease` and `release`.

Debug stays on the current shell unless you opt in.

Enable the stage-first sandbox in debug with either:

- Gradle property: `-Psolarlab.debugStageFirstClient=true`
- Environment variable: `SOLARLAB_STAGE_FIRST_CLIENT=true`

## Rust JNI build behavior

In this first slice, the restored immersive client could package `prerelease` and `release` without the Rust JNI/runtime build.

That build behavior is later superseded by the runtime-mirror slice, which reconnects the immersive client to the Rust-authoritative stream and therefore opts stage-first builds back into the Rust JNI step by default.

See `docs/v2/stage-first-runtime-mirror.md` or the overlay `APPLY_AND_BUILD.txt` for the current cumulative behavior.

## What is still intentionally deferred

This is the first recovery slice, not the end state.

Still to do:

- port the current add/edit-selected workflow into the restored sandbox
- reconnect the restored stage-first renderer to the latest runtime packet stream so the Rust-authoritative path can drive the beautiful client directly
- replace the remaining overhead-first camera assumptions with a true multiscale orbit camera
- add a real deep-space / galaxy context layer so zooming out reveals more universe instead of just a decorative starfield
