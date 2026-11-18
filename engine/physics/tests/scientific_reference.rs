//! Oracles are Kepler geometry and potential differentiation, not a copy of SGL's RK.
use solarlab_domain::science::{ScientificSettings, ZonalHarmonic};
use solarlab_domain::spacecraft::{add, cross, magnitude, scale, sub};
use solarlab_domain::{BodyId, Vector3d};
use solarlab_physics::science::{
    advance_authoritative_scientific, advance_fixed_steps_checked, j2_field, ScientificError,
    ScientificStepReport,
};
use solarlab_physics::{
    advance_authoritative_scalar, advance_authoritative_with_features,
    fixed_step_plan_with_source_count, CollisionModel, IntegratorKind, MassiveBodyState,
    PhysicsPolicy, SolverBackend,
};
use std::f64::consts::TAU;

#[path = "../benchmarks/reference_cases.rs"]
mod reference_cases;

const G: f64 = 6.67430e-11;
const EARTH_MU: f64 = 3.986004418e14;

fn vector(x: f64, y: f64, z: f64) -> Vector3d {
    Vector3d { x, y, z }
}
fn policy(integrator: IntegratorKind, max_step: f64) -> PhysicsPolicy {
    PhysicsPolicy {
        solver_backend: SolverBackend::ReferenceScalar,
        integrator,
        collision_model: CollisionModel::None,
        max_substep_seconds: max_step,
    }
}
fn settings(method: IntegratorKind) -> ScientificSettings {
    ScientificSettings {
        integrator: method,
        ..ScientificSettings::default()
    }
}
fn ids(count: usize) -> Vec<BodyId> {
    (0..count)
        .map(|i| BodyId(format!("reference-{i}")))
        .collect()
}
fn propagate(
    initial: &[MassiveBodyState],
    config: &ScientificSettings,
    duration: f64,
    max_step: f64,
) -> (Vec<MassiveBodyState>, ScientificStepReport) {
    let mut bodies = initial.to_vec();
    let (_, solver, report) = advance_authoritative_scientific(
        &policy(config.integrator, max_step),
        config,
        &ids(bodies.len()),
        &mut bodies,
        duration,
        &[],
    )
    .expect("reference trajectory is within the declared model");
    assert_eq!(solver.effective_integrator, config.integrator);
    assert_eq!(report.effective_integrator, config.integrator);
    assert!(!report.nonconservative_split);
    (bodies, report)
}

// E - e sin(E) = M is a geometrical two-body oracle, independent of time-stepping.
fn elliptic_state(mu: f64, a: f64, e: f64, time: f64) -> (Vector3d, Vector3d) {
    let n = (mu / a.powi(3)).sqrt();
    let mean = (n * time).rem_euclid(TAU);
    let mut eccentric = if e < 0.8 { mean } else { std::f64::consts::PI };
    for _ in 0..30 {
        let change = (eccentric - e * eccentric.sin() - mean) / (1.0 - e * eccentric.cos());
        eccentric -= change;
        if change.abs() < 2e-15 {
            break;
        }
    }
    let denominator = 1.0 - e * eccentric.cos();
    let b = a * (1.0 - e * e).sqrt();
    (
        vector(a * (eccentric.cos() - e), b * eccentric.sin(), 0.0),
        vector(
            -a * n * eccentric.sin() / denominator,
            b * n * eccentric.cos() / denominator,
            0.0,
        ),
    )
}
fn elliptic_pair(mu: f64, a: f64, e: f64, time: f64) -> Vec<MassiveBodyState> {
    let mass_fraction = 1e-4;
    let (r, v) = elliptic_state(mu, a, e, time);
    vec![
        MassiveBodyState {
            mass_kg: mu / G * (1.0 - mass_fraction),
            position_m: scale(r, -mass_fraction),
            velocity_mps: scale(v, -mass_fraction),
        },
        MassiveBodyState {
            mass_kg: mu / G * mass_fraction,
            position_m: scale(r, 1.0 - mass_fraction),
            velocity_mps: scale(v, 1.0 - mass_fraction),
        },
    ]
}
fn relative_error(actual: &[MassiveBodyState], expected: &[MassiveBodyState]) -> (f64, f64) {
    (
        magnitude(sub(
            sub(actual[1].position_m, actual[0].position_m),
            sub(expected[1].position_m, expected[0].position_m),
        )),
        magnitude(sub(
            sub(actual[1].velocity_mps, actual[0].velocity_mps),
            sub(expected[1].velocity_mps, expected[0].velocity_mps),
        )),
    )
}

#[test]
fn yoshida_has_fourth_order_convergence_against_kepler_ellipse() {
    let a: f64 = 8.0e6;
    let period = TAU * (a.powi(3) / EARTH_MU).sqrt();
    let duration = 0.83 * period;
    let initial = elliptic_pair(EARTH_MU, a, 0.3, 0.0);
    let expected = elliptic_pair(EARTH_MU, a, 0.3, duration);
    let mut errors = Vec::new();
    for method in [
        IntegratorKind::LeapfrogKickDriftKick,
        IntegratorKind::YoshidaFourthOrder,
    ] {
        let config = settings(method);
        let (coarse, _) = propagate(&initial, &config, duration, duration / 512.0);
        let (fine, _) = propagate(&initial, &config, duration, duration / 1024.0);
        let coarse_error = relative_error(&coarse, &expected).0;
        let fine_error = relative_error(&fine, &expected).0;
        let ratio = coarse_error / fine_error;
        eprintln!("{method:?}: coarse={coarse_error:.9}m fine={fine_error:.9}m ratio={ratio:.5}");
        if method == IntegratorKind::YoshidaFourthOrder {
            assert!(
                (14.5..17.5).contains(&ratio),
                "fourth-order convergence: {ratio}"
            );
            assert!(fine_error < 0.2);
        } else {
            assert!(
                (3.8..4.2).contains(&ratio),
                "second-order convergence: {ratio}"
            );
        }
        errors.push(fine_error);
    }
    assert!(errors[1] < errors[0] / 100.0);
}

#[test]
fn adaptive_resolves_high_eccentricity_periapsis_and_tolerance_refinement() {
    let a: f64 = 3e7;
    let period = TAU * (a.powi(3) / EARTH_MU).sqrt();
    let start = 0.5 * period;
    let initial = elliptic_pair(EARTH_MU, a, 0.95, start);
    let expected = elliptic_pair(EARTH_MU, a, 0.95, start + period);
    let mut loose = settings(IntegratorKind::DormandPrince54);
    loose.adaptive.relative = 1e-6;
    loose.adaptive.absolute_position_m = 0.1;
    loose.adaptive.absolute_velocity_mps = 1e-4;
    let mut tight = settings(IntegratorKind::DormandPrince54);
    tight.adaptive.relative = 1e-11;
    tight.adaptive.absolute_position_m = 1e-5;
    tight.adaptive.absolute_velocity_mps = 1e-8;
    let (low, low_report) = propagate(&initial, &loose, period, period);
    let (high, high_report) = propagate(&initial, &tight, period, period);
    let low_error = relative_error(&low, &expected).0;
    let high_error = relative_error(&high, &expected).0;
    eprintln!(
        "e=.95: loose {low_error:.6}m {} steps; tight {high_error:.6}m {} steps, h=[{},{}]s",
        low_report.accepted_steps,
        high_report.accepted_steps,
        high_report.minimum_step_seconds,
        high_report.maximum_step_seconds
    );
    assert!(high_error < 0.2);
    assert!(high_error < low_error / 100.0);
    assert!(high_report.accepted_steps > low_report.accepted_steps);
    assert!(high_report.rejected_steps > 0);
    assert!(high_report.maximum_step_seconds / high_report.minimum_step_seconds > 100.0);
}

fn hyperbolic_state(mu: f64, a: f64, e: f64, anomaly: f64) -> (f64, Vector3d, Vector3d) {
    let scale_time = (a.powi(3) / mu).sqrt();
    let denominator = scale_time * (e * anomaly.cosh() - 1.0);
    let b = a * (e * e - 1.0).sqrt();
    (
        scale_time * (e * anomaly.sinh() - anomaly),
        vector(a * (e - anomaly.cosh()), b * anomaly.sinh(), 0.0),
        vector(
            -a * anomaly.sinh() / denominator,
            b * anomaly.cosh() / denominator,
            0.0,
        ),
    )
}

#[test]
fn close_hyperbolic_flyby_matches_independent_anomaly_solution() {
    let mu = 1e10;
    let (start, r0, v0) = hyperbolic_state(mu, 1e5, 1.5, -3.0);
    let (end, r1, v1) = hyperbolic_state(mu, 1e5, 1.5, 3.0);
    let initial = vec![
        MassiveBodyState {
            mass_kg: mu / G,
            position_m: vector(0.0, 0.0, 0.0),
            velocity_mps: vector(0.0, 0.0, 0.0),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: r0,
            velocity_mps: v0,
        },
    ];
    let mut config = settings(IntegratorKind::DormandPrince54);
    config.adaptive.relative = 1e-11;
    config.adaptive.absolute_position_m = 1e-5;
    config.adaptive.absolute_velocity_mps = 1e-8;
    let (actual, report) = propagate(&initial, &config, end - start, end - start);
    let pos_error = magnitude(sub(actual[1].position_m, r1));
    let vel_error = magnitude(sub(actual[1].velocity_mps, v1));
    eprintln!(
        "hyperbola: {pos_error:.9}m {vel_error:.12}m/s {} steps, h=[{},{}]",
        report.accepted_steps, report.minimum_step_seconds, report.maximum_step_seconds
    );
    assert!(pos_error < 0.005);
    assert!(vel_error < 2e-6);
    assert!(report.maximum_step_seconds / report.minimum_step_seconds > 10.0);
    let (coarse, _) = propagate(
        &initial,
        &settings(IntegratorKind::LeapfrogKickDriftKick),
        end - start,
        (end - start) / 10.0,
    );
    assert!(magnitude(sub(coarse[1].position_m, r1)) > 1e4);
}

#[test]
fn adaptive_tolerance_survives_astronomical_translation_and_common_velocity() {
    let initial = elliptic_pair(EARTH_MU, 8e6, 0.2, 0.0);
    let mut translated = initial.clone();
    let origin = vector(1.5e11, -8e10, 7e10);
    let boost = vector(3e4, -4e4, 1e4);
    for body in &mut translated {
        body.position_m = add(body.position_m, origin);
        body.velocity_mps = add(body.velocity_mps, boost);
    }
    let config = settings(IntegratorKind::DormandPrince54);
    let (local, report) = propagate(&initial, &config, 6000.0, 6000.0);
    let (far, far_report) = propagate(&translated, &config, 6000.0, 6000.0);
    let (position_error, velocity_error) = relative_error(&local, &far);
    assert!(
        position_error < 0.02,
        "Galilean invariance within input quantisation: {position_error}"
    );
    assert!(velocity_error < 2e-5);
    assert!((report.accepted_steps as i64 - far_report.accepted_steps as i64).abs() <= 2);
}

fn harmonic(axis_ws: Vector3d) -> ZonalHarmonic {
    ZonalHarmonic {
        body_id: ids(1).remove(0),
        j2: 1.08262668e-3,
        reference_radius_m: 6_378_137.0,
        axis_ws,
    }
}

#[test]
fn arbitrary_axis_j2_is_the_negative_gradient_of_its_potential() {
    let model = harmonic(vector(0.36, -0.48, 0.8));
    for r in [vector(8e6, 2e6, -3e6), vector(-1e7, 1e7, 4e6)] {
        let (acceleration, _) = j2_field(&model, EARTH_MU / G, r).unwrap();
        let h = 10.0;
        let mut gradient = Vector3d::default();
        for index in 0..3 {
            let direction = match index {
                0 => vector(h, 0.0, 0.0),
                1 => vector(0.0, h, 0.0),
                _ => vector(0.0, 0.0, h),
            };
            let positive = j2_field(&model, EARTH_MU / G, add(r, direction)).unwrap().1;
            let negative = j2_field(&model, EARTH_MU / G, sub(r, direction)).unwrap().1;
            let derivative = -(positive - negative) / (2.0 * h);
            match index {
                0 => gradient.x = derivative,
                1 => gradient.y = derivative,
                _ => gradient.z = derivative,
            }
        }
        assert!(magnitude(sub(acceleration, gradient)) / magnitude(acceleration) < 2e-9);
    }
    let pole = vector(0.0, 0.0, 1.0);
    let equatorial = j2_field(&harmonic(pole), EARTH_MU / G, vector(7e6, 0.0, 0.0)).unwrap();
    let polar = j2_field(&harmonic(pole), EARTH_MU / G, vector(0.0, 0.0, 7e6)).unwrap();
    assert!(equatorial.0.x < 0.0 && equatorial.1 < 0.0);
    assert!(polar.0.z > 0.0 && polar.1 > 0.0);
    assert!((polar.0.z / -equatorial.0.x - 2.0).abs() < 1e-14);
    // Proper orthogonal cyclic rotation x->y->z rotates the physical force.
    let rotate = |v: Vector3d| vector(v.z, v.x, v.y);
    let point = vector(8e6, -9e6, 2e6);
    let field = j2_field(&model, EARTH_MU / G, point).unwrap();
    let rotated = j2_field(
        &harmonic(rotate(model.axis_ws)),
        EARTH_MU / G,
        rotate(point),
    )
    .unwrap();
    assert!(magnitude(sub(rotate(field.0), rotated.0)) < 1e-17);
    assert!((field.1 - rotated.1).abs() < 1e-11);
}

#[test]
fn j2_conserves_energy_linear_momentum_and_axial_angular_momentum() {
    let mut initial = elliptic_pair(EARTH_MU, 8e6, 0.1, 0.0);
    let inclination: f64 = 0.9;
    for body in &mut initial {
        body.velocity_mps = vector(
            body.velocity_mps.x,
            body.velocity_mps.y * inclination.cos(),
            body.velocity_mps.y * inclination.sin(),
        );
    }
    let mut config = settings(IntegratorKind::YoshidaFourthOrder);
    config.harmonics.push(harmonic(vector(0.0, 0.0, 1.0)));
    let plan =
        fixed_step_plan_with_source_count(&policy(config.integrator, 10.0), 3600.0, 2, 2, &[])
            .unwrap()
            .with_scientific_settings(&config, &ids(2))
            .unwrap();
    let before = plan.compute_invariants(&initial).unwrap();
    let (actual, _) = propagate(&initial, &config, 3600.0, 10.0);
    let after = plan.compute_invariants(&actual).unwrap();
    let energy_error = (after.total_energy_j / before.total_energy_j - 1.0).abs();
    let momentum_scale = initial[1].mass_kg * magnitude(initial[1].velocity_mps);
    assert!(energy_error < 2e-8, "J2 energy mismatch {energy_error}");
    assert!(
        magnitude(sub(
            before.linear_momentum_kg_mps,
            after.linear_momentum_kg_mps
        )) / momentum_scale
            < 2e-14
    );
    assert!(
        (after.angular_momentum_kg_m2ps.z / before.angular_momentum_kg_m2ps.z - 1.0).abs() < 2e-14
    );
    // The fixed axis can torque the *direction* of orbital angular momentum.
    assert!(
        magnitude(sub(
            after.angular_momentum_kg_m2ps,
            before.angular_momentum_kg_m2ps
        )) / magnitude(before.angular_momentum_kg_m2ps)
            > 1e-4
    );
}

#[test]
fn j2_nodal_precession_agrees_with_first_order_secular_theory() {
    let radius: f64 = 7e6;
    let inclination: f64 = std::f64::consts::FRAC_PI_3;
    let period = TAU * (radius.powi(3) / EARTH_MU).sqrt();
    let speed = (EARTH_MU / radius).sqrt();
    let initial = vec![
        MassiveBodyState {
            mass_kg: EARTH_MU / G,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: vector(radius, 0.0, 0.0),
            velocity_mps: vector(0.0, speed * inclination.cos(), speed * inclination.sin()),
        },
    ];
    let mut config = settings(IntegratorKind::DormandPrince54);
    config.harmonics.push(harmonic(vector(0.0, 0.0, 1.0)));
    let duration = 20.0 * period;
    let (actual, _) = propagate(&initial, &config, duration, period);
    let h = cross(actual[1].position_m, actual[1].velocity_mps);
    let node = cross(vector(0.0, 0.0, 1.0), h);
    let measured = node.y.atan2(node.x);
    let model = &config.harmonics[0];
    let predicted = -1.5
        * model.j2
        * (EARTH_MU / radius.powi(3)).sqrt()
        * (model.reference_radius_m / radius).powi(2)
        * inclination.cos()
        * duration;
    eprintln!("J2 node drift: measured {measured:.9} rad, first-order mean {predicted:.9} rad");
    // Osculating initial elements and short-period terms make this a secular
    // first-order comparison, not an exact solution of the J2 field.
    assert!((measured / predicted - 1.0).abs() < 0.015);
}

#[test]
fn rejected_adaptive_and_gravity_models_are_atomic_and_explicit() {
    let initial = elliptic_pair(EARTH_MU, 8e6, 0.2, 0.0);
    let mut config = settings(IntegratorKind::DormandPrince54);
    config.adaptive.max_attempts_per_interval = 1;
    let mut bodies = initial.clone();
    let failed = advance_authoritative_scientific(
        &policy(config.integrator, 6000.0),
        &config,
        &ids(2),
        &mut bodies,
        6000.0,
        &[],
    );
    assert_eq!(failed.unwrap_err(), ScientificError::WorkLimit);
    assert_eq!(bodies, initial);
    config.adaptive.max_attempts_per_interval = 4096;
    config.adaptive.minimum_step_seconds = 1e4;
    assert_eq!(
        advance_authoritative_scientific(
            &policy(config.integrator, 6000.0),
            &config,
            &ids(2),
            &mut bodies,
            6000.0,
            &[]
        )
        .unwrap_err(),
        ScientificError::StepTooSmall
    );
    assert_eq!(bodies, initial);
    config = settings(IntegratorKind::YoshidaFourthOrder);
    config.harmonics.push(harmonic(vector(0.0, 0.0, 1.0)));
    let mut inside = initial.clone();
    inside[1].position_m = scale(inside[1].position_m, 0.5);
    let inside_before = inside.clone();
    assert_eq!(
        advance_authoritative_scientific(
            &policy(config.integrator, 1.0),
            &config,
            &ids(2),
            &mut inside,
            1.0,
            &[]
        )
        .unwrap_err(),
        ScientificError::InsideHarmonicReferenceRadius
    );
    assert_eq!(inside, inside_before);
    config.integrator = IntegratorKind::DormandPrince54;
    assert_eq!(
        advance_authoritative_scientific(
            &policy(config.integrator, 1.0),
            &config,
            &ids(2),
            &mut inside,
            1.0,
            &[]
        )
        .unwrap_err(),
        ScientificError::InsideHarmonicReferenceRadius
    );
    assert_eq!(inside, inside_before);
    config.harmonics[0].axis_ws = vector(0.0, 0.0, 2.0);
    assert!(!config.valid());
    assert_eq!(
        advance_authoritative_scientific(
            &policy(config.integrator, 1.0),
            &config,
            &ids(2),
            &mut bodies,
            1.0,
            &[]
        )
        .unwrap_err(),
        ScientificError::InvalidSettings
    );
    let mut unknown = settings(IntegratorKind::YoshidaFourthOrder);
    let mut model = harmonic(vector(0.0, 0.0, 1.0));
    model.body_id = BodyId("absent".into());
    unknown.harmonics.push(model);
    assert_eq!(
        advance_authoritative_scientific(
            &policy(unknown.integrator, 1.0),
            &unknown,
            &ids(2),
            &mut bodies,
            1.0,
            &[]
        )
        .unwrap_err(),
        ScientificError::UnknownHarmonicBody
    );
    let (_, report) = advance_authoritative_with_features(
        &policy(IntegratorKind::AdaptiveSymplectic, 1.0),
        &mut bodies,
        1.0,
        &[],
    );
    assert!(report.integration_error.is_some());
    assert_eq!(bodies, initial);
}

#[test]
fn incremental_and_blocking_paths_share_the_selected_method_and_default_is_unchanged() {
    let initial = elliptic_pair(EARTH_MU, 8e6, 0.2, 0.0);
    for method in [
        IntegratorKind::LeapfrogKickDriftKick,
        IntegratorKind::YoshidaFourthOrder,
        IntegratorKind::DormandPrince54,
    ] {
        let config = settings(method);
        let (expected, _) = propagate(&initial, &config, 1900.0, 10.0);
        let p = policy(method, 10.0);
        let plan = fixed_step_plan_with_source_count(&p, 1900.0, 2, 2, &[])
            .unwrap()
            .with_scientific_settings(&config, &ids(2))
            .unwrap();
        let mut staged = initial.clone();
        let mut remaining = plan.total_steps;
        while remaining > 0 {
            let count = remaining.min(13);
            advance_fixed_steps_checked(&plan, &mut staged, count).unwrap();
            remaining -= count;
        }
        assert_eq!(staged, expected, "bounded/chunked {method:?}");
        let mut legacy = initial.clone();
        advance_authoritative_scalar(&p, &mut legacy, 1900.0);
        assert_eq!(legacy, expected, "legacy method dispatch {method:?}");
        let split = plan.for_nonconservative_split();
        assert_eq!(split.report().requested_integrator, method);
        assert_eq!(
            split.report().effective_integrator,
            IntegratorKind::LeapfrogKickDriftKick
        );
        assert!(split.report().nonconservative_split);
    }
}

#[test]
fn matches_independent_dop853_three_source_and_tilted_j2_trajectories() {
    for case in reference_cases::cases() {
        for method in [
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
            let mut bodies = case.samples[0].1.clone();
            let mut previous = 0.0;
            let mut max_position = 0.0_f64;
            let mut max_velocity = 0.0_f64;
            for (time, expected) in case.samples.iter().skip(1) {
                advance_authoritative_scientific(
                    &policy(method, max_step),
                    &config,
                    &case.body_ids,
                    &mut bodies,
                    time - previous,
                    &[],
                )
                .unwrap();
                for (actual, reference) in bodies.iter().zip(expected) {
                    max_position =
                        max_position.max(magnitude(sub(actual.position_m, reference.position_m)));
                    max_velocity = max_velocity
                        .max(magnitude(sub(actual.velocity_mps, reference.velocity_mps)));
                }
                previous = *time;
            }
            eprintln!(
                "DOP853 {} {method:?}: max {max_position:.9}m {max_velocity:.12}m/s over 4h",
                case.name
            );
            assert!(
                max_position < 0.2,
                "independent {} {method:?}: {max_position}m",
                case.name
            );
            assert!(
                max_velocity < 2e-4,
                "independent {} {method:?}: {max_velocity}m/s",
                case.name
            );
        }
    }
}

#[test]
fn powered_flow_reports_order_two_and_stops_exactly_at_fuel_depletion() {
    use solarlab_domain::{AttitudeMode, SpacecraftState};
    use solarlab_physics::spacecraft::{advance_spacecraft_steps_reported, SpacecraftBinding};
    let mut craft = SpacecraftState::reference(BodyId("reference-0".into()), None);
    craft.propellant_mass_kg = 0.75;
    craft.control.throttle = 1.0;
    craft.control.attitude_mode = AttitudeMode::Manual;
    let exhaust = craft.engines[0].max_thrust_n / craft.engines[0].max_mass_flow_kgps;
    let expected_delta_v = exhaust * (craft.mass_kg() / craft.dry_mass_kg).ln();
    for method in [
        IntegratorKind::YoshidaFourthOrder,
        IntegratorKind::DormandPrince54,
    ] {
        let mut bodies = vec![MassiveBodyState {
            mass_kg: 0.0,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        }];
        let mut ships = vec![SpacecraftBinding {
            body_index: 0,
            reference_body_index: None,
            mission_target_index: None,
            rendezvous_target_body_index: None,
            rendezvous_target_spacecraft_index: None,
            rendezvous_target_radius_m: 0.0,
            reference_radius_m: 0.0,
            target_radius_m: 0.0,
            body_radius_m: 1.0,
            epoch_seconds: 0.0,
            epoch_compensation_seconds: 0.0,
            state: craft.clone(),
        }];
        let plan = fixed_step_plan_with_source_count(&policy(method, 0.05), 0.2, 1, 0, &[])
            .unwrap()
            .with_scientific_settings(&settings(method), &ids(1))
            .unwrap();
        let report =
            advance_spacecraft_steps_reported(&plan, &mut bodies, &mut ships, plan.total_steps)
                .unwrap();
        assert_eq!(report.requested_integrator, method);
        assert_eq!(
            report.effective_integrator,
            IntegratorKind::LeapfrogKickDriftKick
        );
        assert!(report.nonconservative_split);
        assert_eq!(ships[0].state.propellant_mass_kg, 0.0);
        assert!((bodies[0].velocity_mps.z - expected_delta_v).abs() < 1e-11);
        assert!((ships[0].epoch_seconds - 0.2).abs() < 1e-15);
        assert!(
            report.accepted_steps > plan.total_steps as u64,
            "fuel boundary subdivided the step"
        );
        let mut solver = plan.report();
        report.apply_to_solver_report(&mut solver);
        assert_eq!(
            solver.effective_integrator,
            IntegratorKind::LeapfrogKickDriftKick
        );
        assert!(solver.nonconservative_split);
    }
}

#[test]
fn radial_assists_align_to_host_relative_direction_in_a_translated_orbit() {
    use solarlab_domain::{AttitudeMode, SpacecraftState};
    use solarlab_physics::spacecraft::{advance_spacecraft_steps_reported, SpacecraftBinding};
    let origin = vector(1e11, 2e11, -3e10);
    for (mode, sign) in [
        (AttitudeMode::RadialOut, 1.0),
        (AttitudeMode::RadialIn, -1.0),
    ] {
        let mut craft = SpacecraftState::reference(
            BodyId("reference-1".into()),
            Some(BodyId("reference-0".into())),
        );
        craft.control.attitude_mode = mode;
        let fuel = craft.propellant_mass_kg;
        let mut ships = vec![SpacecraftBinding {
            body_index: 1,
            reference_body_index: Some(0),
            mission_target_index: None,
            rendezvous_target_body_index: None,
            rendezvous_target_spacecraft_index: None,
            rendezvous_target_radius_m: 0.0,
            reference_radius_m: 6.4e6,
            target_radius_m: 0.0,
            body_radius_m: 1.0,
            epoch_seconds: 0.0,
            epoch_compensation_seconds: 0.0,
            state: craft,
        }];
        let mut bodies = vec![
            MassiveBodyState {
                mass_kg: EARTH_MU / G,
                position_m: origin,
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 0.0,
                position_m: add(origin, vector(8e6, 0.0, 0.0)),
                velocity_mps: vector(0.0, (EARTH_MU / 8e6).sqrt(), 0.0),
            },
        ];
        let plan = fixed_step_plan_with_source_count(
            &policy(IntegratorKind::YoshidaFourthOrder, 0.05),
            30.0,
            2,
            1,
            &[],
        )
        .unwrap();
        let mut remaining = plan.total_steps;
        while remaining > 0 {
            let count = remaining.min(64);
            let report =
                advance_spacecraft_steps_reported(&plan, &mut bodies, &mut ships, count).unwrap();
            assert!(report.nonconservative_split);
            remaining -= count;
        }
        let radial = sub(bodies[1].position_m, bodies[0].position_m);
        let nose = ships[0].state.attitude.rotate(vector(0.0, 0.0, 1.0));
        let alignment =
            solarlab_domain::spacecraft::dot(nose, scale(radial, sign / magnitude(radial)));
        assert!(alignment > 0.999, "radial assist alignment {alignment}");
        assert_eq!(ships[0].state.propellant_mass_kg, fuel);
    }
}

#[test]
fn checked_spacecraft_flow_preserves_adaptive_and_model_rejection_reasons() {
    use solarlab_domain::{AttitudeMode, SpacecraftState};
    use solarlab_physics::spacecraft::{advance_spacecraft_steps_checked, SpacecraftBinding};
    let mut craft = SpacecraftState::reference(
        BodyId("reference-1".into()),
        Some(BodyId("reference-0".into())),
    );
    craft.control.attitude_mode = AttitudeMode::Manual;
    let original = craft.clone();
    let mut ships = vec![SpacecraftBinding {
        body_index: 1,
        reference_body_index: Some(0),
        mission_target_index: None,
        rendezvous_target_body_index: None,
        rendezvous_target_spacecraft_index: None,
        rendezvous_target_radius_m: 0.0,
        reference_radius_m: 6.4e6,
        target_radius_m: 0.0,
        body_radius_m: 1.0,
        epoch_seconds: 0.0,
        epoch_compensation_seconds: 0.0,
        state: craft,
    }];
    let initial = elliptic_pair(EARTH_MU, 8e6, 0.2, 0.0);
    let mut bodies = initial.clone();
    let mut config = settings(IntegratorKind::DormandPrince54);
    config.adaptive.max_attempts_per_interval = 1;
    let plan =
        fixed_step_plan_with_source_count(&policy(config.integrator, 6000.0), 6000.0, 2, 2, &[])
            .unwrap()
            .with_scientific_settings(&config, &ids(2))
            .unwrap();
    assert_eq!(
        advance_spacecraft_steps_checked(&plan, &mut bodies, &mut ships, 1),
        Err(ScientificError::WorkLimit)
    );
    assert_eq!(bodies, initial);
    assert_eq!(ships[0].state, original);
    assert_eq!(ships[0].epoch_seconds, 0.0);
    config.harmonics.push(harmonic(vector(0.0, 0.0, 1.0)));
    config.harmonics[0].reference_radius_m = 1e7;
    let plan = plan.with_scientific_settings(&config, &ids(2)).unwrap();
    assert_eq!(
        advance_spacecraft_steps_checked(&plan, &mut bodies, &mut ships, 1),
        Err(ScientificError::InsideHarmonicReferenceRadius)
    );
    assert_eq!(bodies, initial);
    assert_eq!(ships[0].state, original);
}
