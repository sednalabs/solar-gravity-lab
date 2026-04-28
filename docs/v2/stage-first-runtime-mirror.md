# Stage-first restoration (third recovery slice)

This slice reconnects the restored immersive client to the Rust-authoritative runtime without falling back to the packet-viewer shell as the main experience.

## What this adds

The stage-first Android client now has a second mode: `Runtime`.

That mode keeps the beautiful full-screen `feature-lab` renderer and HUD, but drives it from the latest authoritative runtime packet stream instead of the local `LabSession` snapshot alone.

Concretely, this slice adds:

- a `Runtime` mode button from the stage-first HUD
- a runtime-mirror surface that translates `RenderFrame` packets into `render-core` scene frames
- immersive focus / follow / search inside the authoritative runtime scene
- pause, resume, refresh, step, and playback-rate controls from the stage-first surface
- a collapsed-by-default stage chrome that keeps the 3D view visible while
  leaving speed, trace, and controls access available on a compact rail
- trace-layer controls for `Focus`, `All`, and `Off` presentation modes
- runtime status and debug visibility without promoting the packet-viewer shell back to primary UI

## Stage visibility contract

The runtime mirror and local sandbox are stage-first surfaces. Their default
state should therefore prioritize the rendered world over menus.

The default collapsed chrome shows:

- a compact mission/selection card with a lightweight trajectory glyph
- `Start` / `Pause`
- `Slow`
- trace-layer cycling
- `Fast`
- `More`

The expanded chrome is still available through `More`, but it should be a
temporary command deck rather than the default view. Search, scenarios, debug,
camera framing, render detail, collision, and full transport controls belong in
that expanded state. On phone-width viewports the collapsed rail should keep
all five default controls visible at once, so the user does not need a
system-edge horizontal swipe just to open the expanded command deck. The
expanded compact command deck should wrap dense controls instead of hiding
primary actions behind sideways scrolling; if a control is present only after a
horizontal swipe, it is not discoverable enough for the stage-first surface.

Trace presentation is intentionally user-switchable:

- `Focus` keeps the selected or recently focused body readable without flooding
  the stage with every assist path.
- `All` restores the complete trace and trail layer for inspection.
- `Off` hides the assist layer when the user wants the bodies and lighting to
  carry the scene.

## What it does not pretend to solve yet

This is a reconnection slice, not full parity between the restored sandbox and the Rust runtime.

The runtime mirror is currently a **view-and-drive** surface, not a full runtime-native editor.

That means:

- full add / edit / delete authoring still lives in `Sandbox`
- the runtime mirror focuses on camera, scene presence, transport, and focus-follow behavior
- packet translation currently rebuilds a `RenderSceneFrame` on Android rather than handing the renderer a native 3D scene graph directly

## Implementation notes

- `StageFirstSandboxApp` now multiplexes between local sandbox mode and runtime mirror mode.
- `StageFirstRuntimeMirrorExperience` owns the runtime-connected immersive surface.
- `MainActivity` passes the runtime facade into the stage-first client only when runtime mirror is enabled for the build.
- `SolarSystemRenderHostView` now accepts direct `RenderSceneFrame` submission, so the immersive host can render either local snapshots or translated runtime packets.

## Build behavior

Because runtime mirror depends on the Rust JNI/runtime boundary, `prerelease` and `release` stage-first builds package the native runtime again by default.

If you want a purely local stage-first sandbox build with no runtime mirror button, disable it with either:

- Gradle property: `-Psolarlab.stageFirstRuntimeMirror=false`
- Environment variable: `SOLARLAB_STAGE_FIRST_RUNTIME_MIRROR=false`

With runtime mirror disabled:

- the stage-first client stays in `Sandbox` only
- the runtime button disappears
- `prerelease` / `release` no longer need the Rust JNI build step

## Still intentionally deferred

Still to do after this slice:

- runtime-native add / edit / delete parity inside the immersive client
- a true multiscale orbit camera instead of the remaining flattened stage assumptions
- deeper space context beyond the decorative starfield
- eventual unification so the beautiful client is not translating packets on Android but consuming a richer renderer-facing scene boundary directly
