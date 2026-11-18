# Native spaceflight model and proof

This is the implemented first flight model, `six-dof-strang-rocket-v1`.
It extends the existing authoritative world. It is not a second simulation.

## State and frames

`BodyState` remains the sole store of position and translational velocity.
`SpacecraftState`, keyed by the same `BodyId`, adds a body-to-inertial unit
quaternion, body angular velocity, principal inertia, dry mass, propellant,
engine configuration and control state. The right-handed body frame uses +Z
forward/nose, +X right and +Y up. SI doubles are used throughout simulation.
The renderer receives `[x,y,z,w]`; the Rust named quaternion fields are
`{w,x,y,z}`.

The reference ship has 4,000 kg dry mass, 8,000 kg propellant, a 90 kN main
engine with 450 s specific impulse, and six directional 800 N RCS channels
with 300 s specific impulse. This is a configurable authored reference craft,
not a validated reproduction of a real vehicle. Insertion is an explicitly
approximate circular orbit relative to the selected primary in ICRF XY.
Subsequent propagation includes every active gravitational source in SGL.

The body inertial mass is always dry plus propellant mass. Gravitational
source mass remains separately authored, zero by default. Fuel consumption
therefore never silently turns a teaching spacecraft into a planetary gravity
source. The scientific gravity-only invariant counters retain their existing
source-mass semantics; they are not a closed-system energy budget for fuel and
expelled exhaust.

`LocalReferenceFrame` converts positions and velocities using a double
precision moving origin, orientation and angular velocity. Velocity conversion
includes the angular transport term, omega cross displacement. The tests use
metre/centimetre offsets at astronomical origins and require errors consistent
with the representable precision of the original doubles. Contact solvers,
docking, attachments and collision impulses have not been introduced.

## Integration

Each flight step symmetrically composes:

- half a propulsion/rotation step;
- the existing full gravity kick-drift-kick step;
- half a propulsion/rotation step.

Each active engine supplies a body-frame force, a mass flow, and a lever arm.
For net constant body force F and total positive flow q over a half step,
velocity impulse is `(F/q) * ln(m_before/m_after)`, rotated by the midpoint
attitude. This avoids treating mass as constant during a burn; `log1p` retains
accuracy for small fuel changes. Steps split at the last available propellant.
The transformed impulse enters the integration before/after the gravity drift;
there is no per-frame position or velocity replacement for flight controls.

Angular evolution uses Euler's rigid-body equation
`I * omega_dot = torque - omega cross (I * omega)`, explicit midpoint angular
integration, and a normalized quaternion exponential using midpoint angular
velocity. Engine torque is lever arm cross force. Manual wheel controls and
orientation assists produce bounded torque. Prograde, retrograde, normal and
antinormal use state relative to the designated reference body.

`Hold` is **rate damping**, not a stored-attitude lock. The first controller
models ideal reaction wheels with bounded torque but without power or wheel
momentum saturation. Balanced tank mass changes are represented in translation;
principal rotational inertia is held fixed. Slosh, variable centre of mass and
inertia, aerodynamics, contacts and relativistic corrections are absent.

Powered or actively rotating flight uses at most 0.05 s steps, with a smaller
angle-based bound for existing high spin. Coasting with settled attitude
returns to the scientific orbital step policy. Active directional assists
still require angular integration; requested warp can consequently be bounded
by the existing runtime work budget. Powered/manual rotational commands
reduce requested playback speed to 1x, and new higher warp requests while
those controls are active are rejected. Rate damping below 1e-12 rad/s is
settled to zero for coasting.

## Commands, persistence and prediction

Spawn/control commands are validated before history mutation. Integration uses
private staged solver/component buffers and publishes their translation,
attitude and fuel together. Cancellation discards all staged state. Numeric
failure rejects publication. Body removal removes its component and clears
references from other ships. Generic property editing cannot overwrite a
spacecraft's authoritative fuel-derived mass.

Branches/checkpoints preserve flight state. A flight document uses experiment
version 2 and a required flight-model identifier. Older laboratory-only saved
worlds remain version 1. This deliberately makes an old reader reject a flight
save instead of silently loading it as an unpowered point mass.

The existing prediction family is explicitly **engine-off coast prediction**.
It extrapolates from current authoritative position/velocity using the same
gravity model. It does not predict continued commanded thrust or attitude.
Its orbital step policy is independent of the current burn/rotation step cap.

## Executable evidence

Run the production runtime demonstration:

```sh
cargo run -p solarlab-runtime --example orbital_flight --release
```

Optional first argument sets burn duration in seconds, from 0 to 300. The demo
seeds SGL's Earth–Moon world, inserts the reference ship at 400 km altitude,
performs a prograde burn, damps attitude, saves/reopens and coasts both worlds.
It requires the reopened body's and component's future to match exactly.
A local host run with the default 30-second burn raised osculating apogee from
400 km to about 1,281 km, delivered about 230.94 m/s and consumed about
611.83 kg. These numbers are native host simulation evidence, not Android or
physical-device acceptance.

Focused production-path tests live in `engine/runtime/src/spacecraft.rs`:

- circular and elliptical Kepler period closure;
- finite prograde, retrograde and plane-changing burns;
- rocket equation, linear mass flow and exact exhaustion boundary;
- lever-arm torque, constant angular acceleration and quaternion orientation;
- body-frame RCS and fuel use;
- torque-based assists without attitude snapping;
- quadratic convergence to an independent RK4 translational reference;
- torque-free rotational energy and world angular momentum;
- real-time/coast warp, Hold settling, and engine-off prediction semantics;
- complete save/reload/checkpoint future continuity;
- rejected commands, cancellation, component lifecycle and read-only scene export.

Frame and quaternion tests live in `engine/domain/src/spacecraft.rs`.
Hosted validation and source revision belong in the integration PR evidence;
compilation or these tests alone do not prove native Android interaction,
Vulkan pixels, installed-phone responsiveness or battery/thermal performance.
