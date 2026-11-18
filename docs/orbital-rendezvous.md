# Orbital rendezvous: native guidance and reusable flight components

This is a second working use of the spacecraft engine: a passive Relay station and
an Asteria RCS chaser in nearby Earth orbits. The canonical Earth–Moon world, Rust
authority, changing-mass thrust integration, component identities, commands and
session persistence are shared with orbital and lunar flight. There is no contact
solver or docking response.

```sh
cargo test -p solarlab-runtime rendezvous
cargo run -p solarlab-runtime --example orbital_rendezvous --release
cargo run -p solarlab-runtime --example orbital_rendezvous --release -- match
```

The example issues one guidance command. Every subsequent control evaluation is
performed inside the native integration path; its host loop only advances time
and displays observations. A save halfway through the run resumes active guidance
and checks the later bodies and spacecraft components for exact equality.

## Scenario and ownership

The starting orbits are approximate circular ICRF-XY initial conditions 400 km
above Earth, with the chaser 1 km behind the station. Subsequent motion uses the
full canonical world's gravity. Both vehicles retain physical inertial mass and
have zero gravitational source mass. The 20,000 kg station is an engine-less
spacecraft component, demonstrating composition without a separate kinematics
store or an ECS rewrite. `BodyState` alone owns position and velocity.

`SpacecraftAppearance` is persisted presentation metadata: `Asteria = 1` is the
backward-compatible default, while `RelayStation = 2` selects the separate station
mesh. These identifiers do not change physical mass, forces or collision geometry.
The represented station/chaser hull radii are 12 m and 8 m respectively.

`spawn_rendezvous_flight(ship_id, time)` accepts only a paused, untouched, epoch-zero
canonical Earth–Moon laboratory. It atomically creates the station and chaser and
attaches retained rendezvous state to the caller's spacecraft ID. The station's
stable ID is `rendezvous.station`; consumers read it from telemetry rather than
assuming it. Android must preserve the current experiment before authoring and
binding this candidate session, following the existing lunar-entry boundary.

The lower-level `spawn_rendezvous_scenario` remains available for other native
worlds and configurable initial IDs/altitude/separation. Both spawn paths publish
the ordinary spacecraft commands together or leave the world unchanged.

## Guidance and reference frames

`set_rendezvous_guidance` records one of three native modes:

| Mode | Value | Behavior |
|---|---:|---|
| Manual | 0 | Cut automatic RCS demand and retain mission observations |
| Approach | 1 | Approach and maintain the point 75 m along station −Y |
| MatchVelocity | 2 | Reduce motion relative to the station's moving rigid frame |

`RendezvousStatus` has explicit transport values: Manual 0, Approaching 1,
VelocityMatching 2, Holding 3, KeepOut 4, OutOfFuel 5, OutOfRange 6 and
TargetUnavailable 7. Holding remains active station-keeping. Success is a separate
latched observation of an actual near/slow state, not permission to stop integrating.

The target frame translates and rotates with the station's actual attitude and
angular velocity. It is a rigid body frame, not an orbital radial/tangential frame.
For a body displacement `r`, local velocity includes the angular transport term
`R⁻¹(v_ship − v_station − ω × r)`. Telemetry distinguishes this velocity from the
inertial relative speed and supplies a unit target bearing in the chaser body frame.

The existing controller calculation lives in `engine/physics/src/rendezvous.rs`.
Both the explicit `rendezvous_control` API and persistent guidance call this shared
implementation. It produces bounded body-axis `FlightControl` RCS demands for
balanced axis-aligned thruster banks. Main-engine throttle remains zero. Desired
approach speed is limited to 2 m/s; actual speed remains a physical result of finite
thrust and gravity. No guidance code overwrites position or velocity.

Active intent is validated against the actual RCS banks and target-frame/stand-off
configuration at spawn and import, as well as ordinary guidance commands. Unsupported
active programmes are rejected atomically; general engine configurations remain
available for Manual flight.

Persistent guidance evaluates before every native force segment, at no more than
0.05 s intervals. Fuel depletion can split a segment further. The existing analytic
changing-mass impulse and attitude integration apply the resulting demand. Guidance
reads staged canonical solver bodies; target indices are resolved from stable IDs
when an advance begins and cannot outlive that atomic operation.

Active guidance clamps playback to 1× and rejects faster playback requests even
when the instantaneous RCS demand happens to be zero. Manual coasting supports time
acceleration, with retained rendezvous observations sampled at most 0.5 s apart.
A bounded native advance uses the same physics and only publishes bodies, fuel,
mode and observations together when the operation commits; cancellation discards
the staged work.

## Controls, lifecycle and persistence

Any manual `SetFlightControl` command, including Cut and lifecycle suspension,
cancels automatic guidance. An explicit main-engine programme also cancels it.
Pause alone retains the mode so a saved session can resume the same guided future.
Removing the station or reference body immediately neutralizes the controller.
Removing the chaser removes its component. Missing targets remain identifiable in
retained progress and report `target_available = false`; numerical geometry fields
then have neutral values and must not be interpreted as a physical zero separation.

Mode, target identity, launch epoch, initial fuel, closest observed range/epoch,
stand-off achievement and keep-out entry are part of `SpacecraftState.rendezvous`.
They are saved with branches and checkpoints. Actual integrated observations alone
update progress; telemetry and prediction queries are read-only.

Rendezvous or RelayStation-containing documents declare
`flight_model: six-dof-strang-rocket-rendezvous-v3`, including components retained
only in checkpoints. Older readers therefore reject unsupported control semantics
instead of dropping guidance fields and continuing a stale RCS command. This reader
retains the existing v1/v2 flight readers and ordinary laboratory format. Existing
saves without the new fields default to Asteria appearance and no rendezvous state.

## Navigation limits

Guidance operates within 20 km and uses a 25 m keep-out distance for the reference
pair: represented hull radii plus 5 m. A blocked straight path brakes relative motion
and requires a clear waypoint; entering the region commands an outward correction.
The default scenario starts on the same side as its stand-off point.

These are navigation aids and sampled observations. They do not guarantee collision
avoidance, provide continuous collision detection, plan a route around the station,
model docking ports, attach bodies or resolve contact. The 5 m margin is not a
braking-distance guarantee. Missing targets, excessive range and fuel exhaustion
stop automatic demand safely. Scientific body dynamics remain distinct from these
explicit gameplay goals and approximations.

## Local execution evidence

On the current native implementation, one persistent Approach command followed by
650 simulated seconds closes from approximately 1,000 m and 1.1332 m/s relative
speed to 75.0054 m separation, 0.0291 m stand-off error and 0.0000511 m/s relative
speed. It uses 22.574 kg of RCS fuel; the smallest sampled separation is 74.8133 m.
The station and celestial bodies follow exactly the same trajectory as a coast
control because the chaser's gravitational source mass is zero.

A second production sequence performs MatchVelocity for 90 s (999.232 m range,
0.01036 m/s relative speed), then a manual body-X RCS pulse of 0.25 for 2 s, then
Approach for 650 s. At epoch 742 s it reaches 75.0115 m, 0.0311 m stand-off error
and 0.0000509 m/s, using 23.385 kg total fuel without entering the keep-out region.

The 120 s controlled-trajectory convergence test compares 0.05 s and 0.025 s force
steps against 0.00625 s: target-frame position differences are approximately
0.0153 m and 0.00679 m respectively. Separate coverage checks unpowered relative
motion against an independent circular-orbit solution, moving-frame transforms,
RCS capacity limits, exact save/reopen continuation, checkpoint restoration,
manual cancellation, fuel exhaustion, target removal, warp limits and staged rollback.

These are local source-level native results. They do not establish hosted Android
interaction, a published installable APK, Samsung S25 Ultra performance or physical
device acceptance; those require the corresponding exact-source validation evidence.
