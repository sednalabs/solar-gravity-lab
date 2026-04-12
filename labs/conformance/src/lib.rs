use std::collections::BTreeSet;

use serde::Serialize;
use serde_json::{json, Value};
use solarlab_data::canonical_startup_seed;
use solarlab_domain::{BodyClass, BodyId, BranchId, ScenarioId, TimelineSemantics, Vector3d};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{
    playback_substep_plan, CollisionModel, IntegratorKind, PhysicsInvariants, PhysicsPolicy,
    SolverBackend,
};
use solarlab_runtime::{BodyState, RuntimeConfig, WorldCommand, WorldRuntime, WorldSnapshot};

pub const REPORT_SCHEMA_VERSION: &str = "1.0.0";

#[derive(Debug, Serialize)]
pub struct ConformanceReport {
    pub schema_version: &'static str,
    pub commit_sha: Option<String>,
    pub selected_scenarios: Vec<String>,
    pub passed: bool,
    pub summary: ConformanceSummary,
    pub scenarios: Vec<ScenarioReport>,
}

#[derive(Debug, Serialize)]
pub struct ConformanceSummary {
    pub total: usize,
    pub passed: usize,
    pub failed: usize,
}

#[derive(Debug, Serialize)]
pub struct ScenarioReport {
    pub id: &'static str,
    pub family: &'static str,
    pub description: &'static str,
    pub passed: bool,
    pub metrics: Value,
    pub thresholds: Value,
    pub notes: Vec<String>,
}

#[derive(Clone, Copy)]
struct ScenarioDefinition {
    id: &'static str,
    run: fn() -> ScenarioReport,
}

pub fn scenario_ids() -> Vec<&'static str> {
    scenario_registry()
        .iter()
        .map(|scenario| scenario.id)
        .collect()
}

pub fn run_report(
    requested_scenarios: &[String],
    commit_sha: Option<String>,
) -> Result<ConformanceReport, String> {
    let registry = scenario_registry();
    let selected = if requested_scenarios.is_empty() {
        registry
    } else {
        let requested: BTreeSet<&str> = requested_scenarios.iter().map(String::as_str).collect();
        let unknown: Vec<&str> = requested
            .iter()
            .copied()
            .filter(|requested_id| !registry.iter().any(|scenario| scenario.id == *requested_id))
            .collect();
        if !unknown.is_empty() {
            return Err(format!("unknown scenario ids: {}", unknown.join(", ")));
        }

        registry
            .into_iter()
            .filter(|scenario| requested.contains(scenario.id))
            .collect()
    };

    let scenarios: Vec<ScenarioReport> = selected.iter().map(|scenario| (scenario.run)()).collect();
    let passed = scenarios.iter().all(|scenario| scenario.passed);
    let passed_count = scenarios.iter().filter(|scenario| scenario.passed).count();
    let selected_ids = selected
        .iter()
        .map(|scenario| scenario.id.to_owned())
        .collect();

    Ok(ConformanceReport {
        schema_version: REPORT_SCHEMA_VERSION,
        commit_sha,
        selected_scenarios: selected_ids,
        passed,
        summary: ConformanceSummary {
            total: scenarios.len(),
            passed: passed_count,
            failed: scenarios.len().saturating_sub(passed_count),
        },
        scenarios,
    })
}

fn scenario_registry() -> Vec<ScenarioDefinition> {
    vec![
        ScenarioDefinition {
            id: "major-body-orbit-telemetry",
            run: run_major_body_orbit_telemetry,
        },
        ScenarioDefinition {
            id: "added-body-repeatability",
            run: run_added_body_repeatability,
        },
        ScenarioDefinition {
            id: "collision-playback-cap",
            run: run_collision_playback_cap,
        },
    ]
}

fn run_major_body_orbit_telemetry() -> ScenarioReport {
    let metrics = compute_major_body_telemetry(900.0, 32);

    const RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX: f64 = 1.0e-6;
    const BARYCENTER_DRIFT_M_MAX: f64 = 50.0;
    const BARYCENTER_VELOCITY_DRIFT_MPS_MAX: f64 = 1.0e-3;
    const ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;
    const BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX: f64 = 10.0;
    const BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX: f64 = 1.0e-3;
    const MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;

    let passed = metrics.relative_angular_momentum_drift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX
        && metrics.barycenter_drift_m <= BARYCENTER_DRIFT_M_MAX
        && metrics.barycenter_velocity_drift_mps <= BARYCENTER_VELOCITY_DRIFT_MPS_MAX
        && metrics.angular_momentum_fine_baseline_error_ratio
            <= ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX
        && metrics.barycenter_fine_baseline_distance_error_m
            <= BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX
        && metrics.barycenter_fine_baseline_velocity_error_mps
            <= BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX
        && metrics.moon_earth_fine_baseline_error_ratio <= MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX
        && metrics.absolute_angular_momentum_drift_kg_m2ps.is_finite();

    ScenarioReport {
        id: "major-body-orbit-telemetry",
        family: "scientific correctness",
        description: "Propagate the canonical major-body set and compare coarse motion against legacy drift ceilings plus a finer baseline.",
        passed,
        metrics: json!({
            "step_seconds": 900.0,
            "steps": 32,
            "relative_energy_drift": metrics.relative_energy_drift,
            "absolute_angular_momentum_drift_kg_m2ps": metrics.absolute_angular_momentum_drift_kg_m2ps,
            "relative_angular_momentum_drift": metrics.relative_angular_momentum_drift,
            "barycenter_drift_m": metrics.barycenter_drift_m,
            "barycenter_velocity_drift_mps": metrics.barycenter_velocity_drift_mps,
            "angular_momentum_fine_baseline_error_ratio": metrics.angular_momentum_fine_baseline_error_ratio,
            "barycenter_fine_baseline_distance_error_m": metrics.barycenter_fine_baseline_distance_error_m,
            "barycenter_fine_baseline_velocity_error_mps": metrics.barycenter_fine_baseline_velocity_error_mps,
            "moon_earth_fine_baseline_error_ratio": metrics.moon_earth_fine_baseline_error_ratio,
        }),
        thresholds: json!({
            "relative_angular_momentum_drift_max": RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX,
            "barycenter_drift_m_max": BARYCENTER_DRIFT_M_MAX,
            "barycenter_velocity_drift_mps_max": BARYCENTER_VELOCITY_DRIFT_MPS_MAX,
            "angular_momentum_fine_baseline_error_ratio_max": ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX,
            "barycenter_fine_baseline_distance_error_m_max": BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX,
            "barycenter_fine_baseline_velocity_error_mps_max": BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX,
            "moon_earth_fine_baseline_error_ratio_max": MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX,
        }),
        notes: vec![
            "Thresholds match the current runtime telemetry acceptance test so the CLI and unit test speak the same numerical language.".to_owned(),
            "This slice intentionally uses the canonical major-body subset to avoid burying orbital drift signals under the synthetic small-body cloud.".to_owned(),
        ],
    }
}

fn run_added_body_repeatability() -> ScenarioReport {
    let first = propagate_with_added_body();
    let second = propagate_with_added_body();
    let first_probe = body_position(&first, "teacher-probe");
    let second_probe = body_position(&second, "teacher-probe");
    let first_earth = body_position(&first, "earth");
    let second_earth = body_position(&second, "earth");
    let probe_position_delta_m = displacement_magnitude(&first_probe, &second_probe);
    let earth_position_delta_m = displacement_magnitude(&first_earth, &second_earth);
    let probe_velocity_delta_mps = body_velocity_delta(&first, &second, "teacher-probe");
    let snapshot_match = first == second;
    let passed = snapshot_match
        && probe_position_delta_m == 0.0
        && earth_position_delta_m == 0.0
        && probe_velocity_delta_mps == 0.0;

    ScenarioReport {
        id: "added-body-repeatability",
        family: "added-body correctness",
        description: "Spawn a custom probe body into the canonical solar system and require repeated runs to produce the same final authoritative snapshot.",
        passed,
        metrics: json!({
            "step_seconds": 900.0,
            "steps": 16,
            "body_count": first.bodies.len(),
            "snapshot_match": snapshot_match,
            "probe_position_delta_m": probe_position_delta_m,
            "probe_velocity_delta_mps": probe_velocity_delta_mps,
            "earth_position_delta_m": earth_position_delta_m,
        }),
        thresholds: json!({
            "snapshot_match": true,
            "probe_position_delta_m_max": 0.0,
            "probe_velocity_delta_mps_max": 0.0,
            "earth_position_delta_m_max": 0.0,
        }),
        notes: vec![
            "This scenario exercises the v2 runtime command path rather than only catalog seeding, which makes it a useful regression surface for custom-body editing flows.".to_owned(),
            "Exact equality is intentional here because both runs use the same authoritative backend and command sequence on one host.".to_owned(),
        ],
    }
}

fn run_collision_playback_cap() -> ScenarioReport {
    let collision_models = [
        ("merge", CollisionModel::Merge),
        ("elastic", CollisionModel::Elastic),
        ("fragmentation", CollisionModel::Fragmentation),
    ];
    let mut caps = Vec::new();
    let mut passed = true;

    for (model_name, model) in collision_models {
        let plan = playback_substep_plan(864_000.0, &model, 2_592_000.0);
        if plan.max_substep_seconds != 3_600.0 {
            passed = false;
        }
        caps.push(json!({
            "model": model_name,
            "max_substep_seconds": plan.max_substep_seconds,
        }));
    }

    ScenarioReport {
        id: "collision-playback-cap",
        family: "collision safety",
        description: "Assert that collision-enabled playback modes retain the conservative one-hour substep cap instead of silently stretching the solver budget.",
        passed,
        metrics: json!({
            "total_seconds": 864_000.0,
            "sim_seconds_per_real_second": 2_592_000.0,
            "models": caps,
        }),
        thresholds: json!({
            "expected_max_substep_seconds": 3_600.0,
        }),
        notes: vec![
            "V2 does not yet expose a dedicated collision-resolution harness, so this first conformance slice checks the conservative playback guard that protects collision-enabled modes today.".to_owned(),
        ],
    }
}

#[derive(Clone, Copy)]
struct TelemetryMetrics {
    relative_energy_drift: f64,
    absolute_angular_momentum_drift_kg_m2ps: f64,
    relative_angular_momentum_drift: f64,
    barycenter_drift_m: f64,
    barycenter_velocity_drift_mps: f64,
    angular_momentum_fine_baseline_error_ratio: f64,
    barycenter_fine_baseline_distance_error_m: f64,
    barycenter_fine_baseline_velocity_error_mps: f64,
    moon_earth_fine_baseline_error_ratio: f64,
}

#[derive(Clone)]
struct PropagationResult {
    initial_snapshot: WorldSnapshot,
    final_snapshot: WorldSnapshot,
    initial_invariants: PhysicsInvariants,
    final_invariants: PhysicsInvariants,
}

fn compute_major_body_telemetry(step_seconds: f64, steps: usize) -> TelemetryMetrics {
    let coarse = propagate_major_bodies(step_seconds, steps);
    let fine_step = step_seconds / 4.0;
    let fine = propagate_major_bodies(fine_step, steps * 4);
    let elapsed_seconds = step_seconds * steps as f64;
    let total_mass_kg = total_mass_kg(&coarse.initial_snapshot);
    let initial_angular_momentum =
        vec_magnitude(coarse.initial_invariants.angular_momentum_kg_m2ps);
    let coarse_final_angular_momentum =
        vec_magnitude(coarse.final_invariants.angular_momentum_kg_m2ps);
    let fine_final_angular_momentum = vec_magnitude(fine.final_invariants.angular_momentum_kg_m2ps);
    let initial_barycenter_velocity =
        barycenter_velocity_mps(&coarse.initial_invariants, total_mass_kg);
    let coarse_final_barycenter_velocity =
        barycenter_velocity_mps(&coarse.final_invariants, total_mass_kg);
    let fine_final_barycenter_velocity =
        barycenter_velocity_mps(&fine.final_invariants, total_mass_kg);
    let expected_final_barycenter = expected_barycenter_after_seconds(
        &coarse.initial_invariants,
        total_mass_kg,
        elapsed_seconds,
    );

    let relative_energy_drift = drift(
        coarse.initial_invariants.total_energy_j,
        coarse.final_invariants.total_energy_j,
    );
    let relative_angular_momentum_drift =
        drift(initial_angular_momentum, coarse_final_angular_momentum);
    let absolute_angular_momentum_drift_kg_m2ps =
        (coarse_final_angular_momentum - initial_angular_momentum).abs();
    let barycenter_velocity_drift_mps = displacement_magnitude(
        &coarse_final_barycenter_velocity,
        &initial_barycenter_velocity,
    );
    let angular_momentum_fine_baseline_error_ratio = if fine_final_angular_momentum > 0.0 {
        (coarse_final_angular_momentum - fine_final_angular_momentum).abs()
            / fine_final_angular_momentum
    } else {
        0.0
    };

    let coarse_final_barycenter = coarse.final_invariants.barycenter_m;
    let fine_final_barycenter = fine.final_invariants.barycenter_m;
    let barycenter_drift_m =
        displacement_magnitude(&expected_final_barycenter, &coarse_final_barycenter);
    let barycenter_fine_baseline_distance_error_m =
        displacement_magnitude(&coarse_final_barycenter, &fine_final_barycenter);
    let barycenter_fine_baseline_velocity_error_mps = displacement_magnitude(
        &coarse_final_barycenter_velocity,
        &fine_final_barycenter_velocity,
    );

    let moon_earth_fine_baseline_error_ratio = {
        let coarse_distance = moon_earth_distance_m(&coarse.final_snapshot);
        let fine_distance = moon_earth_distance_m(&fine.final_snapshot);
        if fine_distance > 0.0 {
            (coarse_distance - fine_distance).abs() / fine_distance
        } else {
            0.0
        }
    };

    TelemetryMetrics {
        relative_energy_drift,
        absolute_angular_momentum_drift_kg_m2ps,
        relative_angular_momentum_drift,
        barycenter_drift_m,
        barycenter_velocity_drift_mps,
        angular_momentum_fine_baseline_error_ratio,
        barycenter_fine_baseline_distance_error_m,
        barycenter_fine_baseline_velocity_error_mps,
        moon_earth_fine_baseline_error_ratio,
    }
}

fn propagate_major_bodies(step_seconds: f64, steps: usize) -> PropagationResult {
    let mut runtime = new_runtime(60.0);
    seed_major_bodies(&mut runtime);

    let initial_snapshot = runtime.snapshot();
    let initial_invariants = initial_snapshot.invariants;

    for step in 0..steps {
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: step_seconds,
                },
                i64::try_from(step).expect("step index should fit in i64") + 1_000,
            )
            .expect("advance epoch should succeed");
    }

    let final_snapshot = runtime.snapshot();
    let final_invariants = final_snapshot.invariants;

    PropagationResult {
        initial_snapshot,
        final_snapshot,
        initial_invariants,
        final_invariants,
    }
}

fn propagate_with_added_body() -> WorldSnapshot {
    let mut runtime = new_runtime(60.0);
    runtime
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
        .expect("canonical seed should succeed");
    runtime
        .apply_command(
            WorldCommand::SpawnBody {
                body: BodyState {
                    body_id: BodyId("teacher-probe".into()),
                    body_class: BodyClass::Custom,
                    mass_kg: 5.0e10,
                    radius_m: 50_000.0,
                    position_m: Vector3d {
                        x: 1.496e11 + 550_000_000.0,
                        y: 12_000_000.0,
                        z: 0.0,
                    },
                    velocity_mps: Vector3d {
                        x: -120.0,
                        y: 30_250.0,
                        z: 15.0,
                    },
                },
            },
            2,
        )
        .expect("probe spawn should succeed");

    for step in 0..16 {
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 900.0,
                },
                i64::try_from(step).expect("step index should fit in i64") + 10,
            )
            .expect("advance epoch should succeed");
    }

    runtime.snapshot()
}

fn new_runtime(max_substep_seconds: f64) -> WorldRuntime {
    WorldRuntime::new(
        ScenarioId("sol-system".into()),
        BranchId("main".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    )
}

fn seed_major_bodies(runtime: &mut WorldRuntime) {
    let seed = canonical_startup_seed();
    let major_bodies = seed
        .bodies
        .into_iter()
        .filter(|body| body.body_class != BodyClass::SmallBody)
        .map(|body| BodyState {
            body_id: BodyId(body.body_id),
            body_class: body.body_class,
            mass_kg: body.mass_kg,
            radius_m: body.radius_m,
            position_m: body.position_m,
            velocity_mps: body.velocity_mps,
        });

    for (index, body) in major_bodies.enumerate() {
        runtime
            .apply_command(
                WorldCommand::SpawnBody { body },
                i64::try_from(index).expect("seed index should fit in i64") + 1,
            )
            .expect("major-body seed should succeed");
    }
}

fn body_position(snapshot: &WorldSnapshot, body_id: &str) -> Vector3d {
    snapshot
        .bodies
        .iter()
        .find(|body| body.body_id.0 == body_id)
        .expect("body should exist")
        .position_m
}

fn body_velocity_delta(first: &WorldSnapshot, second: &WorldSnapshot, body_id: &str) -> f64 {
    let left = first
        .bodies
        .iter()
        .find(|body| body.body_id.0 == body_id)
        .expect("left body should exist");
    let right = second
        .bodies
        .iter()
        .find(|body| body.body_id.0 == body_id)
        .expect("right body should exist");
    displacement_magnitude(&left.velocity_mps, &right.velocity_mps)
}

fn drift(initial: f64, final_value: f64) -> f64 {
    if initial.abs() <= f64::EPSILON {
        0.0
    } else {
        (final_value - initial).abs() / initial.abs()
    }
}

fn total_mass_kg(snapshot: &WorldSnapshot) -> f64 {
    snapshot.bodies.iter().map(|body| body.mass_kg).sum()
}

fn vec_magnitude(vector: Vector3d) -> f64 {
    (vector.x * vector.x + vector.y * vector.y + vector.z * vector.z).sqrt()
}

fn displacement_magnitude(a: &Vector3d, b: &Vector3d) -> f64 {
    let dx = a.x - b.x;
    let dy = a.y - b.y;
    let dz = a.z - b.z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

fn expected_barycenter_after_seconds(
    invariants: &PhysicsInvariants,
    total_mass_kg: f64,
    elapsed_seconds: f64,
) -> Vector3d {
    if total_mass_kg <= 0.0 {
        return invariants.barycenter_m;
    }

    Vector3d {
        x: invariants.barycenter_m.x
            + (invariants.linear_momentum_kg_mps.x / total_mass_kg) * elapsed_seconds,
        y: invariants.barycenter_m.y
            + (invariants.linear_momentum_kg_mps.y / total_mass_kg) * elapsed_seconds,
        z: invariants.barycenter_m.z
            + (invariants.linear_momentum_kg_mps.z / total_mass_kg) * elapsed_seconds,
    }
}

fn barycenter_velocity_mps(invariants: &PhysicsInvariants, total_mass_kg: f64) -> Vector3d {
    if total_mass_kg <= 0.0 {
        return Vector3d::default();
    }

    Vector3d {
        x: invariants.linear_momentum_kg_mps.x / total_mass_kg,
        y: invariants.linear_momentum_kg_mps.y / total_mass_kg,
        z: invariants.linear_momentum_kg_mps.z / total_mass_kg,
    }
}

fn moon_earth_distance_m(snapshot: &WorldSnapshot) -> f64 {
    let earth_pos = body_position(snapshot, "earth");
    let moon_pos = body_position(snapshot, "moon");
    displacement_magnitude(&earth_pos, &moon_pos)
}

#[cfg(test)]
mod tests {
    use super::{run_report, scenario_ids};

    #[test]
    fn report_passes_all_registered_scenarios() {
        let report = run_report(&[], Some("deadbeef".into())).expect("report should build");
        assert!(report.passed, "expected all scenarios to pass: {report:#?}");
        assert_eq!(report.summary.total, scenario_ids().len());
    }

    #[test]
    fn report_can_filter_single_scenario() {
        let report = run_report(&["collision-playback-cap".into()], None)
            .expect("filtered report should build");
        assert_eq!(report.summary.total, 1);
        assert_eq!(report.scenarios[0].id, "collision-playback-cap");
    }

    #[test]
    fn unknown_scenario_is_rejected() {
        let error =
            run_report(&["unknown".into()], None).expect_err("unknown scenario should fail");
        assert!(error.contains("unknown scenario ids"));
    }
}
