//! Persisted rendezvous intent and physical observations. No kinematics live here.
use crate::spacecraft::{finite, magnitude};
use crate::{BodyId, Vector3d};
use serde::{Deserialize, Serialize};

pub const MAX_RENDEZVOUS_GUIDANCE_RANGE_M: f64 = 20_000.0;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum RendezvousGuidanceMode {
    #[default]
    Manual = 0,
    Approach = 1,
    MatchVelocity = 2,
}

impl RendezvousGuidanceMode {
    #[must_use]
    pub fn active(self) -> bool {
        self != Self::Manual
    }
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum RendezvousStatus {
    #[default]
    Manual = 0,
    Approaching = 1,
    VelocityMatching = 2,
    Holding = 3,
    KeepOut = 4,
    OutOfFuel = 5,
    OutOfRange = 6,
    TargetUnavailable = 7,
}

/// Balanced axis-aligned RCS controller configuration. This is also usable by
/// standalone native callers; the flight scenario uses the documented defaults.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
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

    #[must_use]
    pub fn valid(self) -> bool {
        finite(self.stand_off_target_m)
            && magnitude(self.stand_off_target_m) < MAX_RENDEZVOUS_GUIDANCE_RANGE_M
            && self.max_approach_speed_mps.is_finite()
            && self.max_approach_speed_mps > 0.0
            && self.max_approach_speed_mps <= 10.0
            && self.position_gain_per_second.is_finite()
            && self.position_gain_per_second > 0.0
            && self.position_gain_per_second <= 0.2
            && self.velocity_response_seconds.is_finite()
            && (1.0..=120.0).contains(&self.velocity_response_seconds)
            && self.arrival_distance_m.is_finite()
            && self.arrival_distance_m > 0.0
            && self.arrival_speed_mps.is_finite()
            && self.arrival_speed_mps > 0.0
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftRendezvousState {
    pub target_body_id: BodyId,
    pub guidance_mode: RendezvousGuidanceMode,
    pub status: RendezvousStatus,
    pub launch_epoch_seconds: f64,
    pub initial_propellant_mass_kg: f64,
    pub closest_observed_range_m: f64,
    pub closest_observed_epoch_seconds: f64,
    /// Latched only from an actual integrated near/slow state, never a prediction.
    pub stand_off_achieved: bool,
    /// Sampled centre separation entered represented hull radii plus 5 m margin.
    /// This records an observation; collision or contact response is not modelled.
    pub keep_out_entered: bool,
}

impl SpacecraftRendezvousState {
    #[must_use]
    pub fn valid(&self, body_id: &BodyId) -> bool {
        !self.target_body_id.0.trim().is_empty()
            && self.target_body_id.0.len() <= 96
            && !self.target_body_id.0.chars().any(char::is_control)
            && self.target_body_id != *body_id
            && self.launch_epoch_seconds.is_finite()
            && self.launch_epoch_seconds >= 0.0
            && self.initial_propellant_mass_kg.is_finite()
            && self.initial_propellant_mass_kg >= 0.0
            && self.closest_observed_range_m.is_finite()
            && self.closest_observed_range_m >= 0.0
            && self.closest_observed_epoch_seconds.is_finite()
            && self.closest_observed_epoch_seconds >= self.launch_epoch_seconds
            && (!self.guidance_mode.active()
                || !matches!(
                    self.status,
                    RendezvousStatus::Manual
                        | RendezvousStatus::OutOfFuel
                        | RendezvousStatus::OutOfRange
                        | RendezvousStatus::TargetUnavailable
                ))
    }
}
