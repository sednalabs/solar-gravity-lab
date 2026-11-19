use serde_json::{json, Value};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::science::ScientificError;
use solarlab_runtime::{AdvanceState, RuntimeError, WorldCommand, WorldRuntime};

fn contact_document() -> Value {
    let mut instances = Vec::new();
    for i in 0..3 {
        let angle = i as f64 * std::f64::consts::TAU / 3.0;
        instances.push(json!({
            "body_id": format!("chaser-{i}"), "definition_id": "sphere",
            "placement": { "kind": "inertial",
                "position_m": {"x": 2.0*angle.cos(), "y": 2.0*angle.sin(), "z": 0.0},
                "velocity_mps": {"x": -1e6*angle.cos(), "y": -1e6*angle.sin(), "z": 0.0} },
            "docking_target_body_id": format!("target-{i}")
        }));
        instances.push(json!({
            "body_id": format!("target-{i}"), "definition_id": "sphere",
            "placement": { "kind": "inertial",
                "position_m": {"x": 1000.0, "y": 1000.0*(i+1) as f64, "z": 0.0},
                "velocity_mps": {"x": 0.0, "y": 0.0, "z": 0.0} }
        }));
    }
    json!({
        "format": "solarlab.scenario", "version": 1,
        "id": "review.contact-budget", "title": "Bounded contact rejection", "base": "empty",
        "spacecraft_definitions": [{
            "id": "sphere", "display_name": "Contact sphere", "visual_asset": "builtin:asteria",
            "structure": {"dry_mass_kg": 1000.0, "radius_m": 8.0,
                "inertia_kg_m2": {"x": 400.0, "y": 400.0, "z": 400.0},
                "max_control_torque_nm": {"x": 0.0, "y": 0.0, "z": 0.0}},
            "propellant": {"capacity_kg": 0.0}, "engines": [],
            "contact": {
                "hull": [{"position_body_m": {"x": 0.0, "y": 0.0, "z": 0.0},
                    "shape": {"kind": "sphere", "radius_m": 1.0}}],
                "port": {"position_body_m": {"x": 0.0, "y": 0.0, "z": 0.0},
                    "inner_radius_m": 0.1, "outer_radius_m": 0.11, "depth_m": 0.005}
            }
        }], "spacecraft": instances
    })
}

fn load(document: Value) -> WorldRuntime {
    WorldRuntime::import_authored_scenario(
        &serde_json::to_vec(&document).unwrap(),
        HardwareProfile::offline_reference(),
        0,
    )
    .unwrap()
}

#[test]
fn contact_budget_rejection_preserves_committed_world_and_save() {
    let mut document = contact_document();
    // Two stationary touching hulls keep this conservative island in its
    // contact-resolution regime while a fast nearby member sets its kinetic
    // motion bound. Fine port features make the finite solve budget insufficient.
    // This exercises actual rejection, not a case solved by free-flight jumps.
    for (instance, x, y, vx) in [
        (0, -1.0, 0.0, 0.0),
        (2, 1.0, 0.0, 0.0),
        (4, 0.0, 100.0, 1e6),
    ] {
        document["spacecraft"][instance]["placement"]["position_m"] =
            json!({"x": x, "y": y, "z": 0.0});
        document["spacecraft"][instance]["placement"]["velocity_mps"] =
            json!({"x": vx, "y": 0.0, "z": 0.0});
    }
    assert_rejection_preserves_world(load(document), ScientificError::ContactResolutionLimit);
}

#[test]
fn contact_gravity_keeps_the_specific_exterior_model_rejection() {
    let mut document = contact_document();
    document["bodies"] = json!([{
        "body_id": "primary", "body_class": "Planet", "mass_kg": 1e15,
        "radius_m": 10.0,
        "position_m": {"x": 0.0, "y": 0.0, "z": 0.0},
        "velocity_mps": {"x": 0.0, "y": 0.0, "z": 0.0}
    }]);
    document["scientific_settings"] = json!({"harmonics": [{
        "body_id": "primary", "j2": 0.001, "reference_radius_m": 10.0,
        "axis_ws": {"x": 0.0, "y": 0.0, "z": 1.0}
    }]});
    document["spacecraft"] = json!([
        {"body_id": "chaser", "definition_id": "sphere",
         "docking_target_body_id": "target",
         "placement": {"kind": "inertial",
            "position_m": {"x": 10.001, "y": 0.0, "z": 0.0},
            "velocity_mps": {"x": -100.0, "y": 0.0, "z": 0.0}}},
        {"body_id": "target", "definition_id": "sphere",
         "placement": {"kind": "inertial",
            "position_m": {"x": 1000.0, "y": 0.0, "z": 0.0},
            "velocity_mps": {"x": 0.0, "y": 0.0, "z": 0.0}}}
    ]);
    assert_rejection_preserves_world(
        load(document),
        ScientificError::InsideHarmonicReferenceRadius,
    );
}

fn assert_rejection_preserves_world(mut world: WorldRuntime, expected: ScientificError) {
    let original = world.export_saved_session().unwrap();
    let snapshot = world.snapshot();
    let ordinal = world.next_command_ordinal();
    let error = world
        .apply_command(
            WorldCommand::AdvanceEpoch {
                delta_seconds: 0.01,
            },
            1,
        )
        .err()
        .expect("contact operation must explicitly reject its unsupported solve");
    assert_eq!(error, RuntimeError::ScientificFailure(expected.clone()));
    assert_eq!(world.snapshot(), snapshot);
    assert_eq!(world.export_saved_session().unwrap(), original);
    let generation = world
        .begin_advance([ordinal, ordinal + 1], 0, ordinal, 0.01, 2)
        .unwrap()
        .generation;
    let error = world
        .continue_advance(generation, 0)
        .err()
        .expect("incremental contact solve must preserve the same rejection");
    assert_eq!(error, RuntimeError::ScientificFailure(expected));
    assert_eq!(
        world.query_advance().unwrap().state,
        AdvanceState::Cancelled
    );
    assert_eq!(world.snapshot(), snapshot);
    assert_eq!(world.export_saved_session().unwrap(), original);
}
