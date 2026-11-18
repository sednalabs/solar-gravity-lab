//! Self-contained physical-device research workloads, with independent oracles.
//! All worlds are synthetic private copies; this crate cannot edit a live runtime.
//! Optional instructions require process-local capability checks before execution.

mod catalogue;
mod integrators;
mod kernels;
mod model;
#[allow(unsafe_code)]
mod neon;
mod radau;
mod workers;
// Full-precision generated quadrature coefficients retain reproducible decimals.
#[allow(clippy::excessive_precision, clippy::unreadable_literal)]
mod radau_coefficients;

use catalogue::{Algorithm, Case, CASES};
use integrators::Stats;
use kernels::ForceMode;
use model::{Body, Fixture};
use serde_json::{json, Value};
use solarlab_physics::{
    CollisionModel, FixedStepPlan, IntegratorKind, PhysicsPolicy, SolverBackend,
};
use std::hint::black_box;
use std::sync::OnceLock;
use std::time::Instant;

/// The Android runner uses this catalogue rather than guessing support from a
/// phone model or architecture string. Runnable is not a measured PASS.
#[must_use]
pub fn catalogue_json() -> String {
    let cases: Vec<_> = CASES
        .iter()
        .map(|case| {
            let (available, reason) = catalogue::availability(case);
            let mut entry = json!({
                "id":case.id, "label":case.label, "family":case.family,
                "status":if available {"runnable"} else {"unsupported"}, "available":available,
                "implemented":true, "reason":reason, "baseline_case_id":case.baseline,
                "fixtures":case.fixtures, "recommendation_ids":case.recommendations,
                "scaled_state_error_limit":case.tolerance, "note":case.note,
                "comparison_kind":case.comparison_kind(), "physics_model":case.physics_model(),
                "baseline_physics_model":"Newtonian", "control_scope_note":case.control_scope_note(),
            });
            annotate_reciprocal_configuration(&mut entry, case);
            entry
        })
        .collect();
    let fixtures: Vec<_> = catalogue::ALL_FIXTURES
        .iter()
        .filter_map(|id| model::fixture(id))
        .map(|f| {
            json!({
                "id":f.id, "body_count":f.bodies.len(), "source_count":f.source_count(),
                "passive_count":f.bodies.len()-f.source_count(), "dt_seconds":f.dt,
                "synthetic":true, "units":"SI with synthetic gravitational parameters",
            })
        })
        .collect();
    json!({
        "schema_version":1, "kind":"solarlab_rust_research_catalogue", "cases":cases, "fixtures":fixtures,
        "identity":{"architecture":std::env::consts::ARCH,"operating_system":std::env::consts::OS,
            "cpu_features":detected_cpu_features(),"cpu_features_source":"solarlab_physics::detect_cpu_features",
            "capability_scope":"current process; detected support does not imply an implemented or executed kernel"},
        "bounds":{"min_steps":1,"max_steps":64,"min_repeats":1,"max_repeats":11},
        "timing_scope":"integration including scratch setup, packing and worker lifecycle; excludes fixture, oracle, diagnostics, JSON and JNI",
        "authoritative_world_mutated":false,
    }).to_string()
}

/// One bounded call. Android cancellation happens between calls. Measurements
/// exclude oracle construction/validation but include all candidate scratch work.
#[must_use]
pub fn run_case_json(case_id: &str, fixture_id: &str, steps: u32, repeats: u32) -> String {
    run_case(case_id, fixture_id, steps, repeats).to_string()
}

fn rejected(case_id: &str, fixture_id: &str, status: &str, reason: &str) -> Value {
    let mut report = json!({"schema_version":1,"case_id":case_id,"fixture_id":fixture_id,"status":status,
        "reason":reason,"elapsed_ns":0,"samples_ns":[],"kernel_total_ns":0,
        "correctness_checked":false,"authoritative_world_mutated":false});
    if let Some(case) = CASES.iter().find(|case| case.id == case_id) {
        annotate_comparison(&mut report, case);
    }
    report
}

fn annotate_comparison(report: &mut Value, case: &Case) {
    report["baseline_case_id"] = json!(case.baseline);
    report["comparison_kind"] = json!(case.comparison_kind());
    report["physics_model"] = json!(case.physics_model());
    report["baseline_physics_model"] = json!("Newtonian");
    report["control_scope_note"] = json!(case.control_scope_note());
    annotate_reciprocal_configuration(report, case);
}

fn annotate_reciprocal_configuration(report: &mut Value, case: &Case) {
    if let ForceMode::NeonRsqrt(refinements) = case.force {
        report["reciprocal_sqrt_configuration"] = json!({
            "scope":"declared kernel configuration; actual_kernel identifies execution",
            "estimate_instruction":"FRSQRTE .2D", "refinement_instruction":"FRSQRTS .2D",
            "refinement_iterations":refinements, "precision":"FP64", "vector_lanes":2,
            "target_block_width":4, "squared_distance_floor":model::MIN_R2,
            "positive_infinite_squared_distance":"zero inverse, matching sqrt/div control",
            "non_finite_output":"fails independent state validation", "scalar_estimate_fallback":false,
            "timing_control":"neon-block-4", "accuracy_oracle":"production FP64 scalar at identical fixed steps",
        });
    }
}

#[allow(clippy::too_many_lines)]
fn run_case(case_id: &str, fixture_id: &str, steps: u32, repeats: u32) -> Value {
    let Some(case) = CASES.iter().find(|case| case.id == case_id) else {
        return rejected(case_id, fixture_id, "FAIL", "Unknown case ID");
    };
    if !(1..=64).contains(&steps) || !(1..=11).contains(&repeats) {
        return rejected(
            case_id,
            fixture_id,
            "FAIL",
            "steps must be 1..64 and repeats must be 1..11",
        );
    }
    let (available, reason) = catalogue::availability(case);
    if !available {
        return rejected(case_id, fixture_id, "SKIP", reason);
    }
    if !case.fixtures.contains(&fixture_id) {
        return rejected(
            case_id,
            fixture_id,
            "SKIP",
            "Fixture outside this experiment's declared scientific/workload domain",
        );
    }
    let Some(fixture) = model::fixture(fixture_id) else {
        return rejected(case_id, fixture_id, "FAIL", "Unknown fixture ID");
    };
    let selected = matches!(case.algorithm, Algorithm::Production(true));
    let Some(plan) = production_plan(&fixture, steps, selected) else {
        return rejected(
            case_id,
            fixture_id,
            "FAIL",
            "Production plan rejected bounded fixture",
        );
    };
    let execution = plan.report();
    let mut samples = Vec::with_capacity(repeats as usize);
    let mut checksums = Vec::with_capacity(repeats as usize);
    let mut final_state = fixture.bodies.clone();
    let mut work_stats = Stats::default();
    for _ in 0..repeats {
        let mut bodies = fixture.bodies.clone();
        let mut production = model::to_production(&fixture.bodies);
        let start = Instant::now();
        let result = match case.algorithm {
            Algorithm::Production(_) => {
                solarlab_physics::advance_fixed_steps(&plan, &mut production, steps as usize)
                    .map(|()| Stats {
                        accepted_steps: u64::from(steps),
                        ..Stats::default()
                    })
                    .map_err(|()| "Production segment rejected")
            }
            _ => execute(case, &mut bodies, steps as usize, fixture.dt),
        };
        black_box(&bodies);
        black_box(&production);
        let elapsed = u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX);
        let Ok(stats) = result else {
            let mut report = rejected(
                case_id,
                fixture_id,
                "FAIL",
                result.err().unwrap_or("Unknown integration failure"),
            );
            samples.push(elapsed);
            report["samples_ns"] = json!(samples);
            report["kernel_total_ns"] = json!(samples.iter().copied().sum::<u64>());
            report["elapsed_ns"] = json!(elapsed);
            report["elapsed_scope"] = json!("failed_attempt_only");
            report["steps_requested"] = json!(steps);
            report["dt_seconds"] = json!(fixture.dt);
            report["partial_state_checksum"] = json!(model::checksum(&bodies));
            report["partial_state_is_accepted"] = json!(false);
            return report;
        };
        if matches!(case.algorithm, Algorithm::Production(_)) {
            bodies = model::from_production(&production);
        }
        samples.push(elapsed);
        checksums.push(model::checksum(&bodies));
        final_state = bodies;
        work_stats = stats;
    }
    let Some((reference, reference_name, reference_uncertainty)) =
        reference_for(case, &fixture, steps)
    else {
        return rejected(
            case_id,
            fixture_id,
            "FAIL",
            "Independent reference could not be computed",
        );
    };
    let error = model::error(&final_state, &reference);
    let finite = model::finite(&final_state);
    let consistent = checksums.iter().all(|hash| hash == &checksums[0]);
    let reference_accuracy_pass =
        model::finite(&reference) && reference_uncertainty <= (0.1 * case.tolerance).max(1.0e-13);
    let accuracy_pass =
        finite && reference_accuracy_pass && error.scaled_state_error <= case.tolerance;
    let before = model::invariants(&fixture.bodies);
    let after = model::invariants(&final_state);
    let mut sorted = samples.clone();
    sorted.sort_unstable();
    let elapsed_ns = sorted[(sorted.len() - 1) / 2];
    let kernel_total_ns: u64 = samples.iter().copied().sum();
    let actual_kernel = if matches!(case.algorithm, Algorithm::Production(_)) {
        execution.path_id.clone()
    } else if matches!(
        case.force,
        ForceMode::NeonBlock(_) | ForceMode::NeonSources3 | ForceMode::NeonRsqrt(_)
    ) {
        format!("research.arm64.fp64.{}", case.id)
    } else {
        format!("research.portable.{}", case.id)
    };
    let numerical_policy = if case.family == "approximate" {
        "isolated approximate forecast; validate against FP64 before acceptance"
    } else {
        "isolated FP64 research; production numerical policy unchanged"
    };
    let mut report = json!({
        "schema_version":1, "kind":"solarlab_rust_research_result", "case_id":case.id,"fixture_id":fixture.id,
        "status":if accuracy_pass && consistent {"PASS"} else {"FAIL"},
        "reason":if !finite {"Non-finite candidate state"} else if !consistent {"Repeated candidates were not bitwise deterministic"}
            else if !reference_accuracy_pass {"Independent reference failed its finer-step convergence check"}
            else if !accuracy_pass {"Candidate exceeded declared state error budget"} else {""},
        "actual_kernel":actual_kernel,"numerical_policy":numerical_policy,"note":case.note,
        "recommendation_ids":case.recommendations,"body_count":fixture.bodies.len(),"source_count":fixture.source_count(),
        "passive_count":fixture.bodies.len()-fixture.source_count(),"steps":steps,"repeats":repeats,
        "dt_seconds":fixture.dt,"simulated_seconds_per_repeat":f64::from(steps)*fixture.dt,
        "elapsed_ns":elapsed_ns,"elapsed_scope":"median_per_repeat","samples_ns":samples,"kernel_total_ns":kernel_total_ns,
        "timing_scope":"integration_and_scratch_only","reference":reference_name,"correctness_checked":true,
        "accuracy_pass":accuracy_pass,"scaled_state_error_limit":case.tolerance,"error":error,
        "reference_scaled_uncertainty":reference_uncertainty,"reference_accuracy_pass":reference_accuracy_pass,
        "invariants_before":before,"invariants_after":after,"work":work_stats,
        "invariants_reference":model::invariants(&reference),
        "checksum":checksums[0],"repeat_checksums":checksums,"repeat_consistent":consistent,
        "baseline_case_id":case.baseline,"authoritative_world_mutated":false,
        "diagnostic_energy_note":"Newtonian source energy diagnostic; modified-force cases do not claim it is their conserved Hamiltonian",
    });
    annotate_comparison(&mut report, case);
    if matches!(case.algorithm, Algorithm::Production(_)) {
        report["production"] = json!({"requested_backend":format!("{:?}",execution.requested_backend),
            "effective_backend":format!("{:?}",execution.effective_backend),"active_workers":execution.schedule.active_workers,
            "fallback_code":format!("{:?}",execution.fallback_code),"fallback_reason":execution.fallback_reason,
            "cpu_features":execution.active_cpu_features,"estimated_pair_count":execution.schedule.estimated_pair_count});
    }
    if case.family == "approximate" {
        report["forecast_acceptance"] = json!({"accepted":accuracy_pass,"checkpoint_reference":reference_name,
            "reference_checksum":model::checksum(&reference),"authoritative_replacement_performed":false});
    }
    report
}

fn detected_cpu_features() -> &'static [String] {
    static FEATURES: OnceLock<Vec<String>> = OnceLock::new();
    FEATURES.get_or_init(solarlab_physics::detect_cpu_features)
}

fn production_plan(fixture: &Fixture, steps: u32, selected: bool) -> Option<FixedStepPlan> {
    let backend = if !selected {
        SolverBackend::ReferenceScalar
    } else if cfg!(target_arch = "aarch64") {
        SolverBackend::SimdArm64
    } else if cfg!(target_arch = "x86_64") {
        SolverBackend::SimdX64
    } else {
        SolverBackend::ReferenceScalar
    };
    let policy = PhysicsPolicy {
        solver_backend: backend,
        integrator: IntegratorKind::LeapfrogKickDriftKick,
        collision_model: CollisionModel::None,
        max_substep_seconds: fixture.dt,
    };
    solarlab_physics::fixed_step_plan_with_source_count(
        &policy,
        fixture.dt * f64::from(steps),
        fixture.bodies.len(),
        fixture.source_count(),
        detected_cpu_features(),
    )
}

fn execute(case: &Case, bodies: &mut [Body], steps: usize, dt: f64) -> Result<Stats, &'static str> {
    match case.algorithm {
        Algorithm::Leapfrog => Ok(integrators::leapfrog(bodies, case.force, steps, dt)),
        Algorithm::PersistentWorkers(workers) => {
            integrators::persistent_leapfrog(bodies, steps, dt, workers)
        }
        Algorithm::Adaptive => integrators::adaptive_doubling(bodies, steps, dt),
        Algorithm::Close => integrators::close_refinement(bodies, steps, dt),
        Algorithm::Yoshida => Ok(integrators::yoshida4(bodies, steps, dt)),
        Algorithm::Kepler => integrators::kepler_drift(bodies, steps, dt),
        Algorithm::Radau => radau::advance(bodies, steps, dt),
        Algorithm::Rk4 => Ok(integrators::rk4(bodies, case.force, steps, dt)),
        Algorithm::Extrapolate(interval) => Ok(integrators::passive_extrapolate(
            bodies, steps, dt, interval,
        )),
        Algorithm::Production(_) => Err("Production integration requires its prepared plan"),
    }
}

fn reference_for(
    case: &Case,
    fixture: &Fixture,
    steps: u32,
) -> Option<(Vec<Body>, &'static str, f64)> {
    if case.family == "numerical" {
        let mut reference = fixture.bodies.clone();
        if fixture.id == "kepler" && case.algorithm != Algorithm::Rk4 {
            let time = f64::from(steps) * fixture.dt;
            reference[1].p = [time.cos(), time.sin(), 0.0];
            reference[1].v = [-time.sin(), time.cos(), 0.0];
            Some((reference, "analytic circular Kepler orbit", 0.0))
        } else {
            integrators::rk4(
                &mut reference,
                case.force,
                steps as usize * 64,
                fixture.dt / 64.0,
            );
            let mut finer = fixture.bodies.clone();
            integrators::rk4(
                &mut finer,
                case.force,
                steps as usize * 128,
                fixture.dt / 128.0,
            );
            let uncertainty = model::error(&reference, &finer).scaled_state_error;
            Some((
                finer,
                "independent same-force RK4, 128x finer steps, convergence checked against 64x",
                uncertainty,
            ))
        }
    } else {
        let plan = production_plan(fixture, steps, false)?;
        let mut reference = model::to_production(&fixture.bodies);
        solarlab_physics::advance_fixed_steps(&plan, &mut reference, steps as usize).ok()?;
        Some((
            model::from_production(&reference),
            "production FP64 scalar at identical fixed steps",
            0.0,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_requests_do_not_run_and_never_silently_clamp() {
        for (case, fixture, steps, repeats) in [
            ("unknown", "kepler", 1, 1),
            ("production-scalar", "kepler", 0, 1),
            ("production-scalar", "kepler", 65, 1),
            ("production-scalar", "kepler", 1, 0),
            ("production-scalar", "kepler", 1, 12),
        ] {
            let report: Value =
                serde_json::from_str(&run_case_json(case, fixture, steps, repeats)).unwrap();
            assert_eq!(report["status"], "FAIL");
            assert_eq!(report["correctness_checked"], false);
        }
        let report: Value =
            serde_json::from_str(&run_case_json("kepler-drift", "dense256", 1, 1)).unwrap();
        assert_eq!(report["status"], "SKIP");
    }

    #[test]
    fn catalogue_has_unique_ids_and_resolvable_baselines_and_fixtures() {
        let mut seen = std::collections::HashSet::new();
        for case in CASES {
            assert!(seen.insert(case.id));
            assert!(CASES.iter().any(|c| c.id == case.baseline));
            for fixture in case.fixtures {
                assert!(model::fixture(fixture).is_some());
            }
        }
        let parsed: Value = serde_json::from_str(&catalogue_json()).unwrap();
        assert_eq!(parsed["schema_version"], 1);
        assert_eq!(parsed["cases"].as_array().unwrap().len(), CASES.len());
        assert_eq!(
            parsed["identity"]["cpu_features"],
            json!(detected_cpu_features())
        );
    }

    #[test]
    fn force_kernel_timing_controls_follow_shipping_dispatch_without_changing_accuracy_oracles() {
        let catalogue: Value = serde_json::from_str(&catalogue_json()).unwrap();
        let entries = catalogue["cases"].as_array().unwrap();
        for case in CASES
            .iter()
            .filter(|case| case.family == "cpu" && case.algorithm == Algorithm::Leapfrog)
        {
            let entry = entries.iter().find(|entry| entry["id"] == case.id).unwrap();
            assert_eq!(
                entry["baseline_case_id"], "production-selected",
                "{}",
                case.id
            );
            assert_eq!(entry["comparison_kind"], "equivalent_kernel");
            assert!(entry["control_scope_note"]
                .as_str()
                .unwrap()
                .contains("timing control is production-selected"));
            // Rejected calls must not erase which control was actually intended.
            let rejected = run_case(case.id, case.fixtures[0], 0, 1);
            assert_eq!(rejected["status"], "FAIL");
            assert_eq!(rejected["baseline_case_id"], entry["baseline_case_id"]);
            assert_eq!(rejected["control_scope_note"], entry["control_scope_note"]);
        }
        let candidate = run_case("source-specialised", "sparse3", 8, 1);
        assert_eq!(candidate["status"], "PASS");
        assert_eq!(candidate["baseline_case_id"], "production-selected");
        assert_eq!(
            candidate["reference"],
            "production FP64 scalar at identical fixed steps"
        );

        for (id, baseline) in [
            ("production-selected", "production-scalar"),
            ("persistent-workers-4", "workers-4"),
            ("neon-rsqrt-refine-2", "neon-block-4"),
            ("neon-rsqrt-refine-3", "neon-block-4"),
            ("yoshida4", "production-scalar"),
            ("fp32-forecast", "production-scalar"),
        ] {
            let entry = entries.iter().find(|entry| entry["id"] == id).unwrap();
            assert_eq!(entry["baseline_case_id"], baseline, "{id}");
        }
    }

    #[test]
    fn hardware_reciprocal_cases_keep_matched_controls_and_explicit_instruction_contracts() {
        let catalogue: Value = serde_json::from_str(&catalogue_json()).unwrap();
        for refinements in [2, 3] {
            let id = format!("neon-rsqrt-refine-{refinements}");
            let entry = catalogue["cases"]
                .as_array()
                .unwrap()
                .iter()
                .find(|entry| entry["id"] == id)
                .unwrap();
            assert_eq!(entry["baseline_case_id"], "neon-block-4");
            assert_eq!(entry["comparison_kind"], "accuracy_cost_experiment");
            assert!(entry["control_scope_note"]
                .as_str()
                .unwrap()
                .contains("timing control is neon-block-4"));
            assert_eq!(
                entry["reciprocal_sqrt_configuration"]["refinement_iterations"],
                refinements
            );
            assert_eq!(
                entry["reciprocal_sqrt_configuration"]["scalar_estimate_fallback"],
                false
            );
            assert_eq!(
                entry["reciprocal_sqrt_configuration"]["estimate_instruction"],
                "FRSQRTE .2D"
            );
            assert_eq!(
                entry["reciprocal_sqrt_configuration"]["refinement_instruction"],
                "FRSQRTS .2D"
            );
            for fixture in catalogue::ALL_FIXTURES {
                let result = run_case(&id, fixture, 8, 2);
                assert_eq!(
                    result["reciprocal_sqrt_configuration"],
                    entry["reciprocal_sqrt_configuration"]
                );
                if neon::available() {
                    assert_eq!(result["status"], "PASS", "{result}");
                    assert_eq!(result["actual_kernel"], format!("research.arm64.fp64.{id}"));
                    assert_eq!(result["repeat_consistent"], true);
                    assert_eq!(
                        result["reference"],
                        "production FP64 scalar at identical fixed steps"
                    );
                } else {
                    assert_eq!(result["status"], "SKIP");
                    assert_eq!(result["actual_kernel"], Value::Null);
                }
            }
        }
    }

    #[test]
    fn comparison_metadata_prevents_cross_model_or_policy_speedup_claims() {
        let catalogue: Value = serde_json::from_str(&catalogue_json()).unwrap();
        for (id, kind, model) in [
            ("production-selected", "equivalent_kernel", "Newtonian"),
            ("persistent-workers-4", "equivalent_kernel", "Newtonian"),
            ("yoshida4", "accuracy_cost_experiment", "Newtonian"),
            ("fp32-forecast", "accuracy_cost_experiment", "Newtonian"),
            ("force-1pn", "different_force_model_cost", "1PN"),
            ("force-j2", "different_force_model_cost", "J2"),
            ("force-radiation", "different_force_model_cost", "radiation"),
        ] {
            let entry = catalogue["cases"]
                .as_array()
                .unwrap()
                .iter()
                .find(|entry| entry["id"] == id)
                .unwrap();
            assert_eq!(entry["comparison_kind"], kind);
            assert_eq!(entry["physics_model"], model);
            assert_eq!(entry["baseline_physics_model"], "Newtonian");
            let case = CASES.iter().find(|case| case.id == id).unwrap();
            // Rejected calls retain classification as well as successful samples.
            for steps in [0, 1] {
                let report = run_case(id, case.fixtures[0], steps, 1);
                assert_eq!(report["status"], if steps == 0 { "FAIL" } else { "PASS" });
                for key in [
                    "comparison_kind",
                    "physics_model",
                    "baseline_physics_model",
                    "control_scope_note",
                ] {
                    assert_eq!(report[key], entry[key]);
                }
            }
        }
        let skipped = run_case("force-j2", "dense256", 1, 1);
        assert_eq!(skipped["status"], "SKIP");
        assert_eq!(skipped["comparison_kind"], "different_force_model_cost");
    }

    #[test]
    fn every_runnable_case_executes_its_own_declared_fixture_and_oracle() {
        for case in CASES {
            let report = run_case(case.id, case.fixtures[0], 4, 1);
            if catalogue::availability(case).0 {
                assert_eq!(report["status"], "PASS", "{report}");
                assert_eq!(report["correctness_checked"], true);
                assert!(report["elapsed_ns"].as_u64().unwrap() > 0);
            } else {
                assert_eq!(report["status"], "SKIP");
            }
        }
    }

    #[test]
    fn scalar_repeats_are_bitwise_stable_and_timing_units_are_explicit() {
        let report = run_case("production-scalar", "kepler", 8, 3);
        assert_eq!(report["status"], "PASS");
        assert_eq!(report["repeat_consistent"], true);
        assert_eq!(report["error"]["scaled_state_error"], 0.0);
        assert_eq!(report["samples_ns"].as_array().unwrap().len(), 3);
        assert_eq!(report["elapsed_scope"], "median_per_repeat");
        assert!(
            report["kernel_total_ns"].as_u64().unwrap() >= report["elapsed_ns"].as_u64().unwrap()
        );
    }

    #[test]
    fn isolated_benchmark_does_not_require_a_runtime_or_checkpoint() {
        let before = model::fixture("canonical15").unwrap();
        let first = run_case("source-tile-32", "canonical15", 8, 1);
        let second = run_case("source-tile-32", "canonical15", 8, 1);
        assert_eq!(first["checksum"], second["checksum"]);
        assert_eq!(before.bodies, model::fixture("canonical15").unwrap().bodies);
        assert_eq!(first["authoritative_world_mutated"], false);
    }
}
