//! Local station mission on the authoritative world, with no separate pose store.
use super::*;
use solarlab_domain::spacecraft::{add, magnitude, sub};
use solarlab_domain::{AttitudeMode, SpacecraftAppearance, SpacecraftDockingState};
use solarlab_physics::docking::{
    desired_mating_attitude, observe, supports_guidance, DockingObservation,
};

pub const DOCKING_STATION_BODY_ID: &str = "docking.station";

#[derive(Clone, Debug, PartialEq)]
pub struct DockingFlightTelemetry {
    pub body_id: String,
    pub target_body_id: String,
    pub epoch_seconds: f64,
    pub guidance_mode: DockingGuidanceMode,
    pub status: DockingStatus,
    pub capture_armed: bool,
    pub attached: bool,
    pub unsafe_contact: bool,
    pub remaining_propellant_kg: f64,
    pub centre_range_m: f64,
    /// None explicitly denotes a removed or invalid target, never a zero range.
    pub observation: Option<DockingObservation>,
}

pub(super) fn neutral_control() -> FlightControl {
    FlightControl {
        attitude_mode: AttitudeMode::Manual,
        ..FlightControl::default()
    }
}

impl WorldRuntime {
    /// A separate, already aligned 30 m approach at 400 km altitude. This is an
    /// authored initial condition, not a correction applied during flight.
    /// Thereafter both ships respond to the same gravity and local contact solve.
    pub fn spawn_docking_flight(
        &mut self,
        ship_id: &str,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.validate_fresh_station_mission()?;
        if ship_id == DOCKING_STATION_BODY_ID {
            return Err(RuntimeError::InvalidFlightState);
        }
        let host = self
            .body_by_id("earth")
            .ok_or(RuntimeError::InvalidFlightState)?;
        let radius = host.radius_m + 400_000.0;
        let speed = (6.67430e-11 * host.source_mass_kg / radius).sqrt();
        let station_position = add(
            host.position_m,
            Vector3d {
                x: radius,
                y: 0.0,
                z: 0.0,
            },
        );
        let velocity = add(
            host.velocity_mps,
            Vector3d {
                x: 0.0,
                y: speed,
                z: 0.0,
            },
        );
        let mut station = SpacecraftState::reference(
            BodyId(DOCKING_STATION_BODY_ID.into()),
            Some(host.body_id.clone()),
        );
        station.appearance = SpacecraftAppearance::RelayStation;
        station.dry_mass_kg = 20_000.0;
        station.propellant_mass_kg = 0.0;
        station.engines.clear();
        station.max_control_torque_nm = Vector3d::default();
        station.inertia_kg_m2 = Vector3d {
            x: 800_000.0,
            y: 800_000.0,
            z: 800_000.0,
        };
        let mut ship =
            SpacecraftState::reference(BodyId(ship_id.into()), Some(host.body_id.clone()));
        ship.attitude =
            desired_mating_attitude(ship.appearance, station.appearance, station.attitude);
        ship.control = neutral_control();
        ship.docking = Some(SpacecraftDockingState::new(station.body_id.clone()));
        let body = |state: &SpacecraftState, position_m, radius_m| BodyState {
            body_id: state.body_id.clone(),
            body_class: BodyClass::Spacecraft,
            mass_kg: state.mass_kg(),
            source_mass_kg: 0.0,
            radius_m,
            position_m,
            velocity_mps: velocity,
        };
        // Relay's service port points along body -Y; Asteria's +Z nose faces it.
        let station_body = body(&station, station_position, 12.0);
        let ship_body = body(
            &ship,
            add(
                station_position,
                Vector3d {
                    x: 0.0,
                    y: -30.0,
                    z: 0.0,
                },
            ),
            8.0,
        );
        let mut staged = self.clone();
        let mut events = staged.apply_command(
            WorldCommand::SpawnSpacecraft {
                body: station_body,
                spacecraft: station,
            },
            time,
        )?;
        events.extend(staged.apply_command(
            WorldCommand::SpawnSpacecraft {
                body: ship_body,
                spacecraft: ship,
            },
            time,
        )?);
        events.extend(staged.apply_command(
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second: 1.0,
            },
            time,
        )?);
        *self = staged;
        Ok(events)
    }

    pub fn set_docking_guidance(
        &mut self,
        ship_id: &str,
        mode: DockingGuidanceMode,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.apply_command(
            WorldCommand::SetDockingGuidance {
                body_id: BodyId(ship_id.into()),
                mode,
            },
            time,
        )
    }

    pub fn undock_spacecraft(
        &mut self,
        ship_id: &str,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.apply_command(
            WorldCommand::UndockSpacecraft {
                body_id: BodyId(ship_id.into()),
            },
            time,
        )
    }

    pub(super) fn validate_docking_guidance(
        &self,
        body_id: &BodyId,
        mode: DockingGuidanceMode,
    ) -> Result<(), RuntimeError> {
        let ship = self
            .spacecraft_by_id(&body_id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
        let docking = ship
            .docking
            .as_ref()
            .ok_or(RuntimeError::InvalidFlightState)?;
        if mode == DockingGuidanceMode::Manual {
            return Ok(());
        }
        let telemetry = self
            .docking_flight_telemetry(&body_id.0)
            .ok_or(RuntimeError::InvalidFlightState)?;
        let observation = telemetry
            .observation
            .ok_or(RuntimeError::InvalidFlightState)?;
        if docking.attachment.is_some() || !supports_guidance(ship) || ship.propellant_mass_kg <= 0.0
            || telemetry.centre_range_m > solarlab_domain::docking::MAX_DOCKING_GUIDANCE_RANGE_M
            // An abort cannot be cleared while interpenetrating or still arriving fast.
            || observation.position_port_m.z < -solarlab_domain::docking::DOCKING_CONTACT_PENETRATION_M
            || (docking.unsafe_contact && observation.relative_speed_mps > solarlab_domain::docking::DOCKING_CAPTURE_SPEED_MPS)
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        Ok(())
    }

    #[must_use]
    pub fn docking_flight_telemetry(&self, ship_id: &str) -> Option<DockingFlightTelemetry> {
        let ship = self.spacecraft_by_id(ship_id)?;
        let body = self.body_by_id(ship_id)?;
        let docking = ship.docking.as_ref()?;
        let target = self.body_by_id(&docking.target_body_id.0);
        let target_ship = self.spacecraft_by_id(&docking.target_body_id.0);
        let observation = target.zip(target_ship).and_then(|(target, state)| {
            observe(
                body.position_m,
                body.velocity_mps,
                ship,
                target.position_m,
                target.velocity_mps,
                state,
            )
            .ok()
        });
        Some(DockingFlightTelemetry {
            body_id: ship_id.into(),
            target_body_id: docking.target_body_id.0.clone(),
            epoch_seconds: self.active_branch().world.epoch_seconds,
            guidance_mode: docking.guidance_mode,
            status: observation
                .as_ref()
                .map_or(DockingStatus::TargetUnavailable, |o| o.status),
            capture_armed: docking.capture_armed,
            attached: docking.attachment.is_some(),
            unsafe_contact: docking.unsafe_contact,
            remaining_propellant_kg: ship.propellant_mass_kg,
            centre_range_m: target.map_or(0.0, |t| magnitude(sub(body.position_m, t.position_m))),
            observation,
        })
    }
}

/// Rest transforms belong to a single chaser→station relation. The body retains
/// all mutable translation, velocity and mass; spacecraft retains attitude/spin.
pub(super) fn valid_docking(
    world: &BranchWorldState,
    body: &BodyState,
    ship: &SpacecraftState,
) -> bool {
    let Some(d) = &ship.docking else {
        return true;
    };
    if body.source_mass_kg != 0.0
        || ship.appearance != SpacecraftAppearance::Asteria
        || ship.mission.is_some()
        || ship.rendezvous.is_some()
        || ship.burn_program.is_some()
        || (d.guidance_mode == DockingGuidanceMode::Approach && !supports_guidance(ship))
        || world.spacecraft.iter().any(|other| {
            other.body_id != ship.body_id
                && other.docking.as_ref().is_some_and(|o| {
                    o.target_body_id == d.target_body_id || o.target_body_id == ship.body_id
                })
        })
    {
        return false;
    }
    let target = world.bodies.iter().find(|b| b.body_id == d.target_body_id);
    let target_ship = world
        .spacecraft
        .iter()
        .find(|s| s.body_id == d.target_body_id);
    let (Some(target), Some(target_ship)) = (target, target_ship) else {
        return target.is_none()
            && target_ship.is_none()
            && d.attachment.is_none()
            && !d.capture_armed
            && d.guidance_mode == DockingGuidanceMode::Manual;
    };
    let valid_attachment_pose = d.attachment.is_none_or(|attachment| {
        let actual_position = target_ship
            .attitude
            .conjugate()
            .rotate(sub(body.position_m, target.position_m));
        let actual_attitude = target_ship
            .attitude
            .conjugate()
            .multiply(ship.attitude)
            .normalized();
        let residual = actual_attitude
            .conjugate()
            .multiply(attachment.attitude_target);
        // Persisted constraints cannot pull a forged or edited body to a remote
        // rest pose. Allow only small solver residuals and f64 astronomical ULPs.
        if magnitude(sub(actual_position, attachment.position_target_m)) > 0.02
            || residual.x.hypot(residual.y).hypot(residual.z) > 0.001
        {
            return false;
        }
        if !observe(
            body.position_m,
            body.velocity_mps,
            ship,
            target.position_m,
            target.velocity_mps,
            target_ship,
        )
        .is_ok_and(|o| {
            o.relative_speed_mps <= solarlab_domain::docking::DOCKING_CAPTURE_SPEED_MPS + 0.01
                && o.relative_angular_speed_radps
                    <= solarlab_domain::docking::DOCKING_CAPTURE_SPIN_RADPS + 0.001
        }) {
            return false;
        }
        let mut rest_ship = ship.clone();
        rest_ship.attitude = attachment.attitude_target;
        rest_ship.angular_velocity_body_radps = Vector3d::default();
        let mut rest_target = target_ship.clone();
        rest_target.attitude = Quaterniond::IDENTITY;
        rest_target.angular_velocity_body_radps = Vector3d::default();
        observe(
            attachment.position_target_m,
            Vector3d::default(),
            &rest_ship,
            Vector3d::default(),
            Vector3d::default(),
            &rest_target,
        )
        .is_ok_and(|o| {
            o.position_port_m.z.abs() <= solarlab_domain::docking::DOCKING_CAPTURE_GAP_M + 0.01
                && o.position_port_m.x.hypot(o.position_port_m.y)
                    <= solarlab_domain::docking::DOCKING_CAPTURE_LATERAL_M + 0.01
                && o.alignment_error_rad
                    <= solarlab_domain::docking::DOCKING_CAPTURE_ALIGNMENT_RAD + 0.001
        })
    });
    valid_attachment_pose
        && target.source_mass_kg == 0.0
        && target_ship.appearance == SpacecraftAppearance::RelayStation
        && target_ship.docking.is_none()
        && target_ship.mission.is_none()
        && target_ship.rendezvous.is_none()
        && target_ship.burn_program.is_none()
        && !target_ship.has_powered_input()
        && magnitude(target_ship.control.rotation_body) == 0.0
        && matches!(
            target_ship.control.attitude_mode,
            AttitudeMode::Manual | AttitudeMode::Hold
        )
}

#[cfg(test)]
#[path = "docking_flight_tests.rs"]
mod tests;
