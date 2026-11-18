//! Bounded flight requests. Runtime owns forces and state transitions.
use super::*;
use solarlab_domain::{AttitudeMode, FlightControl, RendezvousGuidanceMode, RendezvousStatus};
pub(super) const FLIGHT_SAMPLE_LEN: usize = 44;
pub(super) const LUNAR_SAMPLE_LEN: usize = 21;
pub(super) const RENDEZVOUS_SAMPLE_LEN: usize = 27;

pub(super) fn control_from_values(v: &[f64]) -> Result<FlightControl, String> {
    if v.len() != 8 || v.iter().any(|x| !x.is_finite()) {
        return Err("Invalid flight control payload".into());
    }
    if !(0.0..=1.0).contains(&v[0]) || v[1..7].iter().any(|x| x.abs() > 1.0) {
        return Err("Flight control outside its input range".into());
    }
    let attitude_mode = match v[7] {
        0.0 => AttitudeMode::Manual,
        1.0 => AttitudeMode::Hold,
        2.0 => AttitudeMode::Prograde,
        3.0 => AttitudeMode::Retrograde,
        4.0 => AttitudeMode::Normal,
        5.0 => AttitudeMode::AntiNormal,
        _ => return Err("Unknown attitude assist".into()),
    };
    Ok(FlightControl {
        throttle: v[0],
        translation_body: Vector3d {
            x: v[1],
            y: v[2],
            z: v[3],
        },
        rotation_body: Vector3d {
            x: v[4],
            y: v[5],
            z: v[6],
        },
        attitude_mode,
    })
}

pub(super) fn start(handle: SlRuntimeHandle, ship_id: &str, recorded: i64) -> Result<(), String> {
    if ship_id.is_empty() || ship_id.len() > SL_V2_ID_CAPACITY {
        return Err("Invalid spacecraft identifier".into());
    }
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    // Atomic setup includes checkpoint, observer and neutral controls.
    let mut staged = session.runtime.clone();
    staged
        .apply_command(WorldCommand::PausePlayback, recorded)
        .map_err(|e| format!("Could not pause flight: {e:?}"))?;
    if staged.spacecraft_by_id(ship_id).is_none() {
        staged
            .spawn_reference_spacecraft("earth", ship_id, 400_000.0, recorded)
            .map_err(|e| format!("Could not launch spacecraft: {e:?}"))?;
    }
    configure_flight(&mut staged, ship_id, recorded)?;
    session.runtime = staged;
    Ok(())
}

pub(super) fn configure_flight(staged: &mut WorldRuntime, ship_id: &str, recorded: i64) -> Result<(), String> {
    for command in [
        WorldCommand::PausePlayback,
        WorldCommand::SetPlaybackRate {
            sim_seconds_per_real_second: 1.0,
        },
        WorldCommand::FocusBody {
            body_id: Some(BodyId(ship_id.into())),
        },
        WorldCommand::SetObserverMode {
            mode: ObserverMode::FollowSelected,
        },
        WorldCommand::SetFlightControl {
            body_id: BodyId(ship_id.into()),
            control: FlightControl {
                throttle: 0.0,
                translation_body: Vector3d::default(),
                rotation_body: Vector3d::default(),
                attitude_mode: AttitudeMode::Hold,
            },
        },
        WorldCommand::CreateCheckpoint {
            checkpoint_id: None,
            label: Some("Flight checkpoint".into()),
        },
    ] {
        staged
            .apply_command(command, recorded)
            .map_err(|e| format!("Could not enter flight: {e:?}"))?;
    }
    Ok(())
}

pub(super) fn start_lunar(
    handle: SlRuntimeHandle,
    ship_id: &str,
    recorded: i64,
) -> Result<(), String> {
    if ship_id.is_empty() || ship_id.len() > SL_V2_ID_CAPACITY {
        return Err("Invalid spacecraft identifier".into());
    }
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    let mut staged = session.runtime.clone();
    staged
        .apply_command(WorldCommand::PausePlayback, recorded)
        .map_err(|e| format!("Could not pause lunar flight: {e:?}"))?;
    staged
        .spawn_lunar_spacecraft(ship_id, recorded)
        .map_err(|e| format!("Could not prepare the lunar departure: {e:?}"))?;
    configure_flight(&mut staged, ship_id, recorded)?;
    session.runtime = staged;
    Ok(())
}

pub(super) fn begin_lunar_departure(
    handle: SlRuntimeHandle,
    ship_id: &str,
    recorded: i64,
) -> Result<(), String> {
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?
        .runtime
        .start_lunar_departure(ship_id, recorded)
        .map_err(|e| format!("Could not start the guided departure: {e:?}"))?;
    Ok(())
}

pub(super) fn start_rendezvous(
    handle: SlRuntimeHandle,
    ship_id: &str,
    recorded: i64,
) -> Result<(), String> {
    if ship_id.is_empty() || ship_id.len() > SL_V2_ID_CAPACITY {
        return Err("Invalid spacecraft identifier".into());
    }
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    let mut staged = session.runtime.clone();
    staged
        .apply_command(WorldCommand::PausePlayback, recorded)
        .map_err(|e| format!("Could not pause rendezvous flight: {e:?}"))?;
    staged
        .spawn_rendezvous_flight(ship_id, recorded)
        .map_err(|e| format!("Could not prepare station rendezvous: {e:?}"))?;
    configure_flight(&mut staged, ship_id, recorded)?;
    session.runtime = staged;
    Ok(())
}

pub(super) fn set_rendezvous_guidance(
    handle: SlRuntimeHandle,
    ship_id: &str,
    mode: i32,
    recorded: i64,
) -> Result<(), String> {
    let mode = match mode {
        0 => RendezvousGuidanceMode::Manual,
        1 => RendezvousGuidanceMode::Approach,
        2 => RendezvousGuidanceMode::MatchVelocity,
        _ => return Err("Unknown rendezvous guidance mode".into()),
    };
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?
        .runtime
        .set_rendezvous_guidance(ship_id, mode, recorded)
        .map_err(|e| format!("Could not change rendezvous guidance: {e:?}"))?;
    Ok(())
}

pub(super) fn rendezvous_target_id(
    handle: SlRuntimeHandle,
    ship_id: &str,
) -> Result<Option<String>, String> {
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    Ok(runtime
        .rendezvous_flight_telemetry(ship_id)
        .map(|telemetry| telemetry.target_body_id))
}

pub(super) fn rendezvous_sample(
    handle: SlRuntimeHandle,
    ship_id: &str,
) -> Result<Option<[f64; RENDEZVOUS_SAMPLE_LEN]>, String> {
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    let Some(t) = runtime.rendezvous_flight_telemetry(ship_id) else {
        return Ok(None);
    };
    let mode = match t.guidance_mode {
        RendezvousGuidanceMode::Manual => 0.0,
        RendezvousGuidanceMode::Approach => 1.0,
        RendezvousGuidanceMode::MatchVelocity => 2.0,
    };
    let status = match t.status {
        RendezvousStatus::Manual => 0.0,
        RendezvousStatus::Approaching => 1.0,
        RendezvousStatus::VelocityMatching => 2.0,
        RendezvousStatus::Holding => 3.0,
        RendezvousStatus::KeepOut => 4.0,
        RendezvousStatus::OutOfFuel => 5.0,
        RendezvousStatus::OutOfRange => 6.0,
        RendezvousStatus::TargetUnavailable => 7.0,
    };
    let values = [
        1.0,
        t.epoch_seconds,
        t.elapsed_seconds,
        mode,
        status,
        if t.target_available { 1.0 } else { 0.0 },
        t.range_m,
        t.closing_speed_mps,
        t.relative_speed_mps,
        t.position_target_m.x,
        t.position_target_m.y,
        t.position_target_m.z,
        t.velocity_target_mps.x,
        t.velocity_target_mps.y,
        t.velocity_target_mps.z,
        t.target_direction_body.x,
        t.target_direction_body.y,
        t.target_direction_body.z,
        t.keep_out_distance_m,
        t.stand_off_distance_m,
        t.stand_off_error_m,
        t.closest_observed_range_m,
        t.closest_observed_epoch_seconds,
        t.propellant_used_kg,
        t.remaining_propellant_kg,
        if t.stand_off_achieved { 1.0 } else { 0.0 },
        if t.keep_out_entered { 1.0 } else { 0.0 },
    ];
    if values.iter().any(|value| !value.is_finite()) {
        return Err("Non-finite rendezvous telemetry".into());
    }
    Ok(Some(values))
}

pub(super) fn set_control(
    handle: SlRuntimeHandle,
    ship_id: &str,
    values: &[f64],
    recorded: i64,
) -> Result<(), String> {
    let control = control_from_values(values)?;
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let session = registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?;
    session
        .runtime
        .apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId(ship_id.into()),
                control,
            },
            recorded,
        )
        .map_err(|e| format!("Could not apply flight control: {e:?}"))?;
    Ok(())
}

pub(super) fn sample(
    handle: SlRuntimeHandle,
    ship_id: &str,
) -> Result<Option<[f64; FLIGHT_SAMPLE_LEN]>, String> {
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    let Some(ship) = runtime.spacecraft_by_id(ship_id) else {
        return Ok(None);
    };
    let t = runtime
        .flight_telemetry(ship_id)
        .ok_or("The spacecraft reference body is unavailable")?;
    let orbit = t.orbit;
    let mode = match ship.control.attitude_mode {
        AttitudeMode::Manual => 0.0,
        AttitudeMode::Hold => 1.0,
        AttitudeMode::Prograde => 2.0,
        AttitudeMode::Retrograde => 3.0,
        AttitudeMode::Normal => 4.0,
        AttitudeMode::AntiNormal => 5.0,
    };
    let firing = if ship.propellant_mass_kg > 0.0 {
        ship.control.throttle
    } else {
        0.0
    };
    let full_burn = if t.main_engine_mass_flow_kgps > 0.0 {
        ship.propellant_mass_kg / t.main_engine_mass_flow_kgps
    } else {
        0.0
    };
    let result = [
        1.0,
        t.epoch_seconds,
        t.revision as f64,
        ship.dry_mass_kg,
        ship.propellant_mass_kg,
        ship.mass_kg(),
        t.main_engine_max_thrust_n,
        ship.control.throttle,
        t.remaining_delta_v_mps,
        t.altitude_m,
        t.speed_mps,
        t.radial_speed_mps,
        orbit.map_or(0.0, |o| o.periapsis_radius_m - t.reference_radius_m),
        orbit
            .and_then(|o| o.apoapsis_radius_m)
            .map_or(0.0, |r| r - t.reference_radius_m),
        orbit.map_or(0.0, |o| o.eccentricity),
        orbit.map_or(0.0, |o| o.inclination_radians),
        orbit.and_then(|o| o.period_seconds).unwrap_or(0.0),
        ship.attitude.x,
        ship.attitude.y,
        ship.attitude.z,
        ship.attitude.w,
        ship.angular_velocity_body_radps.x,
        ship.angular_velocity_body_radps.y,
        ship.angular_velocity_body_radps.z,
        mode,
        if ship.propellant_mass_kg > 0.0 {
            ship.engines
                .iter()
                .map(|e| e.max_mass_flow_kgps * e.demand(ship.control))
                .sum()
        } else {
            0.0
        },
        full_burn,
        t.moon_distance_m.unwrap_or(-1.0),
        t.relative_position_m.x,
        t.relative_position_m.y,
        t.relative_position_m.z,
        t.relative_velocity_mps.x,
        t.relative_velocity_mps.y,
        t.relative_velocity_mps.z,
        t.reference_radius_m,
        if orbit.is_some() { 1.0 } else { 0.0 },
        if orbit.and_then(|o| o.apoapsis_radius_m).is_some() {
            1.0
        } else {
            0.0
        },
        ship.control.translation_body.x,
        ship.control.translation_body.y,
        ship.control.translation_body.z,
        ship.control.rotation_body.x,
        ship.control.rotation_body.y,
        ship.control.rotation_body.z,
        t.main_engine_max_thrust_n * firing / ship.mass_kg(),
    ];
    if result.iter().any(|x| !x.is_finite()) {
        return Err("Non-finite flight telemetry".into());
    }
    Ok(Some(result))
}

pub(super) fn lunar_sample(
    handle: SlRuntimeHandle,
    ship_id: &str,
) -> Result<Option<[f64; LUNAR_SAMPLE_LEN]>, String> {
    use solarlab_runtime::{LunarMissionOutcome, LunarMissionPhase};
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    let Some(t) = runtime.lunar_mission_telemetry(ship_id) else {
        return Ok(None);
    };
    let phase = match t.phase {
        LunarMissionPhase::Ready => 0.0,
        LunarMissionPhase::PoweredFlight => 1.0,
        LunarMissionPhase::Coast => 2.0,
        LunarMissionPhase::LunarApproach => 3.0,
        LunarMissionPhase::LunarFlyby => 4.0,
        LunarMissionPhase::LunarSurfaceCrossing => 5.0,
        LunarMissionPhase::EarthSurfaceCrossing => 6.0,
        LunarMissionPhase::EscapingEarth => 7.0,
        LunarMissionPhase::Missed => 8.0,
    };
    let outcome = match t.outcome {
        LunarMissionOutcome::InProgress => 0.0,
        LunarMissionOutcome::FlybyAchieved => 1.0,
        LunarMissionOutcome::LunarSurfaceCrossing => 2.0,
        LunarMissionOutcome::EarthSurfaceCrossing => 3.0,
        LunarMissionOutcome::Missed => 4.0,
    };
    let values = [
        1.0,
        t.epoch_seconds,
        t.launch_epoch_seconds,
        t.elapsed_seconds,
        phase,
        outcome,
        t.departure_burn_seconds,
        t.main_engine_full_throttle_seconds,
        t.departure_remaining_seconds,
        if t.guided_burn_active { 1.0 } else { 0.0 },
        t.guided_burn_remaining_seconds,
        t.earth_altitude_m,
        t.moon_altitude_m,
        t.moon_range_rate_mps,
        t.moon_relative_speed_mps,
        t.closest_observed_moon_altitude_m,
        t.closest_observed_epoch_seconds,
        t.remaining_propellant_kg,
        if t.surface_response_modelled {
            1.0
        } else {
            0.0
        },
        solarlab_runtime::LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS[0],
        solarlab_runtime::LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS[1],
    ];
    if values.iter().any(|value| !value.is_finite()) {
        return Err("Non-finite lunar mission telemetry".into());
    }
    Ok(Some(values))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_rendezvous_commit_publishes_current_mesh_poses_through_cached_scene_ffi() {
        struct EncounterScene {
            revision: String,
            epoch: f64,
            frame: u64,
            origin: SlVector3d,
            bodies: [SlVulkanBodyInstance; 2],
        }
        fn export(handle: SlRuntimeHandle) -> EncounterScene {
            let packet = sl_v2_session_export_vulkan_scene(handle);
            assert_eq!(packet.result.code, SlStatusCode::Ok);
            let view =
                sl_v2_vulkan_scene_packet_buffer(packet.handle, SlVulkanSceneBufferKind::BodyInstances);
            assert_eq!(view.result.code, SlStatusCode::Ok);
            assert_eq!(
                view.view.stride_bytes as usize,
                size_of::<SlVulkanBodyInstance>()
            );
            // Both views remain owned by the live packet until release below.
            let bodies = unsafe {
                std::slice::from_raw_parts(
                    view.view.data.cast::<SlVulkanBodyInstance>(),
                    view.view.element_count as usize,
                )
            };
            let revision = unsafe {
                std::slice::from_raw_parts(
                    packet.info.scene_revision.data,
                    packet.info.scene_revision.length as usize,
                )
            };
            let result = EncounterScene {
                revision: std::str::from_utf8(revision).unwrap().to_owned(),
                epoch: packet.info.epoch_seconds,
                frame: packet.info.diagnostics.frame_number,
                origin: packet.info.camera.frame_origin_m,
                bodies: ["flight.odyssey", "rendezvous.station"].map(|id| {
                    *bodies
                        .iter()
                        .find(|body| decode_identifier(&body.body_id, body.body_id_len).unwrap() == id)
                        .unwrap()
                }),
            };
            assert_eq!(
                sl_v2_vulkan_scene_packet_release(packet.handle).code,
                SlStatusCode::Ok
            );
            result
        }
        fn stable_revision(scene: &EncounterScene) -> &str {
            scene.revision.split("|diag:").next().unwrap()
        }

        let created = sl_v2_session_create(super::super::tests::new_params(
            "lab.earth-moon",
            "rendezvous-scene-boundary",
        ));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let handle = created.handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        start_rendezvous(handle, "flight.odyssey", 1).unwrap();
        set_rendezvous_guidance(handle, "flight.odyssey", 1, 2).unwrap();
        let before = export(handle); // Warm the production adapter cache before integrating.
        let initial = rendezvous_sample(handle, "flight.odyssey")
            .unwrap()
            .unwrap();
        assert!((initial[6] - 1_000.0).abs() < 0.01);

        let query = sl_v2_session_query_advance(handle);
        let mut operation = sl_v2_session_begin_advance(
            handle,
            17,
            23,
            query.generation,
            query.current_next_ordinal,
            650.0,
            3,
        );
        assert_eq!(operation.result.code, SlStatusCode::Ok);
        operation = sl_v2_session_continue_advance(handle, operation.generation, 0);
        assert_eq!(operation.result.code, SlStatusCode::Ok);
        assert_eq!(
            operation.state, 1,
            "One bounded continuation cannot commit 650 s of guidance"
        );
        let staged = export(handle);
        assert_eq!(staged.epoch, before.epoch);
        assert_eq!(
            staged.bodies, before.bodies,
            "Uncommitted solver poses cannot leak into a scene"
        );
        assert_eq!(stable_revision(&staged), stable_revision(&before));
        while operation.state == 1 {
            operation =
                sl_v2_session_continue_advance(handle, operation.generation, operation.completed_steps);
            assert_eq!(operation.result.code, SlStatusCode::Ok);
        }
        assert_eq!(operation.state, 2);
        let after = export(handle);
        let telemetry = rendezvous_sample(handle, "flight.odyssey")
            .unwrap()
            .unwrap();
        assert_eq!(after.epoch, 650.0);
        assert_eq!(after.epoch, telemetry[1]);
        assert_eq!(after.frame, 39_000);
        assert_ne!(stable_revision(&before), stable_revision(&after));
        assert_eq!(after.bodies[0].mesh_kind, 1);
        assert_eq!(after.bodies[1].mesh_kind, 2);
        {
            let registry = registry().lock().unwrap();
            let runtime = &registry.get(handle).unwrap().runtime;
            for (id, packed) in ["flight.odyssey", "rendezvous.station"]
                .iter()
                .zip(after.bodies.iter())
            {
                let truth = runtime.body_by_id(id).unwrap().position_m;
                assert_eq!(
                    packed.mesh_position_from_origin_m,
                    [
                        truth.x - after.origin.x,
                        truth.y - after.origin.y,
                        truth.z - after.origin.z,
                    ],
                    "Scene f64 mesh coordinates must equal authoritative current {id} position"
                );
            }
        }
        let range = after.bodies[0]
            .mesh_position_from_origin_m
            .iter()
            .zip(after.bodies[1].mesh_position_from_origin_m.iter())
            .map(|(ship, station)| (ship - station).powi(2))
            .sum::<f64>()
            .sqrt();
        assert!((range - telemetry[6]).abs() < 1e-4);
        assert!(
            (range - 75.0).abs() < 1.0,
            "Exported encounter range is {range}"
        );

        // Playback state comes from snapshot_summary, not a fabricated motion
        // endpoint: pausing an unchanged physical scene must not rewrite its poses.
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::ResumePlayback, 4)
            .unwrap();
        let playing = export(handle);
        assert_eq!(sl_v2_session_snapshot_summary(handle).summary.paused, 0);
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::PausePlayback, 5)
            .unwrap();
        let paused = export(handle);
        assert_eq!(sl_v2_session_snapshot_summary(handle).summary.paused, 1);
        assert_eq!(playing.bodies, after.bodies);
        assert_eq!(paused.bodies, after.bodies);
        assert_eq!(stable_revision(&playing), stable_revision(&paused));
        assert_eq!(paused.epoch, after.epoch);
        println!(
            "Rendezvous committed mesh range={range:.9} m telemetry={:.9} m epoch={}",
            telemetry[6], after.epoch
        );
        assert_eq!(sl_v2_session_destroy(handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn rendezvous_boundary_preserves_guidance_target_and_future_motion() {
        let created = sl_v2_session_create(super::super::tests::new_params(
            "lab.earth-moon",
            "rendezvous-boundary",
        ));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let handle = created.handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        start_rendezvous(handle, "flight.odyssey", 1).unwrap();
        let ready = rendezvous_sample(handle, "flight.odyssey")
            .unwrap()
            .unwrap();
        assert_eq!(ready[0], 1.0);
        assert_eq!(ready[3], 0.0);
        assert_eq!(ready[5], 1.0);
        assert!((ready[6] - 1_000.0).abs() < 0.01);
        assert_eq!(ready[19], 75.0);
        assert_eq!(
            rendezvous_target_id(handle, "flight.odyssey")
                .unwrap()
                .as_deref(),
            Some("rendezvous.station")
        );
        let before_invalid = super::super::persistence::export_save(handle).unwrap();
        assert!(set_rendezvous_guidance(handle, "flight.odyssey", 3, 2).is_err());
        assert_eq!(
            before_invalid,
            super::super::persistence::export_save(handle).unwrap()
        );
        set_rendezvous_guidance(handle, "flight.odyssey", 1, 3).unwrap();
        advance_rendezvous_boundary(handle, 15.0, 4);
        let active = rendezvous_sample(handle, "flight.odyssey")
            .unwrap()
            .unwrap();
        assert_eq!(active[3], 1.0);
        assert!(
            active[24] < ready[24],
            "Guidance must consume real propellant"
        );
        let saved = super::super::persistence::export_save(handle).unwrap();
        assert!(start_rendezvous(handle, "second-chaser", 5).is_err());
        assert_eq!(
            saved,
            super::super::persistence::export_save(handle).unwrap()
        );
        let restored = super::super::persistence::import_save(handle, &saved);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        assert_eq!(
            active,
            rendezvous_sample(restored.handle, "flight.odyssey")
                .unwrap()
                .unwrap()
        );
        assert_eq!(
            rendezvous_target_id(handle, "flight.odyssey").unwrap(),
            rendezvous_target_id(restored.handle, "flight.odyssey").unwrap()
        );
        for session in [handle, restored.handle] {
            advance_rendezvous_boundary(session, 30.0, 6);
        }
        assert_eq!(
            rendezvous_sample(handle, "flight.odyssey").unwrap(),
            rendezvous_sample(restored.handle, "flight.odyssey").unwrap()
        );
        set_control(restored.handle, "flight.odyssey", &[0.0; 8], 7).unwrap();
        assert_eq!(
            rendezvous_sample(restored.handle, "flight.odyssey")
                .unwrap()
                .unwrap()[3],
            0.0
        );
        assert_eq!(
            sample(restored.handle, "flight.odyssey").unwrap().unwrap()[7],
            0.0
        );
        sl_v2_session_destroy(restored.handle);
        sl_v2_session_destroy(handle);
    }

    fn advance_rendezvous_boundary(handle: SlRuntimeHandle, seconds: f64, recorded: i64) {
        let mut registry = registry().lock().unwrap();
        let runtime = &mut registry.get_mut(handle).unwrap().runtime;
        let ordinal = runtime.next_command_ordinal();
        let expected_generation = runtime.query_advance().map_or(0, |op| op.generation);
        let generation = runtime
            .begin_advance(
                [ordinal, ordinal + 1],
                expected_generation,
                ordinal,
                seconds,
                recorded,
            )
            .unwrap()
            .generation;
        let mut completed = 0;
        loop {
            let (operation, _) = runtime.continue_advance(generation, completed).unwrap();
            completed = operation.completed_steps;
            if operation.state == solarlab_runtime::AdvanceState::Committed {
                break;
            }
        }
    }

    #[test]
    fn lunar_boundary_preserves_mission_and_native_cutoff_across_save() {
        let created =
            sl_v2_session_create(super::super::tests::new_params("lab.earth-moon", "lunar"));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let handle = created.handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        start_lunar(handle, "flight.odyssey", 1).unwrap();
        let ready = lunar_sample(handle, "flight.odyssey").unwrap().unwrap();
        assert_eq!(ready[0], 1.0);
        assert_eq!(ready[4], 0.0);
        assert_eq!(ready[6], 300.0);
        assert_eq!(ready[8], 300.0);
        assert_eq!(ready[18], 0.0);
        begin_lunar_departure(handle, "flight.odyssey", 2).unwrap();
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 1.25,
                },
                3,
            )
            .unwrap();
        let burning = lunar_sample(handle, "flight.odyssey").unwrap().unwrap();
        assert_eq!(burning[4], 1.0);
        assert!((burning[7] - 1.25).abs() < 1e-10);
        assert_eq!(burning[9], 1.0);
        assert!((burning[10] - 298.75).abs() < 1e-8);
        let saved = super::super::persistence::export_save(handle).unwrap();
        // Starting a new mission on an evolved world must not mutate it.
        assert!(start_lunar(handle, "another-ship", 4).is_err());
        assert_eq!(
            saved,
            super::super::persistence::export_save(handle).unwrap()
        );
        let restored = super::super::persistence::import_save(handle, &saved);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        assert_eq!(
            burning,
            lunar_sample(restored.handle, "flight.odyssey")
                .unwrap()
                .unwrap()
        );
        // A repeated user request resumes the remaining programme, not another 300 s.
        begin_lunar_departure(restored.handle, "flight.odyssey", 5).unwrap();
        {
            let mut registry = registry().lock().unwrap();
            let runtime = &mut registry.get_mut(restored.handle).unwrap().runtime;
            let ordinal = runtime.next_command_ordinal();
            let generation = runtime
                .begin_advance([ordinal, ordinal + 1], 0, ordinal, 310.0, 6)
                .unwrap()
                .generation;
            let mut completed = 0;
            loop {
                let (operation, _) = runtime.continue_advance(generation, completed).unwrap();
                completed = operation.completed_steps;
                if operation.state == solarlab_runtime::AdvanceState::Committed {
                    break;
                }
            }
        }
        let coast = lunar_sample(restored.handle, "flight.odyssey")
            .unwrap()
            .unwrap();
        assert!((coast[7] - 300.0).abs() < 1e-7);
        assert!(coast[8].abs() < 1e-8);
        assert_eq!(coast[9], 0.0);
        assert_eq!(
            sample(restored.handle, "flight.odyssey").unwrap().unwrap()[7],
            0.0
        );
        sl_v2_session_destroy(restored.handle);
        sl_v2_session_destroy(handle);
    }

    #[test]
    fn radial_flight_reports_speed_without_an_orbital_plane() {
        let handle =
            sl_v2_session_create(super::super::tests::new_params("lab.earth-moon", "radial"))
                .handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        start(handle, "flight.odyssey", 2).unwrap();
        {
            let mut registry = registry().lock().unwrap();
            let r = &mut registry.get_mut(handle).unwrap().runtime;
            let body = r.body_by_id("flight.odyssey").unwrap().clone();
            let earth = r.body_by_id("earth").unwrap().clone();
            r.apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: body.body_id,
                    position_m: body.position_m,
                    velocity_mps: Vector3d {
                        x: earth.velocity_mps.x + 123.0,
                        y: earth.velocity_mps.y,
                        z: earth.velocity_mps.z,
                    },
                },
                3,
            )
            .unwrap();
        }
        let state = sample(handle, "flight.odyssey").unwrap().unwrap();
        assert_eq!(state[35], 0.0);
        assert!((state[10] - 123.0).abs() < 1e-9);
        assert!((state[11] - 123.0).abs() < 1e-9);
        sl_v2_session_destroy(handle);
    }

    #[test]
    fn malformed_controls_are_rejected_before_mutation() {
        assert!(control_from_values(&[0.0; 7]).is_err());
        for bad in [f64::NAN, f64::INFINITY, -0.1, 1.1] {
            let mut v = [0.0; 8];
            v[0] = bad;
            assert!(control_from_values(&v).is_err());
        }
        let mut v = [0.0; 8];
        v[7] = 2.5;
        assert!(control_from_values(&v).is_err());
        v[7] = 2.0;
        assert!(control_from_values(&v).is_ok());
    }
    #[test]
    fn flight_boundary_integrates_live_world_and_preserves_invalid_request() {
        let created =
            sl_v2_session_create(super::super::tests::new_params("lab.earth-moon", "main"));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let handle = created.handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        start(handle, "flight.odyssey", 2).unwrap();
        let before = sample(handle, "flight.odyssey").unwrap().unwrap();
        assert!((before[9] - 400_000.0).abs() < 0.01);
        assert_eq!(before[35], 1.0);
        set_control(
            handle,
            "flight.odyssey",
            &[0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0],
            3,
        )
        .unwrap();
        registry()
            .lock()
            .unwrap()
            .get_mut(handle)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 0.5 }, 4)
            .unwrap();
        let after = sample(handle, "flight.odyssey").unwrap().unwrap();
        assert!(after[4] < before[4]);
        let saved = super::super::persistence::export_save(handle).unwrap();
        assert!(set_control(handle, "flight.odyssey", &[f64::NAN; 8], 5).is_err());
        assert_eq!(
            saved,
            super::super::persistence::export_save(handle).unwrap()
        );
        let restored = super::super::persistence::import_save(handle, &saved);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        let restored_values = sample(restored.handle, "flight.odyssey").unwrap().unwrap();
        for index in [
            1, 3, 4, 5, 8, 9, 10, 17, 18, 19, 20, 21, 22, 23, 28, 29, 30, 31, 32, 33,
        ] {
            assert_eq!(after[index], restored_values[index], "flight field {index}");
        }
        assert_eq!(
            sl_v2_session_destroy(restored.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(sl_v2_session_destroy(handle).code, SlStatusCode::Ok);
        assert!(sample(handle, "flight.odyssey").is_err());
    }
}
