# Laboratory timestep profiles

The laboratory uses fixed-step kick–drift–kick integration. It chooses one
ceiling when an operation starts and retains the resulting `dt` for every
bounded segment of that operation. The change below is a better operation
plan, not an adaptive or relativistic solver.

`newtonian-kdk-f64-lab-step-v1` preserves the original distance/crossing policy,
including its 0.05 s floor. Existing saved experiments retain that profile.
`newtonian-kdk-f64-lab-step-v2` additionally examines each gravitating pair's
finite, resolved osculating Kepler periapsis:

- Specific angular momentum: `h = r × v`.
- Eccentricity vector: `e = (v × h)/mu − r/|r|`.
- Semilatus rectum and periapsis: `p = |h|²/mu`, `q = p/(1+|e|)`.

The implementation normalizes the radial vector before cross products to avoid
unnecessary large intermediate products. It applies the existing local gravity
and crossing-time fractions at the smaller predicted separation. It never
enlarges a user's configured maximum step.

For a periapsis inside a body's physical surface, refinement extends only to
first surface contact, where the existing candidate preview stops. Total speed
at contact follows two-body energy. This prevents a tiny solar perturbation of
a radial release from demanding the resolution of a fictitious passage through
Earth's centre before the preview reaches its surface. Exactly radial or
numerically unresolved angular momentum retains the local distance bound.

The supported step minimum is the smaller of 0.05 s and the explicitly
configured ceiling. If the resolved exterior path needs a smaller step, V2
returns an invalid plan instead of silently enlarging it. Nonfinite or
nonpositive timestep ceilings are rejected by the physics planner; they cannot
fall back to a single full requested step.

## Focused convergence evidence

Run the deterministic tests with:

```sh
cargo test -p solarlab-runtime --lib lab_step::tests -- --nocapture
```

An isolated Earth plus nonperturbing probe starts at apoapsis of a Kepler ellipse
with semimajor axis 40,000 km and eccentricity 0.82. Its periapsis radius is
7,200 km. The exact two-body solution returns to its starting position after one
period. On the local x86_64 development host:

| Operation profile | Step ceiling | One-period position error |
| --- | ---: | ---: |
| Legacy V1 | 60 s | 84,686.20 m |
| Periapsis V2 | 4.83838 s | 551.23 m |
| Fine reference of the same model | 0.5 s | 5.89 m |

V2 and the fine reference differ by 545.34 m; V2 improves this particular
analytic endpoint error by over 150 times. The test also requires exact equality
between staged and blocking execution of the chosen V2 schedule. These are
deterministic numerical regression results, not general ephemeris accuracy.

A separate 100 s radial release from 7,171 km radius remains usable and differs
from a 0.125 s reference by 0.161 m. A hyperbolic conic fixture checks the predicted
periapsis against its independently specified value. The existing complete
candidate-preview test still stops at Earth's surface after bounded work, and
the unchanged three-body laboratory retains its previously qualified 60 s cap.

## Limits

The pairwise conic estimate neglects perturbations when choosing its ceiling.
Long radial operations, motion inside a body's surface, arbitrary close
encounters, and strongly interacting multi-body systems are not thereby
qualified. The Newtonian force model still has no collision response, drag,
tides, relativity or gravity harmonics. The external JPL comparison remains a
separate three-body model check; this refinement does not turn the simulation
into a DE441 ephemeris propagator.
