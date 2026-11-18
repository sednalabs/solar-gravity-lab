//! Finite-burn lunar mission using the existing authoritative world.
use crate::{BodyState, RuntimeError, RuntimeEvent, WorldCommand, WorldRuntime};
use solarlab_domain::spacecraft::{add, cross, dot, magnitude, scale, sub, unit};
use solarlab_domain::{BodyClass, BodyId, Quaterniond, SpacecraftState};
pub const LUNAR_FLIGHT_BODY_ID: &str = "flight.lunar";
pub const LUNAR_FLIGHT_MODEL: &str = "lunar-flyby-finite-burn-v1";
pub const LUNAR_DEPARTURE_BURN_SECONDS: f64 = 300.0;
pub const LUNAR_INSERTION_PHASE_RADIANS: f64 = -2.578;
pub const LUNAR_INSERTION_ALTITUDE_M: f64 = 400_000.0;
pub const LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS: [f64; 2] = [2.59 * 86400.0, 2.63 * 86400.0];
/// Scenario goals, deliberately distinct from the physical gravity model.
pub const LUNAR_FLYBY_SUCCESS_ALTITUDE_M: f64 = 1_000_000.0;
pub const LUNAR_APPROACH_ALTITUDE_M: f64 = 60_000_000.0;
pub const LUNAR_MISSION_DEADLINE_SECONDS: f64 = 4.0 * 86_400.0;
const G: f64 = 6.67430e-11;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LunarMissionPhase {
    Ready,
    PoweredFlight,
    Coast,
    LunarApproach,
    LunarFlyby,
    LunarSurfaceCrossing,
    EarthSurfaceCrossing,
    EscapingEarth,
    Missed,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LunarMissionOutcome {
    InProgress,
    FlybyAchieved,
    LunarSurfaceCrossing,
    EarthSurfaceCrossing,
    Missed,
}
#[derive(Clone, Debug, PartialEq)]
pub struct LunarMissionTelemetry {
    pub model: &'static str,
    pub body_id: String,
    pub epoch_seconds: f64,
    pub launch_epoch_seconds: f64,
    pub elapsed_seconds: f64,
    pub phase: LunarMissionPhase,
    pub outcome: LunarMissionOutcome,
    pub earth_altitude_m: f64,
    pub moon_altitude_m: f64,
    /// Negative means approaching the Moon; positive means receding.
    pub moon_range_rate_mps: f64,
    pub moon_relative_speed_mps: f64,
    pub closest_observed_moon_altitude_m: f64,
    pub closest_observed_epoch_seconds: f64,
    pub departure_burn_seconds: f64,
    pub main_engine_full_throttle_seconds: f64,
    pub departure_remaining_seconds: f64,
    pub guided_burn_active: bool,
    pub guided_burn_remaining_seconds: f64,
    pub remaining_propellant_kg: f64,
    /// Surface crossing is detected, but contact/destruction response is absent.
    pub surface_response_modelled: bool,
}

impl WorldRuntime {
    /// Only untouched epoch-zero Earth–Moon lab is accepted. The application
    /// creates a dedicated session to preserve existing experiments.
    pub fn spawn_lunar_spacecraft(
        &mut self,
        ship_id: &str,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        let world = &self.active_branch().world;
        let seed = solarlab_data::earth_moon_lab_bodies();
        if self.scenario_id.0 != solarlab_data::EARTH_MOON_LAB_ID
            || world.epoch_seconds != 0.0
            || world.bodies.len() != seed.len()
            || !world.spacecraft.is_empty()
            || world
                .initial_conditions_provenance
                .as_ref()
                .is_none_or(|p| p.digest != solarlab_data::EARTH_MOON_LAB_DIGEST)
            || seed.iter().any(|s| {
                world
                    .bodies
                    .iter()
                    .find(|b| b.body_id.0 == s.body_id)
                    .is_none_or(|b| {
                        b.body_class != s.body_class
                            || b.position_m != s.position_m
                            || b.velocity_mps != s.velocity_mps
                            || b.source_mass_kg != s.mass_kg
                            || b.mass_kg != s.mass_kg
                            || b.radius_m != s.radius_m
                    })
            })
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        let earth = self.body_by_id("earth").expect("seed checked");
        let moon = self.body_by_id("moon").expect("seed checked");
        let p = sub(moon.position_m, earth.position_m);
        let v = sub(moon.velocity_mps, earth.velocity_mps);
        let x = unit(p).ok_or(RuntimeError::InvalidFlightState)?;
        let normal = unit(cross(p, v)).ok_or(RuntimeError::InvalidFlightState)?;
        let y = cross(normal, x);
        let radial = add(
            scale(x, LUNAR_INSERTION_PHASE_RADIANS.cos()),
            scale(y, LUNAR_INSERTION_PHASE_RADIANS.sin()),
        );
        let tangent = cross(normal, radial);
        let radius = earth.radius_m + LUNAR_INSERTION_ALTITUDE_M;
        let speed = (G * earth.source_mass_kg / radius).sqrt();
        let mut spacecraft =
            SpacecraftState::reference(BodyId(ship_id.into()), Some(earth.body_id.clone()));
        spacecraft.attitude =
            Quaterniond::pointing(tangent).ok_or(RuntimeError::InvalidFlightState)?;
        let body = BodyState {
            body_id: spacecraft.body_id.clone(),
            body_class: BodyClass::Spacecraft,
            mass_kg: spacecraft.mass_kg(),
            source_mass_kg: 0.0,
            radius_m: 8.0,
            position_m: add(earth.position_m, scale(radial, radius)),
            velocity_mps: add(earth.velocity_mps, scale(tangent, speed)),
        };
        spacecraft.mission = Some(solarlab_domain::SpacecraftMissionState {
            mission_id: LUNAR_FLIGHT_MODEL.into(),
            target_body_id: moon.body_id.clone(),
            launch_epoch_seconds: 0.0,
            closest_target_distance_m: magnitude(sub(body.position_m, moon.position_m)),
            closest_approach_epoch_seconds: 0.0,
            closest_approach_passed: false,
            reference_surface_crossed: false,
            target_surface_crossed: false,
        });
        self.apply_command(WorldCommand::SpawnSpacecraft { body, spacecraft }, time)
    }
    /// Starts the remaining planned full-throttle impulse. A manual control
    /// command cancels the programme; subsequent corrections remain available.
    pub fn start_lunar_departure(
        &mut self,
        ship_id: &str,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        let mission = self
            .lunar_mission_telemetry(ship_id)
            .ok_or(RuntimeError::InvalidFlightState)?;
        if mission.departure_remaining_seconds <= 0.0 {
            return Err(RuntimeError::InvalidFlightState);
        }
        self.apply_command(
            WorldCommand::StartGuidedBurn {
                body_id: BodyId(ship_id.into()),
                full_throttle_seconds: mission.departure_remaining_seconds,
            },
            time,
        )
    }

    /// Read-only observation of the authoritative world and persisted solver
    /// measurements. Wall-clock timers and prediction archives are not involved.
    #[must_use]
    pub fn lunar_mission_telemetry(&self, ship_id: &str) -> Option<LunarMissionTelemetry> {
        let ship = self.spacecraft_by_id(ship_id)?;
        let mission = ship.mission.as_ref()?;
        if mission.mission_id != LUNAR_FLIGHT_MODEL || mission.target_body_id.0 != "moon" {
            return None;
        }
        let body = self.body_by_id(ship_id)?;
        let earth = self.body_by_id("earth")?;
        let moon = self.body_by_id(&mission.target_body_id.0)?;
        let epoch = self.active_branch().world.epoch_seconds;
        let elapsed = epoch - mission.launch_epoch_seconds;
        let moon_relative = sub(body.position_m, moon.position_m);
        let moon_velocity = sub(body.velocity_mps, moon.velocity_mps);
        let range = magnitude(moon_relative);
        let altitude = range - moon.radius_m;
        let range_rate = if range > 0.0 {
            dot(moon_relative, moon_velocity) / range
        } else {
            0.0
        };
        let earth_relative = sub(body.position_m, earth.position_m);
        let earth_velocity = sub(body.velocity_mps, earth.velocity_mps);
        let earth_distance = magnitude(earth_relative);
        let closest_altitude = mission.closest_target_distance_m - moon.radius_m;
        let outcome = if mission.target_surface_crossed {
            LunarMissionOutcome::LunarSurfaceCrossing
        } else if mission.reference_surface_crossed {
            LunarMissionOutcome::EarthSurfaceCrossing
        } else if mission.closest_approach_passed
            && (body.radius_m..=LUNAR_FLYBY_SUCCESS_ALTITUDE_M).contains(&closest_altitude)
        {
            LunarMissionOutcome::FlybyAchieved
        } else if elapsed >= LUNAR_MISSION_DEADLINE_SECONDS {
            LunarMissionOutcome::Missed
        } else {
            LunarMissionOutcome::InProgress
        };
        let phase = match outcome {
            LunarMissionOutcome::LunarSurfaceCrossing => LunarMissionPhase::LunarSurfaceCrossing,
            LunarMissionOutcome::EarthSurfaceCrossing => LunarMissionPhase::EarthSurfaceCrossing,
            LunarMissionOutcome::FlybyAchieved => LunarMissionPhase::LunarFlyby,
            LunarMissionOutcome::Missed => LunarMissionPhase::Missed,
            LunarMissionOutcome::InProgress => {
                if ship.has_powered_input() {
                    LunarMissionPhase::PoweredFlight
                } else if ship.main_engine_full_throttle_seconds == 0.0 && elapsed < 1.0 {
                    LunarMissionPhase::Ready
                } else if altitude < LUNAR_APPROACH_ALTITUDE_M && range_rate < 0.0 {
                    LunarMissionPhase::LunarApproach
                } else if earth_distance > 925_000_000.0
                    && dot(earth_relative, earth_velocity) > 0.0
                    && 0.5 * dot(earth_velocity, earth_velocity)
                        > G * earth.source_mass_kg / earth_distance
                {
                    LunarMissionPhase::EscapingEarth
                } else {
                    LunarMissionPhase::Coast
                }
            }
        };
        let telemetry = LunarMissionTelemetry {
            model: LUNAR_FLIGHT_MODEL,
            body_id: ship_id.into(),
            epoch_seconds: epoch,
            launch_epoch_seconds: mission.launch_epoch_seconds,
            elapsed_seconds: elapsed,
            phase,
            outcome,
            earth_altitude_m: earth_distance - earth.radius_m,
            moon_altitude_m: altitude,
            moon_range_rate_mps: range_rate,
            moon_relative_speed_mps: magnitude(moon_velocity),
            closest_observed_moon_altitude_m: closest_altitude,
            closest_observed_epoch_seconds: mission.closest_approach_epoch_seconds,
            departure_burn_seconds: LUNAR_DEPARTURE_BURN_SECONDS,
            main_engine_full_throttle_seconds: ship.main_engine_full_throttle_seconds,
            departure_remaining_seconds: (LUNAR_DEPARTURE_BURN_SECONDS
                - ship.main_engine_full_throttle_seconds)
                .max(0.0),
            guided_burn_active: ship.burn_program.is_some(),
            guided_burn_remaining_seconds: ship
                .burn_program
                .as_ref()
                .map_or(0.0, |p| p.remaining_full_throttle_seconds),
            remaining_propellant_kg: ship.propellant_mass_kg,
            surface_response_modelled: false,
        };
        [
            telemetry.epoch_seconds,
            telemetry.launch_epoch_seconds,
            telemetry.elapsed_seconds,
            telemetry.earth_altitude_m,
            telemetry.moon_altitude_m,
            telemetry.moon_range_rate_mps,
            telemetry.moon_relative_speed_mps,
            telemetry.closest_observed_moon_altitude_m,
            telemetry.closest_observed_epoch_seconds,
            telemetry.main_engine_full_throttle_seconds,
            telemetry.departure_remaining_seconds,
            telemetry.guided_burn_remaining_seconds,
            telemetry.remaining_propellant_kg,
        ]
        .iter()
        .all(|v| v.is_finite())
        .then_some(telemetry)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AdvanceState, RuntimeConfig};
    use solarlab_domain::{
        AttitudeMode, BranchId, FlightControl, ScenarioId, TimelineSemantics, Vector3d,
    };
    use solarlab_hardware::HardwareProfile;
    use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};

    fn seeded(step: f64) -> WorldRuntime {
        let mut runtime = WorldRuntime::new(
            ScenarioId(solarlab_data::EARTH_MOON_LAB_ID.into()),
            BranchId("lunar-test".into()),
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
    fn mission(step: f64) -> WorldRuntime {
        let mut runtime = seeded(step);
        runtime
            .spawn_lunar_spacecraft(LUNAR_FLIGHT_BODY_ID, 0)
            .unwrap();
        runtime
    }
    fn advance(runtime: &mut WorldRuntime, seconds: f64) {
        let old = runtime.query_advance().map_or(0, |a| a.generation);
        let ordinal = runtime.next_command_ordinal();
        let generation = runtime
            .begin_advance([ordinal, ordinal + 100], old, ordinal, seconds, 0)
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
    fn control(runtime: &mut WorldRuntime, control: FlightControl) {
        runtime
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                    control,
                },
                0,
            )
            .unwrap();
    }
    fn guided(runtime: &mut WorldRuntime, seconds: f64) {
        runtime
            .apply_command(
                WorldCommand::StartGuidedBurn {
                    body_id: BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                    full_throttle_seconds: seconds,
                },
                0,
            )
            .unwrap();
    }
    fn encounter(step: f64, burn: f64) -> (WorldRuntime, LunarMissionTelemetry) {
        let mut runtime = mission(step);
        guided(&mut runtime, burn);
        advance(&mut runtime, burn + 25.0);
        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 10_000.0,
                },
                0,
            )
            .unwrap();
        loop {
            let t = runtime
                .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
                .unwrap();
            if t.outcome != LunarMissionOutcome::InProgress {
                return (runtime, t);
            }
            let dt = if t.moon_altitude_m < 5_000_000.0 {
                10.0
            } else if t.moon_altitude_m < 20_000_000.0 {
                60.0
            } else {
                600.0
            };
            advance(&mut runtime, dt);
        }
    }

    #[test]
    fn native_guided_cutoff_splits_timestep_and_manual_commands_cancel() {
        let mut runtime = mission(10.0);
        let initial = runtime
            .spacecraft_by_id(LUNAR_FLIGHT_BODY_ID)
            .unwrap()
            .clone();
        let flow = initial.engines[0].max_mass_flow_kgps;
        guided(&mut runtime, 1.137);
        advance(&mut runtime, 5.0);
        let ship = runtime.spacecraft_by_id(LUNAR_FLIGHT_BODY_ID).unwrap();
        assert!((ship.main_engine_full_throttle_seconds - 1.137).abs() < 1e-10);
        assert!((ship.propellant_mass_kg - (8000.0 - flow * 1.137)).abs() < 1e-8);
        assert!(ship.burn_program.is_none());
        assert_eq!(ship.control.throttle, 0.0);
        assert_eq!(ship.control.attitude_mode, AttitudeMode::Hold);
        guided(&mut runtime, 5.0);
        advance(&mut runtime, 0.4);
        control(
            &mut runtime,
            FlightControl {
                attitude_mode: AttitudeMode::Hold,
                ..FlightControl::default()
            },
        );
        advance(&mut runtime, 10.0);
        let ship = runtime.spacecraft_by_id(LUNAR_FLIGHT_BODY_ID).unwrap();
        assert!(ship.burn_program.is_none());
        assert!((ship.main_engine_full_throttle_seconds - 1.537).abs() < 1e-10);
        // Programme cancellation does not permanently disable later burns.
        control(
            &mut runtime,
            FlightControl {
                throttle: 0.5,
                ..FlightControl::default()
            },
        );
        advance(&mut runtime, 2.0);
        assert!(
            (runtime
                .spacecraft_by_id(LUNAR_FLIGHT_BODY_ID)
                .unwrap()
                .main_engine_full_throttle_seconds
                - 2.537)
                .abs()
                < 1e-10
        );
    }

    #[test]
    fn main_firing_integral_excludes_rcs_and_respects_actual_depletion() {
        let mut runtime = mission(10.0);
        let initial = runtime
            .spacecraft_by_id(LUNAR_FLIGHT_BODY_ID)
            .unwrap()
            .clone();
        let translation = Vector3d {
            x: 1.0,
            y: 0.0,
            z: 0.0,
        };
        control(
            &mut runtime,
            FlightControl {
                translation_body: translation,
                ..FlightControl::default()
            },
        );
        advance(&mut runtime, 2.0);
        assert_eq!(
            runtime
                .spacecraft_by_id(LUNAR_FLIGHT_BODY_ID)
                .unwrap()
                .main_engine_full_throttle_seconds,
            0.0
        );
        control(
            &mut runtime,
            FlightControl {
                throttle: 0.35,
                translation_body: translation,
                ..FlightControl::default()
            },
        );
        advance(&mut runtime, 1.0);
        let ship = runtime.spacecraft_by_id(LUNAR_FLIGHT_BODY_ID).unwrap();
        assert!((ship.main_engine_full_throttle_seconds - 0.35).abs() < 1e-12);
        let expected_fuel = 8000.0
            - initial.engines[1].max_mass_flow_kgps * 3.0
            - initial.engines[0].max_mass_flow_kgps * 0.35;
        assert!((ship.propellant_mass_kg - expected_fuel).abs() < 1e-8);

        let mut runtime = seeded(10.0);
        let earth = runtime.body_by_id("earth").unwrap();
        let mut state = SpacecraftState::reference(
            BodyId(LUNAR_FLIGHT_BODY_ID.into()),
            Some(BodyId("earth".into())),
        );
        state.propellant_mass_kg = 2.0;
        let expected_seconds = state.propellant_mass_kg / state.engines[0].max_mass_flow_kgps;
        let body = BodyState {
            body_id: state.body_id.clone(),
            body_class: BodyClass::Spacecraft,
            mass_kg: state.mass_kg(),
            source_mass_kg: 0.0,
            radius_m: 8.0,
            position_m: add(
                earth.position_m,
                Vector3d {
                    x: earth.radius_m + 400_000.0,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            velocity_mps: add(
                earth.velocity_mps,
                Vector3d {
                    x: 0.0,
                    y: 7700.0,
                    z: 0.0,
                },
            ),
        };
        runtime
            .apply_command(
                WorldCommand::SpawnSpacecraft {
                    body,
                    spacecraft: state,
                },
                0,
            )
            .unwrap();
        guided(&mut runtime, 10.0);
        advance(&mut runtime, 20.0);
        let ship = runtime.spacecraft_by_id(LUNAR_FLIGHT_BODY_ID).unwrap();
        assert_eq!(ship.propellant_mass_kg, 0.0);
        assert!((ship.main_engine_full_throttle_seconds - expected_seconds).abs() < 1e-12);
        assert!(ship.burn_program.is_none());
        assert_eq!(ship.control.throttle, 0.0);
    }

    #[test]
    fn mission_save_resume_preserves_inflight_program_and_model_gate() {
        let mut runtime = mission(10.0);
        runtime
            .start_lunar_departure(LUNAR_FLIGHT_BODY_ID, 0)
            .unwrap();
        advance(&mut runtime, 100.0);
        let saved = runtime.export_saved_session().unwrap();
        let mut document: serde_json::Value = serde_json::from_slice(&saved).unwrap();
        assert_eq!(document["flight_model"], "six-dof-strang-rocket-v2");
        let mut restored =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        assert_eq!(
            runtime.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID),
            restored.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
        );
        document["flight_model"] = serde_json::json!("six-dof-strang-rocket-v1");
        assert!(WorldRuntime::import_saved_session(
            &serde_json::to_vec(&document).unwrap(),
            HardwareProfile::offline_reference()
        )
        .is_err());
        advance(&mut runtime, 225.0);
        advance(&mut restored, 225.0);
        assert_eq!(runtime.snapshot().bodies, restored.snapshot().bodies);
        assert_eq!(
            runtime.snapshot().spacecraft,
            restored.snapshot().spacecraft
        );
        let t = runtime
            .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            .unwrap();
        assert!(!t.guided_burn_active);
        assert!((t.main_engine_full_throttle_seconds - 300.0).abs() < 1e-7);
        assert!((t.remaining_propellant_kg - 1881.702722).abs() < 0.001);
    }

    #[test]
    fn mission_setup_and_invalid_guidance_reject_without_mutation() {
        let mut runtime = seeded(10.0);
        advance(&mut runtime, 1.0);
        let before = runtime.export_saved_session().unwrap();
        assert!(runtime
            .spawn_lunar_spacecraft(LUNAR_FLIGHT_BODY_ID, 0)
            .is_err());
        assert_eq!(before, runtime.export_saved_session().unwrap());
        let mut runtime = mission(10.0);
        let before = runtime.export_saved_session().unwrap();
        for seconds in [0.0, -1.0, f64::NAN, f64::INFINITY, 86_401.0] {
            assert!(runtime
                .apply_command(
                    WorldCommand::StartGuidedBurn {
                        body_id: BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                        full_throttle_seconds: seconds
                    },
                    0
                )
                .is_err());
            assert_eq!(before, runtime.export_saved_session().unwrap());
        }
        assert!(runtime.spawn_lunar_spacecraft("second-ship", 0).is_err());
        assert_eq!(before, runtime.export_saved_session().unwrap());
    }

    #[test]
    fn planned_finite_burn_reaches_moon_and_persists_actual_encounter() {
        let (runtime, telemetry) = encounter(10.0, 300.0);
        assert_eq!(telemetry.outcome, LunarMissionOutcome::FlybyAchieved);
        assert!((470_000.0..500_000.0).contains(&telemetry.closest_observed_moon_altitude_m));
        assert!(
            (LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS[0]..LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS[1])
                .contains(&telemetry.closest_observed_epoch_seconds)
        );
        assert!(!telemetry.surface_response_modelled);
        assert!(telemetry.moon_range_rate_mps > 0.0);
        assert!((telemetry.main_engine_full_throttle_seconds - 300.0).abs() < 1e-7);
        let saved = runtime.export_saved_session().unwrap();
        let restored =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        assert_eq!(
            Some(telemetry),
            restored.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
        );
    }

    #[test]
    fn lunar_flyby_converges_under_finer_coast_integration() {
        let (_, coarse) = encounter(2.0, 300.0);
        let (_, middle) = encounter(1.0, 300.0);
        let (_, fine) = encounter(0.5, 300.0);
        for telemetry in [&coarse, &middle, &fine] {
            assert_eq!(telemetry.outcome, LunarMissionOutcome::FlybyAchieved);
            assert!((480_000.0..490_000.0).contains(&telemetry.closest_observed_moon_altitude_m));
        }
        let coarse_error =
            (coarse.closest_observed_moon_altitude_m - fine.closest_observed_moon_altitude_m).abs();
        let middle_error =
            (middle.closest_observed_moon_altitude_m - fine.closest_observed_moon_altitude_m).abs();
        assert!(
            middle_error < coarse_error * 0.35,
            "coast refinement: {coarse_error}m vs {middle_error}m"
        );
        assert!(middle_error < 200.0);
    }

    #[test]
    fn staged_mission_observations_do_not_leak_before_commit_or_after_cancel() {
        let mut runtime = mission(10.0);
        guided(&mut runtime, 300.0);
        advance(&mut runtime, 325.0);
        let before = runtime.snapshot();
        let telemetry = runtime.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID);
        let old = runtime.query_advance().unwrap().generation;
        let ordinal = runtime.next_command_ordinal();
        let generation = runtime
            .begin_advance([99, 100], old, ordinal, 100_000.0, 0)
            .unwrap()
            .generation;
        let (operation, _) = runtime.continue_advance(generation, 0).unwrap();
        assert_ne!(operation.state, AdvanceState::Committed);
        assert_eq!(
            runtime.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID),
            telemetry
        );
        runtime.cancel_advance(generation).unwrap();
        assert_eq!(runtime.snapshot().bodies, before.bodies);
        assert_eq!(runtime.snapshot().spacecraft, before.spacecraft);
    }
    #[test]
    fn physical_surface_crossings_and_missing_target_are_explicit() {
        for (origin_id, expected) in [
            ("earth", LunarMissionOutcome::EarthSurfaceCrossing),
            ("moon", LunarMissionOutcome::LunarSurfaceCrossing),
        ] {
            let mut runtime = seeded(10.0);
            let origin = runtime.body_by_id(origin_id).unwrap();
            let moon = runtime.body_by_id("moon").unwrap();
            let mut ship = SpacecraftState::reference(
                BodyId(LUNAR_FLIGHT_BODY_ID.into()),
                Some(BodyId("earth".into())),
            );
            let body = BodyState {
                body_id: ship.body_id.clone(),
                body_class: BodyClass::Spacecraft,
                mass_kg: ship.mass_kg(),
                source_mass_kg: 0.0,
                radius_m: 8.0,
                position_m: add(
                    origin.position_m,
                    Vector3d {
                        x: origin.radius_m + 100.0,
                        y: 0.0,
                        z: 0.0,
                    },
                ),
                velocity_mps: add(
                    origin.velocity_mps,
                    Vector3d {
                        x: -100.0,
                        y: 0.0,
                        z: 0.0,
                    },
                ),
            };
            ship.mission = Some(solarlab_domain::SpacecraftMissionState {
                mission_id: LUNAR_FLIGHT_MODEL.into(),
                target_body_id: BodyId("moon".into()),
                launch_epoch_seconds: 0.0,
                closest_target_distance_m: magnitude(sub(body.position_m, moon.position_m)),
                closest_approach_epoch_seconds: 0.0,
                closest_approach_passed: false,
                reference_surface_crossed: false,
                target_surface_crossed: false,
            });
            runtime
                .apply_command(
                    WorldCommand::SpawnSpacecraft {
                        body,
                        spacecraft: ship,
                    },
                    0,
                )
                .unwrap();
            advance(&mut runtime, 5.0);
            let telemetry = runtime
                .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
                .unwrap();
            assert_eq!(telemetry.outcome, expected);
            assert!(!telemetry.surface_response_modelled);
            let saved = runtime.export_saved_session().unwrap();
            let restored =
                WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                    .unwrap();
            assert_eq!(
                Some(telemetry),
                restored.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            );
        }
        let mut runtime = mission(10.0);
        guided(&mut runtime, 300.0);
        runtime
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId("moon".into()),
                },
                0,
            )
            .unwrap();
        assert!(runtime
            .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            .is_none());
        let ship = runtime.spacecraft_by_id(LUNAR_FLIGHT_BODY_ID).unwrap();
        assert!(ship.mission.is_none() && ship.burn_program.is_none());
        assert_eq!(ship.control.throttle, 0.0);
        assert!(runtime.export_saved_session().is_ok());
    }

    #[test]
    fn missed_departure_reports_deadline_without_fabricating_a_manoeuvre() {
        let mut runtime = mission(10.0);
        advance(&mut runtime, LUNAR_MISSION_DEADLINE_SECONDS);
        let telemetry = runtime
            .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            .unwrap();
        assert_eq!(telemetry.outcome, LunarMissionOutcome::Missed);
        assert_eq!(telemetry.phase, LunarMissionPhase::Missed);
        assert_eq!(telemetry.main_engine_full_throttle_seconds, 0.0);
        assert_eq!(telemetry.remaining_propellant_kg, 8000.0);
        assert!(telemetry.closest_observed_moon_altitude_m > 300_000_000.0);
    }
    #[test]
    fn long_burn_selected_schedule_keeps_observations_within_committed_epoch() {
        let mut runtime = mission(10.0);
        guided(&mut runtime, 300.0);
        // The operation selects0.05s before ignition and retains that schedule
        // after cutoff:1,728,000 substeps expose naive timestamp summation drift.
        advance(&mut runtime, 86_400.0);
        let telemetry = runtime
            .lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
            .unwrap();
        assert!(telemetry.closest_observed_epoch_seconds <= telemetry.epoch_seconds);
        assert!((telemetry.closest_observed_epoch_seconds - 86_400.0).abs() < 1e-8);
        let saved = runtime.export_saved_session().unwrap();
        let reopened =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        assert_eq!(
            Some(telemetry),
            reopened.lunar_mission_telemetry(LUNAR_FLIGHT_BODY_ID)
        );
    }
}
