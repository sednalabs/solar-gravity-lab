//! Small, deterministic, synthetic fixtures. These are not ephemerides.

use solarlab_domain::Vector3d;
use solarlab_physics::MassiveBodyState;

pub type V3 = [f64; 3];
pub const G: f64 = 6.67430e-11;
pub const MIN_R2: f64 = 1.0e-12;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Body {
    /// Gravitational parameter, in m^3/s^2. Zero means a passive target.
    pub mu: f64,
    pub p: V3,
    pub v: V3,
}

#[derive(Clone)]
pub struct Fixture {
    pub id: &'static str,
    pub bodies: Vec<Body>,
    pub dt: f64,
}

impl Fixture {
    pub fn source_count(&self) -> usize {
        self.bodies.iter().filter(|body| body.mu > 0.0).count()
    }
}

pub fn add(a: V3, b: V3) -> V3 {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

pub fn sub(a: V3, b: V3) -> V3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

pub fn scale(a: V3, k: f64) -> V3 {
    [a[0] * k, a[1] * k, a[2] * k]
}

pub fn dot(a: V3, b: V3) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

pub fn norm(a: V3) -> f64 {
    dot(a, a).sqrt()
}

pub fn cross(a: V3, b: V3) -> V3 {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

// Every generated fixture index/count is <=1024 and exactly representable in FP64.
#[allow(clippy::cast_precision_loss)]
pub fn fixture(id: &str) -> Option<Fixture> {
    let (id, count, source_count) = match id {
        "sparse3" => ("sparse3", 1024, 3),
        "canonical15" => ("canonical15", 365, 15),
        "dense192" => ("dense192", 192, 192),
        "dense256" => ("dense256", 256, 256),
        "kepler" => {
            return Some(Fixture {
                id: "kepler",
                bodies: vec![
                    Body {
                        mu: 1.0,
                        p: [0.0; 3],
                        v: [0.0; 3],
                    },
                    Body {
                        mu: 0.0,
                        p: [1.0, 0.0, 0.0],
                        v: [0.0, 1.0, 0.0],
                    },
                ],
                dt: 1.0 / 64.0,
            });
        }
        "close-encounter" => {
            return Some(Fixture {
                id: "close-encounter",
                bodies: vec![
                    Body {
                        mu: 1.0,
                        p: [0.0; 3],
                        v: [0.0; 3],
                    },
                    Body {
                        mu: 1.0e-3,
                        p: [1.0, 0.0, 0.0],
                        v: [0.0, 1.0, 0.0],
                    },
                    Body {
                        mu: 0.0,
                        p: [1.02, -0.025, 0.005],
                        v: [-0.08, 1.15, 0.01],
                    },
                    Body {
                        mu: 0.0,
                        p: [0.25, 0.0, 0.0],
                        v: [0.0, 2.65, 0.0],
                    },
                ],
                dt: 1.0 / 1024.0,
            });
        }
        _ => return None,
    };
    let dense = source_count == count;
    let mut bodies = Vec::with_capacity(count);
    if !dense {
        bodies.push(Body {
            mu: 1.0,
            p: [0.0; 3],
            v: [0.0; 3],
        });
    }
    for i in usize::from(!dense)..count {
        // Irrational angular increments avoid degenerate rings and exact overlaps.
        let angle = i as f64 * 2.399_963_229_728_653;
        let r = if dense {
            2.0 + (i % 17) as f64 * 0.31
        } else {
            2.0 + (i % 97) as f64 * 0.19
        };
        let p = [
            r * angle.cos(),
            r * angle.sin(),
            ((i * 13 % 29) as f64 - 14.0) * 0.07,
        ];
        let speed = if dense { 0.12 } else { r.sqrt().recip() };
        bodies.push(Body {
            mu: if dense {
                1.0 / count as f64
            } else if i < source_count {
                1.0e-5 * (i + 1) as f64
            } else {
                0.0
            },
            p,
            v: [-speed * angle.sin(), speed * angle.cos(), 0.0],
        });
    }
    Some(Fixture {
        id,
        bodies,
        dt: 1.0 / 1024.0,
    })
}

pub fn to_production(bodies: &[Body]) -> Vec<MassiveBodyState> {
    bodies
        .iter()
        .map(|b| MassiveBodyState {
            mass_kg: b.mu / G,
            position_m: Vector3d {
                x: b.p[0],
                y: b.p[1],
                z: b.p[2],
            },
            velocity_mps: Vector3d {
                x: b.v[0],
                y: b.v[1],
                z: b.v[2],
            },
        })
        .collect()
}

pub fn from_production(bodies: &[MassiveBodyState]) -> Vec<Body> {
    bodies
        .iter()
        .map(|b| Body {
            mu: b.mass_kg * G,
            p: [b.position_m.x, b.position_m.y, b.position_m.z],
            v: [b.velocity_mps.x, b.velocity_mps.y, b.velocity_mps.z],
        })
        .collect()
}

pub fn finite(bodies: &[Body]) -> bool {
    bodies
        .iter()
        .all(|b| b.mu.is_finite() && b.p.iter().chain(b.v.iter()).all(|v| v.is_finite()))
}

pub fn checksum(bodies: &[Body]) -> String {
    // A stable checksum for result identity, not a cryptographic integrity claim.
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    for body in bodies {
        for value in std::iter::once(&body.mu)
            .chain(body.p.iter())
            .chain(body.v.iter())
        {
            for byte in value.to_bits().to_le_bytes() {
                hash = (hash ^ u64::from(byte)).wrapping_mul(0x0000_0100_0000_01b3);
            }
        }
    }
    format!("{hash:016x}")
}

#[derive(serde::Serialize, Default)]
pub struct ErrorMetrics {
    pub max_abs_position_m: f64,
    pub max_abs_velocity_mps: f64,
    pub max_component_relative_error: f64,
    pub scaled_state_error: f64,
    pub bitwise_equal_components: usize,
    pub compared_components: usize,
}

pub fn error(actual: &[Body], reference: &[Body]) -> ErrorMetrics {
    let mut e = ErrorMetrics::default();
    for (a, b) in actual.iter().zip(reference) {
        e.max_abs_position_m = e.max_abs_position_m.max(norm(sub(a.p, b.p)));
        e.max_abs_velocity_mps = e.max_abs_velocity_mps.max(norm(sub(a.v, b.v)));
        e.scaled_state_error = e
            .scaled_state_error
            .max(norm(sub(a.p, b.p)) / norm(b.p).max(1.0))
            .max(norm(sub(a.v, b.v)) / norm(b.v).max(1.0));
        for (x, y) in
            a.p.iter()
                .chain(a.v.iter())
                .zip(b.p.iter().chain(b.v.iter()))
        {
            e.max_component_relative_error = e
                .max_component_relative_error
                .max((x - y).abs() / y.abs().max(1.0e-30));
            e.bitwise_equal_components += usize::from(x.to_bits() == y.to_bits());
            e.compared_components += 1;
        }
    }
    e
}

#[derive(serde::Serialize, Default)]
pub struct Invariants {
    pub source_energy_g_times_j: f64,
    pub source_momentum_g_times_kg_mps: V3,
    pub source_angular_momentum_g_times_kg_m2ps: V3,
    pub tracer_specific_energy: Option<f64>,
    pub tracer_radius_m: Option<f64>,
    pub tracer_phase_rad: Option<f64>,
}

pub fn invariants(bodies: &[Body]) -> Invariants {
    let mut out = Invariants::default();
    for (i, b) in bodies.iter().enumerate() {
        if b.mu == 0.0 {
            continue;
        }
        out.source_energy_g_times_j += 0.5 * b.mu * dot(b.v, b.v);
        out.source_momentum_g_times_kg_mps =
            add(out.source_momentum_g_times_kg_mps, scale(b.v, b.mu));
        out.source_angular_momentum_g_times_kg_m2ps = add(
            out.source_angular_momentum_g_times_kg_m2ps,
            scale(cross(b.p, b.v), b.mu),
        );
        for other in &bodies[i + 1..] {
            if other.mu == 0.0 {
                continue;
            }
            let r2 = dot(sub(b.p, other.p), sub(b.p, other.p));
            let potential = if r2 >= MIN_R2 {
                -b.mu * other.mu / r2.sqrt()
            } else {
                -b.mu * other.mu / MIN_R2.sqrt() * (1.5 - 0.5 * r2 / MIN_R2)
            };
            out.source_energy_g_times_j += potential;
        }
    }
    if bodies.len() == 2 && bodies[0].mu > 0.0 && bodies[1].mu == 0.0 {
        let p = sub(bodies[1].p, bodies[0].p);
        let v = sub(bodies[1].v, bodies[0].v);
        out.tracer_specific_energy = Some(0.5 * dot(v, v) - bodies[0].mu / norm(p));
        out.tracer_radius_m = Some(norm(p));
        out.tracer_phase_rad = Some(p[1].atan2(p[0]));
    }
    out
}
