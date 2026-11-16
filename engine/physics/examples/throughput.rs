//! Repeatable release-mode CPU measurements; this is not a device/thermal benchmark.
//! Run `cargo run --release -p solarlab-physics --example throughput -- --samples 11`.
use std::hint::black_box;
use std::time::{Duration, Instant};

use solarlab_data::{canonical_startup_seed, earth_moon_lab_bodies, CanonicalBodySpec};
use solarlab_domain::{BodyClass, Vector3d};
use solarlab_physics::{
    advance_authoritative_scalar, advance_authoritative_with_features, detect_cpu_features,
    solver_equivalence_metrics, CollisionModel, IntegratorKind, MassiveBodyState, PhysicsPolicy,
    SolverBackend,
};

const G: f64 = 6.67430e-11;

fn solver_body(body: CanonicalBodySpec) -> MassiveBodyState {
    let source_mass = match body.body_class {
        BodyClass::Tracer | BodyClass::Spacecraft | BodyClass::SmallBody | BodyClass::Comet => 0.0,
        _ => body.mass_kg,
    };
    MassiveBodyState {
        mass_kg: source_mass,
        position_m: body.position_m,
        velocity_mps: body.velocity_mps,
    }
}

fn fixture(name: &str) -> Vec<MassiveBodyState> {
    let mut lab: Vec<_> = earth_moon_lab_bodies()
        .into_iter()
        .map(solver_body)
        .collect();
    match name {
        "lab-3" => lab,
        "lab-probe-4" => {
            let earth = lab[1];
            let radius = 7_171_000.0;
            let mut probe = earth;
            probe.mass_kg = 0.0;
            probe.position_m.x += radius;
            probe.velocity_mps.y += (G * earth.mass_kg / radius).sqrt();
            lab.push(probe);
            lab
        }
        "canonical-mixed-365" => canonical_startup_seed()
            .bodies
            .into_iter()
            .map(solver_body)
            .collect(),
        "sparse-1024" => {
            let earth = lab[1];
            for index in 3..1024 {
                let angle = index as f64 * 2.399_963_229_728_653;
                let radius = 8.0e6 + (index % 79) as f64 * 1.0e5;
                let mut probe = earth;
                probe.mass_kg = 0.0;
                probe.position_m.x += radius * angle.cos();
                probe.position_m.y += radius * angle.sin();
                probe.position_m.z += (index % 11) as f64 * 50_000.0;
                let speed = (G * earth.mass_kg / radius).sqrt();
                probe.velocity_mps.x -= speed * angle.sin();
                probe.velocity_mps.y += speed * angle.cos();
                lab.push(probe);
            }
            lab
        }
        "dense-128" | "dense-192" | "dense-256" | "dense-512" | "dense-1024" =>
            (0..name.trim_start_matches("dense-").parse::<usize>().expect("dense body count"))
            .map(|index| {
                let phase = index as f64 * 0.618_033_988_749_895;
                MassiveBodyState {
                    mass_kg: 1.0e20 + (index % 17) as f64 * 1.0e19,
                    position_m: Vector3d {
                        x: (index % 16) as f64 * 8.0e8,
                        y: ((index / 16) % 16) as f64 * 8.0e8,
                        z: (index / 256) as f64 * 8.0e8,
                    },
                    velocity_mps: Vector3d {
                        x: phase.sin() * 100.0,
                        y: phase.cos() * 100.0,
                        z: 0.0,
                    },
                }
            })
            .collect(),
        _ => panic!("Unknown workload: {name}"),
    }
}

fn batch(
    initial: &[MassiveBodyState],
    policy: &PhysicsPolicy,
    features: &[String],
    steps: usize,
    repeats: usize,
) -> Duration {
    let started = Instant::now();
    for _ in 0..repeats {
        let mut bodies = initial.to_vec();
        black_box(advance_authoritative_with_features(
            policy,
            &mut bodies,
            steps as f64,
            features,
        ));
        black_box(bodies);
    }
    started.elapsed()
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let option = |name: &str| {
        args.windows(2)
            .find(|a| a[0] == name)
            .map(|a| a[1].as_str())
    };
    let samples: usize = option("--samples")
        .unwrap_or("11")
        .parse()
        .expect("sample count");
    let steps: usize = option("--steps")
        .unwrap_or("64")
        .parse()
        .expect("step count");
    assert!((3..=101).contains(&samples) && (1..=64).contains(&steps));
    let features = detect_cpu_features();
    let host_backend = if cfg!(target_arch = "aarch64") {
        SolverBackend::SimdArm64
    } else {
        SolverBackend::SimdX64
    };
    let workloads = option("--workloads")
        .unwrap_or("lab-3,lab-probe-4,canonical-mixed-365,sparse-1024,dense-128,dense-192,dense-256,dense-512,dense-1024");
    println!("{{\"kind\":\"environment\",\"arch\":\"{}\",\"available_workers\":{},\"features\":{:?},\"steps\":{},\"samples\":{},\"measurement\":\"working-copy plus authoritative advance plus invariants; two warmups; calibrated >=20ms batch\"}}",
        std::env::consts::ARCH, std::thread::available_parallelism().map(usize::from).unwrap_or(1), features, steps, samples);
    for name in workloads.split(',') {
        let initial = fixture(name);
        for backend in [SolverBackend::ReferenceScalar, host_backend.clone()] {
            let policy = PhysicsPolicy {
                solver_backend: backend,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: 1.0,
            };
            let mut oracle = initial.clone();
            advance_authoritative_scalar(&policy, &mut oracle, steps as f64);
            let mut actual = initial.clone();
            let (_, report) =
                advance_authoritative_with_features(&policy, &mut actual, steps as f64, &features);
            let agreement = solver_equivalence_metrics(&oracle, &actual);
            assert!(
                agreement.max_abs_error <= 0.02 && agreement.max_relative_error <= 1.0e-10,
                "{name}: scalar agreement failed: {agreement:?}"
            );
            for body in &actual {
                assert!([
                    body.position_m.x,
                    body.position_m.y,
                    body.position_m.z,
                    body.velocity_mps.x,
                    body.velocity_mps.y,
                    body.velocity_mps.z
                ]
                .iter()
                .all(|v| v.is_finite()));
            }
            let mut repeats = 1;
            while batch(&initial, &policy, &features, steps, repeats) < Duration::from_millis(20)
                && repeats < 16_384
            {
                repeats *= 2;
            }
            for _ in 0..2 {
                batch(&initial, &policy, &features, steps, repeats);
            }
            let mut timings: Vec<_> = (0..samples)
                .map(|_| {
                    batch(&initial, &policy, &features, steps, repeats).as_secs_f64() * 1.0e6
                        / repeats as f64
                })
                .collect();
            timings.sort_by(f64::total_cmp);
            println!("{{\"kind\":\"measurement\",\"workload\":\"{}\",\"bodies\":{},\"sources\":{},\"requested\":\"{:?}\",\"path\":\"{}\",\"active_workers\":{},\"repeats\":{},\"min_us\":{:.3},\"median_us\":{:.3},\"p90_us\":{:.3},\"max_abs_oracle_error\":{},\"max_relative_oracle_error\":{}}}",
                name, initial.len(), initial.iter().filter(|b| b.mass_kg != 0.0).count(), policy.solver_backend,
                report.path_id, report.schedule.active_workers, repeats, timings[0], timings[samples / 2], timings[(samples * 9 / 10).min(samples - 1)], agreement.max_abs_error, agreement.max_relative_error);
        }
    }
}
