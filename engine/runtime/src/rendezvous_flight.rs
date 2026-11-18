//! Persistent native rendezvous entry, commands and read-only cockpit telemetry.
use super::*;
use solarlab_domain::spacecraft::{magnitude, sub, unit};
use solarlab_domain::LocalReferenceFrame;

pub const RENDEZVOUS_STATION_BODY_ID: &str = "rendezvous.station";

#[derive(Clone, Debug, PartialEq)]
pub struct RendezvousFlightTelemetry {
    pub body_id: String,
    pub target_body_id: String,
    pub epoch_seconds: f64,
    pub elapsed_seconds: f64,
    pub guidance_mode: RendezvousGuidanceMode,
    pub status: RendezvousStatus,
    pub target_available: bool,
    pub range_m: f64,
    /// Positive means approaching the target.
    pub closing_speed_mps: f64,
    pub relative_speed_mps: f64,
    pub position_target_m: Vector3d,
    pub velocity_target_mps: Vector3d,
    pub target_direction_body: Vector3d,
    pub keep_out_distance_m: f64,
    pub stand_off_distance_m: f64,
    pub stand_off_error_m: f64,
    pub closest_observed_range_m: f64,
    pub closest_observed_epoch_seconds: f64,
    pub propellant_used_kg: f64,
    pub remaining_propellant_kg: f64,
    pub stand_off_achieved: bool,
    pub keep_out_entered: bool,
}

impl WorldRuntime {
    /// Fresh canonical lab only. Hosts preserve the previous session before
    /// constructing and binding this candidate, as for the lunar flight.
    pub fn spawn_rendezvous_flight(
        &mut self,
        ship_id: &str,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.validate_fresh_station_mission()?;
        self.spawn_rendezvous_scenario_impl(
            &RendezvousScenario {
                chaser_body_id: BodyId(ship_id.into()),
                target_body_id: BodyId(RENDEZVOUS_STATION_BODY_ID.into()),
                ..RendezvousScenario::default()
            },
            time,
            true,
        )
    }

    pub(super) fn validate_fresh_station_mission(&self) -> Result<(), RuntimeError> {
        let world = &self.active_branch().world;
        let seed = solarlab_data::earth_moon_lab_bodies();
        if self.scenario_id.0 != solarlab_data::EARTH_MOON_LAB_ID
            || world.epoch_seconds != 0.0
            || !world.playback.paused
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
        Ok(())
    }

    pub fn set_rendezvous_guidance(
        &mut self,
        ship_id: &str,
        mode: RendezvousGuidanceMode,
        time: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.apply_command(
            WorldCommand::SetRendezvousGuidance {
                body_id: BodyId(ship_id.into()),
                mode,
            },
            time,
        )
    }

    pub(super) fn rendezvous_command_control(
        &self,
        body_id: &BodyId,
        mode: RendezvousGuidanceMode,
    ) -> Result<(FlightControl, RendezvousStatus), RuntimeError> {
        let ship = self
            .spacecraft_by_id(&body_id.0)
            .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
        let rendezvous = ship
            .rendezvous
            .as_ref()
            .ok_or(RuntimeError::InvalidFlightState)?;
        if mode == RendezvousGuidanceMode::Manual {
            return Ok((
                solarlab_physics::rendezvous::neutral_control(),
                RendezvousStatus::Manual,
            ));
        }
        let reference = ship
            .reference_body_id
            .clone()
            .ok_or(RuntimeError::InvalidFlightState)?;
        self.rendezvous_control(
            &RendezvousScenario {
                reference_body_id: reference,
                chaser_body_id: body_id.clone(),
                target_body_id: rendezvous.target_body_id.clone(),
                ..RendezvousScenario::default()
            },
            if mode == RendezvousGuidanceMode::Approach {
                RendezvousGuidance::default()
            } else {
                RendezvousGuidance::velocity_match()
            },
        )
    }

    #[must_use]
    pub fn rendezvous_flight_telemetry(&self, ship_id: &str) -> Option<RendezvousFlightTelemetry> {
        let world = &self.active_branch().world;
        let ship = self.spacecraft_by_id(ship_id)?;
        let body = self.body_by_id(ship_id)?;
        let rendezvous = ship.rendezvous.as_ref()?;
        let target = self.body_by_id(&rendezvous.target_body_id.0);
        let target_ship = self.spacecraft_by_id(&rendezvous.target_body_id.0);
        let observation = target.zip(target_ship).and_then(|(target, state)| {
            let frame = LocalReferenceFrame {
                origin_m: target.position_m,
                velocity_mps: target.velocity_mps,
                attitude: state.attitude,
                angular_velocity_world_radps: state
                    .attitude
                    .rotate(state.angular_velocity_body_radps),
            };
            solarlab_physics::rendezvous::observe(
                body.position_m,
                body.velocity_mps,
                body.radius_m,
                frame,
                target.radius_m,
            )
            .ok()
        });
        let guidance = RendezvousGuidance::default();
        Some(RendezvousFlightTelemetry {
            body_id: ship_id.into(),
            target_body_id: rendezvous.target_body_id.0.clone(),
            epoch_seconds: world.epoch_seconds,
            elapsed_seconds: world.epoch_seconds - rendezvous.launch_epoch_seconds,
            guidance_mode: rendezvous.guidance_mode,
            status: if observation.is_some() {
                rendezvous.status
            } else {
                RendezvousStatus::TargetUnavailable
            },
            target_available: observation.is_some(),
            range_m: observation.map_or(0.0, |t| t.range_m),
            closing_speed_mps: observation.map_or(0.0, |t| t.closing_speed_mps),
            relative_speed_mps: observation.map_or(0.0, |t| t.relative_speed_mps),
            position_target_m: observation.map_or(Vector3d::default(), |t| t.position_target_m),
            velocity_target_mps: observation.map_or(Vector3d::default(), |t| t.velocity_target_mps),
            target_direction_body: if observation.is_some() {
                target
                    .and_then(|t| {
                        unit(
                            ship.attitude
                                .conjugate()
                                .rotate(sub(t.position_m, body.position_m)),
                        )
                    })
                    .unwrap_or_default()
            } else {
                Vector3d::default()
            },
            keep_out_distance_m: observation.map_or(0.0, |t| t.keep_out_distance_m),
            stand_off_distance_m: magnitude(guidance.stand_off_target_m),
            stand_off_error_m: observation.map_or(0.0, |t| {
                magnitude(sub(t.position_target_m, guidance.stand_off_target_m))
            }),
            closest_observed_range_m: rendezvous.closest_observed_range_m,
            closest_observed_epoch_seconds: rendezvous.closest_observed_epoch_seconds,
            propellant_used_kg: rendezvous.initial_propellant_mass_kg - ship.propellant_mass_kg,
            remaining_propellant_kg: ship.propellant_mass_kg,
            stand_off_achieved: rendezvous.stand_off_achieved,
            keep_out_entered: rendezvous.keep_out_entered,
        })
    }
}

#[cfg(test)]
#[path = "rendezvous_flight_tests.rs"]
mod tests;
