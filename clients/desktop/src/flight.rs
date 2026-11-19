use serde::Deserialize;
use serde_json::{json, Value};
use solarlab_domain::{
    AttitudeMode, BodyId, BranchId, CheckpointId, FlightControl, ScenarioId, SpacecraftState,
    TimelineSemantics, Vector3d,
};
use solarlab_hardware::HardwareProfile;
use solarlab_history::OrbitArchiveQuery;
use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_runtime::{FlightBurnRequest, RuntimeConfig, WorldCommand, WorldRuntime};
use std::time::Instant;

const SHIP: &str = "flight.odyssey";

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Action {
    Control {
        sequence: u64,
        control: FlightControl,
    },
    Cut {
        sequence: u64,
    },
    Heartbeat,
    Pause,
    Resume,
    Warp {
        rate: f64,
    },
    Select {
        body_id: String,
    },
    Checkpoint,
    Reset,
    Integrator {
        method: IntegratorKind,
    },
    Preview {
        duration: f64,
        throttle: f64,
        assist: AttitudeMode,
    },
    Prediction,
    Workshop,
}

pub struct FlightSession {
    pub world: WorldRuntime,
    pub selected: String,
    pub generation: u64,
    pub last_control_sequence: u64,
    pub last_heartbeat: Instant,
    pub notice: Option<String>,
    checkpoint: Option<CheckpointId>,
}

fn vector(v: Vector3d) -> [f64; 3] {
    [v.x, v.y, v.z]
}
fn difference(a: Vector3d, b: Vector3d) -> [f64; 3] {
    [a.x - b.x, a.y - b.y, a.z - b.z]
}
fn runtime_error(error: impl std::fmt::Debug) -> String {
    format!("{error:?}")
}

fn visual_asset(ship: &SpacecraftState) -> Value {
    let binding = ship.authored.as_ref().and_then(|a| a.visual_asset.as_ref());
    let record = if let Some(binding) = binding {
        solarlab_data::spacecraft_visual_asset(&binding.asset_id)
    } else {
        solarlab_data::SPACECRAFT_VISUAL_ASSETS
            .iter()
            .find(|asset| asset.mesh_kind == ship.appearance as u32)
    };
    match record {
        Some(asset) => json!({"assetId":asset.asset_id,"meshKind":asset.mesh_kind,
            "geometrySha256":asset.geometry_sha256,"framingRadiusM":asset.framing_radius_m}),
        None => Value::Null,
    }
}

impl FlightSession {
    pub fn new() -> Result<Self, String> {
        let mut world = WorldRuntime::new(
            ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
            BranchId("desktop.flight".into()),
            RuntimeConfig {
                physics: PhysicsPolicy {
                    solver_backend: SolverBackend::ReferenceScalar,
                    integrator: IntegratorKind::LeapfrogKickDriftKick,
                    collision_model: CollisionModel::None,
                    max_substep_seconds: 10.0,
                },
                timeline_semantics: TimelineSemantics::BranchedSandbox,
                live_updates_enabled: false,
            },
            HardwareProfile::offline_reference(),
            0,
        );
        world
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .map_err(runtime_error)?;
        world
            .spawn_reference_spacecraft("earth", SHIP, 400_000.0, 0)
            .map_err(runtime_error)?;
        Ok(Self {
            world,
            selected: SHIP.into(),
            generation: 1,
            last_control_sequence: 0,
            last_heartbeat: Instant::now(),
            notice: None,
            checkpoint: None,
        })
    }

    pub fn cut(&mut self, pause: bool) -> Result<(), String> {
        self.world
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId(self.selected.clone()),
                    control: FlightControl::default(),
                },
                0,
            )
            .map_err(runtime_error)?;
        if pause {
            self.world
                .apply_command(WorldCommand::PausePlayback, 0)
                .map_err(runtime_error)?;
        }
        Ok(())
    }

    pub fn tick(&mut self, seconds: f64) {
        let disconnected = self.last_heartbeat.elapsed().as_secs_f64() > 1.5;
        let needs_release = disconnected
            && (!self.world.snapshot().playback.paused
                || self
                    .world
                    .spacecraft_by_id(&self.selected)
                    .is_some_and(|ship| ship.control != FlightControl::default()));
        if needs_release {
            let _ = self.cut(true);
            self.generation += 1;
            self.notice = Some(
                "Flight paused because the flight deck lost contact. Controls are clear.".into(),
            );
        }
        if let Err(error) = self.world.playback_tick(seconds.clamp(0.001, 0.1), 0) {
            let _ = self.cut(true);
            self.notice = Some(format!("Flight paused: {error:?}"));
        }
    }

    pub fn state(&self) -> Result<Value, String> {
        let snapshot = self.world.snapshot();
        let ship = self
            .world
            .spacecraft_by_id(&self.selected)
            .ok_or("Selected spacecraft unavailable")?;
        let telemetry = self
            .world
            .flight_telemetry(&self.selected)
            .ok_or("Flight telemetry unavailable")?;
        let host = self
            .world
            .body_by_id(&telemetry.reference_body_id)
            .ok_or("Reference body unavailable")?;
        let name = ship.authored.as_ref().map_or_else(
            || {
                if self.selected == SHIP {
                    "Odyssey"
                } else {
                    &self.selected
                }
            },
            |a| a.display_name.as_str(),
        );
        let crafts: Vec<_> = snapshot.spacecraft.iter().filter(|s| self.world.flight_telemetry(&s.body_id.0).is_some())
            .map(|s| json!({"id": s.body_id.0, "name": s.authored.as_ref().map_or_else(|| if s.body_id.0 == SHIP { "Odyssey" } else { s.body_id.0.as_str() }, |a| a.display_name.as_str()),
                "appearance": s.appearance, "visual":visual_asset(s), "attitude": s.attitude,
                "position": self.world.body_by_id(&s.body_id.0).map(|b| difference(b.position_m, host.position_m))})).collect();
        let bodies: Vec<_> = snapshot.bodies.iter().filter(|b| b.source_mass_kg > 0.0)
            .map(|b| json!({"id": b.body_id.0, "radius": b.radius_m, "position": difference(b.position_m, host.position_m),
                "appearance": solarlab_data::canonical_celestial_appearance(&b.body_id.0, b.body_class, b.radius_m)})).collect();
        let orbit = telemetry.orbit.map(|o| json!({"periapsis": o.periapsis_radius_m-telemetry.reference_radius_m,
            "apoapsis": o.apoapsis_radius_m.map(|a| a-telemetry.reference_radius_m), "eccentricity": o.eccentricity,
            "inclination": o.inclination_radians, "period": o.period_seconds}));
        let control = ship.control;
        let flow = if ship.propellant_mass_kg > 0.0 && !snapshot.playback.paused {
            telemetry.main_engine_mass_flow_kgps * control.throttle
        } else {
            0.0
        };
        Ok(
            json!({"generation": self.generation, "revision": telemetry.revision, "epoch": telemetry.epoch_seconds,
            "paused": snapshot.playback.paused, "warp": snapshot.playback.sim_seconds_per_real_second,
            "scenario": snapshot.scenario_id.0, "branch": snapshot.branch_id.0, "notice": self.notice,
            "checkpoint": self.checkpoint.is_some(), "settings": self.world.scientific_settings(),
            "nextControlSequence": self.last_control_sequence.saturating_add(1),
            "ship": {"id": self.selected, "name": name, "appearance": ship.appearance, "visual":visual_asset(ship),
                "position": vector(telemetry.relative_position_m), "velocity": vector(telemetry.relative_velocity_mps),
                "attitude": ship.attitude, "spin": vector(ship.angular_velocity_body_radps), "control": control,
                "fuel": ship.propellant_mass_kg, "mass": ship.mass_kg(), "deltaV": telemetry.remaining_delta_v_mps,
                "maxThrust": telemetry.main_engine_max_thrust_n, "massFlow": flow,
                "altitude": telemetry.altitude_m, "speed": telemetry.speed_mps, "radialSpeed": telemetry.radial_speed_mps,
                "host": telemetry.reference_body_id, "hostRadius": telemetry.reference_radius_m,
                "orbit": orbit, "docking": ship.docking.is_some()}, "crafts": crafts, "bodies": bodies}),
        )
    }

    pub fn handle(&mut self, action: Action) -> Result<Value, String> {
        match action {
            Action::Heartbeat => {
                self.last_heartbeat = Instant::now();
            }
            Action::Control { sequence, control } => {
                if !control.valid() {
                    return Err("Control values are outside their allowed range".into());
                }
                if sequence > self.last_control_sequence {
                    self.world
                        .apply_command(
                            WorldCommand::SetFlightControl {
                                body_id: BodyId(self.selected.clone()),
                                control,
                            },
                            0,
                        )
                        .map_err(runtime_error)?;
                    self.last_control_sequence = sequence;
                    self.last_heartbeat = Instant::now();
                    self.notice = None;
                }
            }
            Action::Cut { sequence } => {
                if sequence > self.last_control_sequence {
                    self.cut(false)?;
                    self.last_control_sequence = sequence;
                }
            }
            Action::Pause => {
                self.world
                    .apply_command(WorldCommand::PausePlayback, 0)
                    .map_err(runtime_error)?;
            }
            Action::Resume => {
                self.last_heartbeat = Instant::now();
                self.notice = None;
                self.world
                    .apply_command(WorldCommand::ResumePlayback, 0)
                    .map_err(runtime_error)?;
            }
            Action::Warp { rate } => {
                if ![1.0, 10.0, 100.0, 1000.0].contains(&rate) {
                    return Err("Unsupported playback rate".into());
                }
                self.world
                    .apply_command(
                        WorldCommand::SetPlaybackRate {
                            sim_seconds_per_real_second: rate,
                        },
                        0,
                    )
                    .map_err(runtime_error)?;
            }
            Action::Select { body_id } => {
                if self.world.flight_telemetry(&body_id).is_none() {
                    return Err("Choose a spacecraft with a reference body".into());
                }
                self.cut(true)?;
                self.selected = body_id;
                self.cut(true)?;
                self.world
                    .apply_command(
                        WorldCommand::FocusBody {
                            body_id: Some(BodyId(self.selected.clone())),
                        },
                        0,
                    )
                    .map_err(runtime_error)?;
                self.generation += 1;
            }
            Action::Checkpoint => {
                self.cut(true)?;
                let id = CheckpointId(format!(
                    "desktop.mark.{}",
                    self.world.next_command_ordinal()
                ));
                self.world
                    .apply_command(
                        WorldCommand::CreateCheckpoint {
                            checkpoint_id: Some(id.clone()),
                            label: Some("Desktop flight mark".into()),
                        },
                        0,
                    )
                    .map_err(runtime_error)?;
                self.checkpoint = Some(id);
                self.generation += 1;
            }
            Action::Reset => {
                let id = self.checkpoint.clone().ok_or("Mark a checkpoint first")?;
                self.cut(true)?;
                self.generation += 1;
                self.world
                    .apply_command(
                        WorldCommand::CreateBranchFromCheckpoint {
                            checkpoint_id: id,
                            new_branch_id: None,
                        },
                        0,
                    )
                    .map_err(runtime_error)?;
                self.selected = Self::initial_selection(&self.world)?;
                self.cut(true)?;
            }
            Action::Integrator { method } => {
                let mut settings = self.world.scientific_settings().clone();
                settings.integrator = method;
                self.world
                    .apply_command(WorldCommand::SetScientificSettings { settings }, 0)
                    .map_err(runtime_error)?;
            }
            Action::Preview {
                duration,
                throttle,
                assist,
            } => {
                let result = self
                    .world
                    .preview_flight_burn(
                        &self.selected,
                        FlightBurnRequest {
                            duration_seconds: duration,
                            throttle,
                            attitude_mode: assist,
                        },
                    )
                    .map_err(runtime_error)?;
                return Ok(json!({"preview": result, "generation": self.generation}));
            }
            Action::Prediction => return self.prediction(),
            Action::Workshop => self.import(include_bytes!(
                "../../../engine/data/authoring/orbital-workshop.scenario.json"
            ))?,
        }
        self.state()
    }

    pub fn import(&mut self, bytes: &[u8]) -> Result<(), String> {
        let document: Value = serde_json::from_slice(bytes).map_err(runtime_error)?;
        let format = document
            .get("format")
            .and_then(Value::as_str)
            .ok_or("Document format missing")?;
        if format == "solarlab.spacecraft" {
            self.world
                .add_authored_spacecraft(bytes, 0)
                .map_err(runtime_error)?;
            self.notice = Some(
                "Spacecraft added from its definition. Choose it in the flight selector.".into(),
            );
            return Ok(());
        }
        let replacement = match format {
            "solarlab.scenario" => WorldRuntime::import_authored_scenario(
                bytes,
                HardwareProfile::offline_reference(),
                0,
            )
            .map_err(runtime_error)?,
            "solarlab.experiment" => {
                WorldRuntime::import_saved_session(bytes, HardwareProfile::offline_reference())
                    .map_err(runtime_error)?
            }
            _ => {
                return Err(
                    "Choose an SGL scenario, spacecraft definition or saved experiment".into(),
                )
            }
        };
        let selected = Self::initial_selection(&replacement)?;
        self.world = replacement;
        self.selected = selected;
        self.generation += 1;
        self.checkpoint = None;
        self.cut(true)?;
        self.notice = Some("Imported world ready. Flight is paused and controls are clear.".into());
        Ok(())
    }

    fn initial_selection(world: &WorldRuntime) -> Result<String, String> {
        let snapshot = world.snapshot();
        if let Some(focus) = snapshot.observer.focus_body_id {
            if world.flight_telemetry(&focus.0).is_some() {
                return Ok(focus.0);
            }
        }
        snapshot
            .spacecraft
            .iter()
            .find(|ship| world.flight_telemetry(&ship.body_id.0).is_some())
            .map(|ship| ship.body_id.0.clone())
            .ok_or_else(|| "The imported world needs a spacecraft with a reference body".into())
    }

    fn prediction(&self) -> Result<Value, String> {
        let telemetry = self
            .world
            .flight_telemetry(&self.selected)
            .ok_or("Flight telemetry unavailable")?;
        let horizon = telemetry
            .orbit
            .and_then(|o| o.period_seconds)
            .unwrap_or(3600.0)
            .clamp(600.0, 12_000.0);
        let archives = self.world.orbit_archives(&OrbitArchiveQuery {
            body_ids: vec![
                BodyId(self.selected.clone()),
                BodyId(telemetry.reference_body_id.clone()),
            ],
            include_trajectory: false,
            include_historical_orbits: false,
            include_prediction: true,
            checkpoint_sample_limit: 0,
            prediction_step_seconds: horizon / 128.0,
            prediction_sample_count: 128,
        });
        let ship = archives
            .iter()
            .find(|a| a.source_body_id.0 == self.selected)
            .ok_or("Coast prediction unavailable")?;
        let host = archives
            .iter()
            .find(|a| a.source_body_id.0 == telemetry.reference_body_id)
            .ok_or("Reference prediction unavailable")?;
        let points: Vec<_> = ship
            .samples
            .iter()
            .zip(&host.samples)
            .map(|(a, b)| difference(a.position_m, b.position_m))
            .collect();
        Ok(
            json!({"points": points, "epoch": telemetry.epoch_seconds, "revision": telemetry.revision,
            "generation": self.generation, "horizon": horizon}),
        )
    }
}
