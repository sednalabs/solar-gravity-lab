//! Persistable numerical and gravity-model choices, independent of rendering.
//! A pole is expressed in the same inertial axes as the world's positions.
use crate::{BodyId, Vector3d};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum IntegratorKind {
    #[default]
    LeapfrogKickDriftKick,
    /// Historical reserved label. No implemented method is advertised under it.
    AdaptiveSymplectic,
    YoshidaFourthOrder,
    DormandPrince54,
}

impl IntegratorKind {
    #[must_use]
    pub fn implemented(self) -> bool {
        self != Self::AdaptiveSymplectic
    }

    #[must_use]
    pub fn label(self) -> &'static str {
        match self {
            Self::LeapfrogKickDriftKick => "Leapfrog KDK (order 2)",
            Self::AdaptiveSymplectic => "Unsupported legacy integrator",
            Self::YoshidaFourthOrder => "Yoshida (order 4, conservative coast)",
            Self::DormandPrince54 => "Dormand–Prince 5(4), adaptive encounter",
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default, deny_unknown_fields)]
pub struct AdaptiveTolerance {
    /// Dimensionless local-error target. It is not a global accuracy guarantee.
    pub relative: f64,
    pub absolute_position_m: f64,
    pub absolute_velocity_mps: f64,
    pub minimum_step_seconds: f64,
    /// Maximum accepted + rejected trials in one fixed outer interval.
    pub max_attempts_per_interval: u32,
    /// Limit h to this fraction of both crossing and dynamical pair timescales.
    /// Prevents a long trial from skipping a fast, compact flyby.
    pub encounter_fraction: f64,
}

impl Default for AdaptiveTolerance {
    fn default() -> Self {
        Self {
            relative: 1.0e-10,
            absolute_position_m: 1.0e-3,
            absolute_velocity_mps: 1.0e-6,
            minimum_step_seconds: 1.0e-6,
            max_attempts_per_interval: 4_096,
            encounter_fraction: 0.2,
        }
    }
}

impl AdaptiveTolerance {
    #[must_use]
    pub fn valid(&self) -> bool {
        self.relative.is_finite()
            && (1.0e-14..=1.0e-2).contains(&self.relative)
            && self.absolute_position_m.is_finite()
            && self.absolute_position_m > 0.0
            && self.absolute_velocity_mps.is_finite()
            && self.absolute_velocity_mps > 0.0
            && self.minimum_step_seconds.is_finite()
            && self.minimum_step_seconds > 0.0
            && (1..=16_384).contains(&self.max_attempts_per_interval)
            && self.encounter_fraction.is_finite()
            && (0.0..=0.25).contains(&self.encounter_fraction)
            && self.encounter_fraction > 0.0
    }
}

/// Leading zonal harmonic of one massive source. This is the exterior J2
/// model, not a terrain/geoid model or a full spherical-harmonic expansion.
/// The axis is fixed in inertial space; no planetary spin torque is integrated.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ZonalHarmonic {
    pub body_id: BodyId,
    pub j2: f64,
    pub reference_radius_m: f64,
    pub axis_ws: Vector3d,
}

impl ZonalHarmonic {
    #[must_use]
    pub fn valid(&self) -> bool {
        let axis_norm = self.axis_ws.x.hypot(self.axis_ws.y).hypot(self.axis_ws.z);
        !self.body_id.0.is_empty()
            && self.body_id.0.len() <= 256
            && self.j2.is_finite()
            && self.j2.abs() <= 0.1
            && self.reference_radius_m.is_finite()
            && self.reference_radius_m > 0.0
            && axis_norm.is_finite()
            && (axis_norm - 1.0).abs() <= 1.0e-10
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default, deny_unknown_fields)]
pub struct ScientificSettings {
    pub integrator: IntegratorKind,
    pub adaptive: AdaptiveTolerance,
    pub harmonics: Vec<ZonalHarmonic>,
}

impl ScientificSettings {
    #[must_use]
    pub fn valid(&self) -> bool {
        self.integrator.implemented()
            && self.adaptive.valid()
            && self.harmonics.len() <= 64
            && self.harmonics.iter().all(ZonalHarmonic::valid)
            && self.harmonics.iter().enumerate().all(|(i, harmonic)| {
                self.harmonics[..i]
                    .iter()
                    .all(|previous| previous.body_id != harmonic.body_id)
            })
    }
}
