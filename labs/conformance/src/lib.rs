use std::collections::BTreeSet;

use serde::Serialize;
use serde_json::{json, Value};
use solarlab_data::canonical_startup_seed;
use solarlab_domain::{BodyClass, BodyId, BranchId, ScenarioId, TimelineSemantics, Vector3d};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{
    compare_arm64_kernel_to_scalar, playback_substep_plan, CollisionModel, IntegratorKind,
    MassiveBodyState, PhysicsInvariants, PhysicsPolicy, SolverBackend,
};
use solarlab_runtime::{BodyState, RuntimeConfig, WorldCommand, WorldRuntime, WorldSnapshot};

pub const REPORT_SCHEMA_VERSION: &str = "1.0.0";
const ASTRONOMICAL_UNIT_M: f64 = 1.495_978_707e11;

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

#[must_use]
pub fn report_exit_code(report: &ConformanceReport) -> u8 {
    if report.passed {
        0
    } else {
        1
    }
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
        ScenarioDefinition {
            id: "arm64-kernel-equivalence",
            run: run_arm64_kernel_equivalence,
        },
        ScenarioDefinition {
            id: "physics-accuracy-telemetry",
            run: run_physics_accuracy_telemetry,
        },
        ScenarioDefinition {
            id: "one-year-earth-orbit-stability",
            run: run_one_year_earth_orbit_stability,
        },
        ScenarioDefinition {
            id: "host-relative-playback-policy",
            run: run_host_relative_playback_policy,
        },
    ]
}

fn run_major_body_orbit_telemetry() -> ScenarioReport {
    let metrics = compute_major_body_telemetry(900.0, 32);

    const RELATIVE_ENERGY_DRIFT_MAX: f64 = 1.0e-6;
    const RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX: f64 = 1.0e-6;
    const BARYCENTER_DRIFT_M_MAX: f64 = 50.0;
    const BARYCENTER_VELOCITY_DRIFT_MPS_MAX: f64 = 1.0e-3;
    const ANGULAR_MOMENTUM_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;
    const BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX: f64 = 10.0;
    const BARYCENTER_FINE_BASELINE_VELOCITY_ERROR_MPS_MAX: f64 = 1.0e-3;
    const MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;

    let passed = metrics.relative_energy_drift <= RELATIVE_ENERGY_DRIFT_MAX
        && metrics.relative_angular_momentum_drift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX
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
            "relative_energy_drift_max": RELATIVE_ENERGY_DRIFT_MAX,
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

fn run_arm64_kernel_equivalence() -> ScenarioReport {
    let policy = PhysicsPolicy {
        solver_backend: SolverBackend::ReferenceScalar,
        integrator: IntegratorKind::LeapfrogKickDriftKick,
        collision_model: CollisionModel::None,
        max_substep_seconds: 1.0,
    };
    let bodies = moon_earth_playback_solver_scenario();
    let report = compare_arm64_kernel_to_scalar(&policy, &bodies, 86_400.0);

    const MAX_ABS_ERROR: f64 = 1.0e-3;
    const MAX_RELATIVE_ERROR: f64 = 1.0e-12;
    const MAX_ENERGY_RELATIVE_ERROR: f64 = 1.0e-12;
    let passed = report.metrics.compared_components > 0
        && report.metrics.bitwise_equal_components > 0
        && report.metrics.max_abs_error <= MAX_ABS_ERROR
        && report.metrics.max_relative_error <= MAX_RELATIVE_ERROR
        && report.energy_relative_error <= MAX_ENERGY_RELATIVE_ERROR;

    ScenarioReport {
        id: "arm64-kernel-equivalence",
        family: "scientific correctness",
        description: "Compare the dedicated arm64 fused-step kernel against the scalar oracle on the canonical moon-earth playback scenario.",
        passed,
        metrics: json!({
            "delta_seconds": 86_400.0,
            "compared_components": report.metrics.compared_components,
            "bitwise_equal_components": report.metrics.bitwise_equal_components,
            "max_abs_error": report.metrics.max_abs_error,
            "max_relative_error": report.metrics.max_relative_error,
            "energy_relative_error": report.energy_relative_error,
        }),
        thresholds: json!({
            "max_abs_error": MAX_ABS_ERROR,
            "max_relative_error": MAX_RELATIVE_ERROR,
            "max_energy_relative_error": MAX_ENERGY_RELATIVE_ERROR,
        }),
        notes: vec![
            "This scenario is host-independent because the harness compares the scalar and arm64 fused kernels directly instead of relying on live runtime dispatch.".to_owned(),
            "It lifts an existing strict parity assertion into the machine-readable conformance surface so ISA checks are no longer trapped in unit-test output.".to_owned(),
        ],
    }
}

fn run_physics_accuracy_telemetry() -> ScenarioReport {
    const STEP_SECONDS: f64 = 3_600.0;
    const STEPS: usize = 6;
    const RELATIVE_ENERGY_DRIFT_MAX: f64 = 1.0e-6;
    const RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX: f64 = 1.0e-6;
    const BARYCENTER_DRIFT_M_MAX: f64 = 50.0;
    const BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX: f64 = 10.0;
    const MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;

    let metrics = compute_major_body_telemetry(STEP_SECONDS, STEPS);
    let passed = metrics.relative_energy_drift <= RELATIVE_ENERGY_DRIFT_MAX
        && metrics.relative_angular_momentum_drift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX
        && metrics.barycenter_drift_m <= BARYCENTER_DRIFT_M_MAX
        && metrics.barycenter_fine_baseline_distance_error_m
            <= BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX
        && metrics.moon_earth_fine_baseline_error_ratio <= MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX
        && metrics.absolute_angular_momentum_drift_kg_m2ps.is_finite()
        && metrics.moon_earth_distance_m.is_finite()
        && metrics.moon_earth_distance_change_m.is_finite()
        && metrics.moon_earth_distance_change_ratio.is_finite()
        && metrics.moon_earth_fine_baseline_error_m.is_finite();

    ScenarioReport {
        id: "physics-accuracy-telemetry",
        family: "telemetry diagnostics",
        description: "Emit the older physics-accuracy telemetry metric family from the Rust-native harness and keep its conservative drift guardrails green.",
        passed,
        metrics: json!({
            "step_seconds": STEP_SECONDS,
            "steps": STEPS,
            "relative_energy_drift": metrics.relative_energy_drift,
            "relative_angular_momentum_drift": metrics.relative_angular_momentum_drift,
            "absolute_angular_momentum_drift_kg_m2_per_s": metrics.absolute_angular_momentum_drift_kg_m2ps,
            "barycenter_drift_m": metrics.barycenter_drift_m,
            "barycenter_velocity_drift_mps": metrics.barycenter_velocity_drift_mps,
            "angular_momentum_fine_baseline_error_ratio": metrics.angular_momentum_fine_baseline_error_ratio,
            "barycenter_fine_baseline_distance_error_m": metrics.barycenter_fine_baseline_distance_error_m,
            "barycenter_fine_baseline_velocity_error_mps": metrics.barycenter_fine_baseline_velocity_error_mps,
            "moon_earth_distance_au": metrics.moon_earth_distance_m / ASTRONOMICAL_UNIT_M,
            "moon_earth_distance_change_au": metrics.moon_earth_distance_change_m / ASTRONOMICAL_UNIT_M,
            "moon_earth_distance_change_ratio": metrics.moon_earth_distance_change_ratio,
            "moon_earth_distance_fine_baseline_error_au": metrics.moon_earth_fine_baseline_error_m / ASTRONOMICAL_UNIT_M,
            "moon_earth_distance_fine_baseline_error_ratio": metrics.moon_earth_fine_baseline_error_ratio,
        }),
        thresholds: json!({
            "relative_energy_drift_max": RELATIVE_ENERGY_DRIFT_MAX,
            "relative_angular_momentum_drift_max": RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX,
            "barycenter_drift_m_max": BARYCENTER_DRIFT_M_MAX,
            "barycenter_fine_baseline_distance_error_m_max": BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX,
            "moon_earth_distance_fine_baseline_error_ratio_max": MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX,
        }),
        notes: vec![
            "This pulls the older physics-accuracy telemetry seam into the Rust-native conformance harness instead of leaving it stranded in the legacy Kotlin generator.".to_owned(),
            "The scenario intentionally keeps the metric names familiar so parity-matrix references can move without forcing humans to relearn the diagnostic vocabulary.".to_owned(),
        ],
    }
}

fn run_one_year_earth_orbit_stability() -> ScenarioReport {
    const STEP_SECONDS: f64 = 6.0 * 3_600.0;
    const STEPS: usize = 365 * 4;
    const MIN_FINAL_DISTANCE_AU: f64 = 0.97;
    const MAX_FINAL_DISTANCE_AU: f64 = 1.03;
    const MAX_RELATIVE_ENERGY_DRIFT: f64 = 5.0e-3;

    let policy = PhysicsPolicy {
        solver_backend: SolverBackend::ReferenceScalar,
        integrator: IntegratorKind::LeapfrogKickDriftKick,
        collision_model: CollisionModel::None,
        max_substep_seconds: STEP_SECONDS,
    };
    let mut bodies = simple_sun_earth_two_body();
    let starting_invariants = solarlab_physics::compute_invariants(&bodies);

    for _ in 0..STEPS {
        solarlab_physics::advance_authoritative_scalar(&policy, &mut bodies, STEP_SECONDS);
    }

    let final_invariants = solarlab_physics::compute_invariants(&bodies);
    let earth_distance_au = vec_magnitude(bodies[1].position_m) / ASTRONOMICAL_UNIT_M;
    let relative_energy_drift = drift(
        starting_invariants.total_energy_j,
        final_invariants.total_energy_j,
    );
    let passed = (MIN_FINAL_DISTANCE_AU..=MAX_FINAL_DISTANCE_AU).contains(&earth_distance_au)
        && relative_energy_drift < MAX_RELATIVE_ENERGY_DRIFT;

    ScenarioReport {
        id: "one-year-earth-orbit-stability",
        family: "scientific correctness",
        description: "Advance a barycenter-recentered Sun/Earth two-body system for one Julian year and require both orbital radius stability and low energy drift.",
        passed,
        metrics: json!({
            "step_seconds": STEP_SECONDS,
            "steps": STEPS,
            "simulated_seconds": STEP_SECONDS * STEPS as f64,
            "earth_distance_au": earth_distance_au,
            "relative_energy_drift": relative_energy_drift,
        }),
        thresholds: json!({
            "earth_distance_au_min": MIN_FINAL_DISTANCE_AU,
            "earth_distance_au_max": MAX_FINAL_DISTANCE_AU,
            "relative_energy_drift_max": MAX_RELATIVE_ENERGY_DRIFT,
        }),
        notes: vec![
            "This ports the older one-year Sun/Earth stability proof into the Rust-native harness so long-horizon orbital behavior is no longer trapped in the legacy Kotlin simulation tests.".to_owned(),
            "The scenario stays intentionally simple and cheap by using the two-body circular-orbit baseline rather than the full canonical solar-system seed.".to_owned(),
        ],
    }
}

fn run_host_relative_playback_policy() -> ScenarioReport {
    let playback_tick_seconds = 86_400.0;
    let playback_window_ticks = 6;
    let fine_max_substep_seconds = 120.0;
    let legacy_policy_cap = legacy_high_speed_policy_cap(playback_tick_seconds);
    let policy_plan =
        playback_substep_plan(playback_tick_seconds, &CollisionModel::None, 2_592_000.0);
    let two_day_plan = playback_substep_plan(
        2.0 * playback_tick_seconds,
        &CollisionModel::None,
        2_592_000.0,
    );

    let baseline = host_relative_turning_angles(
        playback_window_ticks,
        playback_tick_seconds,
        fine_max_substep_seconds,
    );
    let legacy_policy = host_relative_turning_angles(
        playback_window_ticks,
        playback_tick_seconds,
        legacy_policy_cap,
    );
    let current_policy = host_relative_turning_angles(
        playback_window_ticks,
        playback_tick_seconds,
        policy_plan.max_substep_seconds,
    );
    let legacy_policy_max_turning_error = max_turning_angle_error(&legacy_policy, &baseline);
    let current_policy_max_turning_error = max_turning_angle_error(&current_policy, &baseline);

    let passed = policy_plan.max_substep_seconds == 3_600.0
        && legacy_policy_cap == 7_200.0
        && two_day_plan.max_substep_seconds == 14_400.0
        && policy_plan.max_substep_seconds < legacy_policy_cap
        && current_policy_max_turning_error <= legacy_policy_max_turning_error;

    ScenarioReport {
        id: "host-relative-playback-policy",
        family: "scientific correctness",
        description: "Compare the host-relative short-window playback cap against the coarser legacy policy and require lower or equal turning-angle error.",
        passed,
        metrics: json!({
            "playback_tick_seconds": playback_tick_seconds,
            "playback_window_ticks": playback_window_ticks,
            "baseline_max_substep_seconds": fine_max_substep_seconds,
            "legacy_policy_cap_seconds": legacy_policy_cap,
            "policy_cap_seconds": policy_plan.max_substep_seconds,
            "two_day_policy_cap_seconds": two_day_plan.max_substep_seconds,
            "legacy_policy_max_turning_error": legacy_policy_max_turning_error,
            "current_policy_max_turning_error": current_policy_max_turning_error,
        }),
        thresholds: json!({
            "expected_policy_cap_seconds": 3_600.0,
            "expected_legacy_policy_cap_seconds": 7_200.0,
            "expected_two_day_policy_cap_seconds": 14_400.0,
            "current_policy_max_turning_error_lte_legacy": true,
        }),
        notes: vec![
            "This keeps the high-speed host-relative playback guard machine-readable instead of leaving it trapped in the physics unit tests.".to_owned(),
            "The scenario focuses on turning-angle stability, which is the user-visible scientific failure mode for overly coarse playback integration.".to_owned(),
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
    moon_earth_distance_m: f64,
    moon_earth_distance_change_m: f64,
    moon_earth_distance_change_ratio: f64,
    moon_earth_fine_baseline_error_m: f64,
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
    let angular_momentum_fine_baseline_error_ratio =
        drift(fine_final_angular_momentum, coarse_final_angular_momentum);

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

    let initial_moon_earth_distance_m = moon_earth_distance_m(&coarse.initial_snapshot);
    let coarse_moon_earth_distance_m = moon_earth_distance_m(&coarse.final_snapshot);
    let fine_moon_earth_distance_m = moon_earth_distance_m(&fine.final_snapshot);
    let moon_earth_distance_change_m = coarse_moon_earth_distance_m - initial_moon_earth_distance_m;
    let moon_earth_distance_change_ratio =
        drift(initial_moon_earth_distance_m, coarse_moon_earth_distance_m);
    let moon_earth_fine_baseline_error_m =
        (coarse_moon_earth_distance_m - fine_moon_earth_distance_m).abs();
    let moon_earth_fine_baseline_error_ratio =
        drift(fine_moon_earth_distance_m, coarse_moon_earth_distance_m);

    TelemetryMetrics {
        relative_energy_drift,
        absolute_angular_momentum_drift_kg_m2ps,
        relative_angular_momentum_drift,
        barycenter_drift_m,
        barycenter_velocity_drift_mps,
        angular_momentum_fine_baseline_error_ratio,
        barycenter_fine_baseline_distance_error_m,
        barycenter_fine_baseline_velocity_error_mps,
        moon_earth_distance_m: coarse_moon_earth_distance_m,
        moon_earth_distance_change_m,
        moon_earth_distance_change_ratio,
        moon_earth_fine_baseline_error_m,
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
                    source_mass_kg: 0.0,
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

fn moon_earth_playback_solver_scenario() -> Vec<MassiveBodyState> {
    vec![
        MassiveBodyState {
            mass_kg: 1.98847e30,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        },
        MassiveBodyState {
            mass_kg: 5.972168e24,
            position_m: Vector3d {
                x: 1.496e11,
                y: 0.0,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: 0.0,
                y: 29_780.0,
                z: 0.0,
            },
        },
        MassiveBodyState {
            mass_kg: 7.342e22,
            position_m: Vector3d {
                x: 1.496e11 + 384_400_000.0,
                y: 0.0,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: 0.0,
                y: 30_802.0,
                z: 0.0,
            },
        },
    ]
}

fn host_relative_turning_angles(
    ticks: usize,
    tick_seconds: f64,
    max_substep_seconds: f64,
) -> Vec<f64> {
    let mut runtime = new_runtime(max_substep_seconds);
    runtime
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
        .expect("canonical seed should succeed");

    let mut host_relative_vectors = Vec::with_capacity(ticks + 1);
    host_relative_vectors.push(moon_from_earth(&runtime.snapshot()));

    for step in 0..ticks {
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: tick_seconds,
                },
                i64::try_from(step).expect("step index should fit in i64") + 100,
            )
            .expect("advance epoch should succeed");
        host_relative_vectors.push(moon_from_earth(&runtime.snapshot()));
    }

    host_relative_vectors
        .windows(2)
        .map(|window| turning_angle(window[0], window[1]))
        .collect()
}

fn simple_sun_earth_two_body() -> Vec<MassiveBodyState> {
    const G_M3_PER_KG_S2: f64 = 6.674_30e-11;
    let sun_mass_kg = 1.988_47e30;
    let earth_mass_kg = 5.972_37e24;
    let earth_orbital_radius_m = ASTRONOMICAL_UNIT_M;
    let earth_speed_mps = (G_M3_PER_KG_S2 * sun_mass_kg / earth_orbital_radius_m).sqrt();
    let total_mass_kg = sun_mass_kg + earth_mass_kg;
    let barycenter_x_m = earth_mass_kg * earth_orbital_radius_m / total_mass_kg;
    let sun_velocity_y_mps = -(earth_mass_kg * earth_speed_mps) / sun_mass_kg;

    vec![
        MassiveBodyState {
            mass_kg: sun_mass_kg,
            position_m: Vector3d {
                x: -barycenter_x_m,
                y: 0.0,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: 0.0,
                y: sun_velocity_y_mps,
                z: 0.0,
            },
        },
        MassiveBodyState {
            mass_kg: earth_mass_kg,
            position_m: Vector3d {
                x: earth_orbital_radius_m - barycenter_x_m,
                y: 0.0,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: 0.0,
                y: earth_speed_mps,
                z: 0.0,
            },
        },
    ]
}

fn moon_from_earth(snapshot: &WorldSnapshot) -> Vector3d {
    let moon = body_position(snapshot, "moon");
    let earth = body_position(snapshot, "earth");
    Vector3d {
        x: moon.x - earth.x,
        y: moon.y - earth.y,
        z: moon.z - earth.z,
    }
}

fn max_turning_angle_error(actual: &[f64], expected: &[f64]) -> f64 {
    actual
        .iter()
        .zip(expected.iter())
        .map(|(left, right)| (left - right).abs())
        .fold(0.0_f64, f64::max)
}

fn legacy_high_speed_policy_cap(total_seconds: f64) -> f64 {
    (total_seconds / 12.0).clamp(3_600.0, 21_600.0)
}

fn turning_angle(previous: Vector3d, current: Vector3d) -> f64 {
    let denominator = vec_magnitude(previous) * vec_magnitude(current);
    if denominator <= f64::EPSILON {
        return 0.0;
    }

    let cosine = ((previous.x * current.x) + (previous.y * current.y) + (previous.z * current.z))
        / denominator;
    cosine.clamp(-1.0, 1.0).acos()
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
        .filter(|body| {
            !matches!(body.body_class, BodyClass::SmallBody | BodyClass::Comet)
        })
        .map(|body| {
            let source_mass_kg = BodyState::default_source_mass_kg(&body.body_class, body.mass_kg);
            BodyState {
                body_id: BodyId(body.body_id),
                body_class: body.body_class,
                mass_kg: body.mass_kg,
                source_mass_kg,
                radius_m: body.radius_m,
                position_m: body.position_m,
                velocity_mps: body.velocity_mps,
            }
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
    if initial == 0.0 {
        final_value.abs()
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
    use serde_json::json;

    use super::{
        drift, report_exit_code, run_report, scenario_ids, ConformanceReport, ConformanceSummary,
        ScenarioReport,
    };

    #[test]
    fn scenario_registry_lists_expected_ids() {
        assert_eq!(
            scenario_ids(),
            vec![
                "major-body-orbit-telemetry",
                "added-body-repeatability",
                "collision-playback-cap",
                "arm64-kernel-equivalence",
                "physics-accuracy-telemetry",
                "one-year-earth-orbit-stability",
                "host-relative-playback-policy",
            ]
        );
    }

    #[test]
    fn drift_uses_absolute_error_when_baseline_is_zero() {
        assert_eq!(drift(0.0, 0.0), 0.0);
        assert_eq!(drift(0.0, 2.5), 2.5);
    }

    #[test]
    fn drift_does_not_mask_tiny_nonzero_baselines() {
        let tiny = f64::EPSILON / 2.0;
        assert!(drift(tiny, 2.5).is_finite());
        assert!(drift(tiny, 2.5) > 1.0);
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

    #[test]
    fn report_exit_code_tracks_pass_state() {
        let passing = ConformanceReport {
            schema_version: "1.0.0",
            commit_sha: None,
            selected_scenarios: vec!["example".into()],
            passed: true,
            summary: ConformanceSummary {
                total: 1,
                passed: 1,
                failed: 0,
            },
            scenarios: vec![],
        };
        let failing = ConformanceReport {
            schema_version: "1.0.0",
            commit_sha: None,
            selected_scenarios: vec!["example".into()],
            passed: false,
            summary: ConformanceSummary {
                total: 1,
                passed: 0,
                failed: 1,
            },
            scenarios: vec![ScenarioReport {
                id: "example",
                family: "scientific correctness",
                description: "example",
                passed: false,
                metrics: json!({}),
                thresholds: json!({}),
                notes: vec![],
            }],
        };

        assert_eq!(report_exit_code(&passing), 0);
        assert_eq!(report_exit_code(&failing), 1);
    }
}
