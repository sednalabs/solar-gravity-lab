//! Persisted docking intent and fixed-constraint rest pose. Translational state
//! remains exclusively in the world body; no solver handles are serialized.
use crate::spacecraft::{finite, magnitude};
use crate::{BodyId, Quaterniond, Vector3d};
use serde::{Deserialize, Serialize};

pub const MAX_DOCKING_GUIDANCE_RANGE_M: f64 = 1_000.0;
pub const DOCKING_CAPTURE_GAP_M: f64 = 0.15;
/// Narrow tolerance for contact solver compression, never an approach target.
pub const DOCKING_CONTACT_PENETRATION_M: f64 = 0.005;
pub const DOCKING_CAPTURE_LATERAL_M: f64 = 0.05;
pub const DOCKING_CAPTURE_ALIGNMENT_RAD: f64 = 5.0 * std::f64::consts::PI / 180.0;
pub const DOCKING_CAPTURE_SPEED_MPS: f64 = 0.1;
pub const DOCKING_CAPTURE_SPIN_RADPS: f64 = std::f64::consts::PI / 180.0;

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum DockingGuidanceMode {
    #[default]
    Manual = 0,
    Approach = 1,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum DockingStatus {
    #[default]
    Manual = 0,
    Aligning = 1,
    Approaching = 2,
    CaptureReady = 3,
    Docked = 4,
    UnsafeContact = 5,
    OutOfFuel = 6,
    OutOfRange = 7,
    TargetUnavailable = 8,
}

/// Fixed-joint rest transform of the ship centre in the target body frame.
/// Recorded from the accepted physical pose, so capture never snaps either body.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct DockingAttachment {
    pub position_target_m: Vector3d,
    pub attitude_target: Quaterniond,
}

impl DockingAttachment {
    #[must_use]
    pub fn valid(self) -> bool {
        finite(self.position_target_m)
            && magnitude(self.position_target_m) <= 100.0
            && (self.attitude_target.norm_squared() - 1.0).abs() < 1e-8
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftDockingState {
    pub target_body_id: BodyId,
    pub guidance_mode: DockingGuidanceMode,
    pub capture_armed: bool,
    pub attachment: Option<DockingAttachment>,
    /// An unqualified impact cancels capture. Braking in the contact solver must
    /// never transform an unsafe arrival into an automatic successful docking.
    pub unsafe_contact: bool,
}

impl SpacecraftDockingState {
    #[must_use]
    pub fn new(target_body_id: BodyId) -> Self {
        Self {
            target_body_id,
            guidance_mode: DockingGuidanceMode::Manual,
            capture_armed: false,
            attachment: None,
            unsafe_contact: false,
        }
    }

    #[must_use]
    pub fn valid(&self, body_id: &BodyId) -> bool {
        !self.target_body_id.0.trim().is_empty()
            && self.target_body_id.0.len() <= 96
            && !self.target_body_id.0.chars().any(char::is_control)
            && self.target_body_id != *body_id
            && self.attachment.is_none_or(DockingAttachment::valid)
            && (self.attachment.is_none()
                || (!self.capture_armed
                    && self.guidance_mode == DockingGuidanceMode::Manual
                    && !self.unsafe_contact))
            && (!self.unsafe_contact
                || (!self.capture_armed && self.guidance_mode == DockingGuidanceMode::Manual))
            && (self.guidance_mode != DockingGuidanceMode::Approach || self.capture_armed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_or_contradictory_attachment_is_rejected() {
        let id = BodyId("ship".into());
        let mut state = SpacecraftDockingState::new(BodyId("station".into()));
        assert!(state.valid(&id));
        state.attachment = Some(DockingAttachment {
            position_target_m: Vector3d {
                x: 0.0,
                y: -7.56,
                z: 0.0,
            },
            attitude_target: Quaterniond::IDENTITY,
        });
        assert!(state.valid(&id));
        state.capture_armed = true;
        assert!(!state.valid(&id));
        state.capture_armed = false;
        state.attachment.as_mut().unwrap().attitude_target.w = f64::NAN;
        assert!(!state.valid(&id));
    }
}
