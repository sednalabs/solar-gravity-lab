# Physical station docking

Milestone F (`w19001`) extends programme `w18994` with a separate 30 m approach
to Relay. It reuses the authoritative Rust world, finite-fuel controls, native
meshes, Android cockpit and history. The existing [1 km rendezvous](orbital-rendezvous.md)
retains its contact-free stand-off objective. A–E baseline main is
`dac95def8a6dda19736f1f41f3261fb19afc082d`; docking is added on
`feature/docking-flight-20261119`. Exact-source hosted Android and physical S25
Ultra acceptance remain separate gates from this source contract.

## Run the production mission

```sh
cargo test -p solarlab-physics docking
cargo test -p solarlab-runtime docking
cargo test -p solarlab-ffi docking
cargo run -p solarlab-runtime --example station_docking
```

The [example](../engine/runtime/examples/station_docking.rs) issues one Approach
command, advances native physics to capture, saves/reopens the attached pair and
compares future bodies/components. It then releases the joint, asserts unchanged
position/velocity at release, and applies finite-fuel RCS to retreat. The host
loop advances time and prints observations; it does not edit kinematics.

Android preserves the previous experiment before binding a fresh canonical
Earth–Moon mission. Initial vehicle centres are 30 m apart in an approximate
400 km Earth orbit. Initial port alignment is authored; all subsequent motion
comes from gravity, forces, torque and constraints. Presentation reads the native
target ID (`docking.station` for this scenario).

Dock arms native approach while preserving Run/Pause. Run advances it. Manual
input or Cut cancels approach and disarms capture; Cut or Pause does not detach
an existing joint. Undock preserves time state and both bodies' instantaneous
physical state. The pilot supplies separating RCS. Attached thrust is disabled.

## Authority and integration

`BodyState` alone owns persistent position, velocity and mass. `SpacecraftState`
owns attitude, spin, principal inertia, propellant, controls and docking state.
Contacts are opted in through a chaser-to-station relation. Both members must
have zero gravitational source mass; their positive inertial masses still govern
thrust acceleration, recoil and impulses. Initial Relay mass is 20,000 kg.

[physics/docking.rs](../engine/physics/src/docking.rs) uses `rapier3d-f64` pinned to
0.36.1, with `dim3`, `f64`, `std` and `enhanced-determinism`, without default
features. The dependency declares Apache-2.0 and requires Rust 1.86 or later;
SGL remains AGPL-3.0-or-later.

Each contact step is at most 0.01 s. The selected authoritative gravity kernel
applies one half-kick, Rapier owns local drift/rotation/contact, and the same
gravity kernel applies the remaining half-kick. Rapier's gravity is zero.
Existing propulsion splitting supplies fuel-limited non-gravitational impulses.
Other bodies retain canonical drift. No body receives two independent drifts.

Local rigid rotation uses Rapier's numerical approximation. An independent
Euler-equation RK4 comparison measures first-order convergence for non-principal
free rotation and applied body torque as the contact step halves. This is
separately tested and is not a claim of globally symplectic rigid-body rotation.

All opted-in members share a translating, nonrotating frame based on their
actual inertial centre of mass and bulk velocity. Astronomical coordinates are
subtracted in f64 before metre offsets are formed. Stable IDs determine insertion
order. The temporary solver is reconstructed each step; results publish only
with the ordinary atomic advance. Cancellation discards pose, fuel, control and
attachment changes together.

CCD selects one swept body per pair using translational and angular motion;
Rapier 0.36 does not sweep two bullets against one another. Sleeping is disabled,
constraint iterations are set to 12 and CCD substeps are limited to four. There is no arbitrary local speed clamp. Collider
density is zero because mass/inertia are explicit. These choices do not establish
cross-platform bitwise contact equivalence or measured phone performance.

## Real contact before attachment

Authored convex compounds approximate pressure sections, structures and arrays.
Relay's aperture is an open segmented annulus. Separate collar colliders identify
intended mating contact. See [asset provenance](../render/android-vulkan/assets-src/README.md).
Friction 0.4 and restitution 0.1 are configured gameplay approximations, not
measured material properties. Deformation, damage and seals are not modelled.

Asteria's port is body `(0, 0, 3.52)` m; Relay's is `(0, -4.04, 0)` m and faces
body −Y. Native observations use the target port frame: +X right, +Y key-up, +Z
outward. Positive axial distance is an open gap. Relative velocity includes
angular transport at both ports; full orientation error includes roll.

| Capture condition | Native limit |
| --- | ---: |
| Axial eligibility window | Up to 0.15 m in front of the port |
| Solver compression allowance | 0.005 m behind the port plane |
| Lateral offset | 0.05 m |
| Full mating orientation error | 5° |
| Actual relative port speed | 0.1 m/s |
| Relative angular speed | 1°/s |

Being inside the window is **not attachment**. Armed capture also requires
actual collar-to-collar contact, nonpositive contact separation, a positive
impulse and a qualifying normal. Predictive braking contacts cannot latch.
Eligibility is sampled before contact dissipates an unsafe arrival speed.
Unsafe impacts and other hull impacts cancel guidance and latch unsafe contact.

Safe contact stores the actual accepted relative position/quaternion as a fixed
constraint; it never snaps the ship to an ideal pose. Relay is dynamic and
recoils. Pair contacts are disabled while attached. Release injects no impulse.
Guidance uses real RCS banks and bounded torque; range, fuel, geometry and target
availability can stop it. Docking worlds stay at no more than 1× playback even
when guidance is idle. This controller does not plan routes around obstacles.

## Save, lifecycle and validation

Target ID, guidance/arming, unsafe state and the actual-rest attachment persist
in spacecraft state, branches and checkpoints. Rapier handles, caches and
warm-start impulses do not. Docking documents declare
`flight_model: six-dof-strang-rocket-docking-v4` in the existing version-2 flight
envelope, including docking retained only in checkpoints. Earlier lab/flight
models remain readable; older clients must reject unsupported v4 semantics.

Import checks graph identity, zero source mass, appearance/control compatibility,
actual/rest pose and relative-motion consistency. One chaser may target a station;
chains are unsupported. Docking cannot coexist on the same ship with lunar,
rendezvous or main-engine programmes. Direct physical edits cannot move an
opted-in member independently of its relation.

Removing the target releases attachment and neutralizes approach without changing
the chaser's instantaneous kinematics. Its retained ID reports unavailable;
neutral telemetry fields are not zero physical separation. Reusing that missing
ID for an unrelated new body is rejected. The same rules apply to checkpoints.

Native [physics](../engine/physics/src/docking_tests.rs),
[runtime](../engine/runtime/src/docking_flight_tests.rs) and
[FFI](../engine/ffi/src/docking.rs) tests cover production paths. A local integrated
run captured at 75 s using approximately 3.22 kg RCS propellant, with a roughly
14 μm axial gap and 2.12 mm lateral offset after actual contact. These are local
results, not hosted Android or physical-phone acceptance.

The existing public Validation Lab Rust lane runs `station_docking`. Android
adds a fourth method to
[OrbitalFlightJourneyInstrumentationTest](../clients/android/app/src/androidTest/kotlin/com/sednalabs/solarlab/OrbitalFlightJourneyInstrumentationTest.kt):
capture, attached save/reopen, release and held RCS departure. Paired native
PixelCopy/window captures retain per-mesh material checks and require full hulls
clear of actual HUD rectangles. Acceptance needs the tested revision, results,
genuine images and installable APK; no new runner or privileged lane is added.

Planetary contacts, deformable structures, multiple-port assemblies, docking
chains, arbitrary imported collision hulls, fuel-dependent inertia and a general
spatial-query API remain unimplemented. One local world serves this nearby pair;
widely separated contact populations need further partitioning. Physical S25
performance, thermals and stable signed upgrade qualification retain their
separate hardware/release ownership.
