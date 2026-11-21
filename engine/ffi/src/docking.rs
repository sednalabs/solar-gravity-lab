//! Ownership-only docking transport. Numerical observations come from Rust.
use super::*;
use solarlab_domain::docking::{
    DOCKING_CAPTURE_GAP_M, DOCKING_CAPTURE_LATERAL_M, DOCKING_CAPTURE_SPEED_MPS,
};
use solarlab_domain::DockingGuidanceMode;

pub(super) const DOCKING_SAMPLE_LEN: usize = 25;

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
    let mut staged = session.runtime.clone();
    staged
        .apply_command(WorldCommand::PausePlayback, recorded)
        .map_err(|e| format!("Could not pause docking flight: {e:?}"))?;
    staged
        .spawn_docking_flight(ship_id, recorded)
        .map_err(|e| format!("Could not prepare docking flight: {e:?}"))?;
    super::flight::configure_flight(&mut staged, ship_id, recorded)?;
    session.runtime = staged;
    Ok(())
}

pub(super) fn set_guidance(
    handle: SlRuntimeHandle,
    ship_id: &str,
    mode: i32,
    recorded: i64,
) -> Result<(), String> {
    let mode = match mode {
        0 => DockingGuidanceMode::Manual,
        1 => DockingGuidanceMode::Approach,
        _ => return Err("Unknown docking guidance mode".into()),
    };
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &mut registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    runtime.set_docking_guidance(ship_id, mode, recorded).map_err(|error| {
        if error == RuntimeError::AdvanceBusy {
            return "Finish or cancel the current time advance before changing docking guidance".to_owned();
        }
        let Some(t) = runtime.docking_flight_telemetry(ship_id) else {
            return "The docking spacecraft is no longer available".to_owned();
        };
        if t.attached { "Choose Undock before requesting another approach" }
        else if t.observation.is_none() { "The station is no longer available" }
        else if t.remaining_propellant_kg <= 0.0 { "The spacecraft has no RCS propellant remaining" }
        else { "The ports are not ready for this approach. Release thrust and back away slowly before trying Dock again" }.to_owned()
    })?;
    Ok(())
}

pub(super) fn undock(handle: SlRuntimeHandle, ship_id: &str, recorded: i64) -> Result<(), String> {
    let mut registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    registry
        .get_mut(handle)
        .ok_or("The experiment is no longer open")?
        .runtime
        .undock_spacecraft(ship_id, recorded)
        .map_err(|e| {
            if e == RuntimeError::AdvanceBusy {
                "Finish or cancel the current time advance before undocking".to_owned()
            } else {
                "The spacecraft has no attachment to release".to_owned()
            }
        })?;
    Ok(())
}

pub(super) fn target_id(handle: SlRuntimeHandle, ship_id: &str) -> Result<Option<String>, String> {
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    Ok(registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime
        .docking_flight_telemetry(ship_id)
        .map(|t| t.target_body_id))
}

/// Schema 1, exactly 25 f64s, paired with a separate stable UTF-8 target ID.
pub(super) fn sample(
    handle: SlRuntimeHandle,
    ship_id: &str,
) -> Result<Option<[f64; DOCKING_SAMPLE_LEN]>, String> {
    let registry = registry()
        .lock()
        .map_err(|_| "Runtime lock is unavailable")?;
    let runtime = &registry
        .get(handle)
        .ok_or("The experiment is no longer open")?
        .runtime;
    let Some(t) = runtime.docking_flight_telemetry(ship_id) else {
        return Ok(None);
    };
    let o = t.observation;
    let position = o.map_or(Vector3d::default(), |o| o.position_port_m);
    let velocity = o.map_or(Vector3d::default(), |o| o.velocity_port_mps);
    let flag = |b| if b { 1.0 } else { 0.0 };
    let values = [
        1.0,
        t.epoch_seconds,
        t.guidance_mode as u32 as f64,
        t.status as u32 as f64,
        flag(o.is_some()),
        flag(t.capture_armed),
        flag(t.attached),
        o.map_or(0.0, |o| o.port_distance_m),
        o.map_or(0.0, |o| o.closing_speed_mps),
        o.map_or(0.0, |o| o.relative_speed_mps),
        position.x,
        position.y,
        position.z,
        velocity.x,
        velocity.y,
        velocity.z,
        o.map_or(0.0, |o| o.alignment_error_rad),
        o.map_or(0.0, |o| o.relative_angular_speed_radps),
        flag(o.is_some_and(|o| o.capture_ready)),
        flag(t.unsafe_contact),
        t.remaining_propellant_kg,
        t.centre_range_m,
        DOCKING_CAPTURE_LATERAL_M,
        DOCKING_CAPTURE_GAP_M,
        DOCKING_CAPTURE_SPEED_MPS,
    ];
    if values.iter().any(|v| !v.is_finite()) {
        return Err("Non-finite docking telemetry".into());
    }
    Ok(Some(values))
}

#[cfg(test)]
mod tests {
    use super::*;
    const SHIP: &str = "flight.odyssey";
    fn advance(handle: SlRuntimeHandle, seconds: f64) {
        let mut registry = registry().lock().unwrap();
        let runtime = &mut registry.get_mut(handle).unwrap().runtime;
        let ordinal = runtime.next_command_ordinal();
        let previous = runtime.query_advance().map_or(0, |o| o.generation);
        let generation = runtime
            .begin_advance([ordinal, ordinal + 1], previous, ordinal, seconds, 0)
            .unwrap()
            .generation;
        let mut completed = 0;
        loop {
            let (op, _) = runtime.continue_advance(generation, completed).unwrap();
            completed = op.completed_steps;
            if op.state == solarlab_runtime::AdvanceState::Committed {
                break;
            }
        }
    }
    #[test]
    fn docking_transport_preserves_target_constraint_and_release_across_save() {
        let created = sl_v2_session_create(super::super::tests::new_params(
            "lab.earth-moon",
            "docking-boundary",
        ));
        assert_eq!(created.result.code, SlStatusCode::Ok);
        let h = created.handle;
        registry()
            .lock()
            .unwrap()
            .get_mut(h)
            .unwrap()
            .runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        start(h, SHIP, 1).unwrap();
        let ready = sample(h, SHIP).unwrap().unwrap();
        assert_eq!(ready.len(), 25);
        assert_eq!(ready[0], 1.0);
        assert_eq!(ready[4], 1.0);
        assert!((ready[21] - 30.0).abs() < 1e-6);
        assert!((ready[12] - 22.44).abs() < 5e-5);
        assert_eq!(
            target_id(h, SHIP).unwrap().as_deref(),
            Some(solarlab_runtime::DOCKING_STATION_BODY_ID)
        );
        let before = super::super::persistence::export_save(h).unwrap();
        assert!(set_guidance(h, SHIP, 2, 2).is_err());
        assert!(undock(h, SHIP, 2).is_err());
        assert_eq!(super::super::persistence::export_save(h).unwrap(), before);
        set_guidance(h, SHIP, 1, 3).unwrap();
        assert!(
            registry()
                .lock()
                .unwrap()
                .get(h)
                .unwrap()
                .runtime
                .snapshot()
                .playback
                .paused
        );
        for _ in 0..200 {
            advance(h, 1.0);
            if sample(h, SHIP).unwrap().unwrap()[6] == 1.0 {
                break;
            }
        }
        let docked = sample(h, SHIP).unwrap().unwrap();
        assert_eq!(docked[3], 4.0);
        assert_eq!(docked[6], 1.0);
        assert_eq!(docked[19], 0.0);
        assert!(docked[20] < ready[20]);
        let saved = super::super::persistence::export_save(h).unwrap();
        let restored = super::super::persistence::import_save(h, &saved);
        assert_eq!(restored.result.code, SlStatusCode::Ok);
        assert_eq!(sample(restored.handle, SHIP).unwrap(), Some(docked));
        assert_eq!(
            target_id(restored.handle, SHIP).unwrap(),
            target_id(h, SHIP).unwrap()
        );
        for handle in [h, restored.handle] {
            advance(handle, 10.0);
        }
        assert_eq!(
            sample(h, SHIP).unwrap(),
            sample(restored.handle, SHIP).unwrap()
        );
        let mut before_release = super::super::flight::sample(restored.handle, SHIP)
            .unwrap()
            .unwrap();
        undock(restored.handle, SHIP, 4).unwrap();
        assert_eq!(sample(restored.handle, SHIP).unwrap().unwrap()[6], 0.0);
        before_release[2] += 1.0; // The release is a recorded command; all physical values remain equal.
        assert_eq!(
            super::super::flight::sample(restored.handle, SHIP).unwrap(),
            Some(before_release)
        );
        assert!(
            registry()
                .lock()
                .unwrap()
                .get(restored.handle)
                .unwrap()
                .runtime
                .snapshot()
                .playback
                .paused
        );
        assert_eq!(
            sl_v2_session_destroy(restored.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(sl_v2_session_destroy(h).code, SlStatusCode::Ok);
        assert!(sample(h, SHIP).is_err());
    }
}
