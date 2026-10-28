//! Temporary, non-gating characterization of the existing playback planner.
use std::time::Instant;

use serde_json::{json, Value};
use solarlab_data::{canonical_startup_seed, scenario_pack_seed, CanonicalBodySpec};
use solarlab_domain::Vector3d;
use solarlab_physics::{
    advance_authoritative_scalar, checked_substep_count, compute_invariants, playback_substep_plan,
    CollisionModel, IntegratorKind, MassiveBodyState, PhysicsPolicy, SolverBackend,
};
use solarlab_runtime::BodyState;

const RATE: f64 = 2_592_000.0;
const TICK: f64 = 1_296_000.0;

fn policy(max_substep_seconds: f64) -> PhysicsPolicy {
    PhysicsPolicy {
        solver_backend: SolverBackend::ReferenceScalar,
        integrator: IntegratorKind::LeapfrogKickDriftKick,
        collision_model: CollisionModel::None,
        max_substep_seconds,
    }
}

fn solver_bodies(specs: &[CanonicalBodySpec]) -> Vec<MassiveBodyState> {
    specs
        .iter()
        .map(|b| MassiveBodyState {
            mass_kg: BodyState::default_source_mass_kg(&b.body_class, b.mass_kg),
            position_m: b.position_m,
            velocity_mps: b.velocity_mps,
        })
        .collect()
}

fn sub(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}
fn dot(a: Vector3d, b: Vector3d) -> f64 {
    a.x * b.x + a.y * b.y + a.z * b.z
}
fn norm(a: Vector3d) -> f64 {
    dot(a, a).sqrt()
}
fn finite(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}
fn all_finite(bodies: &[MassiveBodyState]) -> bool {
    bodies
        .iter()
        .all(|b| b.mass_kg.is_finite() && finite(b.position_m) && finite(b.velocity_mps))
}
fn relative_error(actual: f64, reference: f64) -> Option<f64> {
    (reference != 0.0).then_some((actual - reference).abs() / reference.abs())
}

fn comparison(
    specs: &[CanonicalBodySpec],
    primary: &str,
    secondary: &str,
    tick: f64,
    ticks: usize,
) -> Value {
    let initial = solver_bodies(specs);
    let initial_invariants = compute_invariants(&initial);
    let mut strict = initial.clone();
    let mut adaptive = initial;
    let primary_idx = specs.iter().position(|b| b.body_id == primary).unwrap();
    let secondary_idx = specs.iter().position(|b| b.body_id == secondary).unwrap();
    let plan = playback_substep_plan(tick, &CollisionModel::None, RATE);
    let strict_policy = policy(1.0);
    let adaptive_policy = policy(plan.max_substep_seconds);
    let mut samples = Vec::new();
    for tick_index in 1..=ticks {
        let start = Instant::now();
        let strict_invariants = advance_authoritative_scalar(&strict_policy, &mut strict, tick);
        let strict_elapsed = start.elapsed().as_secs_f64();
        let start = Instant::now();
        let adaptive_invariants =
            advance_authoritative_scalar(&adaptive_policy, &mut adaptive, tick);
        let adaptive_elapsed = start.elapsed().as_secs_f64();
        let strict_rel = sub(
            strict[secondary_idx].position_m,
            strict[primary_idx].position_m,
        );
        let adaptive_rel = sub(
            adaptive[secondary_idx].position_m,
            adaptive[primary_idx].position_m,
        );
        let strict_vel = sub(
            strict[secondary_idx].velocity_mps,
            strict[primary_idx].velocity_mps,
        );
        let adaptive_vel = sub(
            adaptive[secondary_idx].velocity_mps,
            adaptive[primary_idx].velocity_mps,
        );
        let denominator = norm(strict_rel) * norm(adaptive_rel);
        let angle = if denominator > 0.0 {
            Some(
                (dot(strict_rel, adaptive_rel) / denominator)
                    .clamp(-1.0, 1.0)
                    .acos(),
            )
        } else {
            None
        };
        samples.push(json!({
            "tick": tick_index,
            "simulated_elapsed_seconds": tick * tick_index as f64,
            "strict_elapsed_seconds": strict_elapsed,
            "adaptive_elapsed_seconds": adaptive_elapsed,
            "strict_all_bodies_finite": all_finite(&strict),
            "adaptive_all_bodies_finite": all_finite(&adaptive),
            "relative_position_error_m": norm(sub(adaptive_rel, strict_rel)),
            "relative_position_error_fraction": (norm(strict_rel) > 0.0).then_some(norm(sub(adaptive_rel, strict_rel)) / norm(strict_rel)),
            "relative_velocity_error_mps": norm(sub(adaptive_vel, strict_vel)),
            "relative_vector_3d_angular_separation_radians": angle,
            "strict_energy_j": strict_invariants.total_energy_j,
            "adaptive_energy_j": adaptive_invariants.total_energy_j,
            "energy_relative_error": relative_error(adaptive_invariants.total_energy_j, strict_invariants.total_energy_j),
            "strict_energy_drift_from_initial": relative_error(strict_invariants.total_energy_j, initial_invariants.total_energy_j),
            "adaptive_energy_drift_from_initial": relative_error(adaptive_invariants.total_energy_j, initial_invariants.total_energy_j),
            "linear_momentum_difference_kg_mps": norm(sub(adaptive_invariants.linear_momentum_kg_mps, strict_invariants.linear_momentum_kg_mps)),
            "angular_momentum_difference_kg_m2ps": norm(sub(adaptive_invariants.angular_momentum_kg_m2ps, strict_invariants.angular_momentum_kg_m2ps)),
            "angular_momentum_relative_error": (norm(strict_invariants.angular_momentum_kg_m2ps) > 0.0).then_some(norm(sub(adaptive_invariants.angular_momentum_kg_m2ps, strict_invariants.angular_momentum_kg_m2ps)) / norm(strict_invariants.angular_momentum_kg_m2ps)),
        }));
    }
    json!({
        "body_ids": specs.iter().map(|b| b.body_id.as_str()).collect::<Vec<_>>(),
        "relative_pair": [primary, secondary],
        "tick_seconds": tick,
        "strict_max_substep_seconds": 1.0,
        "adaptive_max_substep_seconds": plan.max_substep_seconds,
        "strict_substeps_per_tick": checked_substep_count(&strict_policy, tick),
        "adaptive_substeps_per_tick": checked_substep_count(&adaptive_policy, tick),
        "samples": samples,
    })
}

fn main() {
    let canonical = canonical_startup_seed().bodies;
    let major: Vec<_> = canonical
        .iter()
        .filter(|b| ["sun", "earth", "moon"].contains(&b.body_id.as_str()))
        .cloned()
        .collect();
    let jupiter_seed = scenario_pack_seed("showcase.jupiter-system").expect("Jupiter scenario");
    let fast: Vec<_> = jupiter_seed
        .bodies
        .iter()
        .filter(|b| ["sun", "jupiter", "io"].contains(&b.body_id.as_str()))
        .cloned()
        .collect();
    let plan = playback_substep_plan(TICK, &CollisionModel::None, RATE);
    let mut full = solver_bodies(&canonical);
    let initial_invariants = compute_invariants(&full);
    let start = Instant::now();
    let final_invariants =
        advance_authoritative_scalar(&policy(plan.max_substep_seconds), &mut full, TICK);
    let full_elapsed = start.elapsed().as_secs_f64();
    let report = json!({
        "characterization_only": true,
        "source_sha": std::env::var("GITHUB_SHA").ok(),
        "base_sha": "2c00946e5eca84e87b53b55cbf5b07e103a77990",
        "experiment": "Hypothetical playback planner activation; current runtime does not consume this planner.",
        "hosted_runner": {
            "os": std::env::var("RUNNER_OS").ok(),
            "architecture": std::env::var("RUNNER_ARCH").ok(),
            "image_os": std::env::var("ImageOS").ok(),
            "image_version": std::env::var("ImageVersion").ok(),
            "run_id": std::env::var("GITHUB_RUN_ID").ok(),
            "run_attempt": std::env::var("GITHUB_RUN_ATTEMPT").ok(),
            "workflow_sha": std::env::var("GITHUB_WORKFLOW_SHA").ok(),
        },
        "rate_sim_seconds_per_real_second": RATE,
        "real_tick_seconds": 0.5,
        "integrator": "LeapfrogKickDriftKick scalar",
        "collision_mode": "None",
        "reference": "same initial bodies and solver; max substep 1 second",
        "accuracy_accepted": null,
        "existing_major_body_conformance_energy_drift_threshold": 1.0e-6,
        "threshold_scope_note": "Existing threshold is for a different major-body scenario; values here are observations, not a conformance verdict.",
        "major_body_max_cadence": comparison(&major, "earth", "moon", TICK, 2),
        "jupiter_io_max_cadence": comparison(&fast, "jupiter", "io", TICK, 2),
        "canonical_full_short_comparison": comparison(&canonical, "earth", "moon", 60.0, 1),
        "canonical_full_max_tick": {
            "body_count": full.len(),
            "elapsed_seconds": full_elapsed,
            "all_bodies_finite": all_finite(&full),
            "adaptive_substeps": checked_substep_count(&policy(plan.max_substep_seconds), TICK),
            "initial_energy_j": initial_invariants.total_energy_j,
            "final_energy_j": final_invariants.total_energy_j,
            "energy_relative_drift": relative_error(final_invariants.total_energy_j, initial_invariants.total_energy_j),
            "linear_momentum_change_kg_mps": norm(sub(final_invariants.linear_momentum_kg_mps, initial_invariants.linear_momentum_kg_mps)),
            "angular_momentum_change_kg_m2ps": norm(sub(final_invariants.angular_momentum_kg_m2ps, initial_invariants.angular_momentum_kg_m2ps)),
        },
        "limitations": ["No 1-second full-catalog 15-day reference", "No absolute max-cadence accuracy threshold established", "Hosted CPU timing is not Android latency"]
    });
    println!("{}", serde_json::to_string_pretty(&report).unwrap());
}
