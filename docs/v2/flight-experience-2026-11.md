# Flight direction, planning and planetary light

## Source and acceptance

Experience branch based on docking main `52da8c06`, November 2026. Cockpit
checkpoint `897b7b3` uses planning contract `c038f4a`. Ops `w19004` tracks the lane
under `w19002`. These are source changes, not a published APK or Galaxy acceptance.

Seven renderer host regressions pass, including actual shader arithmetic for
vacuum, missing-shell, planet-shadow, lit-limb, coordinate-rotation, metre-scale
and bounded-output cases. Kotlin, SPIR-V and native Android journeys need
exact-source hosted proof. Galaxy touch usability, timing and thermals remain
separate acceptance steps.

## Pilot experience

Portrait exposes periapsis alongside altitude and apoapsis. Speed/radial motion
remain in the guide. The director uses native position, velocity and attitude:
the crosshair is the nose, a solid dot is ahead, a hollow rim marker is behind.
Angles cover 0–180 degrees, so an aft target cannot look centred. Manual and
Stabilise show prograde only as a reference. Descriptions explain the six orbital
directions and limitations of simple manoeuvre rules. Invalid/unbound estimates
do not invent a finite ellipse; negative periapsis warns of a surface crossing.

Short Orbit/Chase/Map/Encounter controls have explicit Centre recovery using the
native flight reset guard. Lunar Map retains its system overview. Guide pauses
for reading. Missions cuts/suspends flight before existing save-preserving briefs.

Fine selects 20% attitude/RCS authority independently of throttle. Per-button
held demands cancel opposites and restore the remaining direction on release.
CUT, mode changes, lifecycle suspension and guidance handoffs clear held input.
Accessibility pulses are bounded and cannot overlap touch on the same button.
Choosing Fine while idle leaves native guidance alone.

## Native planning

The modal calls `RuntimeFacade.previewFlightBurn` while paused and outside a
staged advance. Rust starts at the actual attitude/angular velocity. Thrust
begins immediately while the controller turns, without an implicit alignment
wait. Results contain orbit altitudes, fuel, elapsed time, native warnings and
source epoch/revision. World changes mark results stale; request edits clear
them. Preview never stages live controls or auto-executes a burn. Docking craft
are outside the first planner scope and receive an accessible disabled control.

Coast choices are native Leapfrog/FourthOrder/AdaptiveEncounter. Harmonic count
is read-only; powered flight retains the forward-time method. Kotlin contains
no propagator. The real JNI/world/Vulkan journey checks preview preserves live
epoch/fuel/controls and exercises Guide, Centre and mission entry. New chrome
joins existing encounter occlusion checks without weakening their assertions.

## Planetary light

Mapped Earth receives bounded single scattering in perspective flight only.
The existing map, authored 100 km shell, physical sphere and Sun direction remain
authoritative. Exponential molecular/aerosol density, solid-planet Sun shadow,
view extinction and ocean glint give a thin, directional horizon. Sky rays cross
the whole atmospheric shell; laboratory appearance remains unchanged.

The fixed budget is six view samples and three solar samples per illuminated
view sample. Distances use planet radii. Optical depths, aerosol asymmetry and
exposure are display parameters, not a calibrated spectrum, weather simulation
or scientific atmosphere solver.

Primary references informing this original implementation:

- Sean O'Neil, [GPU Gems 2, Chapter 16](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-16-accurate-atmospheric-scattering):
  density, optical paths, phase functions and space-view sample cost.
- Eric Bruneton, [Precomputed Atmospheric Scattering](https://ebruneton.github.io/precomputed_atmospheric_scattering/):
  calibrated spectra versus display approximation and CPU testing of GLSL.
  SGL does not implement Bruneton's precomputed multiple scattering.

`test_flight_atmosphere.py` executes the production GLSL function through a C++
vector adapter instead of duplicating it. Host invariants do not establish
native Vulkan visual acceptance or Galaxy performance.
