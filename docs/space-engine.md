# Spaceflight foundation

Programme w18994, streams w18995–w18999 and docking extension w19001, separate
from scientific fidelity and the Earth–Moon laboratory. A–E are merged at main
`dac95def8a6dda19736f1f41f3261fb19afc082d`; exact-source hosted proof is
[Validation Lab 44348359492](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44348359492).
Docking extends that baseline on `feature/docking-flight-20261119`. Hardware
PR249 and follow-up w19000 retain independent ownership. Source, hosted tests,
installable debug artifacts, tagged releases and physical S25 acceptance are
separate claims.

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
relativity and structural dynamics are not simulated. Local rigid contact is
explicitly opted in by the separate docking scenario; ordinary orbital flight
and the laboratory do not gain universal collision response.
Launch authors an approximate 400 km circular ICRF-XY orbit around current Earth;
this is not a JPL spacecraft vector. Powered input caps warp to 1x; fine attitude
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

[Station docking](station-docking.md) implements the first local contact path
using Rapier f64. Opted-in zero-source-mass bodies retain meaningful inertial
mass. Rapier owns their bounded local drift between authoritative gravity
half-kicks and publishes through the existing atomic advance. Moving frames
subtract astronomical origins in f64; port observations include angular
transport. There are no independent mutable Rapier and celestial pose stores.
Authored hulls, CCD and an actual-pose fixed attachment serve the nearby reference
pair; general spatial queries and widely separated contact islands remain open.

## Reproduction

```sh
cargo test -p solarlab-domain -p solarlab-physics -p solarlab-runtime -p solarlab-ffi
cargo run -p solarlab-runtime --example orbital_flight
cargo run --release -p solarlab-runtime --example lunar_flight -- 60
cargo run --release -p solarlab-runtime --example orbital_rendezvous
cargo run --release -p solarlab-runtime --example orbital_rendezvous -- match
cargo run -p solarlab-runtime --example station_docking
cargo check -p solarlab-ffi --target aarch64-linux-android
python3 tools/test_import_spacecraft_gltf.py
```

Flight saves use format version2. Mission/burn saves declare flight_model
`six-dof-strang-rocket-v2`; persistent rendezvous or station appearance declares
`six-dof-strang-rocket-rendezvous-v3`; docking components, including checkpoints,
declare `six-dof-strang-rocket-docking-v4`. Ordinary lab saves retain v1. The reader
retains earlier flight models, while older clients reject unsupported
programmes instead of silently discarding their controls. Scene ABI13/body stride296
transports mesh facts and precise offsets.
The original glTF asset includes documented provenance under
[assets-src](../render/android-vulkan/assets-src/).

Tests cover Kepler and independent RK4 references, rocket-equation delta-v,
step convergence, torque, fuel, lifecycle, frames and persistence. The demo
burns/coasts, saves/reopens and compares future states. Exact hosted results and
APK evidence are recorded per checkpoint; unrecorded evidence is not acceptance.

## Implemented scenarios

Android Flight Mode provides touch attitude/RCS controls, throttle, orientation
assists, close/chase cameras, an orbit map, checkpoints and ordinary session
saves. Flight map framing preserves controlled-ship selection. The perspective
renderer shares logarithmic depth between the actual spacecraft triangles and
the Earth/Moon backgrounds; ordinary laboratory projection is unchanged.

The lunar entry first saves the current complete laboratory, then creates an
isolated canonical Earth–Moon session. Its optional guided departure applies
300 full-throttle seconds through the same native force integration. Physics
owns cutoff and persisted closest-approach observations. Manual input cancels
the programme, and a later request uses the actual remaining firing time.
Mission rules and numerical convergence are documented in
[lunar-flight.md](../engine/physics/benchmarks/lunar-flight.md).

The lunar map's bounded, cached engine-off forecast is a display approximation.
It uses separate temporary solver buffers and never supplies mission outcomes.
Full trajectory prediction and native observed encounter distances must not be
confused. A strict forecast work budget can shorten its horizon.

[Orbital rendezvous](orbital-rendezvous.md) demonstrates native reuse with a
passive station and an RCS chaser, including persistent approach and velocity
matching inside native integration. It has executable examples and analytical,
convergence and persistence tests. Android station interaction consumes these
same commands and observations; visual and interaction acceptance require its
exact-source hosted journey evidence. That scenario retains its contact-free
75 m stand-off objective. The separate [30 m docking mission](station-docking.md)
adds force-driven approach, safe physical collar contact, a retained fixed
attachment, and release followed by manual RCS departure.
