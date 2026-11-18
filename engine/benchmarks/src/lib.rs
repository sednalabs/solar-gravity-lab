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
            json!({
                "id":case.id, "label":case.label, "family":case.family,
                "status":if available {"runnable"} else {"unsupported"}, "available":available,
                "implemented":true, "reason":reason, "baseline_case_id":case.baseline,
                "fixtures":case.fixtures, "recommendation_ids":case.recommendations,
                "scaled_state_error_limit":case.tolerance, "note":case.note,
            })
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
    json!({"schema_version":1,"case_id":case_id,"fixture_id":fixture_id,"status":status,
        "reason":reason,"elapsed_ns":0,"samples_ns":[],"kernel_total_ns":0,
        "correctness_checked":false,"authoritative_world_mutated":false})
}

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
    let mut final_stats = Stats::default();
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
            return rejected(
                case_id,
                fixture_id,
                "FAIL",
                result.err().unwrap_or("Unknown integration failure"),
            );
        };
        if matches!(case.algorithm, Algorithm::Production(_)) {
            bodies = model::from_production(&production);
        }
        samples.push(elapsed);
        checksums.push(model::checksum(&bodies));
        final_state = bodies;
        final_stats = stats;
    }
    let Some((reference, reference_name)) = reference_for(case, &fixture, steps) else {
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
    let accuracy_pass = finite && error.scaled_state_error <= case.tolerance;
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
        ForceMode::NeonBlock(_) | ForceMode::NeonSources3
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
            else if !accuracy_pass {"Candidate exceeded declared state error budget"} else {""},
        "actual_kernel":actual_kernel,"numerical_policy":numerical_policy,"note":case.note,
        "recommendation_ids":case.recommendations,"body_count":fixture.bodies.len(),"source_count":fixture.source_count(),
        "passive_count":fixture.bodies.len()-fixture.source_count(),"steps":steps,"repeats":repeats,
        "dt_seconds":fixture.dt,"simulated_seconds_per_repeat":f64::from(steps)*fixture.dt,
        "elapsed_ns":elapsed_ns,"elapsed_scope":"median_per_repeat","samples_ns":samples,"kernel_total_ns":kernel_total_ns,
        "timing_scope":"integration_and_scratch_only","reference":reference_name,"correctness_checked":true,
        "accuracy_pass":accuracy_pass,"scaled_state_error_limit":case.tolerance,"error":error,
        "invariants_before":before,"invariants_after":after,"work":final_stats,
        "checksum":checksums[0],"repeat_checksums":checksums,"repeat_consistent":consistent,
        "baseline_case_id":case.baseline,"authoritative_world_mutated":false,
        "diagnostic_energy_note":"Newtonian source energy diagnostic; modified-force cases do not claim it is their conserved Hamiltonian",
    });
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

fn production_plan(fixture: &Fixture, steps: u32, selected: bool) -> Option<FixedStepPlan> {
    static FEATURES: OnceLock<Vec<String>> = OnceLock::new();
    let features = FEATURES.get_or_init(solarlab_physics::detect_cpu_features);
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
        features,
    )
}

fn execute(case: &Case, bodies: &mut [Body], steps: usize, dt: f64) -> Result<Stats, &'static str> {
    match case.algorithm {
        Algorithm::Leapfrog => Ok(integrators::leapfrog(bodies, case.force, steps, dt)),
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

fn reference_for(case: &Case, fixture: &Fixture, steps: u32) -> Option<(Vec<Body>, &'static str)> {
    if case.family == "numerical" {
        let mut reference = fixture.bodies.clone();
        if fixture.id == "kepler" && case.algorithm != Algorithm::Rk4 {
            let time = f64::from(steps) * fixture.dt;
            reference[1].p = [time.cos(), time.sin(), 0.0];
            reference[1].v = [-time.sin(), time.cos(), 0.0];
            Some((reference, "analytic circular Kepler orbit"))
        } else {
            integrators::rk4(
                &mut reference,
                case.force,
                steps as usize * 64,
                fixture.dt / 64.0,
            );
            Some((
                reference,
                "independent RK4 with 64x finer fixed steps and same force law",
            ))
        }
    } else {
        let plan = production_plan(fixture, steps, false)?;
        let mut reference = model::to_production(&fixture.bodies);
        solarlab_physics::advance_fixed_steps(&plan, &mut reference, steps as usize).ok()?;
        Some((
            model::from_production(&reference),
            "production FP64 scalar at identical fixed steps",
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
