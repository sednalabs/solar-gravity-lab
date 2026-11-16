//! Versioned fixed-step ceilings for the laboratory's current world.
//! V2 additionally resolves finite osculating two-body periapsides. This still
//! chooses one ceiling before an operation; it is not adaptive integration or
//! a collision/close-encounter guarantee. Radial degeneracies keep the local
//! distance bound so short releases from rest remain usable. When a predicted
//! periapsis is inside a body's physical surface, the extra refinement extends
//! only to first contact, where probe previews stop. Below-surface, long radial,
//! or strongly perturbed multi-body encounters are not qualified by this estimate.
use super::{BodyState, PhysicsPolicy};
use solarlab_domain::Vector3d;

const G: f64 = 6.67430e-11;
const MIN_LAB_STEP_SECONDS: f64 = 0.05;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum LabStepPolicy {
    LegacyDistanceV1,
    #[default]
    PeriapsisV2,
}

impl LabStepPolicy {
    pub(crate) fn model_id(self) -> &'static str {
        match self {
            Self::LegacyDistanceV1 => "newtonian-kdk-f64-lab-step-v1",
            Self::PeriapsisV2 => "newtonian-kdk-f64-lab-step-v2",
        }
    }

    pub(crate) fn from_model_id(value: &str) -> Option<Self> {
        match value {
            "newtonian-kdk-f64-lab-step-v1" => Some(Self::LegacyDistanceV1),
            "newtonian-kdk-f64-lab-step-v2" => Some(Self::PeriapsisV2),
            _ => None,
        }
    }

    pub(crate) fn for_world(
        self,
        configured: &PhysicsPolicy,
        bodies: &[BodyState],
    ) -> PhysicsPolicy {
        match self {
            Self::LegacyDistanceV1 => legacy_for_world(configured, bodies),
            Self::PeriapsisV2 => for_world(configured, bodies),
        }
    }
}

pub(super) fn for_world(configured: &PhysicsPolicy, bodies: &[BodyState]) -> PhysicsPolicy {
    let mut policy = configured.clone();
    if !policy.max_substep_seconds.is_finite() || policy.max_substep_seconds <= 0.0 {
        return policy;
    }
    let supported_minimum = MIN_LAB_STEP_SECONDS.min(configured.max_substep_seconds);
    for (index, first) in bodies.iter().enumerate() {
        for second in &bodies[index + 1..] {
            let source_mass = first.source_mass_kg + second.source_mass_kg;
            if source_mass <= 0.0 {
                continue;
            }
            let mu = G * source_mass;
            let r = difference(first.position_m, second.position_m);
            let v = difference(first.velocity_mps, second.velocity_mps);
            let distance = magnitude(r);
            let speed = magnitude(v);
            if !mu.is_finite() || !distance.is_finite() || distance <= 0.0 || !speed.is_finite() {
                policy.max_substep_seconds = 0.0;
                return policy;
            }
            let mut required = local_step(distance, speed, mu);
            if let Some((periapsis, periapsis_speed)) =
                resolved_periapsis(r, v, distance, speed, mu)
            {
                let exterior_distance = periapsis
                    .max(first.radius_m + second.radius_m)
                    .min(distance);
                let exterior_speed = if exterior_distance > periapsis {
                    // Total speed at first contact follows two-body energy,
                    // not just its tangential component. The force law and
                    // surface-crossing behavior themselves remain unchanged.
                    speed.hypot(
                        (2.0 * mu * (exterior_distance.recip() - distance.recip()))
                            .max(0.0)
                            .sqrt(),
                    )
                } else {
                    periapsis_speed
                };
                required = required.min(local_step(exterior_distance, exterior_speed, mu));
            }
            if required < supported_minimum || required.is_nan() {
                // Zero is an explicitly invalid plan. The physics planner and
                // runtime preflight reject it; never enlarge a required step.
                policy.max_substep_seconds = 0.0;
                return policy;
            }
            policy.max_substep_seconds = policy.max_substep_seconds.min(required);
        }
    }
    policy
}

fn local_step(distance: f64, speed: f64, mu: f64) -> f64 {
    let gravity_step = 0.005 * distance * (distance / mu).sqrt();
    let crossing_step = if speed > 0.0 {
        0.01 * distance / speed
    } else {
        f64::INFINITY
    };
    gravity_step.min(crossing_step)
}

/// Osculating Kepler q=p/(1+e), evaluated without multiplying r*v before
/// taking the cross product. A radial/unresolved angular momentum has no
/// finite resolved periapsis and deliberately retains the local bound.
fn resolved_periapsis(
    r: Vector3d,
    v: Vector3d,
    distance: f64,
    speed: f64,
    mu: f64,
) -> Option<(f64, f64)> {
    if speed == 0.0 {
        return None;
    }
    let direction = multiply(r, distance.recip());
    let transverse = cross(direction, v);
    let transverse_speed = magnitude(transverse);
    if !transverse_speed.is_finite() || transverse_speed <= 64.0 * f64::EPSILON * speed {
        return None;
    }
    let eccentricity_vector = difference(multiply(cross(v, transverse), distance / mu), direction);
    let eccentricity = magnitude(eccentricity_vector);
    let p = distance * (transverse_speed * (distance / mu).sqrt()).powi(2);
    let periapsis = (p / (1.0 + eccentricity)).min(distance);
    if !eccentricity.is_finite() || !periapsis.is_finite() || periapsis <= 0.0 {
        return None;
    }
    Some((periapsis, transverse_speed * (distance / periapsis)))
}

fn difference(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}
fn multiply(v: Vector3d, scalar: f64) -> Vector3d {
    Vector3d {
        x: v.x * scalar,
        y: v.y * scalar,
        z: v.z * scalar,
    }
}
fn magnitude(v: Vector3d) -> f64 {
    v.x.hypot(v.y).hypot(v.z)
}
fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}

/// Exact legacy behavior for saved experiments that were qualified with V1.
pub(super) fn legacy_for_world(configured: &PhysicsPolicy, bodies: &[BodyState]) -> PhysicsPolicy {
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

    fn isolated_probe(radius: f64, vx: f64, vy: f64) -> (PhysicsPolicy, Vec<BodyState>) {
        let (policy, seeded) = fixture();
        let mut earth = seeded[1].clone();
        earth.position_m = Vector3d::default();
        earth.velocity_mps = Vector3d::default();
        let mut probe = earth.clone();
        probe.body_id = BodyId("probe".into());
        probe.body_class = BodyClass::Spacecraft;
        probe.mass_kg = 1000.0;
        probe.source_mass_kg = 0.0;
        probe.radius_m = 2.0;
        probe.position_m.x = radius;
        probe.velocity_mps.x = vx;
        probe.velocity_mps.y = vy;
        (policy, vec![earth, probe])
    }

    fn solver_state(bodies: &[BodyState]) -> Vec<solarlab_physics::MassiveBodyState> {
        bodies
            .iter()
            .map(|body| solarlab_physics::MassiveBodyState {
                mass_kg: body.source_mass_kg,
                position_m: body.position_m,
                velocity_mps: body.velocity_mps,
            })
            .collect()
    }

    #[test]
    fn policy_model_ids_are_explicit_and_legacy_behavior_remains_available() {
        for policy in [LabStepPolicy::LegacyDistanceV1, LabStepPolicy::PeriapsisV2] {
            assert_eq!(
                LabStepPolicy::from_model_id(policy.model_id()),
                Some(policy)
            );
        }
        assert_eq!(LabStepPolicy::from_model_id("unknown-model"), None);
        let (configured, bodies) = isolated_probe(1.0, 0.0, 0.0);
        assert_eq!(
            LabStepPolicy::LegacyDistanceV1
                .for_world(&configured, &bodies)
                .max_substep_seconds,
            0.05
        );
        assert_eq!(
            LabStepPolicy::PeriapsisV2
                .for_world(&configured, &bodies)
                .max_substep_seconds,
            0.0
        );
        assert!(solarlab_physics::fixed_step_plan(
            &for_world(&configured, &bodies),
            1.0,
            bodies.len(),
            &[]
        )
        .is_none());
    }

    #[test]
    fn periapsis_bound_resolves_eccentric_orbit_without_changing_fixed_schedule() {
        let (_, seed) = fixture();
        let mu = G * seed[1].source_mass_kg;
        let semi_major: f64 = 40_000_000.0;
        let eccentricity = 0.82;
        let apoapsis = semi_major * (1.0 + eccentricity);
        let speed = (mu * (2.0 / apoapsis - semi_major.recip())).sqrt();
        let period = std::f64::consts::TAU * (semi_major.powi(3) / mu).sqrt();
        let (configured, bodies) = isolated_probe(apoapsis, 0.0, speed);
        let legacy = legacy_for_world(&configured, &bodies);
        let current = for_world(&configured, &bodies);
        assert_eq!(legacy.max_substep_seconds, 60.0);
        assert!((4.0..5.0).contains(&current.max_substep_seconds));
        let initial = solver_state(&bodies);
        let mut coarse = initial.clone();
        let mut refined = initial.clone();
        let mut fine = initial.clone();
        solarlab_physics::advance_authoritative_scalar(&legacy, &mut coarse, period);
        solarlab_physics::advance_authoritative_scalar(&current, &mut refined, period);
        solarlab_physics::advance_authoritative_scalar(
            &PhysicsPolicy {
                max_substep_seconds: 0.5,
                ..configured
            },
            &mut fine,
            period,
        );
        let closure_error = |state: &[solarlab_physics::MassiveBodyState]| {
            magnitude(difference(state[1].position_m, initial[1].position_m))
        };
        let legacy_error = closure_error(&coarse);
        let current_error = closure_error(&refined);
        let fine_error = closure_error(&fine);
        let convergence = magnitude(difference(refined[1].position_m, fine[1].position_m));
        eprintln!("eccentric Kepler closure: legacy={legacy_error}m, periapsis={current_error}m, fine={fine_error}m, current_vs_fine={convergence}m, step={}s", current.max_substep_seconds);
        assert!(legacy_error > 80_000.0);
        assert!(current_error < 650.0 && current_error < legacy_error / 100.0);
        assert!(fine_error < 10.0 && convergence < 650.0);
        // The same selected dt is used whether this operation is staged or
        // blocking; q estimation is not re-evaluated partway through a plan.
        let plan = solarlab_physics::fixed_step_plan(&current, period, bodies.len(), &[]).unwrap();
        let mut staged = initial;
        let mut remaining = plan.total_steps;
        while remaining > 0 {
            let count = remaining.min(solarlab_physics::MAX_INCREMENTAL_STEPS);
            solarlab_physics::advance_fixed_steps(&plan, &mut staged, count).unwrap();
            remaining -= count;
        }
        assert_eq!(staged, refined);
    }

    #[test]
    fn radial_release_keeps_short_resolvable_motion_and_matches_fine_reference() {
        let (configured, bodies) = isolated_probe(7_171_000.0, 0.0, 0.0);
        let selected = for_world(&configured, &bodies);
        assert!((4.0..5.0).contains(&selected.max_substep_seconds));
        let mut actual = solver_state(&bodies);
        let mut fine = actual.clone();
        solarlab_physics::advance_authoritative_scalar(&selected, &mut actual, 100.0);
        solarlab_physics::advance_authoritative_scalar(
            &PhysicsPolicy {
                max_substep_seconds: 0.125,
                ..configured
            },
            &mut fine,
            100.0,
        );
        let error = magnitude(difference(actual[1].position_m, fine[1].position_m));
        eprintln!("short radial release100s error={error}m");
        assert!(actual[1].position_m.x < bodies[1].position_m.x);
        assert!(error < 10.0);
        // No assertion qualifies an entire radial impact or singular crossing.
    }

    #[test]
    fn tiny_transverse_perturbation_does_not_reject_a_surface_limited_fall() {
        let (configured, bodies) = isolated_probe(7_171_000.0, -1.0, 0.001);
        let selected = for_world(&configured, &bodies).max_substep_seconds;
        assert!((3.0..5.0).contains(&selected));
        let r = bodies[1].position_m;
        let v = bodies[1].velocity_mps;
        let (q, _) = resolved_periapsis(
            r,
            v,
            magnitude(r),
            magnitude(v),
            G * bodies[0].source_mass_kg,
        )
        .unwrap();
        assert!(
            q < 1.0,
            "the formal point-mass periapsis is deep inside Earth"
        );
    }

    #[test]
    fn hyperbolic_periapsis_estimate_matches_known_conic_and_tightens_step() {
        let (_, seed) = fixture();
        let mu = G * seed[1].source_mass_kg;
        let q = 9_000_000.0;
        let infinity_speed: f64 = 3_000.0;
        let eccentricity = 1.0 + q * infinity_speed.powi(2) / mu;
        let angular_momentum = (mu * q * (1.0 + eccentricity)).sqrt();
        let radius = 80_000_000.0;
        let vy = angular_momentum / radius;
        let vx = -(infinity_speed.powi(2) + 2.0 * mu / radius - vy * vy).sqrt();
        let (configured, bodies) = isolated_probe(radius, vx, vy);
        let r = bodies[1].position_m;
        let v = bodies[1].velocity_mps;
        let (estimated_q, estimated_speed) =
            resolved_periapsis(r, v, radius, magnitude(v), mu).unwrap();
        assert!((estimated_q / q - 1.0).abs() < 1.0e-12);
        assert!((estimated_speed / (angular_momentum / q) - 1.0).abs() < 1.0e-12);
        let selected = for_world(&configured, &bodies).max_substep_seconds;
        assert!(selected > 0.0 && selected < 7.0);
        assert_eq!(
            legacy_for_world(&configured, &bodies).max_substep_seconds,
            60.0
        );
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
