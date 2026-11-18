//! Print a trajectory-comparison CSV against independent DOP853 reference data.
use solarlab_domain::spacecraft::{magnitude, sub};
use solarlab_physics::science::advance_authoritative_scientific;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};

#[path = "../benchmarks/reference_cases.rs"]
mod reference_cases;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("case,method,outer_cap_seconds,max_position_error_m,max_velocity_error_mps,accepted_steps,rejected_trials,force_evaluations,minimum_step_seconds,maximum_step_seconds");
    for case in reference_cases::cases() {
        for method in [
            IntegratorKind::LeapfrogKickDriftKick,
            IntegratorKind::YoshidaFourthOrder,
            IntegratorKind::DormandPrince54,
        ] {
            let mut config = case.settings.clone();
            config.integrator = method;
            config.adaptive.relative = 1e-11;
            config.adaptive.absolute_position_m = 1e-5;
            config.adaptive.absolute_velocity_mps = 1e-8;
            let max_step = if method == IntegratorKind::DormandPrince54 {
                300.0
            } else {
                5.0
            };
            let policy = PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: method,
                collision_model: CollisionModel::None,
                max_substep_seconds: max_step,
            };
            let mut bodies = case.samples[0].1.clone();
            let mut previous = 0.0;
            let (mut position_error, mut velocity_error) = (0.0_f64, 0.0_f64);
            let (mut accepted, mut rejected, mut evaluations) = (0, 0, 0);
            let (mut minimum, mut maximum) = (f64::INFINITY, 0.0_f64);
            for (time, reference) in case.samples.iter().skip(1) {
                let (_, _, report) = advance_authoritative_scientific(
                    &policy,
                    &config,
                    &case.body_ids,
                    &mut bodies,
                    time - previous,
                    &[],
                )?;
                for (actual, expected) in bodies.iter().zip(reference) {
                    position_error =
                        position_error.max(magnitude(sub(actual.position_m, expected.position_m)));
                    velocity_error = velocity_error
                        .max(magnitude(sub(actual.velocity_mps, expected.velocity_mps)));
                }
                accepted += report.accepted_steps;
                rejected += report.rejected_steps;
                evaluations += report.force_evaluations;
                minimum = minimum.min(report.minimum_step_seconds);
                maximum = maximum.max(report.maximum_step_seconds);
                previous = *time;
            }
            println!("{},{method:?},{max_step},{position_error:.12},{velocity_error:.12},{accepted},{rejected},{evaluations},{minimum:.9},{maximum:.9}",case.name);
        }
    }
    Ok(())
}
