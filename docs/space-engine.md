# Spaceflight foundation

Programme w18994, streams w18995–w18999, separate from scientific fidelity and
Earth–Moon laboratory. Baseline main `20c1ae79d15889fbf02e6fd376335eeed1810003`.
Integration branch `feature/spaceflight-foundation-20261118`. Hardware PR249 is
independent. Main is newer than published alpha.2; source, hosted tests, APK and
physical S25 acceptance are separate claims.

| Authority | Existing foundation | Flight extension |
| --- | --- | --- |
| Rust runtime | IDs, positions, velocity, commands, history | Attached spacecraft state, same world/checkpoints |
| Rust physics | Source-aware FP64 KDK and SIMD | Propulsion/attitude splitting, fuel depletion |
| Rust domain | Body identity/classification | Quaternion, inertia, engines, fuel, controls, frames |
| Scene/Vulkan | Camera-relative immutable export | Precise mesh pose, triangles, materials, exhaust |
| Android | Native lab, lifecycle, saves | Dedicated Flight Mode through same JNI |
| Validation Lab | Public Rust/FFI/Android/emulator | New production-path flight coverage |

SI/f64 throughout. Body +Z is nose/thrust, +X right, +Y up. Unit quaternion maps
body into inertial axes. Angular velocity/inertia use body axes. BodyState alone
owns position/velocity. Dry plus fuel mass is inertial mass; source mass remains
independent and defaults to zero. Forces enter symmetric integration around the
existing gravity solver with rocket-equation impulse and depletion boundaries.
Rotation includes the gyroscopic term.

The configurable reference ship has 4,000 kg dry mass, 8,000 kg fuel, 90 kN main
thrust at 450 s specific impulse, 800 N RCS axes at 300 s, fixed principal inertia
and ideal bounded reaction wheels. Wheel saturation/power, fuel slosh, drag,
relativity, resolved collisions and structural dynamics are not simulated.
Launch authors an approximate 400 km circular ICRF-XY orbit around current Earth;
this is not a JPL spacecraft vector. Powered input caps warp to1x; fine attitude
steps can limit achieved coasting speed. Rust instrumentation is osculating
two-body estimates. Numerical orbit paths are coast predictions, excluding
future throttle, not guaranteed intercepts or planned-burn solutions.

## Composition and local interactions

Typed components use BodyId and existing lifecycle commands. Solver indices are
private to staged operations. No ECS dependency is needed for the first ships.
[hecs](https://docs.rs/hecs/latest/hecs/) was evaluated as a small query-oriented
store; [bevy_ecs](https://docs.rs/bevy_ecs/latest/bevy_ecs/) adds systems/resources
and parallel scheduling. Neither warrants replacing the current authority.
Reconsider internal storage when measured gameplay populations justify it.

[Rapier](https://rapier.rs/docs/user_guides/rust/rigid_bodies/) is the candidate
for contacts, joints, spatial queries and CCD. This slice has no contact solver
or docking. Moving-frame transforms subtract origins in f64 and include angular
velocity transport. A future contact island must own each bounded local step,
apply gravity once and publish changes atomically to the existing world. Never
integrate independent Rapier and celestial positions for one entity.

## Reproduction

```sh
cargo test -p solarlab-domain -p solarlab-physics -p solarlab-runtime -p solarlab-ffi
cargo run -p solarlab-runtime --example orbital_flight
cargo check -p solarlab-ffi --target aarch64-linux-android
python3 tools/test_import_spacecraft_gltf.py
```

Flight saves use version2 and flight_model `six-dof-strang-rocket-v1`; ordinary
lab saves retain v1. Unsupported clients reject flight rather than losing its
components. Scene ABI13/body stride296 transports mesh facts and precise offsets.
The original glTF asset includes documented provenance under
[assets-src](../render/android-vulkan/assets-src/).

Tests cover Kepler and independent RK4 references, rocket-equation delta-v,
step convergence, torque, fuel, lifecycle, frames and persistence. The demo
burns/coasts, saves/reopens and compares future states. Exact hosted results and
APK evidence are recorded per checkpoint; unrecorded evidence is not acceptance.
