//! Flight component lifecycle and world/solver binding. No independent kinematics.
use super::*;
use solarlab_domain::spacecraft::{add, finite};
use solarlab_physics::spacecraft::SpacecraftBinding;

impl WorldRuntime {
    #[must_use]
    pub fn spacecraft_by_id(&self, id: &str) -> Option<&SpacecraftState> {
        self.active_branch()
            .world
            .spacecraft
            .iter()
            .find(|s| s.body_id.0 == id)
    }
    /// Author an ideal circular starting orbit in ICRF XY. The parent body's
    /// current barycentric velocity is preserved. This setup is explicitly
    /// approximate; subsequent flight uses the full world's gravity.
    pub fn spawn_reference_spacecraft(
        &mut self,
        host_id: &str,
        ship_id: &str,
        altitude_m: f64,
        recorded_at_unix_ms: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        if !self.active_branch().world.playback.paused {
            return Err(RuntimeError::BodyEditRequiresPause);
        }
        let host = self
            .body_by_id(host_id)
            .ok_or_else(|| RuntimeError::UnknownBody(BodyId(host_id.into())))?;
        let radius = host.radius_m + altitude_m;
        if !altitude_m.is_finite()
            || altitude_m <= 0.0
            || !radius.is_finite()
            || host.source_mass_kg <= 0.0
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        let velocity = (6.674_30e-11 * host.source_mass_kg / radius).sqrt();
        let mut spacecraft =
            SpacecraftState::reference(BodyId(ship_id.into()), Some(host.body_id.clone()));
        spacecraft.attitude = Quaterniond::pointing(Vector3d {
            x: 0.0,
            y: 1.0,
            z: 0.0,
        })
        .expect("unit direction");
        let body = BodyState {
            body_id: spacecraft.body_id.clone(),
            body_class: BodyClass::Spacecraft,
            mass_kg: spacecraft.mass_kg(),
            source_mass_kg: 0.0,
            radius_m: 8.0,
            position_m: add(
                host.position_m,
                Vector3d {
                    x: radius,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            velocity_mps: add(
                host.velocity_mps,
                Vector3d {
                    x: 0.0,
                    y: velocity,
                    z: 0.0,
                },
            ),
        };
        self.apply_command(
            WorldCommand::SpawnSpacecraft { body, spacecraft },
            recorded_at_unix_ms,
        )
    }

    pub(super) fn validate_flight_command(
        &self,
        command: &WorldCommand,
    ) -> Result<(), RuntimeError> {
        let world = &self.active_branch().world;
        match command {
            WorldCommand::SpawnSpacecraft { body, spacecraft } => {
                if !world.playback.paused {
                    return Err(RuntimeError::BodyEditRequiresPause);
                }
                if world.bodies.iter().any(|b| b.body_id == body.body_id) {
                    return Err(RuntimeError::DuplicateBody(body.body_id.clone()));
                }
                if world.spacecraft.iter().any(|s| {
                    s.docking
                        .as_ref()
                        .is_some_and(|d| d.target_body_id == body.body_id)
                }) || spacecraft.docking.as_ref().is_some_and(|d| {
                    !world
                        .spacecraft
                        .iter()
                        .any(|s| s.body_id == d.target_body_id)
                }) || !valid_ship_body(body, spacecraft)
                    || !valid_mission(world, body, spacecraft)
                    || spacecraft.reference_body_id.as_ref().is_some_and(|id| {
                        *id == body.body_id || !world.bodies.iter().any(|b| b.body_id == *id)
                    })
                {
                    return Err(RuntimeError::InvalidFlightState);
                }
            }
            WorldCommand::SpawnBody { body }
                if world.spacecraft.iter().any(|s| {
                    s.docking
                        .as_ref()
                        .is_some_and(|d| d.target_body_id == body.body_id)
                }) =>
            {
                return Err(RuntimeError::InvalidFlightState);
            }
            WorldCommand::SetFlightControl { body_id, control } => {
                if !control.valid() {
                    return Err(RuntimeError::InvalidFlightState);
                }
                let ship = self
                    .spacecraft_by_id(&body_id.0)
                    .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                let mut proposed = ship.clone();
                proposed.control = *control;
                proposed.burn_program = None;
                if let Some(r) = &mut proposed.rendezvous {
                    r.guidance_mode = RendezvousGuidanceMode::Manual;
                    r.status = RendezvousStatus::Manual;
                }
                if let Some(d) = &mut proposed.docking {
                    d.guidance_mode = DockingGuidanceMode::Manual;
                    d.capture_armed = false;
                }
                if !proposed.valid()
                    || (world.spacecraft.iter().any(|s| {
                        s.docking
                            .as_ref()
                            .is_some_and(|d| d.target_body_id == *body_id)
                    }) && (proposed.has_powered_input()
                        || solarlab_domain::spacecraft::magnitude(control.rotation_body) > 0.0
                        || !matches!(
                            control.attitude_mode,
                            solarlab_domain::AttitudeMode::Manual
                                | solarlab_domain::AttitudeMode::Hold
                        )))
                {
                    return Err(RuntimeError::InvalidFlightState);
                }
                if !matches!(
                    control.attitude_mode,
                    solarlab_domain::AttitudeMode::Manual | solarlab_domain::AttitudeMode::Hold
                ) && ship.reference_body_id.is_none()
                {
                    return Err(RuntimeError::InvalidFlightState);
                }
            }
            WorldCommand::StartGuidedBurn {
                body_id,
                full_throttle_seconds,
            } => {
                let ship = self
                    .spacecraft_by_id(&body_id.0)
                    .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                if ship.docking.is_some()
                    || world.spacecraft.iter().any(|s| {
                        s.docking
                            .as_ref()
                            .is_some_and(|d| d.target_body_id == *body_id)
                    })
                    || !full_throttle_seconds.is_finite()
                    || *full_throttle_seconds <= 0.0
                    || *full_throttle_seconds > 86_400.0
                    || ship.propellant_mass_kg <= 0.0
                    || ship.reference_body_id.is_none()
                    || !ship
                        .engines
                        .iter()
                        .any(|e| e.control == solarlab_domain::EngineControl::Main)
                {
                    return Err(RuntimeError::InvalidFlightState);
                }
            }
            WorldCommand::SetRendezvousGuidance { body_id, mode } => {
                self.rendezvous_command_control(body_id, *mode)?;
            }
            WorldCommand::SetDockingGuidance { body_id, mode } => {
                self.validate_docking_guidance(body_id, *mode)?;
            }
            WorldCommand::UndockSpacecraft { body_id } => {
                if self
                    .spacecraft_by_id(&body_id.0)
                    .and_then(|s| s.docking.as_ref())
                    .and_then(|d| d.attachment)
                    .is_none()
                {
                    return Err(RuntimeError::InvalidFlightState);
                }
            }
            WorldCommand::SetBodyKinematics { body_id, .. }
                if world.spacecraft.iter().any(|s| {
                    (s.body_id == *body_id && s.docking.is_some())
                        || s.docking
                            .as_ref()
                            .is_some_and(|d| d.target_body_id == *body_id)
                }) =>
            {
                return Err(RuntimeError::InvalidFlightState);
            }
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second,
            } if *sim_seconds_per_real_second > 1.0
                && world.spacecraft.iter().any(|s| {
                    s.docking.is_some()
                        || s.has_powered_input()
                        || s.rendezvous
                            .as_ref()
                            .is_some_and(|r| r.guidance_mode.active())
                        || solarlab_domain::spacecraft::magnitude(s.control.rotation_body) > 0.0
                }) =>
            {
                return Err(RuntimeError::InvalidPlaybackRate(
                    *sim_seconds_per_real_second,
                ))
            }
            WorldCommand::SetBodyProperties { body_id, .. }
                if self.spacecraft_by_id(&body_id.0).is_some() =>
            {
                return Err(RuntimeError::InvalidFlightState)
            }
            _ => {}
        }
        Ok(())
    }
}

pub(super) fn valid_ship_body(body: &BodyState, ship: &SpacecraftState) -> bool {
    ship.valid()
        && ship.authored.as_ref().and_then(|assembly| assembly.visual_asset.as_ref()).is_none_or(|binding| {
            solarlab_data::spacecraft_visual_asset(&binding.asset_id).is_some_and(|asset| {
                asset.mesh_kind == binding.mesh_kind
                    && asset.geometry_sha256 == binding.geometry_sha256
                    && body.radius_m >= asset.framing_radius_m
                    && (asset.contact_profile == Some(ship.appearance)
                        || ship.authored.as_ref().is_some_and(|a| a.contact.is_some()))
            })
        })
        && ship.body_id == body.body_id
        && body.body_class == BodyClass::Spacecraft
        && !body.body_id.0.trim().is_empty()
        && body.body_id.0.len() <= 96
        && !body.body_id.0.chars().any(char::is_control)
        && body.mass_kg == ship.mass_kg()
        && body.source_mass_kg.is_finite()
        && body.source_mass_kg >= 0.0
        && body.radius_m.is_finite()
        && body.radius_m > 0.0
        && ship
            .authored
            .as_ref()
            .and_then(|a| a.contact.as_ref())
            .is_none_or(|geometry| geometry.bounding_radius_m() <= body.radius_m)
        && finite(body.position_m)
        && finite(body.velocity_mps)
}

pub(super) fn valid_mission(
    world: &BranchWorldState,
    body: &BodyState,
    ship: &SpacecraftState,
) -> bool {
    let valid_rendezvous = ship.rendezvous.as_ref().is_none_or(|r| {
        if r.launch_epoch_seconds > world.epoch_seconds
            || r.closest_observed_epoch_seconds > world.epoch_seconds + 1e-6
        {
            return false;
        }
        if !r.guidance_mode.active() {
            return true;
        }
        if ship.reference_body_id.is_none()
            || !solarlab_physics::rendezvous::supports_guidance(ship)
        {
            return false;
        }
        let Some(target) = world.bodies.iter().find(|b| b.body_id == r.target_body_id) else {
            return false;
        };
        let Some(target_ship) = world
            .spacecraft
            .iter()
            .find(|s| s.body_id == r.target_body_id)
        else {
            return false;
        };
        let frame = solarlab_domain::LocalReferenceFrame {
            origin_m: target.position_m,
            velocity_mps: target.velocity_mps,
            attitude: target_ship.attitude,
            angular_velocity_world_radps: target_ship
                .attitude
                .rotate(target_ship.angular_velocity_body_radps),
        };
        let guidance = if r.guidance_mode == RendezvousGuidanceMode::Approach {
            RendezvousGuidance::default()
        } else {
            RendezvousGuidance::velocity_match()
        };
        solarlab_physics::rendezvous::observe(
            body.position_m,
            body.velocity_mps,
            body.radius_m,
            frame,
            target.radius_m,
        )
        .and_then(|observation| {
            solarlab_physics::rendezvous::control(
                ship,
                body.velocity_mps,
                frame,
                &observation,
                guidance,
            )
        })
        .is_ok()
    });
    valid_rendezvous
        && docking_flight::valid_docking(world, body, ship)
        && ship.mission.as_ref().is_none_or(|mission| {
            mission.launch_epoch_seconds <= world.epoch_seconds
                && mission.closest_approach_epoch_seconds <= world.epoch_seconds + 1e-6
                && mission.target_body_id != ship.body_id
                && world
                    .bodies
                    .iter()
                    .any(|b| b.body_id == mission.target_body_id)
        })
}

pub(super) fn bindings(world: &BranchWorldState) -> Vec<SpacecraftBinding> {
    world
        .spacecraft
        .iter()
        .map(|state| {
            let body_index = world
                .bodies
                .iter()
                .position(|b| b.body_id == state.body_id)
                .expect("spacecraft body invariant");
            let reference_body_index = state
                .reference_body_id
                .as_ref()
                .and_then(|id| world.bodies.iter().position(|b| b.body_id == *id));
            let mission_target_index = state.mission.as_ref().and_then(|m| {
                world
                    .bodies
                    .iter()
                    .position(|b| b.body_id == m.target_body_id)
            });
            SpacecraftBinding {
                body_index,
                reference_body_index,
                mission_target_index,
                rendezvous_target_body_index: state.rendezvous.as_ref().and_then(|r| {
                    world
                        .bodies
                        .iter()
                        .position(|b| b.body_id == r.target_body_id)
                }),
                rendezvous_target_spacecraft_index: state.rendezvous.as_ref().and_then(|r| {
                    world
                        .spacecraft
                        .iter()
                        .position(|s| s.body_id == r.target_body_id)
                }),
                rendezvous_target_radius_m: state
                    .rendezvous
                    .as_ref()
                    .and_then(|r| world.bodies.iter().find(|b| b.body_id == r.target_body_id))
                    .map_or(0.0, |b| b.radius_m),
                reference_radius_m: reference_body_index.map_or(0.0, |i| world.bodies[i].radius_m),
                target_radius_m: mission_target_index.map_or(0.0, |i| world.bodies[i].radius_m),
                body_radius_m: world.bodies[body_index].radius_m,
                epoch_seconds: world.epoch_seconds,
                epoch_compensation_seconds: 0.0,
                state: state.clone(),
            }
        })
        .collect()
}
pub(super) fn apply_bindings(world: &mut BranchWorldState, bindings: Vec<SpacecraftBinding>) {
    for binding in &bindings {
        world.bodies[binding.body_index].mass_kg = binding.state.mass_kg();
    }
    world.spacecraft = bindings.into_iter().map(|s| s.state).collect();
}

#[cfg(test)]
mod tests {
    use super::*;
    use solarlab_domain::spacecraft::{cross, magnitude, sub};
    use solarlab_domain::{AttitudeMode, FlightControl};
    use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};
    const G: f64 = 6.67430e-11;
    const EARTH_MASS: f64 = 5.97219e24;
    fn v(x: f64, y: f64, z: f64) -> Vector3d {
        Vector3d { x, y, z }
    }
    fn runtime(step: f64) -> WorldRuntime {
        WorldRuntime::new(
            ScenarioId("flight-tests".into()),
            BranchId("main".into()),
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
        )
    }
    fn earth(r: &mut WorldRuntime) {
        r.apply_command(
            WorldCommand::SpawnBody {
                body: BodyState {
                    body_id: BodyId("earth".into()),
                    body_class: BodyClass::Planet,
                    mass_kg: EARTH_MASS,
                    source_mass_kg: EARTH_MASS,
                    radius_m: 6_371_000.0,
                    position_m: v(0.0, 0.0, 0.0),
                    velocity_mps: v(0.0, 0.0, 0.0),
                },
            },
            0,
        )
        .unwrap();
    }
    fn orbital(step: f64) -> WorldRuntime {
        let mut r = runtime(step);
        earth(&mut r);
        r.spawn_reference_spacecraft("earth", "ship", 400_000.0, 0)
            .unwrap();
        r
    }
    fn vacuum(step: f64) -> WorldRuntime {
        let mut r = runtime(step);
        let s = SpacecraftState::reference(BodyId("ship".into()), None);
        r.apply_command(
            WorldCommand::SpawnSpacecraft {
                body: BodyState {
                    body_id: s.body_id.clone(),
                    body_class: BodyClass::Spacecraft,
                    mass_kg: s.mass_kg(),
                    source_mass_kg: 0.0,
                    radius_m: 8.0,
                    position_m: Vector3d::default(),
                    velocity_mps: Vector3d::default(),
                },
                spacecraft: s,
            },
            0,
        )
        .unwrap();
        r
    }
    fn control(r: &mut WorldRuntime, c: FlightControl) {
        r.apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId("ship".into()),
                control: c,
            },
            0,
        )
        .unwrap();
    }
    fn advance(r: &mut WorldRuntime, dt: f64) {
        let previous = r.query_advance().map_or(0, |o| o.generation);
        let revision = r.next_command_ordinal();
        let op = r
            .begin_advance([revision, revision + 17], previous, revision, dt, 0)
            .unwrap();
        let generation = op.generation;
        let mut complete = 0;
        loop {
            let (op, _) = r.continue_advance(generation, complete).unwrap();
            complete = op.completed_steps;
            if op.state == AdvanceState::Committed {
                break;
            }
        }
    }
    fn ship(r: &WorldRuntime) -> &BodyState {
        r.body_by_id("ship").unwrap()
    }

    #[test]
    fn circular_and_elliptical_coast_match_kepler_period() {
        for eccentricity in [0.0, 0.3] {
            let mut r = orbital(0.5);
            let initial = ship(&r).clone();
            let rp = magnitude(initial.position_m);
            let a = rp / (1.0 - eccentricity);
            let speed = (G * EARTH_MASS * (2.0 / rp - 1.0 / a)).sqrt();
            r.apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: initial.body_id,
                    position_m: initial.position_m,
                    velocity_mps: v(0.0, speed, 0.0),
                },
                0,
            )
            .unwrap();
            let period = std::f64::consts::TAU * (a.powi(3) / (G * EARTH_MASS)).sqrt();
            advance(&mut r, period);
            let error = magnitude(sub(ship(&r).position_m, initial.position_m));
            assert!(error < 15.0, "e={eccentricity} closure error {error}");
            assert!((magnitude(ship(&r).velocity_mps) - speed).abs() < 0.01);
            assert_eq!(
                r.body_by_id("earth").unwrap().position_m,
                Vector3d::default()
            );
            assert_eq!(
                r.spacecraft_by_id("ship").unwrap().propellant_mass_kg,
                8000.0
            );
        }
    }
    #[test]
    fn vacuum_burn_matches_rocket_equation_and_depletes_at_exact_boundary() {
        let mut r = vacuum(0.05);
        let original = r.spacecraft_by_id("ship").unwrap().clone();
        let exhaust: f64 = 450.0 * 9.80665;
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                ..FlightControl::default()
            },
        );
        advance(&mut r, 10.0);
        let fuel = 8000.0 - 10.0 * 90_000.0 / exhaust;
        let expected = exhaust * (12_000.0 / (4000.0 + fuel)).ln();
        assert!((ship(&r).velocity_mps.z - expected).abs() < 1e-9);
        assert!((r.spacecraft_by_id("ship").unwrap().propellant_mass_kg - fuel).abs() < 1e-8);
        advance(&mut r, 8000.0 / (90_000.0 / exhaust));
        let final_ship = r.spacecraft_by_id("ship").unwrap();
        assert_eq!(final_ship.propellant_mass_kg, 0.0);
        assert_eq!(ship(&r).mass_kg, 4000.0);
        assert!(
            (ship(&r).velocity_mps.z - exhaust * (original.mass_kg() / original.dry_mass_kg).ln())
                .abs()
                < 1e-7
        );
        let velocity = ship(&r).velocity_mps;
        advance(&mut r, 20.0);
        assert_eq!(ship(&r).velocity_mps, velocity);
    }
    #[test]
    fn prograde_retrograde_and_normal_burns_change_real_orbital_energy_and_plane() {
        let baseline = orbital(0.05);
        let initial = ship(&baseline);
        let mu = G * EARTH_MASS;
        let initial_energy =
            0.5 * magnitude(initial.velocity_mps).powi(2) - mu / magnitude(initial.position_m);
        for (direction, sign) in [(v(0.0, 1.0, 0.0), 1.0), (v(0.0, -1.0, 0.0), -1.0)] {
            let mut r = baseline.clone();
            r.active_branch_mut().world.spacecraft[0].attitude =
                Quaterniond::pointing(direction).unwrap();
            control(
                &mut r,
                FlightControl {
                    throttle: 1.0,
                    ..FlightControl::default()
                },
            );
            advance(&mut r, 10.0);
            let body = ship(&r);
            let energy =
                0.5 * magnitude(body.velocity_mps).powi(2) - mu / magnitude(body.position_m);
            assert!((energy - initial_energy) * sign > 500_000.0);
        }
        let mut r = baseline;
        r.active_branch_mut().world.spacecraft[0].attitude = Quaterniond::IDENTITY;
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                ..FlightControl::default()
            },
        );
        advance(&mut r, 10.0);
        let h = cross(ship(&r).position_m, ship(&r).velocity_mps);
        assert!(h.y.abs() / magnitude(h) > 0.009);
        assert!(ship(&r).velocity_mps.z > 75.0);
    }
    #[test]
    fn torque_produces_angular_acceleration_and_quaternion_rotation() {
        let mut r = vacuum(0.05);
        control(
            &mut r,
            FlightControl {
                rotation_body: v(0.5, 0.0, 0.0),
                ..FlightControl::default()
            },
        );
        advance(&mut r, 2.0);
        let s = r.spacecraft_by_id("ship").unwrap();
        let alpha = 3000.0 / 24000.0;
        let angle = 0.5 * alpha * 4.0;
        assert!((s.angular_velocity_body_radps.x - alpha * 2.0).abs() < 1e-12);
        assert!((s.attitude.w - (angle * 0.5).cos()).abs() < 1e-12);
        assert!((s.attitude.x - (angle * 0.5).sin()).abs() < 1e-12);
        assert!(s.attitude.is_unit());
        assert_eq!(s.propellant_mass_kg, 8000.0);
    }
    #[test]
    fn rcs_translation_uses_body_frame_and_consumes_fuel() {
        let mut r = vacuum(0.05);
        r.active_branch_mut().world.spacecraft[0].attitude =
            Quaterniond::from_rotation_vector(v(0.0, 0.0, std::f64::consts::FRAC_PI_2));
        control(
            &mut r,
            FlightControl {
                translation_body: v(1.0, 0.0, 0.0),
                ..FlightControl::default()
            },
        );
        advance(&mut r, 5.0);
        assert!(ship(&r).velocity_mps.y > 0.33);
        assert!(ship(&r).velocity_mps.x.abs() < 1e-12);
        assert!(
            (r.spacecraft_by_id("ship").unwrap().propellant_mass_kg
                - (8000.0 - 5.0 * 800.0 / (300.0 * 9.80665)))
                .abs()
                < 1e-8
        );
    }
    #[test]
    fn attitude_assist_turns_with_torque_without_snapping() {
        let mut r = orbital(0.05);
        r.active_branch_mut().world.spacecraft[0].attitude = Quaterniond::IDENTITY;
        control(
            &mut r,
            FlightControl {
                attitude_mode: AttitudeMode::Prograde,
                ..FlightControl::default()
            },
        );
        assert_eq!(
            r.spacecraft_by_id("ship").unwrap().attitude,
            Quaterniond::IDENTITY
        );
        advance(&mut r, 0.05);
        assert!(r.spacecraft_by_id("ship").unwrap().attitude.w > 0.999999);
        advance(&mut r, 15.0);
        let s = r.spacecraft_by_id("ship").unwrap();
        let nose = s.attitude.rotate(v(0.0, 0.0, 1.0));
        let velocity = solarlab_domain::spacecraft::unit(ship(&r).velocity_mps).unwrap();
        assert!(solarlab_domain::spacecraft::dot(nose, velocity) > 0.999);
    }
    #[test]
    fn saves_and_checkpoints_restore_fuel_attitude_controls_and_future() {
        let mut r = orbital(0.05);
        control(
            &mut r,
            FlightControl {
                throttle: 0.75,
                rotation_body: v(0.1, 0.0, 0.0),
                ..FlightControl::default()
            },
        );
        advance(&mut r, 2.0);
        r.apply_command(
            WorldCommand::CreateCheckpoint {
                checkpoint_id: Some(CheckpointId("burn".into())),
                label: Some("mid burn".into()),
            },
            0,
        )
        .unwrap();
        let saved = r.export_saved_session().unwrap();
        let doc: serde_json::Value = serde_json::from_slice(&saved).unwrap();
        assert_eq!(doc["version"], 2);
        assert_eq!(doc["flight_model"], "six-dof-strang-rocket-v1");
        let mut reopened =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        assert_eq!(r.snapshot().spacecraft, reopened.snapshot().spacecraft);
        assert_eq!(r.snapshot().bodies, reopened.snapshot().bodies);
        advance(&mut r, 4.0);
        advance(&mut reopened, 4.0);
        assert_eq!(r.snapshot().bodies, reopened.snapshot().bodies);
        assert_eq!(r.snapshot().spacecraft, reopened.snapshot().spacecraft);
        reopened
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: CheckpointId("burn".into()),
                    new_branch_id: None,
                },
                0,
            )
            .unwrap();
        let checkpoint =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        assert_eq!(
            reopened.snapshot().spacecraft,
            checkpoint.snapshot().spacecraft
        );
    }
    #[test]
    fn warp_is_available_for_coast_and_rejected_for_powered_flight() {
        let mut r = orbital(1.0);
        r.apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 1000.0,
            },
            0,
        )
        .unwrap();
        r.apply_command(WorldCommand::ResumePlayback, 0).unwrap();
        let tick = r.playback_tick(0.01, 0).unwrap();
        assert_eq!(tick.applied_seconds, 10.0);
        assert!(!tick.limited);
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                ..FlightControl::default()
            },
        );
        assert_eq!(r.snapshot().playback.sim_seconds_per_real_second, 1.0);
        assert!(matches!(
            r.apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 10.0
                },
                0
            ),
            Err(RuntimeError::InvalidPlaybackRate(_))
        ));
    }
    #[test]
    fn invalid_commands_documents_and_staged_cancel_preserve_world() {
        let mut r = orbital(0.05);
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal();
        assert_eq!(
            r.apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId("ship".into()),
                    control: FlightControl {
                        throttle: f64::NAN,
                        ..FlightControl::default()
                    }
                },
                0
            ),
            Err(RuntimeError::InvalidFlightState)
        );
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal(), ordinal);
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                ..FlightControl::default()
            },
        );
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal();
        let generation = r
            .begin_advance([55, 56], 0, ordinal, 10.0, 0)
            .unwrap()
            .generation;
        r.continue_advance(generation, 0).unwrap();
        r.cancel_advance(generation).unwrap();
        assert_eq!(r.snapshot(), before);
        let mut doc: serde_json::Value =
            serde_json::from_slice(&r.export_saved_session().unwrap()).unwrap();
        doc["version"] = 1.into();
        assert!(WorldRuntime::import_saved_session(
            &serde_json::to_vec(&doc).unwrap(),
            HardwareProfile::offline_reference()
        )
        .is_err());
        doc["version"] = 2.into();
        doc["branches"][0]["world"]["spacecraft"][0]["attitude"]["w"] = 0.into();
        assert!(WorldRuntime::import_saved_session(
            &serde_json::to_vec(&doc).unwrap(),
            HardwareProfile::offline_reference()
        )
        .is_err());
    }
    #[test]
    fn hold_damps_rotation_before_full_coasting_warp_resumes() {
        let mut r = orbital(1.0);
        r.active_branch_mut().world.spacecraft[0].angular_velocity_body_radps = v(0.1, 0.0, 0.0);
        control(
            &mut r,
            FlightControl {
                attitude_mode: AttitudeMode::Hold,
                ..FlightControl::default()
            },
        );
        r.apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 1000.0,
            },
            0,
        )
        .unwrap();
        r.apply_command(WorldCommand::ResumePlayback, 0).unwrap();
        let tick = r.playback_tick(0.01, 0).unwrap();
        assert!(tick.limited);
        assert!(tick.applied_seconds <= 3.2);
        r.apply_command(WorldCommand::PausePlayback, 0).unwrap();
        advance(&mut r, 20.0);
        assert_eq!(
            r.spacecraft_by_id("ship")
                .unwrap()
                .angular_velocity_body_radps,
            Vector3d::default()
        );
        r.apply_command(WorldCommand::ResumePlayback, 0).unwrap();
        let tick = r.playback_tick(0.01, 0).unwrap();
        // Committing a non-binary epoch may round the applied duration down by
        // a few ulps; the orbital work ceiling is fully restored.
        assert!((tick.applied_seconds - 10.0).abs() < 1e-12);
        assert_eq!(r.effective_physics_policy().max_substep_seconds, 1.0);
    }
    #[test]
    fn coast_predictions_do_not_extrapolate_controls_or_use_the_burn_step() {
        let mut r = orbital(1.0);
        let query = OrbitArchiveQuery {
            body_ids: vec![BodyId("ship".into())],
            include_trajectory: false,
            include_historical_orbits: false,
            include_prediction: true,
            prediction_step_seconds: 10.0,
            prediction_sample_count: 10,
            ..OrbitArchiveQuery::default()
        };
        let coast = r.orbit_archives(&query);
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                attitude_mode: AttitudeMode::Prograde,
                ..FlightControl::default()
            },
        );
        assert_eq!(r.orbit_archives(&query), coast);
        assert_eq!(r.effective_physics_policy().max_substep_seconds, 0.05);
        assert_eq!(r.coast_physics_policy().max_substep_seconds, 1.0);
    }
    #[test]
    fn off_centre_engine_produces_the_correct_lever_arm_torque() {
        let mut r = vacuum(0.01);
        let s = &mut r.active_branch_mut().world.spacecraft[0];
        s.engines[0].position_body_m = v(1.0, 0.0, 0.0);
        control(
            &mut r,
            FlightControl {
                throttle: 0.1,
                ..FlightControl::default()
            },
        );
        advance(&mut r, 0.1);
        let s = r.spacecraft_by_id("ship").unwrap();
        assert!((s.angular_velocity_body_radps.y + 9000.0 / 24000.0 * 0.1).abs() < 1e-12);
        assert!(ship(&r).velocity_mps.z > 0.074);
    }

    #[test]
    fn numeric_overflow_rejects_direct_and_staged_publication_atomically() {
        let mut r = vacuum(0.05);
        let world = &mut r.active_branch_mut().world;
        world.spacecraft[0].dry_mass_kg = 1e-300;
        world.spacecraft[0].propellant_mass_kg = 1e-300;
        world.spacecraft[0].engines[0].max_thrust_n = 1e308;
        world.spacecraft[0].engines[0].max_mass_flow_kgps = 1e-300;
        world.bodies[0].mass_kg = 2e-300;
        control(
            &mut r,
            FlightControl {
                throttle: 1.0,
                ..FlightControl::default()
            },
        );
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal();
        assert_eq!(
            r.apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 0.01
                },
                0
            ),
            Err(RuntimeError::ScientificFailure(solarlab_physics::science::ScientificError::NonFinite))
        );
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal(), ordinal);
        let generation = r
            .begin_advance([ordinal, 17], 0, ordinal, 0.01, 0)
            .unwrap()
            .generation;
        assert!(matches!(
            r.continue_advance(generation, 0),
            Err(RuntimeError::ScientificFailure(solarlab_physics::science::ScientificError::NonFinite))
        ));
        assert_eq!(r.query_advance().unwrap().state, AdvanceState::Cancelled);
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal(), ordinal);
    }

    #[test]
    fn lifecycle_removes_components_and_clears_missing_reference() {
        let mut r = orbital(0.05);
        r.apply_command(
            WorldCommand::RemoveBody {
                body_id: BodyId("earth".into()),
            },
            0,
        )
        .unwrap();
        assert!(r
            .spacecraft_by_id("ship")
            .unwrap()
            .reference_body_id
            .is_none());
        r.export_saved_session().unwrap();
        r.apply_command(
            WorldCommand::RemoveBody {
                body_id: BodyId("ship".into()),
            },
            0,
        )
        .unwrap();
        assert!(r.snapshot().spacecraft.is_empty());
    }
    #[test]
    fn renderer_exports_authoritative_attitude_and_cuts_exhaust_at_empty_tank() {
        let mut r = vacuum(0.05);
        control(
            &mut r,
            FlightControl {
                throttle: 0.7,
                ..FlightControl::default()
            },
        );
        let before = r.snapshot();
        let scene = r.render_scene();
        let view = scene.bodies[0].spacecraft.unwrap();
        assert_eq!(view.attitude_xyzw, [0.0, 0.0, 0.0, 1.0]);
        assert_eq!(view.main_engine_throttle, 0.7);
        assert_eq!(r.snapshot(), before);
        r.active_branch_mut().world.spacecraft[0].propellant_mass_kg = 0.0;
        r.active_branch_mut().world.bodies[0].mass_kg = 4000.0;
        assert_eq!(
            r.render_scene().bodies[0]
                .spacecraft
                .unwrap()
                .main_engine_throttle,
            0.0
        );
    }

    // Independent RK4 translational oracle: no production gravity or propulsion
    // routines, constant inertial +Y thrust, and linear mass-flow derivative.
    fn oracle(dt: f64, duration: f64) -> (Vector3d, Vector3d) {
        let radius: f64 = 6_771_000.0;
        let mut x = [
            radius,
            0.0,
            0.0,
            0.0,
            (G * EARTH_MASS / radius).sqrt(),
            0.0,
            12000.0,
        ];
        fn f(x: [f64; 7]) -> [f64; 7] {
            let radius = (x[0] * x[0] + x[1] * x[1] + x[2] * x[2]).sqrt();
            let k = -G * EARTH_MASS / radius.powi(3);
            [
                x[3],
                x[4],
                x[5],
                k * x[0],
                k * x[1] + 90000.0 / x[6],
                k * x[2],
                -90000.0 / (450.0 * 9.80665),
            ]
        }
        fn plus(x: [f64; 7], a: [f64; 7], s: f64) -> [f64; 7] {
            std::array::from_fn(|i| x[i] + s * a[i])
        }
        for _ in 0..(duration / dt).round() as usize {
            let k1 = f(x);
            let k2 = f(plus(x, k1, dt * 0.5));
            let k3 = f(plus(x, k2, dt * 0.5));
            let k4 = f(plus(x, k3, dt));
            x = std::array::from_fn(|i| {
                x[i] + dt * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]) / 6.0
            });
        }
        (v(x[0], x[1], x[2]), v(x[3], x[4], x[5]))
    }
    #[test]
    fn powered_orbit_converges_quadratically_to_independent_rk4() {
        let (reference_position, reference_velocity) = oracle(0.002, 20.0);
        let mut errors = Vec::new();
        for step in [0.05, 0.025, 0.0125] {
            let mut r = orbital(step);
            control(
                &mut r,
                FlightControl {
                    throttle: 1.0,
                    ..FlightControl::default()
                },
            );
            advance(&mut r, 20.0);
            errors.push(magnitude(sub(ship(&r).position_m, reference_position)));
            assert!(magnitude(sub(ship(&r).velocity_mps, reference_velocity)) < 1e-5);
        }
        assert!(errors[0] < 0.002, "errors {errors:?}");
        assert!(
            errors[1] < errors[0] * 0.35 && errors[2] < errors[1] * 0.4,
            "errors {errors:?}"
        );
    }
    #[test]
    fn unforced_rigid_body_preserves_energy_and_world_angular_momentum() {
        let mut r = vacuum(0.025);
        r.active_branch_mut().world.spacecraft[0].angular_velocity_body_radps = v(0.1, 0.2, 0.3);
        fn momentum(s: &SpacecraftState) -> Vector3d {
            s.attitude.rotate(v(
                s.inertia_kg_m2.x * s.angular_velocity_body_radps.x,
                s.inertia_kg_m2.y * s.angular_velocity_body_radps.y,
                s.inertia_kg_m2.z * s.angular_velocity_body_radps.z,
            ))
        }
        fn energy(s: &SpacecraftState) -> f64 {
            let w = s.angular_velocity_body_radps;
            0.5 * (s.inertia_kg_m2.x * w.x * w.x
                + s.inertia_kg_m2.y * w.y * w.y
                + s.inertia_kg_m2.z * w.z * w.z)
        }
        let s = r.spacecraft_by_id("ship").unwrap();
        let h0 = momentum(s);
        let e0 = energy(s);
        advance(&mut r, 100.0);
        let s = r.spacecraft_by_id("ship").unwrap();
        assert!((energy(s) / e0 - 1.0).abs() < 1e-7);
        assert!(magnitude(sub(momentum(s), h0)) / magnitude(h0) < 1e-5);
    }
}
