//! Independent construction checks, not a second production orbital solver.
use super::*;
use solarlab_domain::spacecraft::sub;
use std::f64::consts::{FRAC_PI_2, PI, TAU};

const REFERENCE: &[u8] = include_bytes!("../tests/fixtures/authoring-conics-cspice.json");
const SCHOOL: &[u8] = include_bytes!("../../data/authoring/conic-flight-school.scenario.json");

fn placement() -> Value {
    json!({
        "kind": "conic_orbit", "host_body_id": "reference.primary",
        "periapsis_altitude_m": 400_000.0, "eccentricity": 0.4,
        "true_anomaly_rad": -0.8, "inclination_rad": 0.7,
        "ascending_node_longitude_rad": 1.2, "argument_of_periapsis_rad": 0.4,
    })
}

fn scenario(placement: Value) -> Value {
    let mut value: Value = serde_json::from_slice(WORKSHOP).unwrap();
    value["base"] = json!("empty");
    value["bodies"] = json!([{
        "body_id": "reference.primary", "body_class": BodyClass::Planet,
        "mass_kg": 5.972e24, "radius_m": 6_371_000.0,
        "position_m": {"x":0,"y":0,"z":0},
        "velocity_mps": {"x":0,"y":0,"z":0},
    }]);
    value["spacecraft"] = json!([{
        "body_id":"reference.craft", "definition_id":"iris-electric", "placement":placement,
    }]);
    value["focus_body_id"] = json!("reference.craft");
    value
}

fn construct(value: &Value) -> Result<WorldRuntime, AuthoringError> {
    WorldRuntime::import_authored_scenario(
        &serde_json::to_vec(value).unwrap(),
        HardwareProfile::offline_reference(),
        1_000,
    )
}

fn vector(value: &Value) -> Vector3d {
    Vector3d {
        x: value[0].as_f64().unwrap(),
        y: value[1].as_f64().unwrap(),
        z: value[2].as_f64().unwrap(),
    }
}

#[test]
fn conic_placements_match_twelve_independent_cspice_states() {
    let reference: Value = serde_json::from_slice(REFERENCE).unwrap();
    assert_eq!(reference["cspice_version"], "CSPICE_N0067");
    assert_eq!(reference["cases"].as_array().unwrap().len(), 12);
    let mut worst_position = 0.0_f64;
    let mut worst_velocity = 0.0_f64;
    for case in reference["cases"].as_array().unwrap() {
        let world = construct(&scenario(case["placement"].clone())).unwrap();
        let body = world.body_by_id("reference.craft").unwrap();
        let position_error = magnitude(sub(body.position_m, vector(&case["position_m"])));
        let velocity_error = magnitude(sub(body.velocity_mps, vector(&case["velocity_mps"])));
        worst_position = worst_position.max(position_error);
        worst_velocity = worst_velocity.max(velocity_error);
        assert!(position_error < 2e-5, "{}: {position_error} m", case["id"]);
        assert!(
            velocity_error < 2e-9,
            "{}: {velocity_error} m/s",
            case["id"]
        );
        let ship = world.spacecraft_by_id("reference.craft").unwrap();
        let forward = ship.attitude.rotate(Vector3d {
            x: 0.0,
            y: 0.0,
            z: 1.0,
        });
        assert!(dot(forward, unit(body.velocity_mps).unwrap()) > 1.0 - 1e-12);
        assert_eq!(ship.control.throttle, 0.0);
        assert_eq!(ship.control.attitude_mode, AttitudeMode::Manual);
    }
    eprintln!("12 CSPICE conics: worst position={worst_position} m; velocity={worst_velocity} m/s");
}

#[test]
fn conic_states_preserve_requested_energy_plane_and_apsides() {
    let mu = 6.67430e-11 * 5.972e24;
    let periapsis = 6_771_000.0;
    for eccentricity in [0.0_f64, 0.4, 0.999, 1.0, 1.4, 3.0] {
        for inclination in [0.0_f64, FRAC_PI_2, 2.6, PI] {
            for anomaly in [-0.8_f64, 0.0, 0.8] {
                let mut p = placement();
                p["eccentricity"] = json!(eccentricity);
                p["inclination_rad"] = json!(inclination);
                p["true_anomaly_rad"] = json!(anomaly);
                let world = construct(&scenario(p)).unwrap();
                let t = world.flight_telemetry("reference.craft").unwrap();
                let orbit = t.orbit.unwrap();
                let energy = 0.5 * dot(t.relative_velocity_mps, t.relative_velocity_mps)
                    - mu / magnitude(t.relative_position_m);
                let expected_energy = mu * (eccentricity - 1.0) / (2.0 * periapsis);
                assert!((energy - expected_energy).abs() < 1e-7);
                assert!((orbit.eccentricity - eccentricity).abs() < 5e-14);
                assert!((orbit.periapsis_radius_m - periapsis).abs() < 1e-7);
                let h = unit(cross(t.relative_position_m, t.relative_velocity_mps)).unwrap();
                // A node lies along (cos(node), sin(node), 0), with +Z crossed
                // into the orbit normal's XY projection. This checks the plane
                // independently of the production perifocal rotation matrix.
                let expected_h = Vector3d {
                    x: 1.2_f64.sin() * inclination.sin(),
                    y: -1.2_f64.cos() * inclination.sin(),
                    z: inclination.cos(),
                };
                assert!(magnitude(sub(h, expected_h)) < 1e-14);
                if eccentricity > 0.0 && anomaly != 0.0 {
                    assert_eq!(
                        t.radial_speed_mps.is_sign_negative(),
                        anomaly.is_sign_negative()
                    );
                }
                if eccentricity < 1.0 {
                    let apoapsis = periapsis * (1.0 + eccentricity) / (1.0 - eccentricity);
                    assert!((orbit.apoapsis_radius_m.unwrap() - apoapsis).abs() < apoapsis * 2e-10);
                } else if eccentricity > 1.0 {
                    assert!(orbit.apoapsis_radius_m.is_none());
                    assert!(orbit.period_seconds.is_none());
                }
            }
        }
    }
}

#[test]
fn conic_placement_translates_and_boosts_with_its_host() {
    let base = construct(&scenario(placement())).unwrap();
    let original = base.flight_telemetry("reference.craft").unwrap();
    let mut shifted = scenario(placement());
    shifted["bodies"][0]["position_m"] = json!({"x":1e12,"y":-2e11,"z":3e10});
    shifted["bodies"][0]["velocity_mps"] = json!({"x":30000,"y":-15000,"z":4000});
    let translated = construct(&shifted).unwrap();
    let t = translated.flight_telemetry("reference.craft").unwrap();
    assert!(magnitude(sub(t.relative_position_m, original.relative_position_m)) < 0.0002);
    assert!(magnitude(sub(t.relative_velocity_mps, original.relative_velocity_mps)) < 1e-9);
    assert_eq!(
        base.spacecraft_by_id("reference.craft").unwrap().attitude,
        translated
            .spacecraft_by_id("reference.craft")
            .unwrap()
            .attitude
    );
}

#[test]
fn conic_hosts_and_explicit_attitudes_use_the_shared_authoring_contract() {
    let mut value = scenario(placement());
    value["spacecraft"][0]["attitude"] = json!(Quaterniond::IDENTITY);
    let world = construct(&value).unwrap();
    let ship = world.spacecraft_by_id("reference.craft").unwrap();
    assert_eq!(ship.attitude, Quaterniond::IDENTITY);
    assert_eq!(
        ship.reference_body_id.as_ref().unwrap().0,
        "reference.primary"
    );
    for missing_or_self in ["reference.missing", "reference.craft"] {
        let mut value = scenario(placement());
        value["spacecraft"][0]["placement"]["host_body_id"] = json!(missing_or_self);
        assert!(matches!(
            construct(&value),
            Err(AuthoringError::UnresolvedReference(_))
        ));
    }
    let mut cyclic = scenario(placement());
    let mut second = cyclic["spacecraft"][0].clone();
    second["body_id"] = json!("reference.second");
    second["placement"]["host_body_id"] = json!("reference.craft");
    cyclic["spacecraft"][0]["placement"]["host_body_id"] = json!("reference.second");
    cyclic["spacecraft"].as_array_mut().unwrap().push(second);
    assert!(matches!(
        construct(&cyclic),
        Err(AuthoringError::UnresolvedReference(_))
    ));
}

#[test]
fn conic_domains_reject_singular_branches_and_out_of_range_angles() {
    let asymptote = (-1.0_f64 / 1.4).acos();
    for (field, value) in [
        ("periapsis_altitude_m", 0.0),
        ("periapsis_altitude_m", -1.0),
        ("periapsis_altitude_m", f64::EPSILON),
        ("periapsis_altitude_m", 1e20),
        ("eccentricity", -0.1),
        ("eccentricity", 1e308),
        ("inclination_rad", -0.1),
        ("inclination_rad", PI + 0.01),
        ("ascending_node_longitude_rad", -0.1),
        ("ascending_node_longitude_rad", TAU + 0.01),
        ("argument_of_periapsis_rad", -0.1),
        ("argument_of_periapsis_rad", TAU + 0.01),
        ("true_anomaly_rad", -PI - 0.01),
        ("true_anomaly_rad", PI + 0.01),
    ] {
        let mut p = placement();
        p[field] = json!(value);
        assert!(construct(&scenario(p)).is_err(), "accepted {field}={value}");
    }
    for (eccentricity, anomaly) in [
        (1.0, PI),
        (1.0, -PI),
        (1.4, asymptote),
        (1.4, -asymptote),
        (1.4, asymptote + 0.001),
        (1.4, PI),
    ] {
        let mut p = placement();
        p["eccentricity"] = json!(eccentricity);
        p["true_anomaly_rad"] = json!(anomaly);
        assert!(
            construct(&scenario(p)).is_err(),
            "accepted e={eccentricity}, f={anomaly}"
        );
    }
    let mut massless = scenario(placement());
    massless["bodies"][0]["source_mass_kg"] = json!(0.0);
    assert!(construct(&massless).is_err());
    let mut unknown = scenario(placement());
    unknown["spacecraft"][0]["placement"]["degrees"] = json!(true);
    assert!(construct(&unknown).is_err());
    let mut missing = scenario(placement());
    missing["spacecraft"][0]["placement"]
        .as_object_mut()
        .unwrap()
        .remove("inclination_rad");
    assert!(construct(&missing).is_err());
}

#[test]
fn conic_branch_limits_include_closed_apoapsis_and_real_open_states() {
    for (eccentricity, anomaly) in [
        (0.5, PI),
        (0.5, -PI),
        (1.0 - 1e-10, PI),
        (1.0, 3.0),
        (1.4, (-1.0_f64 / 1.4).acos() - 0.01),
    ] {
        let mut p = placement();
        p["eccentricity"] = json!(eccentricity);
        p["true_anomaly_rad"] = json!(anomaly);
        let world = construct(&scenario(p)).unwrap();
        let body = world.body_by_id("reference.craft").unwrap();
        assert!(finite(body.position_m) && finite(body.velocity_mps));
    }
    // Angles at a complete turn remain unambiguous and deterministic.
    let mut p = placement();
    p["ascending_node_longitude_rad"] = json!(TAU);
    p["argument_of_periapsis_rad"] = json!(TAU);
    assert!(construct(&scenario(p)).is_ok());
}

#[test]
fn invalid_conic_after_a_valid_addition_rolls_back_all_state() {
    let mut world = load(WORKSHOP);
    let before = world.export_saved_session().unwrap();
    let ordinal = world.next_command_ordinal();
    let mut document: Value = serde_json::from_slice(IRIS).unwrap();
    let mut first = placement();
    first["host_body_id"] = json!("earth");
    document["spacecraft"][0]["placement"] = first;
    let mut invalid = document["spacecraft"][0].clone();
    invalid["body_id"] = json!("custom.invalid-conic");
    invalid["placement"]["eccentricity"] = json!(1.4);
    invalid["placement"]["true_anomaly_rad"] = json!(PI);
    document["spacecraft"].as_array_mut().unwrap().push(invalid);
    assert!(world
        .add_authored_spacecraft(&serde_json::to_vec(&document).unwrap(), 1_500)
        .is_err());
    assert_eq!(world.export_saved_session().unwrap(), before);
    assert_eq!(world.next_command_ordinal(), ordinal);
    assert!(world.body_by_id("custom.iris").is_none());
}

#[test]
fn external_conic_school_keeps_its_native_flight_and_exact_save_future() {
    let mut world = load(SCHOOL);
    assert_eq!(world.snapshot().spacecraft.len(), 3);
    assert_eq!(
        world.snapshot().observer.focus_body_id.unwrap().0,
        "school.lunar-arrival"
    );
    let lunar = world.flight_telemetry("school.lunar-arrival").unwrap();
    assert_eq!(lunar.reference_body_id, "moon");
    assert!(lunar.radial_speed_mps < -1_000.0);
    assert!(lunar.orbit.unwrap().apoapsis_radius_m.is_none());
    let earth = world.flight_telemetry("school.earth-elliptic").unwrap();
    assert!((earth.orbit.unwrap().eccentricity - 0.35).abs() < 1e-10);
    let retrograde = world.flight_telemetry("school.retrograde").unwrap();
    assert!(
        cross(
            retrograde.relative_position_m,
            retrograde.relative_velocity_mps
        )
        .z < 0.0
    );
    world
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId("school.lunar-arrival".into()),
                control: FlightControl {
                    throttle: 0.4,
                    attitude_mode: AttitudeMode::Retrograde,
                    ..FlightControl::default()
                },
            },
            1_100,
        )
        .unwrap();
    advance(&mut world, 10.0);
    let save = world.export_saved_session().unwrap();
    let mut reopened =
        WorldRuntime::import_saved_session(&save, HardwareProfile::offline_reference()).unwrap();
    advance(&mut world, 60.0);
    advance(&mut reopened, 60.0);
    assert_eq!(world.snapshot().bodies, reopened.snapshot().bodies);
    assert_eq!(world.snapshot().spacecraft, reopened.snapshot().spacecraft);
    assert!(
        world
            .spacecraft_by_id("school.lunar-arrival")
            .unwrap()
            .delivered_delta_v_mps
            > 50.0
    );
}
