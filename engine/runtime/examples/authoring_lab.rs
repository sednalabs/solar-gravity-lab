//! Native authoring validation and flight demonstration, with no application UI.
//! `cargo run -p solarlab-runtime --example authoring_lab -- SCENARIO --burn 10 --coast 60`
use solarlab_domain::{AttitudeMode, BodyId, FlightControl};
use solarlab_hardware::HardwareProfile;
use solarlab_runtime::{AdvanceState, WorldCommand, WorldRuntime};
use std::{error::Error, path::PathBuf};
type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn advance(world: &mut WorldRuntime, seconds: f64) -> Result<()> {
    if seconds == 0.0 {
        return Ok(());
    }
    let ordinal = world.next_command_ordinal();
    let previous = world.query_advance().map_or(0, |a| a.generation);
    let generation = world
        .begin_advance([ordinal, ordinal + 100], previous, ordinal, seconds, 0)
        .map_err(|e| format!("Cannot start advance: {e:?}"))?
        .generation;
    let mut completed = 0;
    loop {
        let (operation, _) = world
            .continue_advance(generation, completed)
            .map_err(|e| format!("Cannot advance: {e:?}"))?;
        completed = operation.completed_steps;
        if operation.state == AdvanceState::Committed {
            return Ok(());
        }
    }
}
fn mode(value: &str) -> Result<AttitudeMode> {
    Ok(match value {
        "manual" => AttitudeMode::Manual, "hold" => AttitudeMode::Hold,
        "prograde" => AttitudeMode::Prograde, "retrograde" => AttitudeMode::Retrograde,
        "normal" => AttitudeMode::Normal, "antinormal" => AttitudeMode::AntiNormal,
        "radial-out" => AttitudeMode::RadialOut, "radial-in" => AttitudeMode::RadialIn,
        _ => return Err("Unknown attitude; use manual, hold, prograde, retrograde, normal, antinormal, radial-out or radial-in".into()),
    })
}
fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let mut source = None;
    let mut additions = Vec::new();
    let mut selected = None;
    let mut burn = 0.0_f64;
    let mut coast = 0.0_f64;
    let mut throttle = 1.0_f64;
    let mut attitude = AttitudeMode::Prograde;
    let mut save_path = None;
    let mut state_path = None;
    while let Some(arg) = args.next() {
        if arg == "--help" {
            println!("authoring_lab [SCENARIO.json] [--add CRAFT.json] [--ship ID] [--burn SECONDS] [--throttle 0..1] [--attitude MODE] [--coast SECONDS] [--save EXPERIMENT.json] [--state STATE.json]\n\nOmit flight options to validate only. With no file, loads the supplied orbital workshop. All state and flight use the native authoritative WorldRuntime. Every run checks that save/reload preserves its coast future exactly.");
            return Ok(());
        }
        if !arg.starts_with('-') && source.is_none() {
            source = Some(PathBuf::from(arg));
            continue;
        }
        let value = args.next().ok_or("Option requires a value")?;
        match arg.as_str() {
            "--add" => additions.push(PathBuf::from(value)),
            "--ship" => selected = Some(value),
            "--burn" => burn = value.parse()?,
            "--coast" => coast = value.parse()?,
            "--throttle" => throttle = value.parse()?,
            "--attitude" => attitude = mode(&value)?,
            "--save" => save_path = Some(PathBuf::from(value)),
            "--state" => state_path = Some(PathBuf::from(value)),
            _ => return Err(format!("Unknown option: {arg}").into()),
        }
    }
    if !burn.is_finite()
        || !(0.0..=3600.0).contains(&burn)
        || !coast.is_finite()
        || !(0.0..=86400.0).contains(&coast)
        || !throttle.is_finite()
        || !(0.0..=1.0).contains(&throttle)
    {
        return Err("Require burn 0..3600 s, coast 0..86400 s and throttle 0..1".into());
    }
    let bytes = source.map(std::fs::read).transpose()?.unwrap_or_else(|| {
        include_bytes!("../../data/authoring/orbital-workshop.scenario.json").to_vec()
    });
    let mut world =
        WorldRuntime::import_authored_scenario(&bytes, HardwareProfile::offline_reference(), 0)?;
    for addition in additions {
        world.add_authored_spacecraft(&std::fs::read(addition)?, 0)?;
    }
    let initial = world.snapshot();
    let selected = selected
        .or_else(|| {
            initial
                .observer
                .focus_body_id
                .filter(|id| world.spacecraft_by_id(&id.0).is_some())
                .map(|id| id.0)
        })
        .or_else(|| initial.spacecraft.first().map(|s| s.body_id.0.clone()));
    if selected
        .as_ref()
        .is_some_and(|id| world.spacecraft_by_id(id).is_none())
    {
        return Err("Selected --ship identity does not name a spacecraft in this scenario".into());
    }
    if burn > 0.0 {
        let id = selected
            .as_ref()
            .ok_or("The scenario contains no spacecraft to burn")?;
        world
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId(id.clone()),
                    control: FlightControl {
                        throttle,
                        attitude_mode: attitude,
                        ..FlightControl::default()
                    },
                },
                0,
            )
            .map_err(|e| format!("Cannot set flight controls: {e:?}"))?;
        advance(&mut world, burn)?;
        world
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId(id.clone()),
                    control: FlightControl {
                        attitude_mode: AttitudeMode::Manual,
                        ..FlightControl::default()
                    },
                },
                0,
            )
            .map_err(|e| format!("Cannot cut engine: {e:?}"))?;
    }
    let checkpoint = world.export_saved_session()?;
    let mut reopened =
        WorldRuntime::import_saved_session(&checkpoint, HardwareProfile::offline_reference())?;
    advance(&mut world, coast)?;
    advance(&mut reopened, coast)?;
    if world.snapshot().bodies != reopened.snapshot().bodies
        || world.snapshot().spacecraft != reopened.snapshot().spacecraft
    {
        return Err("Save/reload future differs from uninterrupted simulation".into());
    }
    let snapshot = world.snapshot();
    let scene = solarlab_runtime::extract_render_scene(&snapshot);
    let report = serde_json::json!({
        "format": "solarlab.authoring-report", "version": 1,
        "scenario_id": snapshot.scenario_id.0, "epoch_seconds": snapshot.epoch_seconds,
        "body_count": snapshot.bodies.len(), "spacecraft_count": snapshot.spacecraft.len(),
        "selected_body_id": selected,
        "save_resume_exact": true,
        "spacecraft": snapshot.spacecraft.iter().map(|ship| {
            let rendered = scene.bodies.iter().find(|body| body.body_id == ship.body_id).unwrap();
            let telemetry = world.flight_telemetry(&ship.body_id.0);
            serde_json::json!({"body_id": ship.body_id.0, "display_name": rendered.display_name,
                "mesh_kind": rendered.spacecraft.map(|s| s.mesh_kind), "remaining_propellant_kg": ship.propellant_mass_kg,
                "dry_mass_kg": ship.dry_mass_kg, "total_mass_kg": ship.mass_kg(),
                "delivered_delta_v_mps": ship.delivered_delta_v_mps, "altitude_m": telemetry.as_ref().map(|t| t.altitude_m),
                "flight": telemetry.as_ref().map(|t| serde_json::json!({
                    "reference_body_id": t.reference_body_id,
                    "relative_position_m": t.relative_position_m,
                    "relative_velocity_mps": t.relative_velocity_mps,
                    "speed_mps": t.speed_mps, "radial_speed_mps": t.radial_speed_mps,
                    "main_engine_max_thrust_n": t.main_engine_max_thrust_n,
                    "main_engine_mass_flow_kgps": t.main_engine_mass_flow_kgps,
                    "remaining_delta_v_mps": t.remaining_delta_v_mps,
                    "full_throttle_propellant_seconds": (t.main_engine_mass_flow_kgps > 0.0)
                        .then(|| ship.propellant_mass_kg / t.main_engine_mass_flow_kgps),
                    "orbit": t.orbit.map(|o| serde_json::json!({
                        "model": "instantaneous-two-body",
                        "eccentricity": o.eccentricity,
                        "inclination_rad": o.inclination_radians,
                        "periapsis_altitude_m": o.periapsis_radius_m - t.reference_radius_m,
                        "apoapsis_altitude_m": o.apoapsis_radius_m.map(|r| r - t.reference_radius_m),
                        "period_seconds": o.period_seconds,
                    })),
                })),
                "components": ship.authored})
        }).collect::<Vec<_>>()
    });
    if let Some(path) = save_path {
        std::fs::write(path, world.export_saved_session()?)?;
    }
    if let Some(path) = state_path {
        std::fs::write(
            path,
            serde_json::to_vec_pretty(&serde_json::json!({
                "format": "solarlab.state-observation", "version": 1, "epoch_seconds": snapshot.epoch_seconds,
                "bodies": snapshot.bodies, "spacecraft": snapshot.spacecraft,
            }))?,
        )?;
    }
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
