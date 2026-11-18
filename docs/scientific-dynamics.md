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
fuel/attitude/contact flow, use `spacecraft::advance_spacecraft_steps_reported`
on staged body and spacecraft buffers and retain the returned actual-method
report. The solver backend/path continues to describe the monopole force
kernel; J2 corrections share that kernel's results and are added in scalar f64.

The older nonfallible `advance_authoritative_*` APIs now honour an implemented
method selected in `PhysicsPolicy`, using default adaptive settings and point
masses. They preserve the input on a numerical rejection; the form returning
`SolverExecutionReport` supplies `integration_error`. New runtime/prediction
callers must use checked APIs and the world's complete `ScientificSettings`.
The old scalar-only signature cannot return a numerical error and should not
be used for user-selected scientific forecasts.

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
