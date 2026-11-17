# Camera and interaction model (implemented and qualified)

This is the **implemented** Android native-camera and direct-manipulation
contract, not an unstarted 3D design proposal. The camera, selection and
placement paths passed exact-source
[hosted native Android tests](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943).
Those tests do not replace physical S25 usability evaluation.

## Camera and navigation

The native renderer uses an orthographic orbit camera with focus/centre,
scale, yaw/pitch, and right/up/forward basis vectors. Origin-relative
packing keeps large world coordinates out of low-precision GPU arithmetic.
The `LabViewportState` reported after presentation carries the same
projection and interpolation fraction used for native picking and drawing.

| Gesture | Declared orbit map | Native 3D view |
| --- | --- | --- |
| One-finger drag | Pan the plane view | Orbit around the current view |
| Pinch | Zoom about gesture midpoint | Zoom about gesture midpoint |
| Two-finger translation | Pan | Pan |
| Tap a visible body/label | Select | Select |

Visible zoom buttons, scale readout, named Earth/Moon/system destinations,
object search and explicit Frame/Follow actions complement gestures. Selection
does not implicitly reframe. A frame is a one-shot camera request; Follow
moves the subject and user offset together. Camera gestures may deliberately
detach Follow without moving the physical simulation. Map-to-3D switching
preserves the current centre/scale and restores the earlier 3D orientation.

## Picking and object identification

Native picking tests sphere front surfaces against the displayed camera and
depth before considering larger UI touch affordances. Body anchors from
the **submitted** native viewport drive readable Compose overlays and labels;
an occluded anchor means its centre is covered, not necessarily its entire
visible disc. Markers and labels are interaction targets, not physical radii.
The viewport clears on pause, session replacement and surface changes so
stale screen coordinates cannot place or select objects.

The actual renderer contract and tests are in
[LAB_NAVIGATION.md](../render/android-vulkan/LAB_NAVIGATION.md).

## Probe placement and editing

Placement is an explicit draft in a named physical plane. The view uses
`LabPlane(originM, normal)` and orthographic ray/plane intersection, never an
undocumented `z=0` fallback. The user can choose a host, altitude, heading,
initial velocity, probe mass, and the independent **Attracts other bodies**
source-gravity switch. Tap/drag gestures move a ghost preview, exact fields
remain available, and a bounded model-based near-term forecast updates before
Create. A second finger cancels the active placement gesture and hands
control back to the camera; Cancel leaves the authoritative world unchanged.

The preview is non-committable during active drag. A Create operation needs
a current candidate, session/revision/epoch identity and paused state before
applying one authoritative Rust command. Editing a body can atomically change
mass, source mass, radius, position and velocity without replacing identity.
Undo/Cancel and save/reopen remain separate actions.

## Limits and acceptance

The preview is a near-term **visual aid**, not a qualified very-long-horizon
orbit predictor. The current Newtonian simulation omits atmospheric drag,
relativity, gravity harmonics, tides and resolved collisions. Novel camera
or placement changes must preserve the shared 3D/depth/scale worldview and
pass real native gestures, selection, portrait/landscape and lifecycle tests;
S25 installed-device performance and ergonomics still require direct evidence.
