# Orbital rendezvous reuse demonstration

This is a second runnable use of the spacecraft engine: a passive station and an
RCS chaser in nearby Earth orbits. The native example uses the canonical Earth–Moon
world and the same Rust authority, finite-thrust integration, spacecraft components,
commands and persistence as orbital flight. It does not implement contact or docking.

```sh
cargo test -p solarlab-runtime rendezvous
cargo run -p solarlab-runtime --example orbital_rendezvous --release
cargo run -p solarlab-runtime --example orbital_rendezvous --release -- match
```

The default scenario authors approximate circular ICRF-XY initial conditions at
400 km above Earth, with the chaser 1 km behind the station along the orbit. Both
vehicles have zero gravitational source mass and retain physical inertial mass.
The 20,000 kg station is an engine-less spacecraft component. It demonstrates
composition without adding another mutable kinematics store or a new ECS.

## Runtime API

`RendezvousScenario` supplies stable primary, station and chaser IDs plus initial
altitude/separation. `WorldRuntime::spawn_rendezvous_scenario` publishes the two
ordinary spacecraft spawn commands atomically. Existing IDs or invalid initial
conditions reject the entire setup.

`rendezvous_telemetry` reads live body state and returns range, closing speed,
inertial relative speed, and station-frame position/velocity. The station frame
translates and rotates with its actual attitude and angular velocity; it is not
an orbital radial/tangential frame. Velocity transformation includes the angular
transport term. There are no cached body indices or independently mutable poses.

`rendezvous_control` is a read-only, stateless controller; `apply_rendezvous_guidance`
issues its result through the ordinary `SetFlightControl` command. Default guidance
approaches a point 75 m along station -Y. Velocity matching is a separate mode.
The controller updates at 0.5 s intervals in the example, with physical integration
using its normal smaller steps. Body-axis RCS demand is capped to the configured
thrusters' actual force. Main engine throttle remains zero.

Guidance requires balanced axis-aligned RCS banks. It checks a 20 km operating
range and the represented hull radii plus a 5 m keep-out margin. A straight
approach crossing this region instead brakes relative motion and requires a
clear waypoint. A keep-out entry commands an outward correction. These are
navigation aids, not collision avoidance
or a contact guarantee. Missing targets, excessive range and empty tanks produce
neutral thrust commands. Target lookup happens each time, so removal cannot leave
a stale solver reference in the controller.

The existing session save owns both vehicles, controls, fuel and physical state.
Scenario/guidance settings are explicit serializable caller configuration; the
controller has no hidden history to restore. The example resumes a save halfway
through its run and compares the later bodies and spacecraft components exactly.

## Local execution evidence

The initial local canonical-world approach run closed from 1,000 m at 1.133 m/s
relative speed to 75.005 m at approximately 0.00005 m/s after 650 simulated seconds,
using 22.575 kg of RCS propellant. Save/resume produced identical subsequent
physical state. These numbers are execution evidence for this source slice,
not hosted Android, released APK or physical-device acceptance.

Tests compare the unpowered pair against an independently calculated circular
relative-motion solution; check controlled trajectory convergence with smaller
physical steps; verify arrival, fuel consumption and keep-out distance; prove the
station's trajectory is unaffected by the zero-source-mass chaser; and cover
rotating frames, save/resume, target removal, atomic setup and guidance boundaries.

The demonstration currently runs through the native runtime/example. A dedicated
station mesh, Android rendezvous selection and HUD, contact response, port alignment
and docking attachment are not part of this implementation.
