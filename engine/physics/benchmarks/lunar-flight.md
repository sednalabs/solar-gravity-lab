# Finite-burn Earth–Moon flight

This scenario is executable through the production Rust runtime. It authors one
starting orbit and then uses the same authoritative gravity, propulsion,
rotation, commands, staged advances and persistence as interactive flight.
There are no trajectory keyframes or position/velocity corrections after spawn.

The numerical results below are local source validation dated 2026-11-18.
An exact-source hosted run, installable APK, Android visual evidence and physical
Galaxy S25 acceptance are separate delivery gates; these results establish none
of those gates by themselves.

## Run and reproduce

```sh
cargo run --release -p solarlab-runtime --example lunar_flight
cargo run --release -p solarlab-runtime --example lunar_flight -- 1
cargo run --release -p solarlab-runtime --example lunar_flight -- 10 0
cargo test -p solarlab-runtime flight_mission::tests
cargo test -p solarlab-domain -p solarlab-physics -p solarlab-runtime
```

The optional arguments are the maximum coast step in seconds (default 10) and
full-throttle burn duration in seconds (default 300). Existing Earth–Moon local
motion/periapsis safeguards may tighten the coast cap further. Zero burn
exercises the missed-departure outcome.

The default run reports a successful lunar flyby, actual main firing of
300.000000000 seconds, 1881.702722 kg propellant remaining and a closest observed
lunar altitude of about 480.422 km, 2.609491 simulated days after launch. It saves
and reloads after the departure and checks bit-identical subsequent physical
state. Coast uses the same solver at a requested 10,000× simulation rate.

## Authored initial conditions and flight plan

The dedicated scenario accepts only the untouched bundled Earth–Moon lab at its
canonical epoch. Scenario identity, seed provenance, body identities/classes,
positions, velocities, inertial/source masses and radii are checked before
mutation. An evolved or edited laboratory is rejected. Clients create a separate
fresh lab session, preserving existing experiments.

The spacecraft starts 400 km above the Earth. Its orbital plane is the actual
plane defined by the bundled Earth-to-Moon position and relative velocity. The
radial direction is rotated −2.578 radians from Earth-to-Moon in that plane.
Its initial tangential speed is the circular two-body value at that radius;
Earth's barycentric position and velocity are retained. The body +Z axis points
along that tangent. This is deliberately authored scenario setup, followed by
full Sun/Earth/Moon Newtonian gravity, rather than an assertion that the initial
circle is an exact three-body solution.

The reference spacecraft has 4000 kg dry mass, 8000 kg propellant, 90 kN main
thrust and 450 s specific impulse. Full-throttle mass flow is
`90000 / (450 * 9.80665)` kg/s. The 300-second finite burn consumes 6118.297278 kg;
the corresponding ideal rocket-equation integrated thrust impulse is
3146.729982 m/s. This scalar impulse is not a claim that orbital speed increases
by precisely that amount: gravity and the turning thrust direction act throughout
the burn. The prograde assist uses bounded torque and does not snap attitude.
Spacecraft gravitational source mass remains zero.

The launch phase and finite burn were selected by running the production solver
against the bundled seed. The scenario is not a generic lunar transfer planner;
waiting before departure, altering controls, changing spacecraft parameters or
editing the astronomical model can miss the Moon. Later burns and RCS corrections
remain available. No Lambert solution or hidden correction is applied.

## Native programme and mission API

`WorldRuntime::spawn_lunar_spacecraft(ship_id, recorded_at_unix_ms)` adds the
spacecraft and its mission metadata through `SpawnSpacecraft`.
`start_lunar_departure(ship_id, recorded_at_unix_ms)` starts the remaining portion
of the nominal 300 full-throttle-equivalent seconds.

The reusable `StartGuidedBurn { body_id, full_throttle_seconds }` command starts
full-throttle prograde flight. It accepts positive finite durations up to one
simulated day, requires a reference body, a main engine and fuel, and lowers
playback to 1×. The physics integrator splits at the programme's remaining time
and at propellant depletion. At either boundary it cuts throttle and selects
Hold (angular-rate damping). Every explicit `SetFlightControl` command cancels
the programme; further manual burns remain possible. Removing a required
reference/mission target cuts the programme as well.

`SpacecraftState::main_engine_full_throttle_seconds` integrates actual commanded
main-engine firing, including partial throttle and the fraction of a step with
remaining fuel. It excludes RCS propellant use. It is not inferred from total
tank depletion. Saving mid-programme preserves remaining duration and the
accumulator; resuming does not restart a 300-second timer.

`lunar_mission_telemetry(ship_id)` is read-only. It exposes simulation epoch,
launch epoch, elapsed time, phase/outcome, actual firing, programme activity and
remaining duration, Earth altitude, Moon altitude/range rate/relative speed,
closest observed lunar altitude and epoch, and remaining propellant. Negative
Moon range rate means approaching. UI wall clocks and coast predictions are
not sources of mission truth.

The mission component stores identity, target ID, launch epoch, closest actual
range/epoch, passage of that closest approach and observed surface crossings.
It stores no mutable position or velocity. These observations update at each
actual gravity integration boundary, including time warp, inside the staged
operation. Cancellation discards them. They publish atomically with the physical
world and persist through save/checkpoint/branch operations.

Mission or programme documents carry `flight_model: six-dof-strang-rocket-v2`.
A client that understands only the earlier flight model rejects the document
instead of silently ignoring an engine cutoff. The current reader still accepts
ordinary v1 flight documents; laboratory-only documents remain unchanged.

## Explicit mission outcomes

| Observation | Native feedback |
| --- | --- |
| Fresh scenario, before departure | Ready |
| An actual thrust control is active with fuel | Powered flight |
| Engine off, target still distant | Coast |
| Moon surface altitude below 60,000 km with negative range rate | Lunar approach |
| The closest observed approach has passed, altitude between spacecraft radius (8 m) and 1000 km, and no surface crossing recorded | Flyby achieved |
| Separation reaches Earth or Moon radius plus spacecraft radius | Respective surface crossing |
| Outside 925,000 km from Earth, outward motion and positive osculating Earth-relative two-body energy | Escaping Earth indicator |
| No successful flyby or recorded surface crossing by four simulated days | Missed |

These are transparent scenario rules. The 60,000 km approach band, 1000 km goal,
925,000 km escape indicator and four-day deadline are gameplay thresholds, not
additional forces or exact three-body boundaries. A surface crossing is a failure
observation; collision/contact response, destruction and terrain are not modelled.
The renderer or UI must not call it a simulated landing or physical impact
response. Closest approach is a sample at solver boundaries, not an interpolated
or analytically solved continuous minimum.

## Numerical evidence and limits

The native example uses identical initial conditions, finite burn and bounded
attitude controller for these coast refinements:

| Configured coast cap | Closest lunar altitude | Time after launch |
| --- | ---: | ---: |
| 10 s (further limited by the existing lab policy) | 480.421642 km | 2.609490741 d |
| 2 s | 483.170207 km | 2.609641204 d |
| 1 s | 483.702244 km | 2.609664352 d |
| 0.5 s | 483.835303 km | 2.609664352 d |

The last two refinements change altitude by about 532 m then 133 m, consistent
with the second-order translational method. The default coarse result differs
from the half-second result by about 3.4 km. The HUD therefore presents observed
simulation results, not a claim of metre-accurate lunar navigation. All tested
settings remain inside the 470–500 km acceptance interval and the authored
2.59–2.63 day encounter window. Powered and attitude integration retain a maximum
0.05-second step.

The focused tests cover a non-grid 1.137-second automatic cutoff, cancellation
and subsequent burns, RCS/main shared-tank accounting, exact fuel-limited firing,
in-flight programme save/reload, old-model rejection, fresh-seed protection,
invalid-command nonmutation, staged observation rollback, native flyby,
coast convergence, surface-crossing persistence, missing-target lifecycle and
unpowered deadline failure. Existing analytical Kepler, rocket-equation,
independent RK4 and conservation tests remain in the spacecraft foundation suite.

The model uses the bundled ephemeris only for initial conditions. Future motion
is the integrated Newtonian model, not a future JPL ephemeris. There is no
atmosphere, relativity, nonspherical gravity, terrain/contact solver, fuel slosh,
wheel momentum saturation or engine ignition model. Ideal reaction wheels apply
bounded torque; Hold damps rates rather than locking a fixed attitude. During
rate settling, the flight step remains conservative before unrestricted
coasting throughput resumes.

## Transfer map forecast and bounded refresh

After a real departure, a coasting lunar mission whose osculating apogee reaches
halfway to the Moon (or is unbound) receives an engine-off display forecast up to
three days ahead. The ordinary laboratory, local starting orbit, public archive
queries and active-burn display retain their existing paths. Future commanded
thrust is never included in this coast overlay.

The transfer display runs the existing gravity/KDK solver in temporary buffers
with adaptive local-distance/motion timestep ceilings, capped by both the user's
configured step and 60 seconds. This is a declared display approximation: it does
not retain the departure periapsis timestep throughout distant coasting. Actual
flight continues to use its stronger, versioned periapsis-aware policy. Samples
are spaced at no more than one sixty-fourth of the predicted shortest local
period, capped at one hour, with exactly matching Earth/Moon/ship epochs. Drawing
stops before a sampled surface crossing. Work is capped at 32,768 gravity steps
and 512 samples; stricter configurations can therefore shorten the shown horizon.
If no sample fits the budget, the display omits the forecast rather than falling
back to an unbounded computation.

The cache is derived, non-persisted data guarded by a mutex; runtime cloning resets
it. Physical edits, controls, target removal and branch changes invalidate it.
Focus, camera and viewport changes do not re-integrate. Coasting refresh uses a
local 30–900 simulated-second interval and a 500 ms minimum elapsed build interval
to limit work during high warp. Samples before the current epoch are trimmed and
all bodies receive a current-state anchor. A control change rebuilds immediately
when the longer coast display is eligible. Cache age is available through
`lunar_coast_forecast_diagnostics()` along with source/end epochs and work counts.
The approximation and possible cache age apply only to the overlay; mission
outcomes and the closest-observed HUD remain actual committed solver results.

A local optimized native run at a configured 60-second cap produced the complete
three-day forecast using 171 samples and 5040 gravity steps. Its sampled closest
lunar altitude was 484.417 km, compared with the authoritative example's
480.422 km. One local scene-build measurement was 0.740 ms, and the immediate
cache hit was 0.094 ms. These are single measurements on the development host,
not Android or Galaxy S25 performance evidence. The native example prints the
measured costs so hosted/device lanes can collect their own evidence.

Dedicated tests verify transfer reach, paired epochs, bounded first sample,
physical/save nonmutation, cache reuse across view changes, the high-warp refresh
gate, control invalidation, fine-step work limits and unchanged local-orbit
sampling. A separate long-operation regression selects a 0.05-second schedule
before the 300-second burn and advances an entire day (1,728,000 substeps): staged
observation epochs use compensated summation, remain inside the committed time
boundary, and save/reload successfully without relaxing persistence validation.
