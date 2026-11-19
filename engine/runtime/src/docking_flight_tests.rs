use super::*;
use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

const SHIP: &str = "flight.odyssey";
fn seeded() -> WorldRuntime {
    let mut r = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("docking-test".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: 30.0,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    );
    r.apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    r.apply_command(WorldCommand::PausePlayback, 0).unwrap();
    r
}
fn flight() -> WorldRuntime {
    let mut r = seeded();
    r.spawn_docking_flight(SHIP, 0).unwrap();
    r
}
fn tick(r: &mut WorldRuntime, seconds: f64) {
    let previous = r.query_advance().map_or(0, |a| a.generation);
    let ordinal = r.next_command_ordinal();
    let generation = r
        .begin_advance([ordinal, ordinal + 100], previous, ordinal, seconds, 0)
        .unwrap()
        .generation;
    let mut completed = 0;
    loop {
        let (op, _) = r.continue_advance(generation, completed).unwrap();
        completed = op.completed_steps;
        if op.state == AdvanceState::Committed {
            break;
        }
    }
}
fn dock(r: &mut WorldRuntime) {
    r.set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .unwrap();
    for _ in 0..200 {
        tick(r, 1.0);
        if r.docking_flight_telemetry(SHIP).unwrap().attached {
            return;
        }
    }
    panic!(
        "Native approach did not dock: {:?}",
        r.docking_flight_telemetry(SHIP)
    );
}
fn same_physics(a: &WorldRuntime, b: &WorldRuntime) {
    assert_eq!(a.snapshot().bodies, b.snapshot().bodies);
    assert_eq!(a.snapshot().spacecraft, b.snapshot().spacecraft);
}

#[test]
fn contact_model_guard_is_exported_only_when_contact_state_exists() {
    let seeded_document: serde_json::Value =
        serde_json::from_slice(&seeded().export_saved_session().unwrap()).unwrap();
    assert!(seeded_document.get("contact_model").is_none());
    let docking_document: serde_json::Value =
        serde_json::from_slice(&flight().export_saved_session().unwrap()).unwrap();
    assert_eq!(
        docking_document["contact_model"],
        "bounded-feature-contact-v1"
    );
    assert_eq!(
        docking_document["flight_model"],
        "six-dof-strang-rocket-docking-v4"
    );
}

#[test]
fn contact_model_guard_rejects_unknown_solver_tags() {
    let mut document: serde_json::Value =
        serde_json::from_slice(&flight().export_saved_session().unwrap()).unwrap();
    document["contact_model"] = serde_json::json!("unknown-contact-model");
    assert!(matches!(
        WorldRuntime::import_saved_session(
            &serde_json::to_vec(&document).unwrap(),
            HardwareProfile::offline_reference()
        ),
        Err(SessionPersistenceError::UnsupportedVersion)
    ));
}

#[test]
fn contact_model_guard_migrates_legacy_v4_state_and_guards_reexport() {
    let mut original = flight();
    let guarded: serde_json::Value =
        serde_json::from_slice(&original.export_saved_session().unwrap()).unwrap();
    let mut legacy = guarded.clone();
    legacy.as_object_mut().unwrap().remove("contact_model");
    assert_eq!(legacy["flight_model"], "six-dof-strang-rocket-docking-v4");
    let mut migrated = WorldRuntime::import_saved_session(
        &serde_json::to_vec(&legacy).unwrap(),
        HardwareProfile::offline_reference(),
    )
    .unwrap();
    same_physics(&original, &migrated);
    assert_eq!(
        original.snapshot().epoch_seconds,
        migrated.snapshot().epoch_seconds
    );
    let exported: serde_json::Value =
        serde_json::from_slice(&migrated.export_saved_session().unwrap()).unwrap();
    assert_eq!(exported, guarded);
    // Migration preserves state and uses the current solver for its future.
    tick(&mut original, 0.01);
    tick(&mut migrated, 0.01);
    same_physics(&original, &migrated);
}

#[test]
fn docking_scenario_is_atomic_fresh_and_keeps_scientific_bodies() {
    let mut r = seeded();
    let original = r.snapshot();
    assert!(r.spawn_docking_flight("earth", 0).is_err());
    assert_eq!(r.snapshot().bodies, original.bodies);
    r.spawn_docking_flight(SHIP, 0).unwrap();
    for body in original.bodies {
        assert_eq!(r.body_by_id(&body.body_id.0), Some(&body));
    }
    let t = r.docking_flight_telemetry(SHIP).unwrap();
    assert!((t.centre_range_m - 30.0).abs() < 1e-6);
    assert!((t.observation.unwrap().position_port_m.z - 22.44).abs() < 5e-5);
    assert!(t.observation.unwrap().alignment_error_rad < 1e-8);
    assert_eq!(r.body_by_id(SHIP).unwrap().source_mass_kg, 0.0);
    assert_eq!(
        r.body_by_id(DOCKING_STATION_BODY_ID)
            .unwrap()
            .source_mass_kg,
        0.0
    );
    assert_eq!(r.effective_physics_policy().max_substep_seconds, 0.01);
    assert!(r
        .apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 10.0
            },
            0
        )
        .is_err());
    assert!(r.spawn_docking_flight("second", 0).is_err());
}

#[test]
fn native_docking_save_joint_continuity_and_physical_retreat() {
    let mut r = flight();
    let initial_fuel = r.spacecraft_by_id(SHIP).unwrap().propellant_mass_kg;
    dock(&mut r);
    let t = r.docking_flight_telemetry(SHIP).unwrap();
    println!("Captured native world: {t:?}");
    assert_eq!(t.status, DockingStatus::Docked);
    assert!(!t.capture_armed && !t.unsafe_contact);
    assert!(t.remaining_propellant_kg < initial_fuel);
    assert!(t.observation.unwrap().position_port_m.z.abs() <= 0.005);
    let bytes = r.export_saved_session().unwrap();
    let mut forged: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let ships = forged["branches"][0]["world"]["spacecraft"]
        .as_array_mut()
        .unwrap();
    let ship = ships.iter_mut().find(|s| s["docking"].is_object()).unwrap();
    ship["docking"]["attachment"]["position_target_m"]["x"] = serde_json::json!(30.0);
    assert!(WorldRuntime::import_saved_session(
        &serde_json::to_vec(&forged).unwrap(),
        HardwareProfile::offline_reference()
    )
    .is_err());
    let mut forged: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    let bodies = forged["branches"][0]["world"]["bodies"]
        .as_array_mut()
        .unwrap();
    let body = bodies.iter_mut().find(|b| b["body_id"] == SHIP).unwrap();
    body["velocity_mps"]["x"] = serde_json::json!(1.0e6);
    assert!(WorldRuntime::import_saved_session(
        &serde_json::to_vec(&forged).unwrap(),
        HardwareProfile::offline_reference()
    )
    .is_err());
    assert!(String::from_utf8_lossy(&bytes).contains("six-dof-strang-rocket-docking-v4"));
    let mut resumed =
        WorldRuntime::import_saved_session(&bytes, HardwareProfile::offline_reference()).unwrap();
    same_physics(&r, &resumed);
    for _ in 0..20 {
        tick(&mut r, 1.0);
        tick(&mut resumed, 1.0);
    }
    same_physics(&r, &resumed);
    // Controls cannot apply thrust through a captured joint; CUT cannot detach it.
    assert!(r
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId(SHIP.into()),
                control: FlightControl {
                    throttle: 1.0,
                    ..FlightControl::default()
                }
            },
            0
        )
        .is_err());
    r.apply_command(
        WorldCommand::SetFlightControl {
            body_id: BodyId(SHIP.into()),
            control: neutral_control(),
        },
        0,
    )
    .unwrap();
    assert!(r.docking_flight_telemetry(SHIP).unwrap().attached);
    let before = r.snapshot();
    r.undock_spacecraft(SHIP, 0).unwrap();
    assert_eq!(
        r.snapshot().bodies,
        before.bodies,
        "Release must not inject a separating impulse"
    );
    assert_eq!(
        r.spacecraft_by_id(SHIP).unwrap().attitude,
        before
            .spacecraft
            .iter()
            .find(|s| s.body_id.0 == SHIP)
            .unwrap()
            .attitude
    );
    r.apply_command(
        WorldCommand::SetFlightControl {
            body_id: BodyId(SHIP.into()),
            control: FlightControl {
                translation_body: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: -0.2,
                },
                ..neutral_control()
            },
        },
        0,
    )
    .unwrap();
    tick(&mut r, 10.0);
    assert!(r.docking_flight_telemetry(SHIP).unwrap().centre_range_m > t.centre_range_m + 0.2);
    assert!(r.spacecraft_by_id(SHIP).unwrap().propellant_mass_kg < t.remaining_propellant_kg);
    assert!(!r.docking_flight_telemetry(SHIP).unwrap().attached);
}

#[test]
fn active_guidance_save_reopen_and_bounded_chunking_preserve_motion() {
    let mut r = flight();
    r.set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .unwrap();
    tick(&mut r, 5.0);
    let bytes = r.export_saved_session().unwrap();
    let mut resumed =
        WorldRuntime::import_saved_session(&bytes, HardwareProfile::offline_reference()).unwrap();
    for _ in 0..10 {
        tick(&mut r, 1.0);
        tick(&mut resumed, 1.0);
    }
    same_physics(&r, &resumed);
    let mut bounded = r.clone();
    let previous = bounded.query_advance().map_or(0, |a| a.generation);
    let ordinal = bounded.next_command_ordinal();
    let generation = bounded
        .begin_advance([ordinal, ordinal + 100], previous, ordinal, 10.0, 0)
        .unwrap()
        .generation;
    let mut completed = 0;
    loop {
        let (op, _) = bounded.continue_advance(generation, completed).unwrap();
        completed = op.completed_steps;
        if op.state == AdvanceState::Committed {
            break;
        }
    }
    for _ in 0..10 {
        tick(&mut r, 1.0);
    }
    same_physics(&r, &bounded);
}

#[test]
fn cancelled_contact_advance_keeps_committed_world_and_fuel() {
    let mut r = flight();
    r.set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .unwrap();
    let before = r.snapshot();
    let ordinal = r.next_command_ordinal();
    let generation = r
        .begin_advance([1, 7], 0, ordinal, 600.0, 0)
        .unwrap()
        .generation;
    let (op, _) = r.continue_advance(generation, 0).unwrap();
    assert_eq!(op.state, AdvanceState::Running);
    assert!(r.export_saved_session().is_err());
    r.cancel_advance(generation).unwrap();
    assert_eq!(r.snapshot().bodies, before.bodies);
    assert_eq!(r.snapshot().spacecraft, before.spacecraft);
    assert_eq!(r.next_command_ordinal(), ordinal);
}

#[test]
fn target_removal_releases_attachment_and_persists_unavailable_identity() {
    let mut r = flight();
    dock(&mut r);
    let old_station = r.body_by_id(DOCKING_STATION_BODY_ID).unwrap().clone();
    r.apply_command(
        WorldCommand::RemoveBody {
            body_id: BodyId(DOCKING_STATION_BODY_ID.into()),
        },
        0,
    )
    .unwrap();
    assert!(
        r.apply_command(WorldCommand::SpawnBody { body: old_station }, 0)
            .is_err(),
        "A removed docking target ID cannot be silently reused"
    );
    let t = r.docking_flight_telemetry(SHIP).unwrap();
    assert_eq!(t.status, DockingStatus::TargetUnavailable);
    assert!(!t.attached && !t.capture_armed);
    assert!(t.observation.is_none());
    tick(&mut r, 1.0);
    let reopened = WorldRuntime::import_saved_session(
        &r.export_saved_session().unwrap(),
        HardwareProfile::offline_reference(),
    )
    .unwrap();
    assert_eq!(
        reopened.docking_flight_telemetry(SHIP),
        r.docking_flight_telemetry(SHIP)
    );
    assert!(r
        .set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .is_err());
}

#[test]
fn saved_docking_cannot_be_downgraded_or_reassigned_to_a_gravitating_body() {
    let r = flight();
    let bytes = r.export_saved_session().unwrap();
    let mut document: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    document["flight_model"] = serde_json::json!("six-dof-strang-rocket-rendezvous-v3");
    assert!(matches!(
        WorldRuntime::import_saved_session(
            &serde_json::to_vec(&document).unwrap(),
            HardwareProfile::offline_reference()
        ),
        Err(SessionPersistenceError::UnsupportedVersion)
    ));
    let mut invalid = r.clone();
    invalid
        .active_branch_mut()
        .world
        .spacecraft
        .iter_mut()
        .find(|s| s.body_id.0 == SHIP)
        .unwrap()
        .docking
        .as_mut()
        .unwrap()
        .target_body_id = BodyId("earth".into());
    assert!(invalid.export_saved_session().is_err());
    let mut invalid = r;
    invalid
        .active_branch_mut()
        .world
        .bodies
        .iter_mut()
        .find(|b| b.body_id.0 == SHIP)
        .unwrap()
        .source_mass_kg = 1.0;
    assert!(invalid.export_saved_session().is_err());
}

#[test]
fn checkpoint_only_docking_still_requires_the_contact_model() {
    let mut r = flight();
    r.apply_command(
        WorldCommand::CreateCheckpoint {
            checkpoint_id: Some(CheckpointId("docking-start".into())),
            label: None,
        },
        0,
    )
    .unwrap();
    for id in [SHIP, DOCKING_STATION_BODY_ID] {
        r.apply_command(
            WorldCommand::RemoveBody {
                body_id: BodyId(id.into()),
            },
            0,
        )
        .unwrap();
    }
    let saved = r.export_saved_session().unwrap();
    let doc: serde_json::Value = serde_json::from_slice(&saved).unwrap();
    assert_eq!(doc["flight_model"], "six-dof-strang-rocket-docking-v4");
    assert_eq!(doc["contact_model"], "bounded-feature-contact-v1");
    let mut reopened =
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).unwrap();
    reopened
        .apply_command(
            WorldCommand::CreateBranchFromCheckpoint {
                checkpoint_id: CheckpointId("docking-start".into()),
                new_branch_id: None,
            },
            0,
        )
        .unwrap();
    assert!(reopened.docking_flight_telemetry(SHIP).is_some());
}

#[test]
fn docking_rearm_rejects_a_penetrating_aborted_pair_atomically() {
    let mut r = flight();
    let station = r.body_by_id(DOCKING_STATION_BODY_ID).unwrap().position_m;
    let world = &mut r.active_branch_mut().world;
    world
        .bodies
        .iter_mut()
        .find(|b| b.body_id.0 == SHIP)
        .unwrap()
        .position_m = add(
        station,
        Vector3d {
            x: 0.0,
            y: -7.54,
            z: 0.0,
        },
    );
    world
        .spacecraft
        .iter_mut()
        .find(|s| s.body_id.0 == SHIP)
        .unwrap()
        .docking
        .as_mut()
        .unwrap()
        .unsafe_contact = true;
    let before = r.snapshot();
    let ordinal = r.next_command_ordinal();
    assert!(r
        .set_docking_guidance(SHIP, DockingGuidanceMode::Approach, 0)
        .is_err());
    assert_eq!(r.next_command_ordinal(), ordinal);
    assert_eq!(r.snapshot().bodies, before.bodies);
    assert_eq!(r.snapshot().spacecraft, before.spacecraft);
}
