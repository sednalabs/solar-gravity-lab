# Conformance Lab

This crate is the first runnable v2-native scientific harness.

It intentionally starts small and deterministic:

- `major-body-orbit-telemetry` checks coarse major-body propagation against the
  current legacy drift ceilings plus a finer baseline.
- `added-body-repeatability` proves the authoritative command path stays
  deterministic when a custom body is introduced.
- `collision-playback-cap` checks the conservative playback guard that keeps
  collision-enabled modes from silently stretching solver substeps.

Run it with:

```bash
cargo run -p solarlab-conformance -- --pretty
```

List the available scenarios with:

```bash
cargo run -p solarlab-conformance -- --list-scenarios
```
