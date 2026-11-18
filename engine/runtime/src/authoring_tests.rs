use super::*;
use serde_json::{json, Value};
const WORKSHOP: &[u8] = include_bytes!("../../data/authoring/orbital-workshop.scenario.json");
const DEPOT: &[u8] = include_bytes!("../../data/authoring/lunar-depot.scenario.json");
const IRIS: &[u8] = include_bytes!("../../data/authoring/iris.spacecraft.json");
fn load(bytes: &[u8]) -> WorldRuntime {
    WorldRuntime::import_authored_scenario(bytes, HardwareProfile::offline_reference(), 1_000)
        .unwrap()
}
fn advance(world: &mut WorldRuntime, duration: f64) {
    let ordinal = world.next_command_ordinal();
    let previous = world.query_advance().map_or(0, |a| a.generation);
    let generation = world
        .begin_advance([ordinal, ordinal + 500], previous, ordinal, duration, 2_000)
        .unwrap()
        .generation;
    let mut complete = 0;
    loop {
        let (operation, _) = world.continue_advance(generation, complete).unwrap();
        complete = operation.completed_steps;
        if operation.state == AdvanceState::Committed {
            break;
        }
    }
}

#[test]
fn two_external_designs_have_distinct_real_burns_in_the_authoritative_world() {
    let mut world = load(WORKSHOP);
    assert_eq!(world.snapshot().spacecraft.len(), 2);
    assert!(world.snapshot().playback.paused);
    let initial = world.snapshot().spacecraft;
    for ship in &initial {
        assert_eq!(
            world.body_by_id(&ship.body_id.0).unwrap().source_mass_kg,
            0.0
        );
        assert_eq!(
            ship.control,
            FlightControl {
                attitude_mode: AttitudeMode::Manual,
                ..FlightControl::default()
            }
        );
        world
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: ship.body_id.clone(),
                    control: FlightControl {
                        throttle: 1.0,
                        attitude_mode: AttitudeMode::Manual,
                        ..FlightControl::default()
                    },
                },
                1_100,
            )
            .unwrap();
    }
    advance(&mut world, 10.0);
    for ship in initial {
        let current = world.spacecraft_by_id(&ship.body_id.0).unwrap();
        let main = ship
            .engines
            .iter()
            .find(|e| e.control == EngineControl::Main)
            .unwrap();
        let fuel = main.max_mass_flow_kgps * 10.0;
        let exhaust_speed = main.max_thrust_n / main.max_mass_flow_kgps;
        let expected = exhaust_speed * (ship.mass_kg() / (ship.mass_kg() - fuel)).ln();
        assert!((current.propellant_mass_kg - (ship.propellant_mass_kg - fuel)).abs() < 1.0e-8);
        assert!((current.delivered_delta_v_mps - expected).abs() < 1.0e-8);
        assert_eq!(
            world.body_by_id(&ship.body_id.0).unwrap().mass_kg,
            current.mass_kg()
        );
    }
    assert!(
        world
            .spacecraft_by_id("workshop.sable")
            .unwrap()
            .delivered_delta_v_mps
            > 20.0
    );
    assert!(
        world
            .spacecraft_by_id("workshop.iris")
            .unwrap()
            .delivered_delta_v_mps
            < 0.01
    );
}

#[test]
fn dependency_order_is_resolved_for_external_depot_components() {
    let world = load(DEPOT);
    let ship = world.spacecraft_by_id("depot.tender").unwrap();
    let target = world.spacecraft_by_id("depot.keel").unwrap();
    assert!(ship.authored.as_ref().unwrap().contact.is_some());
    assert!(target.authored.as_ref().unwrap().contact.is_some());
    assert_eq!(ship.reference_body_id.as_ref().unwrap().0, "moon");
    assert_eq!(
        ship.docking.as_ref().unwrap().target_body_id,
        target.body_id
    );
    assert!(
        world
            .docking_flight_telemetry("depot.tender")
            .unwrap()
            .observation
            .unwrap()
            .alignment_error_rad
            < 1e-10
    );
}

#[test]
fn invalid_external_documents_are_rejected_at_the_boundary() {
    let original: Value = serde_json::from_slice(WORKSHOP).unwrap();
    let mutations: Vec<Box<dyn Fn(&mut Value)>> = vec![
        Box::new(|v| v["version"] = json!(99)),
        Box::new(|v| v["unknown_option"] = json!(true)),
        Box::new(|v| v["spacecraft_definitions"][0]["structure"]["dry_mass_kg"] = json!(-1)),
        Box::new(|v| {
            v["spacecraft_definitions"][0]["structure"]["inertia_kg_m2"]["x"] = json!(1e12)
        }),
        Box::new(|v| {
            v["spacecraft_definitions"][0]["engines"][0]["direction_body"] =
                json!({"x":0,"y":0,"z":0})
        }),
        Box::new(|v| v["spacecraft_definitions"][0]["visual_asset"] = json!("missing.glb")),
        Box::new(|v| v["spacecraft"][0]["propellant_kg"] = json!(91)),
        Box::new(|v| v["spacecraft"][0]["attitude"] = json!({"w":0,"x":0,"y":0,"z":0})),
        Box::new(|v| v["spacecraft"][0]["definition_id"] = json!("missing")),
        Box::new(|v| v["spacecraft"][1]["body_id"] = json!("workshop.iris")),
        Box::new(|v| v["spacecraft"][0]["reference_body_id"] = json!("workshop.iris")),
        Box::new(|v| v["spacecraft"][0]["placement"]["orbit_normal"] = json!({"x":1,"y":0,"z":0})),
        Box::new(|v| v["focus_body_id"] = json!("missing")),
        Box::new(|v| v["scientific_settings"] = json!({"integrator":"adaptive-symplectic"})),
    ];
    for (index, mutation) in mutations.into_iter().enumerate() {
        let mut value = original.clone();
        mutation(&mut value);
        let bytes = serde_json::to_vec(&value).unwrap();
        assert!(
            WorldRuntime::import_authored_scenario(&bytes, HardwareProfile::offline_reference(), 0)
                .is_err(),
            "accepted bad case {index}"
        );
    }
    assert_eq!(
        WorldRuntime::import_authored_scenario(
            &vec![b' '; MAX_AUTHORING_BYTES + 1],
            HardwareProfile::offline_reference(),
            0
        )
        .err(),
        Some(AuthoringError::TooLarge)
    );
}

#[test]
fn late_failure_in_multi_craft_addition_rolls_back_world_history_and_counters() {
    let mut world = load(WORKSHOP);
    let before = world.export_saved_session().unwrap();
    let ordinal = world.next_command_ordinal();
    let mut document: Value = serde_json::from_slice(IRIS).unwrap();
    let mut second = document["spacecraft"][0].clone();
    second["body_id"] = json!("custom.unresolvable");
    second["placement"]["host_body_id"] = json!("missing.host");
    document["spacecraft"].as_array_mut().unwrap().push(second);
    assert!(world
        .add_authored_spacecraft(&serde_json::to_vec(&document).unwrap(), 1_500)
        .is_err());
    assert!(world.body_by_id("custom.iris").is_none());
    assert_eq!(world.next_command_ordinal(), ordinal);
    assert_eq!(world.export_saved_session().unwrap(), before);
    world.add_authored_spacecraft(IRIS, 1_500).unwrap();
    assert!(world.body_by_id("custom.iris").is_some());
    let added = world.export_saved_session().unwrap();
    assert!(world.add_authored_spacecraft(IRIS, 1_600).is_err());
    assert_eq!(world.export_saved_session().unwrap(), added);
}

#[test]
fn authored_components_survive_save_resume_and_reject_old_physics_labels() {
    let mut world = load(DEPOT);
    world
        .set_docking_guidance("depot.tender", DockingGuidanceMode::Approach, 1_500)
        .unwrap();
    advance(&mut world, 1.0);
    let bytes = world.export_saved_session().unwrap();
    let value: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(value["flight_model"], "six-dof-strang-rocket-authored-v5");
    let mut reopened =
        WorldRuntime::import_saved_session(&bytes, HardwareProfile::offline_reference()).unwrap();
    advance(&mut world, 1.0);
    advance(&mut reopened, 1.0);
    assert_eq!(world.snapshot().bodies, reopened.snapshot().bodies);
    assert_eq!(world.snapshot().spacecraft, reopened.snapshot().spacecraft);
    let mut downgraded = value;
    downgraded["flight_model"] = json!("six-dof-strang-rocket-docking-v4");
    assert_eq!(
        WorldRuntime::import_saved_session(
            &serde_json::to_vec(&downgraded).unwrap(),
            HardwareProfile::offline_reference()
        )
        .err(),
        Some(SessionPersistenceError::UnsupportedVersion)
    );
}

#[test]
fn authored_additions_require_a_paused_committed_world() {
    let mut world = load(WORKSHOP);
    world
        .apply_command(WorldCommand::ResumePlayback, 1_200)
        .unwrap();
    let ordinal = world.next_command_ordinal();
    assert!(world.add_authored_spacecraft(IRIS, 1_300).is_err());
    assert_eq!(world.next_command_ordinal(), ordinal);
    world
        .apply_command(WorldCommand::PausePlayback, 1_400)
        .unwrap();
    let ordinal = world.next_command_ordinal();
    world
        .begin_advance([1, 2], 0, ordinal, 100.0, 1_500)
        .unwrap();
    assert!(world.add_authored_spacecraft(IRIS, 1_600).is_err());
    assert!(world.body_by_id("custom.iris").is_none());
}
