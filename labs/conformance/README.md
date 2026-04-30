# Conformance Lab

This crate is the first runnable v2-native scientific harness.

It intentionally starts small and deterministic:

- `major-body-orbit-telemetry` checks coarse major-body propagation against the
  current legacy drift ceilings plus a finer baseline.
- `added-body-repeatability` proves the authoritative command path stays
  deterministic when a custom probe body is introduced with zero source mass.
- `collision-playback-cap` checks the conservative playback guard that keeps
  collision-enabled modes from silently stretching solver substeps.
- `arm64-kernel-equivalence` checks the dedicated arm64 fused-step kernel
  against the scalar oracle on the moon/earth playback scenario.
- `physics-accuracy-telemetry` lifts the older moon-host and barycenter drift
  diagnostics into the Rust-native harness so telemetry guardrails are emitted
  in the same machine-readable report as the other scientific checks.
- `one-year-earth-orbit-stability` ports the older one-year Sun/Earth
  stability proof into the Rust-native harness so long-horizon orbital drift is
  checked in the same report surface.
- `host-relative-playback-policy` checks that the newer host-relative
  short-window playback cap stays more conservative than the coarse legacy path.

The runtime crate also has focused regression coverage for source-mass
semantics: teaching probes, tracers, spacecraft, and small-body markers can
carry display mass without perturbing canonical bodies unless their source mass
is explicitly nonzero.

Run it with:

```bash
cargo run -p solarlab-conformance -- --pretty
```

List the available scenarios with:

```bash
cargo run -p solarlab-conformance -- --list-scenarios
```
