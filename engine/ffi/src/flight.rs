//! Bounded flight requests. Runtime owns forces and state transitions.
use super::*;
use solarlab_domain::{AttitudeMode, FlightControl};
pub(super) const FLIGHT_SAMPLE_LEN: usize = 44;

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
    session.runtime = staged;
    Ok(())
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
        orbit.map_or(0.0, |o| o.speed_mps),
        orbit.map_or(0.0, |o| o.radial_speed_mps),
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

#[cfg(test)]
mod tests {
    use super::*;
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
