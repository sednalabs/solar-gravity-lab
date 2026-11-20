//! Navigation checks use independent event-root fixtures and state invariants.
use solarlab_domain::Vector3d;
use solarlab_runtime::{OrbitGuidance, OrbitRegime, OrbitalElements};
use std::f64::consts::{PI, TAU};

const MU: f64 = 3.986_004_418e14;
fn v(x: f64, y: f64, z: f64) -> Vector3d {
    Vector3d { x, y, z }
}
fn norm(p: Vector3d) -> f64 {
    p.x.hypot(p.y).hypot(p.z)
}
fn dot(a: Vector3d, b: Vector3d) -> f64 {
    a.x * b.x + a.y * b.y + a.z * b.z
}
fn plus(a: Vector3d, b: Vector3d) -> Vector3d {
    v(a.x + b.x, a.y + b.y, a.z + b.z)
}
fn minus(a: Vector3d, b: Vector3d) -> Vector3d {
    v(a.x - b.x, a.y - b.y, a.z - b.z)
}
fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    v(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )
}
fn rotate(p: Vector3d) -> Vector3d {
    // Orthogonal signed permutation plus a 3/5,4/5 rotation; reverses no axes.
    v(0.6 * p.z - 0.8 * p.x, p.y, 0.8 * p.z + 0.6 * p.x)
}
fn state(e: f64, anomaly: f64) -> (Vector3d, Vector3d) {
    let periapsis = 7_000_000.0;
    let semilatus = periapsis * (1.0 + e);
    // Exact apsis cases use an exactly orthogonal state. sin(pi) is a tiny
    // positive number in floating point, so ±pi trig is just before/after the
    // event and cannot truthfully require a zero countdown.
    let (sine, cosine) = if anomaly.abs() == PI {
        (0.0, -1.0)
    } else {
        anomaly.sin_cos()
    };
    let radius = semilatus / (1.0 + e * cosine);
    let velocity_scale = (MU / semilatus).sqrt();
    (
        v(radius * cosine, radius * sine, 0.0),
        v(-velocity_scale * sine, velocity_scale * (e + cosine), 0.0),
    )
}
fn close(actual: f64, expected: f64, tolerance: f64) {
    assert!((actual-expected).abs()<=tolerance,"actual {actual:.15e}, expected {expected:.15e}, difference {:.6e}, tolerance {tolerance:.6e}",(actual-expected).abs());
}
fn json_vector(value: &serde_json::Value) -> Vector3d {
    v(
        value[0].as_f64().unwrap(),
        value[1].as_f64().unwrap(),
        value[2].as_f64().unwrap(),
    )
}

#[test]
fn native_apsides_match_independent_cartesian_event_roots() {
    let fixture: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/navigation-reference.json")).unwrap();
    let cases = fixture["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 11);
    assert!(
        fixture["refinement_max_time_difference_seconds"]
            .as_f64()
            .unwrap()
            < 1e-6
    );
    assert!(
        fixture["refinement_max_position_difference_m"]
            .as_f64()
            .unwrap()
            < 0.002
    );
    let mut maximum_time_error = 0.0_f64;
    let mut maximum_position_error = 0.0_f64;
    for case in cases {
        let position = json_vector(&case["position_m"]);
        let velocity = json_vector(&case["velocity_mps"]);
        let mu = case["mu"].as_f64().unwrap();
        for rotated in [false, true] {
            let (position, velocity) = if rotated {
                (rotate(position), rotate(velocity))
            } else {
                (position, velocity)
            };
            let guidance = OrbitGuidance::from_relative(position, velocity, mu).unwrap();
            for (key, time, location) in [
                (
                    "periapsis",
                    guidance.time_to_periapsis_seconds,
                    guidance.periapsis_position_m,
                ),
                (
                    "apoapsis",
                    guidance.time_to_apoapsis_seconds,
                    guidance.apoapsis_position_m,
                ),
            ] {
                if !case["checked_events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|event| event.as_str() == Some(key))
                {
                    continue;
                }
                let reference = &case[key];
                if reference.is_null() {
                    assert_eq!(time, None, "{}: {key}", case["name"]);
                    continue;
                }
                let expected_time = reference["seconds"].as_f64().unwrap();
                let expected_position = json_vector(&reference["position_m"]);
                let expected_position = if rotated {
                    rotate(expected_position)
                } else {
                    expected_position
                };
                let actual_time = time.unwrap();
                let position_error = norm(minus(location.unwrap(), expected_position));
                close(
                    actual_time,
                    expected_time,
                    2e-6_f64.max(expected_time * 2e-10),
                );
                assert!(
                    position_error < 0.02,
                    "{} {key}: {position_error} m",
                    case["name"]
                );
                maximum_time_error = maximum_time_error.max((actual_time - expected_time).abs());
                maximum_position_error = maximum_position_error.max(position_error);
            }
        }
    }
    println!("Independent Cartesian event roots: max time error {maximum_time_error:.9e} s; apsis position error {maximum_position_error:.9e} m");
}

#[test]
fn circularisation_cancels_radial_velocity_and_preserves_plane_and_sense() {
    for e in [0.0, 0.3, 0.95, 1.0, 1.5] {
        for anomaly in [-1.2, 0.0, 1.2] {
            let (position, velocity) = state(e, anomaly);
            let position = rotate(position);
            let velocity = rotate(velocity);
            let guidance = OrbitGuidance::from_relative(position, velocity, MU).unwrap();
            let corrected = plus(velocity, guidance.circularisation_delta_velocity_mps);
            let radius = norm(position);
            close(dot(position, corrected) / radius, 0.0, 1e-10);
            close(norm(corrected).powi(2), MU / radius, 1e-7);
            close(
                norm(guidance.circularisation_delta_velocity_mps),
                guidance.circularisation_delta_v_mps,
                1e-12,
            );
            let initial_normal = cross(position, velocity);
            let corrected_normal = cross(position, corrected);
            close(
                dot(initial_normal, corrected_normal)
                    / (norm(initial_normal) * norm(corrected_normal)),
                1.0,
                5e-16,
            );
            let result = OrbitalElements::from_relative(position, corrected, MU).unwrap();
            assert!(result.eccentricity < 2e-15);
            close(result.periapsis_radius_m, radius, 1e-7);
            close(result.apoapsis_radius_m.unwrap(), radius, 1e-7);
        }
    }
    let position = v(7e6, 0.0, 0.0);
    let velocity = v(3000.0, 7000.0, 0.0);
    let guidance = OrbitGuidance::from_relative(position, velocity, MU).unwrap();
    let scalar_speed_difference = (norm(velocity) - (MU / norm(position)).sqrt()).abs();
    assert!(scalar_speed_difference < 100.0);
    assert!(guidance.circularisation_delta_v_mps > 3000.0);
}

#[test]
fn circular_parabolic_radial_and_nonfinite_states_are_explicit() {
    for e in [0.0, 5e-7] {
        let (p, velocity) = state(e, 0.9);
        let guidance = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
        assert_eq!(guidance.regime, OrbitRegime::NearCircular);
        assert!(
            guidance.time_to_periapsis_seconds.is_none()
                && guidance.time_to_apoapsis_seconds.is_none()
        );
        assert!(guidance.periapsis_position_m.is_none() && guidance.apoapsis_position_m.is_none());
    }
    for e in [1.0 - 5e-9, 1.0, 1.0 + 5e-9] {
        let (p, velocity) = state(e, -0.9);
        let guidance = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
        assert_eq!(guidance.regime, OrbitRegime::NearParabolic);
        assert!(
            guidance.time_to_periapsis_seconds.is_none()
                && guidance.time_to_apoapsis_seconds.is_none()
        );
        assert!(guidance.periapsis_position_m.is_some());
        assert!(guidance.apoapsis_position_m.is_none());
    }
    for (e, regime) in [
        (2e-6, OrbitRegime::Elliptic),
        (1.0 - 2e-8, OrbitRegime::Elliptic),
        (1.0 + 2e-8, OrbitRegime::Hyperbolic),
    ] {
        let (p, velocity) = state(e, -0.9);
        let guidance = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
        assert_eq!(guidance.regime, regime);
        assert!(guidance
            .time_to_periapsis_seconds
            .is_some_and(|t| t.is_finite() && t > 0.0));
    }
    for velocity in [
        v(1000.0, 0.0, 0.0),
        v(1000.0, 1e-8, 0.0),
        v(f64::NAN, 7000.0, 0.0),
        v(0.0, f64::INFINITY, 0.0),
    ] {
        assert!(OrbitGuidance::from_relative(v(7e6, 0.0, 0.0), velocity, MU).is_none());
    }
    for mu in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        assert!(OrbitGuidance::from_relative(v(7e6, 0.0, 0.0), v(0.0, 7000.0, 0.0), mu).is_none());
    }
    assert!(OrbitGuidance::from_relative(Vector3d::default(), v(0.0, 7000.0, 0.0), MU).is_none());
}

#[test]
fn event_countdowns_distinguish_current_passage_next_lap_and_escape() {
    for e in [0.01, 0.7, 0.95] {
        let a = 7e6 / (1.0 - e);
        let period = TAU * (a * a * a / MU).sqrt();
        for rotated in [false, true] {
            for (anomaly, peri, apo) in [
                (0.0, 0.0, period / 2.0),
                (PI, period / 2.0, 0.0),
                (-PI, period / 2.0, 0.0),
            ] {
                let (p, velocity) = state(e, anomaly);
                let (p, velocity) = if rotated {
                    (rotate(p), rotate(velocity))
                } else {
                    (p, velocity)
                };
                let guidance = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
                close(
                    guidance.time_to_periapsis_seconds.unwrap(),
                    peri,
                    period * 1e-12,
                );
                close(
                    guidance.time_to_apoapsis_seconds.unwrap(),
                    apo,
                    period * 1e-12,
                );
            }
        }
        let (p, velocity) = state(e, 1e-5);
        let just_after = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
        assert!(just_after.time_to_periapsis_seconds.unwrap() > 0.99 * period);
        let (p, velocity) = state(e, -1e-5);
        let just_before = OrbitGuidance::from_relative(p, velocity, MU).unwrap();
        assert!(just_before.time_to_periapsis_seconds.unwrap() < 0.01 * period);
    }
    for anomaly in [-1.0, 0.0, 1.0] {
        let (p, velocity) = state(1.5, anomaly);
        let guidance = OrbitGuidance::from_relative(rotate(p), rotate(velocity), MU).unwrap();
        assert_eq!(guidance.regime, OrbitRegime::Hyperbolic);
        assert!(
            guidance.time_to_apoapsis_seconds.is_none() && guidance.apoapsis_position_m.is_none()
        );
        if anomaly > 0.0 {
            assert!(guidance.time_to_periapsis_seconds.is_none());
        } else if anomaly == 0.0 {
            close(guidance.time_to_periapsis_seconds.unwrap(), 0.0, 1e-10);
        } else {
            assert!(guidance.time_to_periapsis_seconds.unwrap() > 0.0);
        }
    }
}
