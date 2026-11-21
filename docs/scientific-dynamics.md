# Scientific flight dynamics

SGL has three implemented integration choices sharing the same gravity kernel
and physical model. Leapfrog KDK remains the default. Choosing an algorithm
changes the trajectory calculation; it is no longer an inert label. Scientific
settings are domain data, so runtime commands, persistence, authored scenarios
and predictions can select the same calculation.

## Choosing a method

| Method | Suitable work | Accuracy and cost | Boundaries |
| --- | --- | --- | --- |
| Leapfrog kick–drift–kick | Routine flight and long smooth coasts | Second order; established scalar/SIMD point-mass path is preserved | Fixed time step; resolve close approaches by choosing a sufficiently small step |
| Yoshida fourth order | Smooth conservative coasts that need lower phase error | Symmetric composition of three KDK steps; fourth order, six gravity evaluations per outer step in this implementation | Signed intermediate gravity steps; no automatic close-approach accuracy control |
| Dormand–Prince 5(4) | Eccentric periapsis passages and close flybys | Fifth-order accepted state, fourth-order embedded local error estimate; seven force evaluations per trial | Adaptive, not symplectic; no regularisation of collision singularities and no guarantee of global or ephemeris accuracy |

The historical `adaptive-symplectic` identifier is explicitly unsupported. It
does not alias one of these methods. There is no automatic numerical downgrade
after a rejected step.

Powered flight, attitude control and rigid contacts use the existing positive
time KDK gravity split. Fuel use and contact impulses cannot be composed through
Yoshida's negative time stage, and adapting only the gravity part cannot claim
fifth-order accuracy for the combined powered trajectory. The split retains the
selected gravity model, including J2. Actual execution reports identify the
requested method, the effective method and whether the nonconservative split
was used, including an operation in which a burn finishes partway through.

## Adaptive encounter control

`AdaptiveTolerance` has separate absolute position and velocity tolerances,
a relative tolerance, a minimum time step, a trial budget and an encounter
fraction. Defaults are `1e-3 m`, `1e-6 m/s`, `1e-10`, `1e-6 s`, 4096 trials per
outer interval, and 0.2 respectively. These are local error controls. Tighter
values require more computation and can expose the finite precision of the
initial states.

The maximum of the position and velocity error ratios over all bodies determines
acceptance. Relative scales use distance and speed relative to the nearest
massive source. A lunar flyby therefore does not inherit a Sun-distance-sized
position allowance. Each outer interval is translated and boosted into a local
inertial frame before evaluating the Runge–Kutta stages, then restored once;
large common position and velocity offsets do not inflate the tolerances.

An additional step bound uses both the pair crossing time `r / v` and dynamical
time `sqrt(r³ / mu)`. Embedded error estimates alone can miss the impulse from a
compact encounter skipped by an excessively long trial. This bound also applies
when an error estimate happens to be very small. It is an encounter-resolution
guard, not continuous collision detection.

The solver rejects work when it exhausts the configured accepted-plus-rejected
trial budget, requires a smaller step than permitted, creates a nonfinite
intermediate state, or enters an invalid gravity-model domain. Checked segments
stage their results privately and commit only on success. The blocking checked
entry point also keeps the full requested advance atomic. Runtime spacecraft
flow operates on caller-owned staged state, which must be discarded on failure.
An error does not silently disable J2 or retry using another integration method.

The contact split also preserves the reason for rejection. A
`ContactResolutionLimit` means the local contact solve cannot resolve the
encounter within its bounded subdivision budget; `ContactMotionEnvelope` means
the solved motion exceeded the supported interaction bound. Neither is treated
as an invalid spacecraft definition. Gravity-domain errors, including entry
below a J2 reference radius, keep their original scientific classification even
when they arise during contact flow. Runtime rejection leaves the committed
world and exported saved session unchanged.

The fixed outer schedule still determines caller observation/commit boundaries.
Adaptive trial times are internal to that schedule. This implementation has no
dense-output event locator. Surface-crossing and closest-approach observations
retain the runtime's existing observation resolution; small internal adaptive
steps do not by themselves turn those observations into exact event times.

## An explicit, oriented J2 gravity model

`ZonalHarmonic` binds a massive source by stable `BodyId`, with unnormalised J2,
reference radius in metres and a unit pole vector in the simulation's inertial
position axes. It never reads a renderer's artistic axial tilt, scene rotation,
material or display name. Importers must convert poles into the world's frame;
an equatorial J2000 pole is not automatically an ecliptic pole.

For source-relative position **r**, distance r, unit pole **n** and
`s = dot(n, r) / r`, the added specific potential is

\[
\Phi_{J_2}=\frac{\mu J_2 R^2}{2r^3}(3s^2-1).
\]

The implementation uses the negative gradient of this same potential for the
acceleration. Equatorial positive J2 strengthens inward gravity; at a pole the
J2 correction points outward. The full field also includes the usual monopole.
For a massive target, the source receives the opposite force. A massless tracer
receives acceleration without backreaction. If both bodies have J2, each
monopole–quadrupole term is included; quadrupole–quadrupole coupling is omitted.

`plan.compute_invariants` includes the J2 potential energy. Linear momentum and
energy are appropriate diagnostics. With one fixed symmetry axis, the component
of orbital angular momentum along that axis is conserved; the full orbital
angular-momentum vector generally is not. Planetary spin torque is not evolved,
so this model treats fixed axes as an unmodelled angular-momentum reservoir.

This is an exterior leading zonal-harmonic model. It rejects nonzero J2 below the
source reference radius instead of extending a singular exterior expansion into
a planet. It is not a geoid, terrain field, atmospheric model, relativistic
correction, time-dependent pole model or full spherical-harmonic expansion.
J2 is opt-in; existing point-mass worlds remain unchanged. Explicit zero J2 is a
disabled term.

## Public API and consistency

The persisted types live in `solarlab_domain::science`; `IntegratorKind` is also
re-exported from `solarlab_physics` for existing callers.

```rust
use solarlab_physics::{fixed_step_plan_with_source_count, MAX_INCREMENTAL_STEPS};
use solarlab_physics::science::advance_fixed_steps_checked;

// IDs must describe this exact solver-buffer ordering throughout the plan.
let plan = fixed_step_plan_with_source_count(
    &policy, duration_seconds, bodies.len(), source_count, &cpu_features,
).ok_or("invalid plan")?
 .with_scientific_settings(&settings, &body_ids)?;

let mut remaining = plan.total_steps;
let mut numerical_report = plan.empty_scientific_report();
while remaining > 0 {
    let count = remaining.min(MAX_INCREMENTAL_STEPS);
    numerical_report.accumulate(&advance_fixed_steps_checked(&plan, &mut bodies, count)?);
    remaining -= count;
}
let diagnostics = plan.compute_invariants(&bodies)?;
let mut execution_report = plan.report();
numerical_report.apply_to_solver_report(&mut execution_report);
```

For an atomic blocking advance, use `advance_authoritative_scientific`. For
fuel/attitude/contact flow, use `spacecraft::advance_spacecraft_steps_checked`
on staged body and spacecraft buffers and retain the returned actual-method
report. This checked flow preserves numerical rejection reasons; the older
`advance_spacecraft_steps_reported` wrapper maps them to a legacy unit error.
Counts refer to accepted positive intervals (adaptive substeps for DP, outer
steps for KDK/Yoshida); signed Yoshida stages are counted as force evaluations,
not separate accepted intervals. The solver backend/path describes the monopole force
kernel; J2 corrections share that kernel's results and are added in scalar f64.

The older nonfallible `advance_authoritative_*` APIs now honour an implemented
method selected in `PhysicsPolicy`, using default adaptive settings and point
masses. They preserve the input on a numerical rejection; the form returning
`SolverExecutionReport` supplies `integration_error`. New runtime/prediction
callers must use checked APIs and the world's complete `ScientificSettings`.
The old scalar-only signature cannot return a numerical error and should not
be used for user-selected scientific forecasts.

## Native orbit navigation

`OrbitGuidance::from_relative` supplies the same navigation to native telemetry
and the before/after ends of a finite-burn receipt. It uses a primary-relative
position and velocity in inertial axes and the same gravitational parameter as
`OrbitalElements`. Clients display these values rather than reconstructing
orbital physics in Kotlin or JavaScript.

For an elliptic orbit, native guidance converts the state to eccentric anomaly
and Kepler mean anomaly to find the next periapsis and apoapsis. A current apsis
has a zero countdown; immediately after that passage, its countdown is to the
next orbit. A hyperbolic approach has a future periapsis but no apoapsis; after
periapsis there is no future periapsis countdown. Apsides are also provided as
host-relative vectors in the input inertial axes, so direction is preserved for
inclined and retrograde orbits.

The availability contract is deliberate:

* Eccentricity below `1e-6` is `nearCircular`: a numerical direction is not a
  useful distinguished apsis, so both countdowns and markers are absent.
* `abs(e - 1) <= 1e-8` is `nearParabolic`: geometric periapsis remains useful,
  but a finite-period anomaly conversion is too ill-conditioned for a countdown.
* Zero/nonfinite inputs, invalid gravitational parameters, and an effectively
  radial state (transverse speed at most `1e-10` of total speed) have no guidance.
* Optional numbers are either finite or absent; unavailable events never become
  zero-valued placeholders or infinite periods.

The ideal circularisation impulse at the current radius is
`delta_v = sqrt(mu / r) * transverse_unit - relative_velocity`. It preserves the
orbital plane and sense while cancelling radial speed. Subtracting the current
scalar speed from circular speed is insufficient away from an apsis. For
example, at 7000 km radius with radial speed 3000 m/s and transverse speed
7000 m/s, the scalar speed difference is under 100 m/s, but the required vector
impulse exceeds 3000 m/s. The result does not certify that the ship has enough
fuel, can turn in time, or can achieve the impulse with a finite burn. Use the
native finite-burn preview to inspect actual attitude, thrust and fuel flow.

These are instantaneous osculating two-body estimates. They do not propagate
other bodies, J2, current thrust, atmosphere, or terrain to an exact future
event. A finite burn still uses the full configured gravity model; its attached
end-point navigation remains a local conic estimate. NAIF's
[osculating-element contract](https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/C/cspice/oscelt_c.html)
explains the inertial-state requirement and the circular, radial and
near-parabolic singularities. It is a reference for the validity boundaries,
not a claim that SGL uses CSPICE or has matched a planetary ephemeris.

The independent reference generator
`engine/runtime/benchmarks/generate_navigation_reference.py` integrates
Cartesian point-mass motion with SciPy DOP853 and locates zeroes of radial
velocity. It does not import SGL or compute event times from Kepler anomalies.
Eleven checked-in cases cover inclined bound motion in both senses, a strongly
eccentric orbit on each side of apoapsis, inbound/outbound lunar hyperbolas,
a small but resolved eccentricity, and periapsis approaches immediately outside
the near-parabolic boundary on both sides of escape. Small inbound anomalies
use stable series and signed phase calculations: an angular roundoff threshold
would otherwise erase seconds of travel when the period is enormous. A second tighter integration with a
halved maximum step checks fixture uncertainty. Rust repeats the comparisons
after an independent spatial rotation, checks impulse reconstruction, current
and next passages, unavailable domains, common position/velocity offsets and
read-only finite-burn receipt consistency.

```sh
cargo test -p solarlab-runtime --test navigation_guidance
cargo test -p solarlab-runtime --lib flight_telemetry
cargo test -p solarlab-runtime --lib scientific_flight
```

Initial local comparison across both orientations gave a maximum event-time
difference of `4.08e-8 s` and apsis-position difference of `7.45e-5 m`. The
fixture's own refinement differed by at most `1.51e-7 s` and `0.00114 m`.
These are synthetic point-mass checks, not an error bound for perturbations,
long forecasts or real flight. Hosted exact-source qualification and physical
Galaxy acceptance remain separate evidence.

## Independent validation

Run the Rust suite without Python or network access:

```sh
cargo test -p solarlab-physics --test scientific_reference
cargo run -p solarlab-physics --example scientific_comparison
```

The suite uses four distinct reference techniques:

* Elliptic and hyperbolic Kepler geometry, solving anomalies rather than running
  another integrator, checks phase accuracy and convergence order.
* Numerical differentiation of the explicit J2 potential checks the force;
  rotated axes, reciprocal forces and invariant checks exercise the model's
  orientation and massive-body coupling.
* First-order secular nodal precession checks the accumulated direction and
  magnitude of J2's orbital-plane effect. Its 1.5% bound acknowledges
  osculating/mean-element and short-period differences; it is not an exact J2
  solution.
* Checked-in DOP853 trajectories from a separate Python/SciPy program check
  three gravitational sources plus a passive craft, with and without two tilted
  J2 sources. The Python implementation rotates to each pole's Cartesian frame
  before evaluating the field. It does not call or import SGL.

The fixture generator lives at
`engine/physics/benchmarks/generate_scientific_reference.py`. It runs two
DOP853 integrations with different tolerances and step caps, checks their
agreement, then writes the CSV and JSON provenance containing package versions,
controls, refinement differences and SHA-256 hashes. Regeneration requires
NumPy and SciPy; fixture consumption in Rust has no additional dependency.

```sh
python3 engine/physics/benchmarks/generate_scientific_reference.py
```

Measured local x86_64 results for the initial implementation:

| Reference case | Observed result |
| --- | --- |
| Ellipse, eccentricity 0.3, halve step 512 to 1024 intervals | KDK endpoint error 5980.563 to 1495.105 m; ratio 4.00010. Yoshida error 2.421452 to 0.151358 m; ratio 15.99814 |
| Eccentricity 0.95, one full orbit from apoapsis | Loose adaptive endpoint error 591.897 m / 79 accepted steps; tight controls 0.011409 m / 564 steps; accepted h from 1.787 to 396.427 s |
| Hyperbola, 50 km periapsis, eccentricity 1.5 | Tight adaptive endpoint error 3.83 micrometres and 1.08e-9 m/s; 270 accepted steps |
| Three-source and two-tilted-J2 DOP853 fixtures, 4 hours | Yoshida at 5 s: max position difference 0.02854 m. Adaptive with 300 s outer cap: max difference 0.02331 m. Both below 2.52e-5 m/s velocity difference |
| DOP853 fixture's own refinement | Maximum sampled difference below 0.000092 m and 8.2e-9 m/s across both cases |
| J2 node drift, 20 nominal circular-orbit periods | Measured -0.0849431 rad; first-order secular prediction -0.0847113 rad |

These are synthetic accuracy regressions with specified initial states and
controls. They are not universal error limits, performance comparisons at equal
cost, independent confirmation of all force laws, or an accuracy claim against
JPL Horizons. Fixture uncertainty, floating-point frame transformations and
global accumulation remain separate from the local adaptive tolerance. The
hosted validation gate and physical Galaxy build acceptance are separate
release evidence, recorded by the integration campaign.

### Independent contact references

The contact suite uses analytical impact events separately from the orbital
integrator oracles. Run it and the runtime failure checks with:

```sh
cargo test -p solarlab-physics --test contact_reference_cases
cargo test -p solarlab-runtime --test contact_rejection
```

Local x86_64 review on 19 November 2026 used contact source `d4375c1`, with
the checked scientific error path. All reference spheres have radius 1 m,
inertial mass 1000 kg and restitution 0.1. Their designated docking targets
remain distant, so these cases also exercise contacts across different pairs.

| Reference | Measured result |
| --- | --- |
| Two spheres start at x = -20, +20 m with velocities +4000, -4000 m/s; advance 0.01 s | Analytical impact at 0.00475 s, final x = -3.1, +3.1 m and velocities -400, +400 m/s. Across outer steps 0.01, 0.005, 0.0025 and 0.00125 s: maximum endpoint error 0.727 mm, inferred impact-time error 0.166 microseconds, rebound velocities agree to floating-point precision |
| Same crossing with common velocity (30000, -5000, 2000) m/s and x-origin 1.5e11 m | Relative trajectories and impulses pass the 0.1 mm / 0.0001 m/s agreement bounds; reversing the spacecraft input order gives identical results |
| Three spheres at x = -6, 0, +6 m with velocities +200, 0, 0 m/s; advance 0.06 s | Two separated analytical impacts at 0.02 and 0.056363636… s give final x = 1.6, 4.18, 6.22 m and velocities 90, 49.5, 60.5 m/s. Four outer-step sizes give maximum position error 1.932 mm and rebound velocities agree to floating-point precision |
| Simultaneous collinear and equilateral three-body encounters | Finite separated final states, conserved momentum, no increase in total translational plus rotational energy, and identical outcomes under input-order reversal; finer-step endpoint differences stay within the explicit 3 cm test bound |
| Exhausted contact-resolution budget and entry below a J2 reference radius during contact flow | Distinct scientific errors survive both blocking and incremental runtime APIs; committed snapshots and exported save bytes remain unchanged |

Exactly simultaneous rigid impacts are a separate modelling limit: a binary
restitution coefficient alone does not specify a unique physical multi-impact
law. The simultaneous tests check deterministic conservation and bounded
motion; they do not certify a uniquely correct impulse distribution. The
nondegenerate sequential case is the independent accuracy oracle for ordinary
multiple-body encounters.

These are measured examples within a finite contact-resolution envelope. The
solver limits contact-near point motion to one eighth of the smallest primitive
feature and permits at most 2048 solves, including retries, per local island
drift of at most 0.01 s. It checks physical motion and separate numerical
correction allowances before accepting the drift. Very fine geometry, extreme
motion disparity within an island, or excessive constraint correction can
produce an explicit rejection. This is not a universal continuous-collision
guarantee for arbitrary grazing geometry, nor a phone-performance claim.

## Principia and numerical references

Principia was used as an architectural and numerical reference, not imported as
an implementation or treated as a badge of accuracy. Sources were inspected at
Principia commit `95877e4b81372f62f80d2d840be34bfcd7c11fce`.

* [Principia Ephemeris](https://github.com/mockingbirdnest/Principia/blob/95877e4b81372f62f80d2d840be34bfcd7c11fce/physics/ephemeris.hpp)
  separates fixed planetary integration, adaptive massless trajectory flow,
  model accuracy, and integration status. SGL adopts explicit model/method and
  failure contracts; it does not yet provide Principia's continuous ephemerides.
* [Principia embedded Runge–Kutta–Nyström flow](https://github.com/mockingbirdnest/Principia/blob/95877e4b81372f62f80d2d840be34bfcd7c11fce/integrators/embedded_explicit_runge_kutta_nystr%C3%B6m_integrator_body.hpp)
  provides concrete reference handling for error-controlled steps, exact final
  boundaries, vanishing steps and step limits. SGL's present adaptive algorithm
  is Dormand–Prince applied to the first-order position/velocity system, not a
  claim that it uses Principia's integrator family or its validation results.
* [Principia oblate-body parameters](https://github.com/mockingbirdnest/Principia/blob/95877e4b81372f62f80d2d840be34bfcd7c11fce/physics/oblate_body_body.hpp)
  keep reference radius and physical gravity coefficients explicit. Principia's
  geopotential support is considerably broader than SGL's current J2 model.
* [Yoshida, 1990, *Construction of higher order symplectic integrators*](https://doi.org/10.1016/0375-9601(90)90092-3)
  defines the symmetric fourth-order composition used here. For a second-order
  step S, the weights are `w1=1/(2-cbrt(2))`, `w0=-cbrt(2)*w1`, `w1`.
* [SciPy RK45 reference](https://docs.scipy.org/doc/scipy/reference/generated/scipy.integrate.RK45.html)
  documents Dormand–Prince's 5(4) pair and links the original 1980 paper.
  [SciPy DOP853 reference](https://docs.scipy.org/doc/scipy/reference/generated/scipy.integrate.DOP853.html)
  describes the independent eighth-order method used for the fixtures.

Further integrator families, high-degree harmonics, fitted ephemerides,
variational equations and relativistic terms need a concrete flight/science
requirement and independent reference evidence before they become product
choices. This change supplies a bounded useful step toward that work.
