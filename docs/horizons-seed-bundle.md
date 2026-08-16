# Horizons Seed Bundle Handoff

> **Historical handoff note.**
>
> This document describes the cartesian TSV seed format originally designed for
> drop-in ingestion under `app/src/main/assets/ephemeris/`. In the v2
> architecture, canonical startup states and scenario seeding are owned by the
> Rust data crate (`solarlab-data` / `engine/data`), with versioned protobuf contracts
> in `proto/solarlab/v2` (`EphemerisBundle`, `ScenarioPackage`). Keep this file as
> reference for the underlying TSV schema and JPL Horizons query parameters.

This project is structured so an authoritative cartesian seed bundle can provide start vectors for the Sun, planets, and dwarf planets.

## Drop-in target path (v1 reference)

Place the generated file at:

`app/src/main/assets/ephemeris/solarlab_horizons_seed_bundle_v1.tsv`

The reference app loads it automatically at startup. If the file is missing or invalid, the app falls back cleanly.

## Bundle format

The file is UTF-8 text with:

- `key=value` metadata header lines
- a separator line: `---`
- a tab-separated body table

Required metadata keys:

- `bundle_version`
- `dataset_name`
- `source`
- `epoch_jd_tdb`
- `center_id`
- `frame`
- `time_scale`
- `position_units`
- `velocity_units`

Required data columns in this exact order:

`body_id`	`name`	`target`	`center_id`	`frame`	`epoch_jd_tdb`	`x_m`	`y_m`	`z_m`	`vx_mps`	`vy_mps`	`vz_mps`	`source`

## Required bodies for a full bundle

- `sun`
- `mercury`
- `venus`
- `earth`
- `mars`
- `jupiter`
- `saturn`
- `uranus`
- `neptune`
- `ceres`
- `pluto`
- `haumea`
- `makemake`
- `eris`

## Recommended Horizons settings

Recommended generation shape:

- cartesian vectors, not orbital elements
- one shared epoch for every body
- one shared center for every body
- one shared frame for every body
- TDB timescale
- geometric states, not light-time corrected states
- output in km and km/s, then convert to m and m/s before writing the bundle

A good baseline for external agents is:

- `EPHEM_TYPE=VECTORS`
- `VEC_TABLE=2`
- `TIME_TYPE=TDB`
- `REF_SYSTEM=ICRF`
- `VEC_CORR=NONE`
- `OUT_UNITS=KM-S`
- `CSV_FORMAT=YES`

## Expectations on the imported states

- all rows must share the same `epoch_jd_tdb`
- all rows must share the same `center_id`
- all rows must share the same `frame`
- positions must be in metres
- velocities must be in metres per second
- `earth` should be the actual Earth body state, not the Earth-Moon barycenter, unless the bundle clearly documents otherwise

## Validation already implemented

The app now validates:

- metadata completeness
- unit declarations
- timescale declaration
- per-row center consistency
- per-row frame consistency
- per-row epoch consistency

If validation fails, the bundle is ignored and the app falls back cleanly.
