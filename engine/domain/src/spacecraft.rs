//! Persistent flight components. Translation belongs to the existing world body.
//! SI units; body +Z nose, +X right, +Y up; attitude maps body to inertial axes.
use crate::{BodyId, Vector3d};
use serde::{Deserialize, Serialize};

#[must_use]
pub fn add(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x + b.x,
        y: a.y + b.y,
        z: a.z + b.z,
    }
}
#[must_use]
pub fn sub(a: Vector3d, b: Vector3d) -> Vector3d {
    add(a, scale(b, -1.0))
}
#[must_use]
pub fn scale(v: Vector3d, s: f64) -> Vector3d {
    Vector3d {
        x: v.x * s,
        y: v.y * s,
        z: v.z * s,
    }
}
#[must_use]
pub fn dot(a: Vector3d, b: Vector3d) -> f64 {
    a.x * b.x + a.y * b.y + a.z * b.z
}
#[must_use]
pub fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}
#[must_use]
pub fn magnitude(v: Vector3d) -> f64 {
    dot(v, v).sqrt()
}
#[must_use]
pub fn unit(v: Vector3d) -> Option<Vector3d> {
    let m = magnitude(v);
    (m.is_finite() && m > 1e-15).then(|| scale(v, 1.0 / m))
}
#[must_use]
pub fn finite(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}

/// Positive principal moments realizable by a nonnegative mass distribution.
/// Subtracting moments avoids overflow in the equivalent triangle inequalities
/// `Ix <= Iy + Iz` while retaining planar bodies at the equality boundary.
#[must_use]
pub fn valid_principal_inertia(i: Vector3d) -> bool {
    finite(i)
        && i.x > 0.0
        && i.y > 0.0
        && i.z > 0.0
        && i.x - i.y <= i.z
        && i.y - i.z <= i.x
        && i.z - i.x <= i.y
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct Quaterniond {
    pub w: f64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}
impl Default for Quaterniond {
    fn default() -> Self {
        Self::IDENTITY
    }
}
impl Quaterniond {
    pub const IDENTITY: Self = Self {
        w: 1.0,
        x: 0.0,
        y: 0.0,
        z: 0.0,
    };
    #[must_use]
    pub fn norm_squared(self) -> f64 {
        self.w * self.w + self.x * self.x + self.y * self.y + self.z * self.z
    }
    #[must_use]
    pub fn normalized(self) -> Self {
        let n = self.norm_squared().sqrt();
        Self {
            w: self.w / n,
            x: self.x / n,
            y: self.y / n,
            z: self.z / n,
        }
    }
    #[must_use]
    pub fn conjugate(self) -> Self {
        Self {
            w: self.w,
            x: -self.x,
            y: -self.y,
            z: -self.z,
        }
    }
    #[must_use]
    pub fn multiply(self, b: Self) -> Self {
        Self {
            w: self.w * b.w - self.x * b.x - self.y * b.y - self.z * b.z,
            x: self.w * b.x + self.x * b.w + self.y * b.z - self.z * b.y,
            y: self.w * b.y - self.x * b.z + self.y * b.w + self.z * b.x,
            z: self.w * b.z + self.x * b.y - self.y * b.x + self.z * b.w,
        }
    }
    #[must_use]
    pub fn rotate(self, v: Vector3d) -> Vector3d {
        let q = Vector3d {
            x: self.x,
            y: self.y,
            z: self.z,
        };
        let t = scale(cross(q, v), 2.0);
        add(v, add(scale(t, self.w), cross(q, t)))
    }
    #[must_use]
    pub fn from_rotation_vector(v: Vector3d) -> Self {
        let a = magnitude(v);
        let s = if a < 1e-10 {
            0.5 - a * a / 48.0
        } else {
            (a * 0.5).sin() / a
        };
        Self {
            w: (a * 0.5).cos(),
            x: v.x * s,
            y: v.y * s,
            z: v.z * s,
        }
        .normalized()
    }
    /// Shortest rotation pointing body +Z along this direction.
    #[must_use]
    pub fn pointing(direction: Vector3d) -> Option<Self> {
        let d = unit(direction)?;
        if d.z < -0.999_999_999_999 {
            Some(Self {
                w: 0.0,
                x: 1.0,
                y: 0.0,
                z: 0.0,
            })
        } else {
            Some(
                Self {
                    w: 1.0 + d.z,
                    x: -d.y,
                    y: d.x,
                    z: 0.0,
                }
                .normalized(),
            )
        }
    }
    #[must_use]
    pub fn is_unit(self) -> bool {
        (self.norm_squared() - 1.0).abs() < 1e-8
    }
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum AttitudeMode {
    #[default]
    Manual,
    Hold,
    Prograde,
    Retrograde,
    Normal,
    AntiNormal,
    RadialOut,
    RadialIn,
}
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Default)]
pub struct FlightControl {
    pub throttle: f64,
    /// RCS translation demand per body axis in [-1,1].
    pub translation_body: Vector3d,
    /// Bounded ideal reaction-wheel torque demand per body axis in [-1,1].
    pub rotation_body: Vector3d,
    pub attitude_mode: AttitudeMode,
}
impl FlightControl {
    #[must_use]
    pub fn valid(self) -> bool {
        self.throttle.is_finite()
            && (0.0..=1.0).contains(&self.throttle)
            && [self.translation_body, self.rotation_body]
                .into_iter()
                .all(|v| finite(v) && v.x.abs() <= 1.0 && v.y.abs() <= 1.0 && v.z.abs() <= 1.0)
    }
}
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum EngineControl {
    Main,
    PositiveX,
    NegativeX,
    PositiveY,
    NegativeY,
    PositiveZ,
    NegativeZ,
}
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftEngine {
    pub direction_body: Vector3d,
    /// Lever arm relative to centre of mass; contributes r × F torque.
    pub position_body_m: Vector3d,
    pub max_thrust_n: f64,
    pub max_mass_flow_kgps: f64,
    pub control: EngineControl,
}
impl SpacecraftEngine {
    #[must_use]
    pub fn demand(&self, c: FlightControl) -> f64 {
        match self.control {
            EngineControl::Main => c.throttle,
            EngineControl::PositiveX => c.translation_body.x.max(0.0),
            EngineControl::NegativeX => (-c.translation_body.x).max(0.0),
            EngineControl::PositiveY => c.translation_body.y.max(0.0),
            EngineControl::NegativeY => (-c.translation_body.y).max(0.0),
            EngineControl::PositiveZ => c.translation_body.z.max(0.0),
            EngineControl::NegativeZ => (-c.translation_body.z).max(0.0),
        }
    }
}

/// Optional finite main-engine programme, explicitly started and cancelled by
/// any manual control command. Remaining time is full-throttle equivalent.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftBurnProgram {
    pub remaining_full_throttle_seconds: f64,
}

/// Mission observations contain no second position/velocity store. Closest
/// range and surface crossings are sampled at actual integration boundaries.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftMissionState {
    pub mission_id: String,
    pub target_body_id: BodyId,
    pub launch_epoch_seconds: f64,
    pub closest_target_distance_m: f64,
    pub closest_approach_epoch_seconds: f64,
    /// Records passage after the closest sampled target approach.
    pub closest_approach_passed: bool,
    pub reference_surface_crossed: bool,
    pub target_surface_crossed: bool,
}

/// Presentation selection only; it never changes mass, shape or forces.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum SpacecraftAppearance {
    #[default]
    Asteria = 1,
    RelayStation = 2,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct SpacecraftState {
    pub body_id: BodyId,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authored: Option<crate::SpacecraftAssembly>,
    #[serde(default)]
    pub appearance: SpacecraftAppearance,
    pub reference_body_id: Option<BodyId>,
    pub attitude: Quaterniond,
    pub angular_velocity_body_radps: Vector3d,
    pub dry_mass_kg: f64,
    pub propellant_mass_kg: f64,
    /// Constant principal moments: central balanced tank, no fuel slosh model.
    pub inertia_kg_m2: Vector3d,
    /// Ideal wheels: bounded torque; power and wheel saturation are not modelled.
    pub max_control_torque_nm: Vector3d,
    pub engines: Vec<SpacecraftEngine>,
    pub control: FlightControl,
    pub delivered_delta_v_mps: f64,
    #[serde(default)]
    pub main_engine_full_throttle_seconds: f64,
    #[serde(default)]
    pub burn_program: Option<SpacecraftBurnProgram>,
    #[serde(default)]
    pub mission: Option<SpacecraftMissionState>,
    #[serde(default)]
    pub rendezvous: Option<crate::SpacecraftRendezvousState>,
    #[serde(default)]
    pub docking: Option<crate::SpacecraftDockingState>,
}
impl SpacecraftState {
    #[must_use]
    pub fn mass_kg(&self) -> f64 {
        self.dry_mass_kg + self.propellant_mass_kg
    }
    #[must_use]
    pub fn valid(&self) -> bool {
        self.authored
            .as_ref()
            .is_none_or(crate::SpacecraftAssembly::valid)
            && self.attitude.is_unit()
            && finite(self.angular_velocity_body_radps)
            && self.dry_mass_kg.is_finite()
            && self.dry_mass_kg > 0.0
            && self.propellant_mass_kg.is_finite()
            && self.propellant_mass_kg >= 0.0
            && self.mass_kg().is_finite()
            && valid_principal_inertia(self.inertia_kg_m2)
            && finite(self.max_control_torque_nm)
            && self.max_control_torque_nm.x >= 0.0
            && self.max_control_torque_nm.y >= 0.0
            && self.max_control_torque_nm.z >= 0.0
            && self.control.valid()
            && self.engines.len() <= 64
            && self.delivered_delta_v_mps.is_finite()
            && self.delivered_delta_v_mps >= 0.0
            && self.main_engine_full_throttle_seconds.is_finite()
            && self.main_engine_full_throttle_seconds >= 0.0
            && self.docking.as_ref().is_none_or(|d| {
                d.valid(&self.body_id)
                    && self.mission.is_none()
                    && self.rendezvous.is_none()
                    && self.burn_program.is_none()
                    && (d.guidance_mode != crate::DockingGuidanceMode::Approach
                        || (self.control.throttle == 0.0
                            && self.control.attitude_mode == AttitudeMode::Manual
                            && self.propellant_mass_kg > 0.0))
                    && (d.attachment.is_none()
                        || (self.control.throttle == 0.0
                            && magnitude(self.control.translation_body) == 0.0
                            && magnitude(self.control.rotation_body) == 0.0
                            && matches!(
                                self.control.attitude_mode,
                                AttitudeMode::Manual | AttitudeMode::Hold
                            )))
            })
            && self.rendezvous.as_ref().is_none_or(|r| {
                r.valid(&self.body_id)
                    && self.mission.is_none()
                    && self.propellant_mass_kg <= r.initial_propellant_mass_kg
                    && (!r.guidance_mode.active()
                        || (self.burn_program.is_none()
                            && self.control.throttle == 0.0
                            && magnitude(self.control.rotation_body) == 0.0
                            && self.control.attitude_mode == AttitudeMode::Hold
                            && self.propellant_mass_kg > 0.0))
            })
            && self.burn_program.as_ref().is_none_or(|p| {
                p.remaining_full_throttle_seconds.is_finite()
                    && p.remaining_full_throttle_seconds > 0.0
                    && p.remaining_full_throttle_seconds <= 86_400.0
                    && self.control.throttle == 1.0
                    && self.control.attitude_mode == AttitudeMode::Prograde
                    && magnitude(self.control.translation_body) == 0.0
                    && magnitude(self.control.rotation_body) == 0.0
                    && self.propellant_mass_kg > 0.0
                    && self.reference_body_id.is_some()
                    && self
                        .engines
                        .iter()
                        .any(|e| e.control == EngineControl::Main)
            })
            && self.mission.as_ref().is_none_or(|m| {
                !m.mission_id.trim().is_empty()
                    && m.mission_id.len() <= 96
                    && !m.mission_id.chars().any(char::is_control)
                    && !m.target_body_id.0.trim().is_empty()
                    && m.target_body_id.0.len() <= 96
                    && !m.target_body_id.0.chars().any(char::is_control)
                    && m.target_body_id != self.body_id
                    && m.launch_epoch_seconds.is_finite()
                    && m.launch_epoch_seconds >= 0.0
                    && m.closest_target_distance_m.is_finite()
                    && m.closest_target_distance_m >= 0.0
                    && m.closest_approach_epoch_seconds.is_finite()
                    && m.closest_approach_epoch_seconds >= m.launch_epoch_seconds
            })
            && self.engines.iter().all(|e| {
                finite(e.direction_body)
                    && (magnitude(e.direction_body) - 1.0).abs() < 1e-8
                    && finite(e.position_body_m)
                    && e.max_thrust_n.is_finite()
                    && e.max_thrust_n > 0.0
                    && e.max_mass_flow_kgps.is_finite()
                    && e.max_mass_flow_kgps > 0.0
            })
    }
    #[must_use]
    pub fn reference(body_id: BodyId, reference_body_id: Option<BodyId>) -> Self {
        let mut engines = vec![SpacecraftEngine {
            direction_body: Vector3d {
                x: 0.0,
                y: 0.0,
                z: 1.0,
            },
            position_body_m: Vector3d::default(),
            max_thrust_n: 90_000.0,
            max_mass_flow_kgps: 90_000.0 / (450.0 * 9.80665),
            control: EngineControl::Main,
        }];
        for (direction_body, control) in [
            (
                Vector3d {
                    x: 1.0,
                    y: 0.0,
                    z: 0.0,
                },
                EngineControl::PositiveX,
            ),
            (
                Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
                EngineControl::NegativeX,
            ),
            (
                Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
                EngineControl::PositiveY,
            ),
            (
                Vector3d {
                    x: 0.0,
                    y: -1.0,
                    z: 0.0,
                },
                EngineControl::NegativeY,
            ),
            (
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 1.0,
                },
                EngineControl::PositiveZ,
            ),
            (
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: -1.0,
                },
                EngineControl::NegativeZ,
            ),
        ] {
            engines.push(SpacecraftEngine {
                direction_body,
                position_body_m: Vector3d::default(),
                max_thrust_n: 800.0,
                max_mass_flow_kgps: 800.0 / (300.0 * 9.80665),
                control,
            });
        }
        Self {
            body_id,
            authored: None,
            appearance: SpacecraftAppearance::Asteria,
            reference_body_id,
            attitude: Quaterniond::IDENTITY,
            angular_velocity_body_radps: Vector3d::default(),
            dry_mass_kg: 4_000.0,
            propellant_mass_kg: 8_000.0,
            inertia_kg_m2: Vector3d {
                x: 24_000.0,
                y: 24_000.0,
                z: 12_000.0,
            },
            max_control_torque_nm: Vector3d {
                x: 6_000.0,
                y: 6_000.0,
                z: 3_000.0,
            },
            engines,
            control: FlightControl::default(),
            delivered_delta_v_mps: 0.0,
            main_engine_full_throttle_seconds: 0.0,
            burn_program: None,
            mission: None,
            rendezvous: None,
            docking: None,
        }
    }
    #[must_use]
    pub fn has_powered_input(&self) -> bool {
        self.propellant_mass_kg > 0.0 && self.engines.iter().any(|e| e.demand(self.control) > 0.0)
    }
    #[must_use]
    pub fn needs_attitude_steps(&self) -> bool {
        magnitude(self.angular_velocity_body_radps) > 1e-12
            || magnitude(self.control.rotation_body) > 0.0
            || !matches!(
                self.control.attitude_mode,
                AttitudeMode::Manual | AttitudeMode::Hold
            )
    }
}

/// Moving rigid frame. Subtract f64 origin before converting local metres to
/// renderer/contact precision. Angular velocity is in world axes.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LocalReferenceFrame {
    pub origin_m: Vector3d,
    pub velocity_mps: Vector3d,
    pub attitude: Quaterniond,
    pub angular_velocity_world_radps: Vector3d,
}
impl LocalReferenceFrame {
    #[must_use]
    pub fn to_local(self, p: Vector3d, v: Vector3d) -> (Vector3d, Vector3d) {
        let r = sub(p, self.origin_m);
        (
            self.attitude.conjugate().rotate(r),
            self.attitude.conjugate().rotate(sub(
                sub(v, self.velocity_mps),
                cross(self.angular_velocity_world_radps, r),
            )),
        )
    }
    #[must_use]
    pub fn to_world(self, p: Vector3d, v: Vector3d) -> (Vector3d, Vector3d) {
        let r = self.attitude.rotate(p);
        (
            add(self.origin_m, r),
            add(
                self.velocity_mps,
                add(
                    self.attitude.rotate(v),
                    cross(self.angular_velocity_world_radps, r),
                ),
            ),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn principal_inertia_preserves_physical_boundaries_without_overflow() {
        let smallest = f64::from_bits(1);
        for [x, y, z] in [
            [1.0, 2.0, 3.0],
            [3.0, 1.0, 2.0],
            [2.0, 3.0, 1.0],
            [f64::MAX, f64::MAX, f64::MAX],
            [f64::MAX, smallest, f64::MAX],
            [smallest, smallest, smallest],
            [smallest, smallest, 2.0 * smallest],
            [1.0e-200, 1.0e100, 1.0e100],
        ] {
            assert!(valid_principal_inertia(Vector3d { x, y, z }));
        }
    }

    #[test]
    fn principal_inertia_rejects_impossible_moments_on_every_axis() {
        let beyond_planar = f64::from_bits(2.0_f64.to_bits() + 1);
        for [x, y, z] in [
            [beyond_planar, 1.0, 1.0],
            [1.0, beyond_planar, 1.0],
            [1.0, 1.0, beyond_planar],
            [f64::MAX, 1.0, 1.0],
            [0.0, 1.0, 1.0],
            [-1.0, 1.0, 1.0],
            [f64::NAN, 1.0, 1.0],
            [1.0, f64::INFINITY, 1.0],
        ] {
            assert!(!valid_principal_inertia(Vector3d { x, y, z }));
        }
    }

    #[test]
    fn rotating_local_frames_roundtrip_at_astronomical_origins() {
        let frame = LocalReferenceFrame {
            origin_m: Vector3d {
                x: 1.5e11,
                y: -2e11,
                z: 3e10,
            },
            velocity_mps: Vector3d {
                x: 30_000.0,
                y: 1000.0,
                z: 2000.0,
            },
            attitude: Quaterniond::from_rotation_vector(Vector3d {
                x: 0.2,
                y: -0.6,
                z: 0.3,
            }),
            angular_velocity_world_radps: Vector3d {
                x: 0.001,
                y: 0.002,
                z: -0.003,
            },
        };
        let p = Vector3d {
            x: 4.25,
            y: -1.125,
            z: 0.03125,
        };
        let velocity = Vector3d {
            x: 0.2,
            y: 0.3,
            z: 0.4,
        };
        let (world_p, world_v) = frame.to_world(p, velocity);
        let (local_p, local_v) = frame.to_local(world_p, world_v);
        assert!(magnitude(sub(local_p, p)) < 3e-5);
        assert!(magnitude(sub(local_v, velocity)) < 1e-7);
        let stationary = frame.to_world(p, Vector3d::default());
        let local = frame.to_local(stationary.0, stationary.1);
        assert!(magnitude(local.1) < 1e-7);
    }
    #[test]
    fn quaternion_points_forward_and_is_right_handed() {
        for dir in [
            Vector3d {
                x: 1.0,
                y: 0.0,
                z: 0.0,
            },
            Vector3d {
                x: 0.0,
                y: 1.0,
                z: 0.0,
            },
            Vector3d {
                x: 0.0,
                y: 0.0,
                z: -1.0,
            },
        ] {
            let q = Quaterniond::pointing(dir).unwrap();
            assert!(q.is_unit());
            assert!(
                magnitude(sub(
                    q.rotate(Vector3d {
                        x: 0.0,
                        y: 0.0,
                        z: 1.0
                    }),
                    dir
                )) < 1e-14
            );
        }
    }
}
