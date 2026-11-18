//! Instrumentation from authoritative state. Osculating elements are an
//! instantaneous two-body conic, not a long-horizon n-body prediction.
use solarlab_domain::Vector3d;

const G: f64 = 6.67430e-11;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OrbitalElements {
    pub distance_m: f64,
    pub speed_mps: f64,
    pub radial_speed_mps: f64,
    pub periapsis_radius_m: f64,
    pub apoapsis_radius_m: Option<f64>,
    pub eccentricity: f64,
    pub inclination_radians: f64,
    pub period_seconds: Option<f64>,
}

impl OrbitalElements {
    /// SI inputs relative to the same primary and epoch, in fixed inertial axes.
    /// Degenerate radial and non-finite states do not define an orbital plane.
    #[must_use]
    pub fn from_relative(position: Vector3d, velocity: Vector3d, mu: f64) -> Option<Self> {
        let r = length(position);
        let v2 = dot(velocity, velocity);
        if !r.is_finite() || r <= 0.0 || !v2.is_finite() || !mu.is_finite() || mu <= 0.0 {
            return None;
        }
        let h = cross(position, velocity);
        let h2 = dot(h, h);
        if !h2.is_finite() || h2 <= 0.0 {
            return None;
        }
        let rv = dot(position, velocity);
        let eccentricity = length(scale(
            sub(scale(position, v2 - mu / r), scale(velocity, rv)),
            1.0 / mu,
        ));
        let energy = v2 * 0.5 - mu / r;
        let bound = energy < 0.0 && eccentricity < 1.0;
        let a = if bound { -mu / (2.0 * energy) } else { 0.0 };
        let result = Self {
            distance_m: r,
            speed_mps: v2.sqrt(),
            radial_speed_mps: rv / r,
            periapsis_radius_m: h2 / (mu * (1.0 + eccentricity)),
            apoapsis_radius_m: bound.then_some(a * (1.0 + eccentricity)),
            eccentricity,
            inclination_radians: (h.z / h2.sqrt()).clamp(-1.0, 1.0).acos(),
            period_seconds: bound.then_some(std::f64::consts::TAU * (a.powi(3) / mu).sqrt()),
        };
        (result.eccentricity.is_finite() && result.periapsis_radius_m.is_finite()).then_some(result)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct FlightTelemetry {
    pub epoch_seconds: f64,
    pub revision: u64,
    pub body_id: String,
    pub reference_body_id: String,
    pub reference_radius_m: f64,
    pub altitude_m: f64,
    pub orbit: Option<OrbitalElements>,
    pub moon_distance_m: Option<f64>,
    pub relative_position_m: Vector3d,
    pub relative_velocity_mps: Vector3d,
    pub main_engine_max_thrust_n: f64,
    pub main_engine_mass_flow_kgps: f64,
    pub remaining_delta_v_mps: f64,
}

impl super::WorldRuntime {
    /// Read-only sample. All positions remain in the existing world-body store.
    #[must_use]
    pub fn flight_telemetry(&self, body_id: &str) -> Option<FlightTelemetry> {
        let ship = self.spacecraft_by_id(body_id)?;
        let body = self.body_by_id(body_id)?;
        let host = self.body_by_id(&ship.reference_body_id.as_ref()?.0)?;
        let position = sub(body.position_m, host.position_m);
        let velocity = sub(body.velocity_mps, host.velocity_mps);
        let (force, flow) = ship
            .engines
            .iter()
            .filter(|e| e.control == solarlab_domain::EngineControl::Main)
            .fold((Vector3d::default(), 0.0), |(f, m), e| {
                (
                    Vector3d {
                        x: f.x + e.direction_body.x * e.max_thrust_n,
                        y: f.y + e.direction_body.y * e.max_thrust_n,
                        z: f.z + e.direction_body.z * e.max_thrust_n,
                    },
                    m + e.max_mass_flow_kgps,
                )
            });
        let thrust = length(force);
        let delta_v = if flow > 0.0 {
            thrust / flow * (ship.mass_kg() / ship.dry_mass_kg).ln()
        } else {
            0.0
        };
        Some(FlightTelemetry {
            epoch_seconds: self.active_branch().world.epoch_seconds,
            revision: self.snapshot_revision(),
            body_id: body_id.into(),
            reference_body_id: host.body_id.0.clone(),
            reference_radius_m: host.radius_m,
            altitude_m: length(position) - host.radius_m,
            orbit: OrbitalElements::from_relative(
                position,
                velocity,
                G * (host.source_mass_kg + body.source_mass_kg),
            ),
            moon_distance_m: self
                .body_by_id("moon")
                .map(|moon| length(sub(body.position_m, moon.position_m)) - moon.radius_m),
            relative_position_m: position,
            relative_velocity_mps: velocity,
            main_engine_max_thrust_n: thrust,
            main_engine_mass_flow_kgps: flow,
            remaining_delta_v_mps: delta_v,
        })
    }
}

fn dot(a: Vector3d, b: Vector3d) -> f64 {
    a.x * b.x + a.y * b.y + a.z * b.z
}
fn length(v: Vector3d) -> f64 {
    dot(v, v).sqrt()
}
fn sub(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}
fn scale(v: Vector3d, s: f64) -> Vector3d {
    Vector3d {
        x: v.x * s,
        y: v.y * s,
        z: v.z * s,
    }
}
fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const MU: f64 = 3.986_004_418e14;
    fn v(x: f64, y: f64, z: f64) -> Vector3d {
        Vector3d { x, y, z }
    }
    #[test]
    fn circular_and_elliptical_metrics_match_kepler() {
        let r = 7_000_000.0;
        let c = OrbitalElements::from_relative(v(r, 0.0, 0.0), v(0.0, (MU / r).sqrt(), 0.0), MU)
            .unwrap();
        assert!(c.eccentricity < 1e-14);
        assert!((c.periapsis_radius_m - r).abs() < 1e-7);
        assert!(
            (c.period_seconds.unwrap() - std::f64::consts::TAU * (r.powi(3) / MU).sqrt()).abs()
                < 1e-9
        );
        let apo = 21_000_000.0;
        let a = (r + apo) * 0.5;
        let e = OrbitalElements::from_relative(
            v(r, 0.0, 0.0),
            v(0.0, (MU * (2.0 / r - 1.0 / a)).sqrt(), 0.0),
            MU,
        )
        .unwrap();
        assert!((e.eccentricity - 0.5).abs() < 1e-14);
        assert!((e.apoapsis_radius_m.unwrap() - apo).abs() < 1e-6);
    }
    #[test]
    fn escape_polar_and_degenerate_states_are_explicit() {
        let p = v(7_000_000.0, 0.0, 0.0);
        let e = OrbitalElements::from_relative(p, v(0.0, 12_000.0, 0.0), MU).unwrap();
        assert!(e.eccentricity > 1.0);
        assert!(e.period_seconds.is_none() && e.apoapsis_radius_m.is_none());
        let polar = OrbitalElements::from_relative(p, v(0.0, 0.0, 7_500.0), MU).unwrap();
        assert!((polar.inclination_radians - std::f64::consts::FRAC_PI_2).abs() < 1e-14);
        assert!(OrbitalElements::from_relative(p, v(1.0, 0.0, 0.0), MU).is_none());
    }
}
