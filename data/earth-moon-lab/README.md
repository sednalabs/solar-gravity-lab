# Earth–Moon laboratory reference data

This small offline dataset supplies the **initial conditions**, not a continuous
ephemeris player, for `lab.earth-moon`.

The three targets are the Sun (10), Earth (399) and Moon (301). Each checked-in
response was obtained from the NASA/JPL Horizons API on 15 November 2026 UTC.
The matching query file records the full URL, parameters, retrieval time and
SHA-256 of the response. The returned ephemeris source is **DE441**.

All states are **geometric**, with no light-time or aberration correction, in
the **ICRF equatorial frame**, relative to the **solar-system barycentre**.
The initial epoch is **JD 2451545.0 TDB**, 1 January 2000 at 12:00 TDB. The
runtime clock is elapsed SI seconds from this epoch. TDB is not a UTC timestamp.
Positions and velocities are converted from km and km/s into metres and m/s.

The raw responses also contain reference states after 1, 7 and 30 days. These
allow external-reference error checks without making network access part of a
build or test. The generator verifies frame, origin, time scale, target,
response digest and finite vector data before producing native constants:

```bash
python3 tools/generate_earth_moon_reference.py
python3 tools/generate_earth_moon_reference.py --check
```

## Model scope

After the starting epoch, Solar Gravity Lab integrates its own Newtonian,
three-body Sun–Earth–Moon system. The initial gravitational parameters come
from the GM values in the Horizons physical summaries; mass is GM divided by
the solver's G, avoiding unnecessary rounding of the gravitational parameter.
The renderer consumes the world and cannot change its physical positions or
mass.

Other planetary perturbations, nonspherical gravity, tides, relativistic terms
and collision response are absent. Error against subsequent DE441 states
therefore includes **model difference**, not just integration error. Fine-step
comparisons separately measure numerical integration error. Creating or editing
an object creates a sandbox experiment, even though its starting state remains
traceable to this dataset. Synthetic decorative moons are not part of this pack.

## Sources

- [Horizons API documentation](https://ssd-api.jpl.nasa.gov/doc/horizons.html)
- [Horizons manual](https://ssd.jpl.nasa.gov/horizons/manual.html)
- Exact queries and original responses in this directory.

## Recorded characterisation

The `earth-moon-reference` conformance scenario propagates the unedited seed at
60-second maximum steps and repeats it with 5-second steps. On the scalar Rust
backend the initial characterisation was:

| Elapsed time | Moon relative to Earth: difference from DE441 | 60 s versus 5 s integration difference |
| --- | ---: | ---: |
| 1 day | 2.44 m | 0.305 m |
| 7 days | 157.86 m | 1.914 m |
| 30 days | 1,647.17 m | 18.373 m |

These are measured differences for this initial epoch and unedited model, not
accuracy guarantees for user experiments. The larger DE441 difference includes
physical-model omissions. The smaller step comparison measures numerical
convergence within the same three-body model. The regression ceilings are
intentionally broader than these observations: 10 / 500 / 5,000 metres against
DE441 and 2 / 10 / 50 metres between the two step sizes. Edited low-orbit probes
require a tighter maximum step.

Reproduce with:

```sh
cargo run -p solarlab-conformance -- --scenario earth-moon-reference --pretty
```

## Playback and edited worlds

The laboratory uses the lesser of its configured 60-second ceiling and a
state-derived local ceiling. Before each operation it considers gravitating
pairs, limiting the step to 0.005 of the local gravitational timescale and 0.01
of the relative crossing time, with a 0.05-second floor. The 800 km Earth-orbit
probe preset therefore receives approximately 4.8-second steps. Its one-orbit
convergence test requires a difference below 500 metres from a 0.5-second
integration of the same four-body model.

The step remains fixed within an operation; this is not an adaptive symplectic
solver. Automatic playback is still bounded to 64 integration steps per tick.
Long manual advances remain staged and cancellable. Close encounters and
collisions are not qualified by these tests. The chosen algorithm is part of
the versioned saved-experiment model identifier, and all other scenarios retain
their existing timestep policy.
