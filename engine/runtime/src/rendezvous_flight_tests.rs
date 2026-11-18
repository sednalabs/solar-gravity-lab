use super::*;
use crate::{AdvanceState, RuntimeConfig};
use solarlab_domain::spacecraft::add;
use solarlab_domain::{AttitudeMode, SpacecraftAppearance};
use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

const SHIP: &str = "flight.odyssey";
fn seeded(step: f64) -> WorldRuntime {
    let mut runtime = WorldRuntime::new(
        ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
        BranchId("rendezvous-test".into()),
        RuntimeConfig {
            physics: PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: step,
            },
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            live_updates_enabled: false,
        },
        HardwareProfile::offline_reference(),
        0,
    );
    runtime
        .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
        .unwrap();
    runtime
        .apply_command(WorldCommand::PausePlayback, 0)
        .unwrap();
    runtime
}
fn flight(step: f64) -> WorldRuntime {
    let mut runtime = seeded(step);
    runtime.spawn_rendezvous_flight(SHIP, 0).unwrap();
    runtime
}
fn advance(runtime: &mut WorldRuntime, seconds: f64) {
    let previous = runtime.query_advance().map_or(0, |a| a.generation);
    let ordinal = runtime.next_command_ordinal();
    let generation = runtime
        .begin_advance([ordinal, ordinal + 100], previous, ordinal, seconds, 0)
        .unwrap()
        .generation;
    let mut completed = 0;
    loop {
        let (operation, _) = runtime.continue_advance(generation, completed).unwrap();
        completed = operation.completed_steps;
        if operation.state == AdvanceState::Committed {
            break;
        }
    }
}
fn telemetry(runtime: &WorldRuntime) -> RendezvousFlightTelemetry {
    runtime.rendezvous_flight_telemetry(SHIP).unwrap()
}
fn manual(runtime: &mut WorldRuntime, control: FlightControl) {
    runtime
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId(SHIP.into()),
                control,
            },
            0,
        )
        .unwrap();
}

#[test]
fn persistent_approach_reaches_actual_standoff_without_a_host_control_loop() {
    let mut runtime = flight(0.05);
    let mut coast = runtime.clone();
    let initial = telemetry(&runtime);
    assert!((initial.range_m - 1000.0).abs() < 0.01);
    assert!(initial.relative_speed_mps > 1.0);
    assert_eq!(initial.keep_out_distance_m, 25.0);
    assert_eq!(
        runtime
            .spacecraft_by_id(RENDEZVOUS_STATION_BODY_ID)
            .unwrap()
            .appearance,
        SpacecraftAppearance::RelayStation
    );
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    advance(&mut runtime, 650.0);
    advance(&mut coast, 650.0);
    let t = telemetry(&runtime);
    println!("persistent approach {t:?}");
    assert!(t.stand_off_error_m < 1.0, "{t:?}");
    assert!(t.relative_speed_mps < 0.02, "{t:?}");
    assert!(t.stand_off_achieved && !t.keep_out_entered);
    assert!(t.closest_observed_range_m > t.keep_out_distance_m);
    assert_eq!(t.guidance_mode, RendezvousGuidanceMode::Approach);
    assert_eq!(t.status, RendezvousStatus::Holding);
    assert!(t.propellant_used_kg > 10.0 && t.propellant_used_kg < 50.0);
    assert_eq!(
        runtime.spacecraft_by_id(SHIP).unwrap().control.throttle,
        0.0
    );
    for id in ["earth", "moon", "sun", RENDEZVOUS_STATION_BODY_ID] {
        assert_eq!(
            runtime.body_by_id(id),
            coast.body_by_id(id),
            "passive chaser must not perturb {id}"
        );
    }
    assert_eq!(runtime.body_by_id(SHIP).unwrap().source_mass_kg, 0.0);
}

#[test]
fn velocity_match_manual_rcs_then_approach_is_a_real_reusable_flight_sequence() {
    for manual_demand in [0.25, 1.0] {
        let mut runtime = flight(30.0);
        runtime
            .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::MatchVelocity, 0)
            .unwrap();
        advance(&mut runtime, 90.0);
        let matched = telemetry(&runtime);
        println!("persistent match {matched:?}");
        assert!(
            matched.range_m > 900.0 && matched.relative_speed_mps < 0.03,
            "{matched:?}"
        );
        manual(
            &mut runtime,
            FlightControl {
                translation_body: Vector3d {
                    x: manual_demand,
                    y: 0.0,
                    z: 0.0,
                },
                attitude_mode: AttitudeMode::Hold,
                ..FlightControl::default()
            },
        );
        assert_eq!(
            telemetry(&runtime).guidance_mode,
            RendezvousGuidanceMode::Manual
        );
        advance(&mut runtime, 2.0);
        runtime
            .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
            .unwrap();
        advance(&mut runtime, 650.0);
        let arrived = telemetry(&runtime);
        println!("match-pulse={manual_demand}-approach {arrived:?}");
        assert!(
            arrived.stand_off_error_m < 1.0 && arrived.relative_speed_mps < 0.02,
            "{arrived:?}"
        );
        assert!(arrived.stand_off_achieved && !arrived.keep_out_entered);
        assert!(arrived.propellant_used_kg > matched.propellant_used_kg);
    }
}

#[test]
fn active_guidance_save_restores_mode_progress_and_exact_future() {
    let mut runtime = flight(0.05);
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    advance(&mut runtime, 200.0);
    let saved = runtime.export_saved_session().unwrap();
    let text = String::from_utf8(saved.clone()).unwrap();
    assert!(text.contains("six-dof-strang-rocket-rendezvous-v3"));
    let downgraded = text.replace(
        "six-dof-strang-rocket-rendezvous-v3",
        "six-dof-strang-rocket-v2",
    );
    assert!(WorldRuntime::import_saved_session(
        downgraded.as_bytes(),
        HardwareProfile::offline_reference()
    )
    .is_err());
    let mut restored =
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).unwrap();
    assert_eq!(telemetry(&runtime), telemetry(&restored));
    advance(&mut runtime, 450.0);
    advance(&mut restored, 450.0);
    assert_eq!(runtime.snapshot().bodies, restored.snapshot().bodies);
    assert_eq!(
        runtime.snapshot().spacecraft,
        restored.snapshot().spacecraft
    );
    assert_eq!(telemetry(&runtime), telemetry(&restored));
    assert!(telemetry(&runtime).stand_off_achieved);
}

#[test]
fn missing_target_stops_immediately_and_retained_progress_can_be_saved() {
    let mut runtime = flight(0.05);
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    advance(&mut runtime, 30.0);
    let before = runtime.snapshot();
    let ordinal = runtime.next_command_ordinal();
    assert!(runtime
        .set_rendezvous_guidance("missing", RendezvousGuidanceMode::Approach, 0)
        .is_err());
    assert_eq!(runtime.snapshot(), before);
    assert_eq!(runtime.next_command_ordinal(), ordinal);
    runtime
        .apply_command(
            WorldCommand::RemoveBody {
                body_id: BodyId(RENDEZVOUS_STATION_BODY_ID.into()),
            },
            0,
        )
        .unwrap();
    let t = telemetry(&runtime);
    assert!(!t.target_available);
    assert_eq!(t.guidance_mode, RendezvousGuidanceMode::Manual);
    assert_eq!(t.status, RendezvousStatus::TargetUnavailable);
    assert_eq!(
        runtime.spacecraft_by_id(SHIP).unwrap().control,
        solarlab_physics::rendezvous::neutral_control()
    );
    let saved = runtime.export_saved_session().unwrap();
    let mut restored =
        WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference()).unwrap();
    assert_eq!(telemetry(&restored), t);
    advance(&mut restored, 1.0);
    assert!(!restored.spacecraft_by_id(SHIP).unwrap().has_powered_input());
    restored
        .apply_command(
            WorldCommand::RemoveBody {
                body_id: BodyId(SHIP.into()),
            },
            0,
        )
        .unwrap();
    assert!(restored.rendezvous_flight_telemetry(SHIP).is_none());
}

#[test]
fn guidance_warp_limits_manual_cancellation_and_staged_rollback_preserve_authority() {
    let mut runtime = flight(30.0);
    runtime
        .apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 100.0,
            },
            0,
        )
        .unwrap();
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    assert_eq!(runtime.snapshot().playback.sim_seconds_per_real_second, 1.0);
    assert!(runtime
        .apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 10.0
            },
            0
        )
        .is_err());
    let before = runtime.snapshot();
    let ordinal = runtime.next_command_ordinal();
    let generation = runtime
        .begin_advance([100, 101], 0, ordinal, 650.0, 0)
        .unwrap()
        .generation;
    runtime.continue_advance(generation, 0).unwrap();
    assert_eq!(runtime.snapshot(), before);
    assert!(runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Manual, 0)
        .is_err());
    runtime.cancel_advance(generation).unwrap();
    assert_eq!(runtime.snapshot(), before);
    assert_eq!(runtime.next_command_ordinal(), ordinal);
    manual(&mut runtime, FlightControl::default());
    assert_eq!(
        telemetry(&runtime).guidance_mode,
        RendezvousGuidanceMode::Manual
    );
    runtime
        .apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 100.0,
            },
            0,
        )
        .unwrap();
    let fuel = telemetry(&runtime).remaining_propellant_kg;
    runtime
        .apply_command(WorldCommand::ResumePlayback, 0)
        .unwrap();
    let tick = runtime.playback_tick(0.1, 0).unwrap();
    assert!(tick.applied_seconds > 0.1);
    assert_eq!(telemetry(&runtime).remaining_propellant_kg, fuel);
}

#[test]
fn fuel_limited_guidance_cuts_at_depletion_and_saves_valid_state() {
    let mut runtime = flight(0.05);
    let world = &mut runtime.active_branch_mut().world;
    let ship = world
        .spacecraft
        .iter_mut()
        .find(|s| s.body_id.0 == SHIP)
        .unwrap();
    ship.propellant_mass_kg = 0.00001;
    let mass = ship.mass_kg();
    world
        .bodies
        .iter_mut()
        .find(|b| b.body_id.0 == SHIP)
        .unwrap()
        .mass_kg = mass;
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    advance(&mut runtime, 2.0);
    let t = telemetry(&runtime);
    assert_eq!(t.remaining_propellant_kg, 0.0);
    assert_eq!(t.guidance_mode, RendezvousGuidanceMode::Manual);
    assert_eq!(t.status, RendezvousStatus::OutOfFuel);
    assert_eq!(
        runtime.spacecraft_by_id(SHIP).unwrap().control,
        solarlab_physics::rendezvous::neutral_control()
    );
    WorldRuntime::import_saved_session(
        &runtime.export_saved_session().unwrap(),
        HardwareProfile::offline_reference(),
    )
    .unwrap();
}

#[test]
fn keep_out_progress_observes_real_coast_states_and_queries_are_read_only() {
    let mut runtime = flight(0.05);
    let target = runtime
        .body_by_id(RENDEZVOUS_STATION_BODY_ID)
        .unwrap()
        .clone();
    // Author a hazardous initial encounter fixture. Guidance never edits these values.
    let body = runtime
        .active_branch_mut()
        .world
        .bodies
        .iter_mut()
        .find(|b| b.body_id.0 == SHIP)
        .unwrap();
    body.position_m = add(
        target.position_m,
        Vector3d {
            x: 40.0,
            y: 0.0,
            z: 0.0,
        },
    );
    body.velocity_mps = add(
        target.velocity_mps,
        Vector3d {
            x: -5.0,
            y: 0.0,
            z: 0.0,
        },
    );
    let before = runtime.snapshot();
    assert!(!telemetry(&runtime).keep_out_entered);
    assert_eq!(runtime.snapshot(), before);
    advance(&mut runtime, 4.0);
    let t = telemetry(&runtime);
    assert!(t.range_m < t.keep_out_distance_m && t.keep_out_entered);
    assert!(!t.stand_off_achieved);
    assert_eq!(t.propellant_used_kg, 0.0);
    assert!(t.closest_observed_epoch_seconds <= t.epoch_seconds + 1e-8);
}

#[test]
fn canonical_entry_rejects_edited_or_in_progress_labs_atomically() {
    let mut runtime = seeded(0.05);
    advance(&mut runtime, 1.0);
    let before = runtime.snapshot();
    let ordinal = runtime.next_command_ordinal();
    assert!(runtime.spawn_rendezvous_flight(SHIP, 0).is_err());
    assert_eq!(runtime.snapshot(), before);
    assert_eq!(runtime.next_command_ordinal(), ordinal);
    let mut clean = seeded(0.05);
    let before = clean.snapshot();
    assert!(clean
        .spawn_rendezvous_flight(RENDEZVOUS_STATION_BODY_ID, 0)
        .is_err());
    assert_eq!(clean.snapshot(), before);
}

#[test]
fn native_guidance_trajectory_converges_with_smaller_force_steps() {
    let mut positions = Vec::new();
    for step in [0.05, 0.025, 0.00625] {
        let mut runtime = flight(step);
        runtime
            .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
            .unwrap();
        advance(&mut runtime, 120.0);
        positions.push(telemetry(&runtime).position_target_m);
    }
    let coarse = magnitude(sub(positions[0], positions[2]));
    let fine = magnitude(sub(positions[1], positions[2]));
    println!("persistent guidance step convergence coarse={coarse} fine={fine}");
    assert!(coarse < 0.05, "coarse={coarse} fine={fine}");
    assert!(fine < coarse * 0.6 + 0.001, "coarse={coarse} fine={fine}");
}

#[test]
fn checkpoint_contains_guidance_target_and_physical_progress() {
    let mut runtime = flight(0.05);
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    advance(&mut runtime, 200.0);
    let marked = telemetry(&runtime);
    runtime
        .apply_command(
            WorldCommand::CreateCheckpoint {
                checkpoint_id: Some(CheckpointId("rendezvous.mark".into())),
                label: Some("Approach".into()),
            },
            0,
        )
        .unwrap();
    manual(&mut runtime, FlightControl::default());
    advance(&mut runtime, 10.0);
    runtime
        .apply_command(
            WorldCommand::CreateBranchFromCheckpoint {
                checkpoint_id: CheckpointId("rendezvous.mark".into()),
                new_branch_id: Some(BranchId("rendezvous.retry".into())),
            },
            0,
        )
        .unwrap();
    assert_eq!(telemetry(&runtime), marked);
    advance(&mut runtime, 450.0);
    assert!(telemetry(&runtime).stand_off_achieved);
}

#[test]
fn active_guidance_rejects_incompatible_banks_at_spawn_and_import_but_manual_accepts_them() {
    for empty in [true, false] {
        let mut runtime = flight(0.05);
        runtime
            .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
            .unwrap();
        let body = runtime.body_by_id(SHIP).unwrap().clone();
        let mut component = runtime.spacecraft_by_id(SHIP).unwrap().clone();
        if empty {
            component.engines.clear();
        } else {
            component.engines[1].direction_body = Vector3d {
                x: 0.0,
                y: 1.0,
                z: 0.0,
            };
        }
        assert!(
            component.valid(),
            "generic physical engines are valid in Manual flight"
        );
        let mut document: serde_json::Value =
            serde_json::from_slice(&runtime.export_saved_session().unwrap()).unwrap();
        let components = document["branches"][0]["world"]["spacecraft"]
            .as_array_mut()
            .unwrap();
        let candidate = components
            .iter_mut()
            .find(|s| s["body_id"] == SHIP)
            .unwrap();
        *candidate = serde_json::to_value(&component).unwrap();
        assert!(
            WorldRuntime::import_saved_session(
                &serde_json::to_vec(&document).unwrap(),
                HardwareProfile::offline_reference()
            )
            .is_err(),
            "active unsupported guidance must fail import, empty={empty}"
        );
        runtime
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId(SHIP.into()),
                },
                0,
            )
            .unwrap();
        let before = runtime.snapshot();
        let ordinal = runtime.next_command_ordinal();
        assert!(runtime
            .apply_command(
                WorldCommand::SpawnSpacecraft {
                    body: body.clone(),
                    spacecraft: component.clone()
                },
                0
            )
            .is_err());
        assert_eq!(runtime.snapshot(), before);
        assert_eq!(runtime.next_command_ordinal(), ordinal);
        let retained = component.rendezvous.as_mut().unwrap();
        retained.guidance_mode = RendezvousGuidanceMode::Manual;
        retained.status = RendezvousStatus::Manual;
        component.control = solarlab_physics::rendezvous::neutral_control();
        runtime
            .apply_command(
                WorldCommand::SpawnSpacecraft {
                    body,
                    spacecraft: component,
                },
                0,
            )
            .unwrap();
        assert!(runtime
            .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
            .is_err());
        let saved = runtime.export_saved_session().unwrap();
        let mut reopened =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        advance(&mut reopened, 1.0);
    }
}

#[test]
fn active_approach_rejects_a_standoff_inside_the_target_hull_on_import() {
    let mut runtime = flight(0.05);
    runtime
        .set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0)
        .unwrap();
    let mut document: serde_json::Value =
        serde_json::from_slice(&runtime.export_saved_session().unwrap()).unwrap();
    let bodies = document["branches"][0]["world"]["bodies"]
        .as_array_mut()
        .unwrap();
    bodies
        .iter_mut()
        .find(|b| b["body_id"] == RENDEZVOUS_STATION_BODY_ID)
        .unwrap()["radius_m"] = 100.0.into();
    assert!(WorldRuntime::import_saved_session(
        &serde_json::to_vec(&document).unwrap(),
        HardwareProfile::offline_reference()
    )
    .is_err());
}

#[test]
fn manual_rotation_throttle_and_attitude_take_over_active_rendezvous() {
    for control in [
        FlightControl { throttle: 0.25, ..FlightControl::default() },
        FlightControl { rotation_body: Vector3d { x: 0.0, y: 0.5, z: 0.0 }, ..FlightControl::default() },
        FlightControl { attitude_mode: AttitudeMode::Prograde, ..FlightControl::default() },
    ] {
        let mut runtime = flight(0.05);
        runtime.set_rendezvous_guidance(SHIP, RendezvousGuidanceMode::Approach, 0).unwrap();
        manual(&mut runtime, control);
        let ship = runtime.spacecraft_by_id(SHIP).unwrap();
        assert_eq!(ship.control, control);
        assert_eq!(ship.rendezvous.as_ref().unwrap().guidance_mode, RendezvousGuidanceMode::Manual);
        assert!(ship.valid());
        advance(&mut runtime, 0.1);
    }
}
