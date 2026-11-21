//! Instantaneous two-body navigation, never a full-model event forecast.
use crate::flight_telemetry::OrbitalElements;
use solarlab_domain::Vector3d;
use std::f64::consts::TAU;

/// Apsides lose their direction as eccentricity tends to zero. Do not turn
/// floating-point noise into a confidently labelled countdown or map marker.
pub const NEAR_CIRCULAR_ECCENTRICITY: f64 = 1e-6;
/// Finite-period anomaly conversion is poorly conditioned around escape.
pub const NEAR_PARABOLIC_ECCENTRICITY_MARGIN: f64 = 1e-8;

#[derive(serde::Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum OrbitRegime {
    Elliptic,
    NearCircular,
    Hyperbolic,
    NearParabolic,
}

#[derive(serde::Serialize, Clone, Copy, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OrbitGuidance {
    pub regime: OrbitRegime,
    /// Time from this state to the next passage; zero at an apsis. An outbound
    /// open orbit has no future periapsis, and open orbits have no apoapsis.
    pub time_to_periapsis_seconds: Option<f64>,
    pub time_to_apoapsis_seconds: Option<f64>,
    /// Positions relative to the primary, in the input inertial frame.
    pub periapsis_position_m: Option<Vector3d>,
    pub apoapsis_position_m: Option<Vector3d>,
    /// Ideal instantaneous impulse at the current radius, preserving orbital
    /// plane and sense. It cancels radial speed, not just total speed error.
    /// This is not a finite burn, fuel budget, or instruction to fire now.
    pub circularisation_delta_v_mps: f64,
    pub circularisation_delta_velocity_mps: Vector3d,
}

impl OrbitGuidance {
    /// SI state relative to one primary at one epoch, in inertial axes.
    /// Returns no guidance for invalid or effectively radial states. Thrust,
    /// other bodies, J2 and surface/atmosphere constraints are not extrapolated.
    #[must_use]
    pub fn from_relative(position: Vector3d, velocity: Vector3d, mu: f64) -> Option<Self> {
        let orbit = OrbitalElements::from_relative(position, velocity, mu)?;
        let radius = orbit.distance_m;
        let radial = scale(position, 1.0 / radius);
        // Build transverse direction from cross products to avoid subtracting
        // almost equal velocities in a nearly radial state.
        let normal = cross(radial, velocity);
        let transverse_speed = norm(normal);
        if !transverse_speed.is_finite() || transverse_speed <= orbit.speed_mps * 1e-10 {
            return None;
        }
        let transverse = cross(scale(normal, 1.0 / transverse_speed), radial);
        let circular_velocity = scale(transverse, (mu / radius).sqrt());
        let circularisation = sub(circular_velocity, velocity);
        let delta_v = norm(circularisation);
        if !delta_v.is_finite() {
            return None;
        }
        let eccentricity = orbit.eccentricity;
        let regime = if eccentricity < NEAR_CIRCULAR_ECCENTRICITY {
            OrbitRegime::NearCircular
        } else if (eccentricity - 1.0).abs() <= NEAR_PARABOLIC_ECCENTRICITY_MARGIN {
            OrbitRegime::NearParabolic
        } else if eccentricity < 1.0 {
            OrbitRegime::Elliptic
        } else {
            OrbitRegime::Hyperbolic
        };
        let mut guidance = Self {
            regime,
            time_to_periapsis_seconds: None,
            time_to_apoapsis_seconds: None,
            periapsis_position_m: None,
            apoapsis_position_m: None,
            circularisation_delta_v_mps: delta_v,
            circularisation_delta_velocity_mps: circularisation,
        };
        if regime == OrbitRegime::NearCircular {
            return Some(guidance);
        }
        let h = cross(position, velocity);
        let eccentricity_vector = sub(scale(cross(velocity, h), 1.0 / mu), radial);
        let periapsis_direction = scale(eccentricity_vector, 1.0 / norm(eccentricity_vector));
        guidance.periapsis_position_m =
            finite_vector(scale(periapsis_direction, orbit.periapsis_radius_m));
        guidance.apoapsis_position_m = orbit
            .apoapsis_radius_m
            .and_then(|radius| finite_vector(scale(periapsis_direction, -radius)));
        if regime == OrbitRegime::NearParabolic {
            guidance.apoapsis_position_m = None;
            return Some(guidance);
        }
        let radius_velocity = dot(position, velocity);
        let dot_roundoff = 8.0
            * f64::EPSILON
            * ((position.x * velocity.x).abs()
                + (position.y * velocity.y).abs()
                + (position.z * velocity.z).abs());
        let at_apsis = radius_velocity.abs() <= dot_roundoff;
        let cosine = dot(periapsis_direction, radial).clamp(-1.0, 1.0);
        if regime == OrbitRegime::Elliptic {
            let semimajor = orbit.periapsis_radius_m / (1.0 - eccentricity);
            let time_scale = semimajor * (semimajor / mu).sqrt();
            let sine_e = radius_velocity / (eccentricity * (mu * semimajor).sqrt());
            let cosine_e = (1.0 - radius / semimajor) / eccentricity;
            // Signed anomaly retains a tiny inbound time; reducing it modulo
            // 2pi would first round it into a huge, almost-complete orbit.
            let anomaly = sine_e.atan2(cosine_e);
            let mean_magnitude = elliptic_mean(anomaly.abs(), eccentricity);
            let peri_phase = if at_apsis && cosine > 0.0 {
                0.0
            } else if anomaly <= 0.0 {
                mean_magnitude
            } else {
                TAU - mean_magnitude
            };
            // Calculate the small remaining phase near apoapsis directly,
            // without subtracting two nearly equal numbers around pi.
            let gap = sine_e.abs().atan2(-cosine_e);
            let from_apo_phase = gap + eccentricity * gap.sin();
            let apo_phase = if at_apsis && cosine < 0.0 {
                0.0
            } else if anomaly >= 0.0 {
                from_apo_phase
            } else {
                TAU - from_apo_phase
            };
            guidance.time_to_periapsis_seconds = finite_time(peri_phase * time_scale);
            guidance.time_to_apoapsis_seconds = finite_time(apo_phase * time_scale);
        } else {
            let semimajor_magnitude = orbit.periapsis_radius_m / (eccentricity - 1.0);
            let time_scale = semimajor_magnitude * (semimajor_magnitude / mu).sqrt();
            let sinh_h = radius_velocity / (eccentricity * (mu * semimajor_magnitude).sqrt());
            let anomaly = sinh_h.asinh();
            if at_apsis && cosine > 0.0 {
                guidance.time_to_periapsis_seconds = Some(0.0);
            } else if radius_velocity < 0.0 {
                guidance.time_to_periapsis_seconds =
                    finite_time(hyperbolic_mean(-anomaly, eccentricity) * time_scale);
            }
        }
        Some(guidance)
    }
}

// The small anomaly terms need their own evaluation near escape. Direct
// subtraction E - sin(E) or sinh(H) - H destroys the periapsis countdown.
fn elliptic_mean(anomaly: f64, eccentricity: f64) -> f64 {
    let x2 = anomaly * anomaly;
    let x_minus_sin = if anomaly < 0.1 {
        anomaly * x2 * (1.0 / 6.0 - x2 * (1.0 / 120.0 - x2 * (1.0 / 5040.0 - x2 / 362880.0)))
    } else {
        anomaly - anomaly.sin()
    };
    (1.0 - eccentricity) * anomaly.sin() + x_minus_sin
}

fn hyperbolic_mean(anomaly: f64, eccentricity: f64) -> f64 {
    let x2 = anomaly * anomaly;
    let sinh_minus_x = if anomaly < 0.1 {
        anomaly * x2 * (1.0 / 6.0 + x2 * (1.0 / 120.0 + x2 * (1.0 / 5040.0 + x2 / 362880.0)))
    } else {
        anomaly.sinh() - anomaly
    };
    (eccentricity - 1.0) * anomaly.sinh() + sinh_minus_x
}

fn finite_time(value: f64) -> Option<f64> {
    (value.is_finite() && value >= 0.0).then_some(value)
}
fn finite_vector(value: Vector3d) -> Option<Vector3d> {
    (value.x.is_finite() && value.y.is_finite() && value.z.is_finite()).then_some(value)
}
fn norm(value: Vector3d) -> f64 {
    value.x.hypot(value.y).hypot(value.z)
}
fn dot(a: Vector3d, b: Vector3d) -> f64 {
    a.x * b.x + a.y * b.y + a.z * b.z
}
fn sub(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}
fn scale(a: Vector3d, factor: f64) -> Vector3d {
    Vector3d {
        x: a.x * factor,
        y: a.y * factor,
        z: a.z * factor,
    }
}
fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}
