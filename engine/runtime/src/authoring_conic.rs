//! Data-only conic placement in the fixed inertial world frame.
use super::{AuthoringError, BodyState, Vector3d};
use serde::{Deserialize, Serialize};
use solarlab_domain::spacecraft::{add, scale};
use std::f64::consts::{PI, TAU};

/// An instantaneous two-body conic. Angles use the world's inertial XY/+Z axes.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct ConicOrbitPlacement {
    pub host_body_id: String,
    pub periapsis_altitude_m: f64,
    pub eccentricity: f64,
    pub true_anomaly_rad: f64,
    pub inclination_rad: f64,
    pub ascending_node_longitude_rad: f64,
    pub argument_of_periapsis_rad: f64,
}

impl ConicOrbitPlacement {
    pub(super) fn relative_state(
        &self,
        host: &BodyState,
    ) -> Result<(Vector3d, Vector3d), AuthoringError> {
        let Self {
            periapsis_altitude_m,
            eccentricity,
            true_anomaly_rad,
            inclination_rad,
            ascending_node_longitude_rad,
            argument_of_periapsis_rad,
            ..
        } = self;
        let periapsis = host.radius_m + periapsis_altitude_m;
        if !periapsis_altitude_m.is_finite()
            || *periapsis_altitude_m <= 0.0
            || !periapsis.is_finite()
            || periapsis <= host.radius_m
            || host.source_mass_kg <= 0.0
        {
            return Err(AuthoringError::InvalidScenario(
                "conic placement needs a massive host and a resolved periapsis above its surface",
            ));
        }
        if !eccentricity.is_finite() || *eccentricity < 0.0 {
            return Err(AuthoringError::InvalidScenario(
                "conic eccentricity must be finite and non-negative",
            ));
        }
        if !(-PI..=PI).contains(true_anomaly_rad)
            || !(0.0..=PI).contains(inclination_rad)
            || !(0.0..=TAU).contains(ascending_node_longitude_rad)
            || !(0.0..=TAU).contains(argument_of_periapsis_rad)
        {
            return Err(AuthoringError::InvalidScenario(
                "conic angles must be radians: anomaly -pi..pi, inclination 0..pi, node and periapsis 0..2pi",
            ));
        }
        // Open conics have one physical branch. Do not wrap an invalid
        // anomaly onto another branch or accept a point at infinity.
        if *eccentricity >= 1.0 && true_anomaly_rad.abs() >= (-1.0 / eccentricity).acos() {
            return Err(AuthoringError::InvalidScenario(
                "conic anomaly is at or beyond the open-orbit asymptote",
            ));
        }
        let p = periapsis * (1.0 + eccentricity);
        // Half-angle form retains the small positive denominator of a
        // near-parabolic ellipse close to apoapsis.
        let half_cos = (true_anomaly_rad * 0.5).cos();
        let denominator = (2.0 * eccentricity * half_cos) * half_cos + (1.0 - eccentricity);
        let radius = p / denominator;
        let speed_scale = (6.67430e-11 * host.source_mass_kg / p).sqrt();
        if !p.is_finite()
            || p <= 0.0
            || !denominator.is_finite()
            || denominator <= 0.0
            || !radius.is_finite()
            || radius <= 0.0
            || !speed_scale.is_finite()
            || speed_scale <= 0.0
        {
            return Err(AuthoringError::InvalidScenario(
                "conic state is singular or exceeds finite numeric range",
            ));
        }
        let (sn, cn) = ascending_node_longitude_rad.sin_cos();
        let (si, ci) = inclination_rad.sin_cos();
        let (sw, cw) = argument_of_periapsis_rad.sin_cos();
        // Perifocal basis: Rz(node) Rx(inclination) Rz(periapsis).
        // Its cross product points along the specified angular momentum.
        let periapsis_axis = Vector3d {
            x: cn * cw - sn * sw * ci,
            y: sn * cw + cn * sw * ci,
            z: sw * si,
        };
        let transverse_axis = Vector3d {
            x: -cn * sw - sn * cw * ci,
            y: -sn * sw + cn * cw * ci,
            z: cw * si,
        };
        let (sf, cf) = true_anomaly_rad.sin_cos();
        let relative_position = add(
            scale(periapsis_axis, radius * cf),
            scale(transverse_axis, radius * sf),
        );
        let relative_velocity = add(
            scale(periapsis_axis, -speed_scale * sf),
            scale(transverse_axis, speed_scale * (eccentricity + cf)),
        );
        Ok((relative_position, relative_velocity))
    }
}
