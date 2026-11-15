//! A conservative fixed-step ceiling for the laboratory's current world.
//! The integrator stays KDK. This chooses one step ceiling before an operation;
//! it is not an adaptive solver or a collision/close-encounter guarantee.
use super::{BodyState, PhysicsPolicy};

const G: f64 = 6.67430e-11;
const MIN_LAB_STEP_SECONDS: f64 = 0.05;

pub(super) fn for_world(configured: &PhysicsPolicy, bodies: &[BodyState]) -> PhysicsPolicy {
    let mut policy = configured.clone();
    let mut ceiling = configured.max_substep_seconds;
    if !ceiling.is_finite() || ceiling <= 0.0 {
        return policy;
    }
    for (index, first) in bodies.iter().enumerate() {
        for second in &bodies[index + 1..] {
            let source_mass = first.source_mass_kg + second.source_mass_kg;
            if source_mass <= 0.0 {
                continue;
            }
            let distance = (first.position_m.x - second.position_m.x)
                .hypot(first.position_m.y - second.position_m.y)
                .hypot(first.position_m.z - second.position_m.z);
            if !distance.is_finite() {
                continue;
            }
            let relative_speed = (first.velocity_mps.x - second.velocity_mps.x)
                .hypot(first.velocity_mps.y - second.velocity_mps.y)
                .hypot(first.velocity_mps.z - second.velocity_mps.z);
            // About 1,250 steps per circular orbit at the limiting separation.
            let gravity_step = 0.005 * distance * (distance / (G * source_mass)).sqrt();
            let crossing_step = if relative_speed > 0.0 {
                0.01 * distance / relative_speed
            } else {
                f64::INFINITY
            };
            let local_ceiling = gravity_step.min(crossing_step).max(MIN_LAB_STEP_SECONDS);
            ceiling = ceiling.min(local_ceiling);
        }
    }
    policy.max_substep_seconds = ceiling;
    policy
}

#[cfg(test)]
mod tests {
    use super::*;
    use solarlab_data::earth_moon_lab_bodies;
    use solarlab_domain::{BodyClass, BodyId};
    use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

    fn fixture() -> (PhysicsPolicy, Vec<BodyState>) {
        let policy = PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: 60.0,
        };
        let bodies = earth_moon_lab_bodies()
            .into_iter()
            .map(|body| BodyState {
                body_id: BodyId(body.body_id),
                body_class: body.body_class,
                mass_kg: body.mass_kg,
                source_mass_kg: body.mass_kg,
                radius_m: body.radius_m,
                position_m: body.position_m,
                velocity_mps: body.velocity_mps,
            })
            .collect();
        (policy, bodies)
    }

    #[test]
    fn unedited_lab_retains_qualified_step_but_low_orbit_probe_tightens_it() {
        let (policy, mut bodies) = fixture();
        assert_eq!(for_world(&policy, &bodies).max_substep_seconds, 60.0);
        let earth = bodies[1].clone();
        let radius = earth.radius_m + 800_000.0;
        let mut probe = earth.clone();
        probe.body_id = BodyId("probe".into());
        probe.body_class = BodyClass::Spacecraft;
        probe.mass_kg = 1_000.0;
        probe.source_mass_kg = 0.0;
        probe.radius_m = 2.0;
        probe.position_m.x += radius;
        probe.velocity_mps.y += (G * earth.source_mass_kg / radius).sqrt();
        bodies.push(probe);
        let step = for_world(&policy, &bodies).max_substep_seconds;
        assert!((4.0..5.0).contains(&step), "low-orbit step: {step}");
        // Qualify the practical 800 km preset against a much finer integration
        // over one nominal orbit, with Sun/Earth/Moon gravity present in both.
        let mut coarse: Vec<solarlab_physics::MassiveBodyState> = bodies
            .iter()
            .map(|body| solarlab_physics::MassiveBodyState {
                mass_kg: body.source_mass_kg,
                position_m: body.position_m,
                velocity_mps: body.velocity_mps,
            })
            .collect();
        let mut reference = coarse.clone();
        let period = std::f64::consts::TAU * (radius.powi(3) / (G * earth.source_mass_kg)).sqrt();
        solarlab_physics::advance_authoritative_scalar(
            &for_world(&policy, &bodies),
            &mut coarse,
            period,
        );
        solarlab_physics::advance_authoritative_scalar(
            &PhysicsPolicy {
                max_substep_seconds: 0.5,
                ..policy.clone()
            },
            &mut reference,
            period,
        );
        let error_x = (coarse[3].position_m.x - coarse[1].position_m.x)
            - (reference[3].position_m.x - reference[1].position_m.x);
        let error_y = (coarse[3].position_m.y - coarse[1].position_m.y)
            - (reference[3].position_m.y - reference[1].position_m.y);
        let error_z = (coarse[3].position_m.z - coarse[1].position_m.z)
            - (reference[3].position_m.z - reference[1].position_m.z);
        let error = error_x.hypot(error_y).hypot(error_z);
        assert!(error < 500.0, "800 km preset convergence error: {error} m");
        let finer = PhysicsPolicy {
            max_substep_seconds: 1.0,
            ..policy
        };
        assert_eq!(for_world(&finer, &bodies).max_substep_seconds, 1.0);
    }

    #[test]
    fn fast_approach_is_bounded_by_crossing_time_even_for_a_massless_probe() {
        let (policy, mut bodies) = fixture();
        let mut probe = bodies[1].clone();
        probe.body_id = BodyId("fast-probe".into());
        probe.source_mass_kg = 0.0;
        probe.position_m.x += 10_000_000.0;
        probe.velocity_mps.x += 1_000_000.0;
        bodies.push(probe);
        assert!(for_world(&policy, &bodies).max_substep_seconds <= 0.1);
    }

    #[test]
    fn low_orbit_playback_obeys_the_same_bounded_policy_as_integration() {
        use crate::{WorldCommand, MAX_INCREMENTAL_STEPS};
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime.config.physics.max_substep_seconds = 60.0;
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        let mut probe = runtime
            .snapshot()
            .bodies
            .into_iter()
            .find(|body| body.body_id.0 == "earth")
            .unwrap();
        let radius = probe.radius_m + 800_000.0;
        let speed = (G * probe.source_mass_kg / radius).sqrt();
        probe.body_id = BodyId("probe".into());
        probe.body_class = BodyClass::Spacecraft;
        probe.mass_kg = 1_000.0;
        probe.source_mass_kg = 0.0;
        probe.radius_m = 2.0;
        probe.position_m.x += radius;
        probe.velocity_mps.y += speed;
        runtime
            .apply_command(WorldCommand::SpawnBody { body: probe }, 2)
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 21_600.0,
                },
                3,
            )
            .unwrap();
        runtime
            .apply_command(WorldCommand::ResumePlayback, 4)
            .unwrap();
        for tick in 0..20 {
            let policy = runtime.effective_physics_policy();
            let result = runtime.playback_tick(0.1, 5 + tick).unwrap();
            assert!(result.limited);
            assert!(
                solarlab_physics::checked_substep_count(&policy, result.applied_seconds).unwrap()
                    <= MAX_INCREMENTAL_STEPS
            );
        }
    }
}
