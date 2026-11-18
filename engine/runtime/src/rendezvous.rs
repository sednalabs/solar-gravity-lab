//! Nearby orbital approach using the existing flight components, without contact.
//! Observations are derived from BodyState; guidance emits ordinary RCS controls.
use super::*;
use solarlab_domain::spacecraft::{add, cross, dot, finite, magnitude, scale, sub};
use solarlab_domain::{AttitudeMode, EngineControl, LocalReferenceFrame};

const G: f64 = 6.67430e-11;
const MAX_GUIDANCE_RANGE_M: f64 = 20_000.0;

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
pub struct RendezvousScenario {
    pub reference_body_id: BodyId,
    pub target_body_id: BodyId,
    pub chaser_body_id: BodyId,
    pub altitude_m: f64,
    /// Initial trailing separation measured along the common circular orbit.
    pub initial_separation_m: f64,
}

impl Default for RendezvousScenario {
    fn default() -> Self {
        Self {
            reference_body_id: BodyId("earth".into()),
            target_body_id: BodyId("rendezvous.station".into()),
            chaser_body_id: BodyId("rendezvous.chaser".into()),
            altitude_m: 400_000.0,
            initial_separation_m: 1_000.0,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RendezvousTelemetry {
    pub epoch_seconds: f64,
    pub revision: u64,
    /// Station-fixed axes, including the rotating-frame velocity transport term.
    pub position_target_m: Vector3d,
    pub velocity_target_mps: Vector3d,
    pub relative_velocity_inertial_mps: Vector3d,
    pub range_m: f64,
    /// Positive when the centres are moving closer together.
    pub closing_speed_mps: f64,
    pub relative_speed_mps: f64,
    /// The two represented hull radii plus a 5 m navigation margin; no contact solver.
    pub keep_out_distance_m: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RendezvousStatus {
    Approaching,
    VelocityMatching,
    Holding,
    KeepOut,
    OutOfFuel,
    OutOfRange,
    TargetUnavailable,
}

/// Stateless local guidance. Keep this explicit configuration with the caller's
/// scenario; spacecraft, fuel and actual controls use the existing session save.
/// The controller supports balanced, axis-aligned RCS banks, not arbitrary jets.
#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct RendezvousGuidance {
    pub approach: bool,
    pub stand_off_target_m: Vector3d,
    pub max_approach_speed_mps: f64,
    pub position_gain_per_second: f64,
    pub velocity_response_seconds: f64,
    pub arrival_distance_m: f64,
    pub arrival_speed_mps: f64,
}

impl Default for RendezvousGuidance {
    fn default() -> Self {
        Self {
            approach: true,
            stand_off_target_m: Vector3d {
                x: 0.0,
                y: -75.0,
                z: 0.0,
            },
            max_approach_speed_mps: 2.0,
            position_gain_per_second: 0.04,
            velocity_response_seconds: 8.0,
            arrival_distance_m: 2.0,
            arrival_speed_mps: 0.05,
        }
    }
}

impl RendezvousGuidance {
    #[must_use]
    pub fn velocity_match() -> Self {
        Self {
            approach: false,
            ..Self::default()
        }
    }

    fn valid(self) -> bool {
        finite(self.stand_off_target_m)
            && magnitude(self.stand_off_target_m) < MAX_GUIDANCE_RANGE_M
            && self.max_approach_speed_mps.is_finite()
            && (0.0..=10.0).contains(&self.max_approach_speed_mps)
            && self.max_approach_speed_mps > 0.0
            && self.position_gain_per_second.is_finite()
            && (0.0..=0.2).contains(&self.position_gain_per_second)
            && self.position_gain_per_second > 0.0
            && self.velocity_response_seconds.is_finite()
            && (1.0..=120.0).contains(&self.velocity_response_seconds)
            && self.arrival_distance_m.is_finite()
            && self.arrival_distance_m > 0.0
            && self.arrival_speed_mps.is_finite()
            && self.arrival_speed_mps > 0.0
    }
}

impl WorldRuntime {
    /// Author two initial conditions in the current host's ICRF-XY circular orbit.
    /// The station is an engine-less spacecraft component, not a gravity source.
    /// Both ordinary spawn commands commit together or leave the world alone.
    pub fn spawn_rendezvous_scenario(
        &mut self,
        scenario: &RendezvousScenario,
        recorded_at_unix_ms: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        if !self.active_branch().world.playback.paused {
            return Err(RuntimeError::BodyEditRequiresPause);
        }
        if scenario.chaser_body_id == scenario.target_body_id
            || scenario.chaser_body_id == scenario.reference_body_id
            || scenario.target_body_id == scenario.reference_body_id
            || !scenario.altitude_m.is_finite()
            || scenario.altitude_m <= 0.0
            || !scenario.initial_separation_m.is_finite()
            || !(100.0..=10_000.0).contains(&scenario.initial_separation_m)
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        let host = self
            .body_by_id(&scenario.reference_body_id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(scenario.reference_body_id.clone()))?;
        let radius = host.radius_m + scenario.altitude_m;
        if !radius.is_finite()
            || radius <= scenario.initial_separation_m * 100.0
            || !host.source_mass_kg.is_finite()
            || host.source_mass_kg <= 0.0
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        let circular_speed = (G * host.source_mass_kg / radius).sqrt();
        let mut station =
            SpacecraftState::reference(scenario.target_body_id.clone(), Some(host.body_id.clone()));
        station.dry_mass_kg = 20_000.0;
        station.propellant_mass_kg = 0.0;
        station.engines.clear();
        station.max_control_torque_nm = Vector3d::default();
        station.inertia_kg_m2 = Vector3d {
            x: 800_000.0,
            y: 800_000.0,
            z: 800_000.0,
        };
        let chaser =
            SpacecraftState::reference(scenario.chaser_body_id.clone(), Some(host.body_id.clone()));
        let make_body = |ship: &SpacecraftState, angle: f64, hull_radius: f64| BodyState {
            body_id: ship.body_id.clone(),
            body_class: BodyClass::Spacecraft,
            mass_kg: ship.mass_kg(),
            source_mass_kg: 0.0,
            radius_m: hull_radius,
            position_m: add(
                host.position_m,
                Vector3d {
                    x: radius * angle.cos(),
                    y: radius * angle.sin(),
                    z: 0.0,
                },
            ),
            velocity_mps: add(
                host.velocity_mps,
                Vector3d {
                    x: -circular_speed * angle.sin(),
                    y: circular_speed * angle.cos(),
                    z: 0.0,
                },
            ),
        };
        let station_body = make_body(&station, 0.0, 12.0);
        let chaser_body = make_body(&chaser, -scenario.initial_separation_m / radius, 8.0);
        let mut staged = self.clone();
        let mut events = staged.apply_command(
            WorldCommand::SpawnSpacecraft {
                body: station_body,
                spacecraft: station,
            },
            recorded_at_unix_ms,
        )?;
        events.extend(staged.apply_command(
            WorldCommand::SpawnSpacecraft {
                body: chaser_body,
                spacecraft: chaser,
            },
            recorded_at_unix_ms,
        )?);
        *self = staged;
        Ok(events)
    }

    /// Resolve stable IDs at every observation. There is no cached solver index.
    pub fn rendezvous_telemetry(
        &self,
        scenario: &RendezvousScenario,
    ) -> Result<RendezvousTelemetry, RuntimeError> {
        if scenario.chaser_body_id == scenario.target_body_id {
            return Err(RuntimeError::InvalidFlightState);
        }
        let (target, frame) = self.rendezvous_target_frame(&scenario.target_body_id)?;
        let chaser = self
            .body_by_id(&scenario.chaser_body_id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(scenario.chaser_body_id.clone()))?;
        self.spacecraft_by_id(&scenario.chaser_body_id.0)
            .ok_or(RuntimeError::InvalidFlightState)?;
        let (position, velocity) = frame.to_local(chaser.position_m, chaser.velocity_mps);
        let relative_velocity = sub(chaser.velocity_mps, target.velocity_mps);
        let displacement = sub(chaser.position_m, target.position_m);
        let range = magnitude(displacement);
        let relative_speed = magnitude(relative_velocity);
        let keep_out = target.radius_m + chaser.radius_m + 5.0;
        if !finite(position)
            || !finite(velocity)
            || !range.is_finite()
            || !relative_speed.is_finite()
            || !keep_out.is_finite()
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        Ok(RendezvousTelemetry {
            epoch_seconds: self.active_branch().world.epoch_seconds,
            revision: self.snapshot_revision(),
            position_target_m: position,
            velocity_target_mps: velocity,
            relative_velocity_inertial_mps: relative_velocity,
            range_m: range,
            closing_speed_mps: if range > 0.0 {
                -dot(displacement, relative_velocity) / range
            } else {
                0.0
            },
            relative_speed_mps: relative_speed,
            keep_out_distance_m: keep_out,
        })
    }

    fn rendezvous_target_frame(
        &self,
        id: &BodyId,
    ) -> Result<(&BodyState, LocalReferenceFrame), RuntimeError> {
        let target = self
            .body_by_id(&id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(id.clone()))?;
        let state = self
            .spacecraft_by_id(&id.0)
            .ok_or(RuntimeError::InvalidFlightState)?;
        Ok((
            target,
            LocalReferenceFrame {
                origin_m: target.position_m,
                velocity_mps: target.velocity_mps,
                attitude: state.attitude,
                angular_velocity_world_radps: state
                    .attitude
                    .rotate(state.angular_velocity_body_radps),
            },
        ))
    }

    /// Compute bounded body-axis RCS demand without mutation or integration.
    /// Missing targets and excessive range return neutral demand so the apply
    /// wrapper cuts any previously latched throttle/RCS command immediately.
    pub fn rendezvous_control(
        &self,
        scenario: &RendezvousScenario,
        guidance: RendezvousGuidance,
    ) -> Result<(FlightControl, RendezvousStatus), RuntimeError> {
        if !guidance.valid() || scenario.chaser_body_id == scenario.target_body_id {
            return Err(RuntimeError::InvalidFlightState);
        }
        let ship = self
            .spacecraft_by_id(&scenario.chaser_body_id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(scenario.chaser_body_id.clone()))?;
        let neutral = FlightControl {
            attitude_mode: AttitudeMode::Hold,
            ..FlightControl::default()
        };
        if self
            .rendezvous_target_frame(&scenario.target_body_id)
            .is_err()
        {
            return Ok((neutral, RendezvousStatus::TargetUnavailable));
        }
        let t = self.rendezvous_telemetry(scenario)?;
        if guidance.approach
            && magnitude(guidance.stand_off_target_m)
                <= t.keep_out_distance_m + guidance.arrival_distance_m
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        if t.range_m > MAX_GUIDANCE_RANGE_M {
            return Ok((neutral, RendezvousStatus::OutOfRange));
        }
        if ship.propellant_mass_kg <= 0.0 {
            return Ok((neutral, RendezvousStatus::OutOfFuel));
        }
        let (_, frame) = self.rendezvous_target_frame(&scenario.target_body_id)?;
        let chaser = self
            .body_by_id(&scenario.chaser_body_id.0)
            .ok_or(RuntimeError::InvalidFlightState)?;
        let error = sub(t.position_target_m, guidance.stand_off_target_m);
        let (target_position, desired_local_velocity, status) = if t.range_m < t.keep_out_distance_m
        {
            let outward = if t.range_m > 1e-9 {
                scale(
                    t.position_target_m,
                    guidance.max_approach_speed_mps / t.range_m,
                )
            } else {
                Vector3d {
                    x: guidance.max_approach_speed_mps,
                    y: 0.0,
                    z: 0.0,
                }
            };
            (t.position_target_m, outward, RendezvousStatus::KeepOut)
        } else if guidance.approach
            && segment_clearance_m(t.position_target_m, guidance.stand_off_target_m)
                <= t.keep_out_distance_m + guidance.arrival_distance_m
        {
            // Brake rather than approach through the station. A clear waypoint
            // is required; this controller does not plan collision-free paths.
            (
                t.position_target_m,
                Vector3d::default(),
                RendezvousStatus::KeepOut,
            )
        } else if guidance.approach {
            let desired = bounded_magnitude(
                scale(error, -guidance.position_gain_per_second),
                guidance.max_approach_speed_mps,
            );
            let status = if magnitude(error) <= guidance.arrival_distance_m
                && magnitude(t.velocity_target_mps) <= guidance.arrival_speed_mps
            {
                RendezvousStatus::Holding
            } else {
                RendezvousStatus::Approaching
            };
            (guidance.stand_off_target_m, desired, status)
        } else {
            (
                t.position_target_m,
                Vector3d::default(),
                RendezvousStatus::VelocityMatching,
            )
        };
        let (_, desired_world_velocity) = frame.to_world(target_position, desired_local_velocity);
        let acceleration = scale(
            sub(desired_world_velocity, chaser.velocity_mps),
            1.0 / guidance.velocity_response_seconds,
        );
        let force = scale(
            ship.attitude.conjugate().rotate(acceleration),
            ship.mass_kg(),
        );
        let capacities = axis_rcs_capacities(ship)?;
        let axis = |f: f64, positive: usize, negative: usize| {
            (f / capacities[if f >= 0.0 { positive } else { negative }]).clamp(-1.0, 1.0)
        };
        let control = FlightControl {
            translation_body: Vector3d {
                x: axis(force.x, 0, 1),
                y: axis(force.y, 2, 3),
                z: axis(force.z, 4, 5),
            },
            ..neutral
        };
        if !control.valid() {
            return Err(RuntimeError::InvalidFlightState);
        }
        Ok((control, status))
    }

    /// Apply the same public flight command used by manual controls. Call at a
    /// bounded cadence (the example uses 0.5 s); physics still chooses substeps.
    pub fn apply_rendezvous_guidance(
        &mut self,
        scenario: &RendezvousScenario,
        guidance: RendezvousGuidance,
        recorded_at_unix_ms: i64,
    ) -> Result<RendezvousStatus, RuntimeError> {
        let (control, status) = self.rendezvous_control(scenario, guidance)?;
        self.apply_command(
            WorldCommand::SetFlightControl {
                body_id: scenario.chaser_body_id.clone(),
                control,
            },
            recorded_at_unix_ms,
        )?;
        Ok(status)
    }
}

fn bounded_magnitude(v: Vector3d, limit: f64) -> Vector3d {
    let m = magnitude(v);
    if m > limit {
        scale(v, limit / m)
    } else {
        v
    }
}

fn segment_clearance_m(start: Vector3d, end: Vector3d) -> f64 {
    let direction = sub(end, start);
    let squared = dot(direction, direction);
    let fraction = if squared > 0.0 {
        (-dot(start, direction) / squared).clamp(0.0, 1.0)
    } else {
        0.0
    };
    magnitude(add(start, scale(direction, fraction)))
}

fn axis_rcs_capacities(ship: &SpacecraftState) -> Result<[f64; 6], RuntimeError> {
    let mut capacity = [0.0; 6];
    let mut torque = [Vector3d::default(); 6];
    for engine in &ship.engines {
        let (index, direction) = match engine.control {
            EngineControl::Main => continue,
            EngineControl::PositiveX => (
                0,
                Vector3d {
                    x: 1.0,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            EngineControl::NegativeX => (
                1,
                Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            EngineControl::PositiveY => (
                2,
                Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
            ),
            EngineControl::NegativeY => (
                3,
                Vector3d {
                    x: 0.0,
                    y: -1.0,
                    z: 0.0,
                },
            ),
            EngineControl::PositiveZ => (
                4,
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 1.0,
                },
            ),
            EngineControl::NegativeZ => (
                5,
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: -1.0,
                },
            ),
        };
        if magnitude(sub(engine.direction_body, direction)) > 1e-8 {
            return Err(RuntimeError::InvalidFlightState);
        }
        capacity[index] += engine.max_thrust_n;
        torque[index] = add(
            torque[index],
            cross(
                engine.position_body_m,
                scale(direction, engine.max_thrust_n),
            ),
        );
    }
    if capacity
        .iter()
        .enumerate()
        .any(|(i, f)| !f.is_finite() || *f <= 0.0 || magnitude(torque[i]) > f * 1e-8)
    {
        return Err(RuntimeError::InvalidFlightState);
    }
    Ok(capacity)
}

#[cfg(test)]
mod tests {
    use super::*;
    use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

    const EARTH_MASS: f64 = 5.97219e24;
    fn v(x: f64, y: f64, z: f64) -> Vector3d {
        Vector3d { x, y, z }
    }

    fn world(step: f64) -> WorldRuntime {
        let mut r = WorldRuntime::new(
            ScenarioId("rendezvous-test".into()),
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
        );
        r.apply_command(
            WorldCommand::SpawnBody {
                body: BodyState {
                    body_id: BodyId("earth".into()),
                    body_class: BodyClass::Planet,
                    mass_kg: EARTH_MASS,
                    source_mass_kg: EARTH_MASS,
                    radius_m: 6_371_000.0,
                    position_m: Vector3d::default(),
                    velocity_mps: Vector3d::default(),
                },
            },
            0,
        )
        .unwrap();
        r
    }

    fn pair(step: f64) -> (WorldRuntime, RendezvousScenario) {
        let mut r = world(step);
        let s = RendezvousScenario::default();
        r.spawn_rendezvous_scenario(&s, 0).unwrap();
        (r, s)
    }

    fn advance(r: &mut WorldRuntime, duration: f64) {
        let previous = r.query_advance().map_or(0, |a| a.generation);
        let ordinal = r.next_command_ordinal();
        let generation = r
            .begin_advance([ordinal, ordinal + 100], previous, ordinal, duration, 0)
            .unwrap()
            .generation;
        let mut completed = 0;
        loop {
            let (operation, _) = r.continue_advance(generation, completed).unwrap();
            completed = operation.completed_steps;
            if operation.state == AdvanceState::Committed {
                break;
            }
        }
    }

    fn fly(
        r: &mut WorldRuntime,
        s: &RendezvousScenario,
        g: RendezvousGuidance,
        seconds: f64,
    ) -> f64 {
        let mut nearest = f64::INFINITY;
        for _ in 0..(seconds * 2.0) as usize {
            r.apply_rendezvous_guidance(s, g, 0).unwrap();
            let c = r.spacecraft_by_id(&s.chaser_body_id.0).unwrap().control;
            assert!(c.valid());
            assert_eq!(c.throttle, 0.0);
            assert_eq!(c.rotation_body, Vector3d::default());
            advance(r, 0.5);
            nearest = nearest.min(r.rendezvous_telemetry(s).unwrap().range_m);
        }
        nearest
    }

    #[test]
    fn far_side_waypoint_brakes_instead_of_approaching_through_station() {
        let (mut runtime, scenario) = pair(0.05);
        let guidance = RendezvousGuidance {
            stand_off_target_m: v(0.0, 75.0, 0.0),
            ..RendezvousGuidance::default()
        };
        let (braking, _) = runtime
            .rendezvous_control(&scenario, RendezvousGuidance::velocity_match())
            .unwrap();
        assert_eq!(
            runtime
                .apply_rendezvous_guidance(&scenario, guidance, 0)
                .unwrap(),
            RendezvousStatus::KeepOut
        );
        assert_eq!(
            runtime
                .spacecraft_by_id(&scenario.chaser_body_id.0)
                .unwrap()
                .control,
            braking
        );
        assert!(fly(&mut runtime, &scenario, guidance, 30.0) > 900.0);
        assert!(
            runtime
                .rendezvous_telemetry(&scenario)
                .unwrap()
                .relative_speed_mps
                < 0.1
        );
    }

    #[test]
    fn unpowered_pair_matches_independent_circular_relative_solution() {
        let (mut r, s) = pair(0.05);
        let before = r.rendezvous_telemetry(&s).unwrap();
        let radius = 6_371_000.0 + s.altitude_m;
        let mean_motion = (G * EARTH_MASS / radius.powi(3)).sqrt();
        let angle = mean_motion * 600.0;
        let p = before.position_target_m;
        let expected_position = v(
            p.x * angle.cos() - p.y * angle.sin(),
            p.x * angle.sin() + p.y * angle.cos(),
            0.0,
        );
        let expected_velocity = v(
            -mean_motion * expected_position.y,
            mean_motion * expected_position.x,
            0.0,
        );
        advance(&mut r, 600.0);
        let after = r.rendezvous_telemetry(&s).unwrap();
        assert!(
            magnitude(sub(after.position_target_m, expected_position)) < 0.001,
            "{after:?}"
        );
        assert!(magnitude(sub(after.relative_velocity_inertial_mps, expected_velocity)) < 1e-6);
        assert!((after.range_m - before.range_m).abs() < 0.001);
        assert_eq!(
            r.body_by_id("earth").unwrap().velocity_mps,
            Vector3d::default()
        );
        assert_eq!(
            r.spacecraft_by_id(&s.chaser_body_id.0)
                .unwrap()
                .propellant_mass_kg,
            8_000.0
        );
    }

    #[test]
    fn rcs_approach_reaches_stand_off_without_entering_keep_out() {
        let (mut r, s) = pair(0.05);
        let mut coast = r.clone();
        let g = RendezvousGuidance::default();
        let nearest = fly(&mut r, &s, g, 650.0);
        let t = r.rendezvous_telemetry(&s).unwrap();
        assert!(
            magnitude(sub(t.position_target_m, g.stand_off_target_m)) < 1.0,
            "{t:?}"
        );
        assert!(t.relative_speed_mps < 0.02, "{t:?}");
        assert!(nearest > t.keep_out_distance_m);
        assert_eq!(
            r.rendezvous_control(&s, g).unwrap().1,
            RendezvousStatus::Holding
        );
        let fuel = r
            .spacecraft_by_id(&s.chaser_body_id.0)
            .unwrap()
            .propellant_mass_kg;
        assert!(fuel < 8_000.0 && fuel > 7_950.0, "fuel {fuel}");
        advance(&mut coast, 650.0);
        // The passive station's trajectory is unchanged by a firing massive chaser.
        assert_eq!(
            r.body_by_id(&s.target_body_id.0),
            coast.body_by_id(&s.target_body_id.0)
        );
        assert_eq!(
            r.body_by_id(&s.chaser_body_id.0).unwrap().source_mass_kg,
            0.0
        );
    }

    #[test]
    fn velocity_match_cancels_relative_motion_without_approaching_station() {
        let (mut r, s) = pair(0.05);
        let initial = r.rendezvous_telemetry(&s).unwrap();
        assert!(initial.relative_speed_mps > 1.0);
        fly(&mut r, &s, RendezvousGuidance::velocity_match(), 90.0);
        let t = r.rendezvous_telemetry(&s).unwrap();
        assert!(t.relative_speed_mps < 0.03, "{t:?}");
        assert!(t.range_m > 900.0);
    }

    #[test]
    fn controlled_trajectory_converges_with_smaller_physics_steps() {
        let mut final_positions = Vec::new();
        for step in [0.05, 0.025, 0.00625] {
            let (mut r, s) = pair(step);
            fly(&mut r, &s, RendezvousGuidance::default(), 120.0);
            final_positions.push(r.rendezvous_telemetry(&s).unwrap().position_target_m);
        }
        let coarse = magnitude(sub(final_positions[0], final_positions[2]));
        let fine = magnitude(sub(final_positions[1], final_positions[2]));
        assert!(coarse < 0.01, "coarse={coarse}, fine={fine}");
        assert!(fine < coarse * 0.4 + 1e-5, "coarse={coarse}, fine={fine}");
    }

    #[test]
    fn save_resume_preserves_both_identities_and_guided_future_exactly() {
        let (mut r, scenario) = pair(0.05);
        let guidance = RendezvousGuidance::default();
        fly(&mut r, &scenario, guidance, 200.0);
        let saved = r.export_saved_session().unwrap();
        let mut reopened =
            WorldRuntime::import_saved_session(&saved, HardwareProfile::offline_reference())
                .unwrap();
        // Guidance is explicit stateless caller configuration, serializable on its own.
        let (restored_scenario, restored_guidance): (RendezvousScenario, RendezvousGuidance) =
            serde_json::from_slice(&serde_json::to_vec(&(&scenario, guidance)).unwrap()).unwrap();
        fly(&mut r, &scenario, guidance, 450.0);
        fly(&mut reopened, &restored_scenario, restored_guidance, 450.0);
        assert_eq!(r.snapshot().bodies, reopened.snapshot().bodies);
        assert_eq!(r.snapshot().spacecraft, reopened.snapshot().spacecraft);
        assert_eq!(
            r.rendezvous_control(&scenario, guidance).unwrap().1,
            RendezvousStatus::Holding
        );
    }

    #[test]
    fn removal_cuts_latched_controls_and_identity_failures_are_atomic() {
        let (mut r, s) = pair(0.05);
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal();
        assert!(r.spawn_rendezvous_scenario(&s, 0).is_err());
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal(), ordinal);
        r.apply_rendezvous_guidance(&s, RendezvousGuidance::default(), 0)
            .unwrap();
        assert!(r
            .spacecraft_by_id(&s.chaser_body_id.0)
            .unwrap()
            .has_powered_input());
        r.apply_command(
            WorldCommand::RemoveBody {
                body_id: s.target_body_id.clone(),
            },
            0,
        )
        .unwrap();
        assert!(r.rendezvous_telemetry(&s).is_err());
        let position = r.body_by_id(&s.chaser_body_id.0).unwrap().clone();
        assert_eq!(
            r.apply_rendezvous_guidance(&s, RendezvousGuidance::default(), 0)
                .unwrap(),
            RendezvousStatus::TargetUnavailable
        );
        assert!(!r
            .spacecraft_by_id(&s.chaser_body_id.0)
            .unwrap()
            .has_powered_input());
        assert_eq!(r.body_by_id(&s.chaser_body_id.0), Some(&position));
        let mut invalid = s.clone();
        invalid.target_body_id = invalid.chaser_body_id.clone();
        let before = r.snapshot();
        assert!(r
            .apply_rendezvous_guidance(&invalid, RendezvousGuidance::default(), 0)
            .is_err());
        assert_eq!(r.snapshot(), before);
        r.apply_command(
            WorldCommand::RemoveBody {
                body_id: s.chaser_body_id.clone(),
            },
            0,
        )
        .unwrap();
        assert!(r
            .apply_rendezvous_guidance(&s, RendezvousGuidance::default(), 0)
            .is_err());
    }

    #[test]
    fn second_spawn_failure_does_not_leave_an_orphan_station() {
        let mut r = world(0.05);
        let s = RendezvousScenario::default();
        r.spawn_reference_spacecraft("earth", &s.chaser_body_id.0, 400_000.0, 0)
            .unwrap();
        let before = r.snapshot();
        let ordinal = r.next_command_ordinal();
        assert!(r.spawn_rendezvous_scenario(&s, 0).is_err());
        assert_eq!(r.snapshot(), before);
        assert_eq!(r.next_command_ordinal(), ordinal);
        assert!(r.body_by_id(&s.target_body_id.0).is_none());
    }

    #[test]
    fn telemetry_accounts_for_rotating_station_at_astronomical_origin() {
        let mut r = world(0.05);
        let s = RendezvousScenario::default();
        let origin = v(1.5e11, -2e11, 3e10);
        let velocity = v(30_000.0, 1_000.0, -2_000.0);
        let mut station =
            SpacecraftState::reference(s.target_body_id.clone(), Some(BodyId("earth".into())));
        station.attitude = Quaterniond {
            w: 0.5_f64.sqrt(),
            x: 0.0,
            y: 0.0,
            z: 0.5_f64.sqrt(),
        };
        station.angular_velocity_body_radps = v(0.0, 0.0, 0.1);
        station.engines.clear();
        let chaser =
            SpacecraftState::reference(s.chaser_body_id.clone(), Some(BodyId("earth".into())));
        for (ship, p, vel) in [
            (station, origin, velocity),
            (
                chaser,
                add(origin, v(-20.0, 100.0, 4.0)),
                add(velocity, v(-9.6, -1.8, 0.1)),
            ),
        ] {
            r.apply_command(
                WorldCommand::SpawnSpacecraft {
                    body: BodyState {
                        body_id: ship.body_id.clone(),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: ship.mass_kg(),
                        source_mass_kg: 0.0,
                        radius_m: 8.0,
                        position_m: p,
                        velocity_mps: vel,
                    },
                    spacecraft: ship,
                },
                0,
            )
            .unwrap();
        }
        let before = r.snapshot();
        let t = r.rendezvous_telemetry(&s).unwrap();
        assert!(magnitude(sub(t.position_target_m, v(100.0, 20.0, 4.0))) < 1e-8);
        assert!(magnitude(sub(t.velocity_target_mps, v(0.2, -0.4, 0.1))) < 1e-9);
        assert!((t.closing_speed_mps + 12.4 / 10416.0_f64.sqrt()).abs() < 1e-9);
        assert_eq!(r.snapshot(), before);
    }

    #[test]
    fn guidance_respects_body_axes_fuel_range_and_keep_out() {
        let (mut r, s) = pair(0.05);
        let g = RendezvousGuidance::default();
        let original = r.rendezvous_control(&s, g).unwrap().0.translation_body;
        // Fixture orientation changes only attitude, never relative translation.
        r.active_branch_mut()
            .world
            .spacecraft
            .iter_mut()
            .find(|ship| ship.body_id == s.chaser_body_id)
            .unwrap()
            .attitude = Quaterniond {
            w: 0.0,
            x: 0.0,
            y: 0.0,
            z: 1.0,
        };
        let rotated = r.rendezvous_control(&s, g).unwrap().0.translation_body;
        assert!(magnitude(sub(rotated, v(-original.x, -original.y, original.z))) < 1e-12);

        let target = r.body_by_id(&s.target_body_id.0).unwrap().clone();
        for (range, expected) in [
            (20.0, RendezvousStatus::KeepOut),
            (30_000.0, RendezvousStatus::OutOfRange),
        ] {
            r.apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: s.chaser_body_id.clone(),
                    position_m: add(target.position_m, v(range, 0.0, 0.0)),
                    velocity_mps: target.velocity_mps,
                },
                0,
            )
            .unwrap();
            let (control, status) = r.rendezvous_control(&s, g).unwrap();
            assert!(control.valid());
            assert_eq!(status, expected);
            if expected == RendezvousStatus::KeepOut {
                // Chaser's -X axis points outward after its 180 degree rotation.
                assert!(control.translation_body.x < 0.0);
            } else {
                assert_eq!(control.translation_body, Vector3d::default());
            }
        }
        r.apply_command(
            WorldCommand::SetBodyKinematics {
                body_id: s.chaser_body_id.clone(),
                position_m: add(target.position_m, v(100.0, 0.0, 0.0)),
                velocity_mps: target.velocity_mps,
            },
            0,
        )
        .unwrap();
        let world = &mut r.active_branch_mut().world;
        let ship = world
            .spacecraft
            .iter_mut()
            .find(|ship| ship.body_id == s.chaser_body_id)
            .unwrap();
        ship.propellant_mass_kg = 0.0;
        let mass = ship.mass_kg();
        world
            .bodies
            .iter_mut()
            .find(|body| body.body_id == s.chaser_body_id)
            .unwrap()
            .mass_kg = mass;
        let (control, status) = r.rendezvous_control(&s, g).unwrap();
        assert_eq!(status, RendezvousStatus::OutOfFuel);
        assert_eq!(control.translation_body, Vector3d::default());
    }

    #[test]
    fn invalid_guidance_and_unsupported_rcs_leave_state_unchanged() {
        let (mut r, s) = pair(0.05);
        for g in [
            RendezvousGuidance {
                velocity_response_seconds: f64::NAN,
                ..RendezvousGuidance::default()
            },
            RendezvousGuidance {
                stand_off_target_m: Vector3d::default(),
                ..RendezvousGuidance::default()
            },
        ] {
            let before = r.snapshot();
            let ordinal = r.next_command_ordinal();
            assert!(r.apply_rendezvous_guidance(&s, g, 0).is_err());
            assert_eq!(r.snapshot(), before);
            assert_eq!(r.next_command_ordinal(), ordinal);
        }
        let ship = r
            .active_branch_mut()
            .world
            .spacecraft
            .iter_mut()
            .find(|ship| ship.body_id == s.chaser_body_id)
            .unwrap();
        ship.engines
            .iter_mut()
            .find(|e| e.control == EngineControl::PositiveX)
            .unwrap()
            .direction_body = v(0.0, 1.0, 0.0);
        let before = r.snapshot();
        assert!(r
            .apply_rendezvous_guidance(&s, RendezvousGuidance::default(), 0)
            .is_err());
        assert_eq!(r.snapshot(), before);
    }
}
