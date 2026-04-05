use solarlab_domain::Vector3d;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SolverBackend {
    ReferenceScalar,
    SimdArm64,
    SimdX64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum IntegratorKind {
    LeapfrogKickDriftKick,
    AdaptiveSymplectic,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CollisionModel {
    None,
    Merge,
    Elastic,
    Fragmentation,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhysicsPolicy {
    pub solver_backend: SolverBackend,
    pub integrator: IntegratorKind,
    pub collision_model: CollisionModel,
    pub max_substep_seconds: f64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PhysicsInvariants {
    pub total_energy_j: f64,
    pub linear_momentum_kg_mps: Vector3d,
    pub angular_momentum_kg_m2ps: Vector3d,
    pub barycenter_m: Vector3d,
}

/// Authoritative state for a massive body participating in mutual gravity.
/// 
/// Massive bodies exert force on all other massive bodies and tracers.
/// Position and velocity use double-precision (f64) to maintain orbital 
/// stability over long simulation durations.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MassiveBodyState {
    pub mass_kg: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}

const G_M3_PER_KG_S2: f64 = 6.67430e-11;
const MIN_DISTANCE_M2: f64 = 1.0e-12;

pub fn advance_authoritative_scalar(
    policy: &PhysicsPolicy,
    bodies: &mut [MassiveBodyState],
    delta_seconds: f64,
) -> PhysicsInvariants {
    if bodies.is_empty() || delta_seconds <= 0.0 {
        return compute_invariants(bodies);
    }

    let max_substep_seconds =
        if policy.max_substep_seconds.is_finite() && policy.max_substep_seconds > 0.0 {
            policy.max_substep_seconds
        } else {
            delta_seconds
        };
    let step_count = (delta_seconds / max_substep_seconds).ceil().max(1.0) as usize;
    let dt_seconds = delta_seconds / step_count as f64;

    for _ in 0..step_count {
        integrate_substep(bodies, dt_seconds);
    }

    compute_invariants(bodies)
}

pub fn compute_invariants(bodies: &[MassiveBodyState]) -> PhysicsInvariants {
    if bodies.is_empty() {
        return PhysicsInvariants::default();
    }

    let mut total_mass = 0.0_f64;
    let mut barycenter_numerator = Vector3d::default();
    let mut linear_momentum = Vector3d::default();
    let mut angular_momentum = Vector3d::default();
    let mut kinetic_energy_j = 0.0_f64;

    for body in bodies {
        total_mass += body.mass_kg;
        barycenter_numerator = add(barycenter_numerator, scale(body.position_m, body.mass_kg));

        let momentum = scale(body.velocity_mps, body.mass_kg);
        linear_momentum = add(linear_momentum, momentum);
        angular_momentum = add(angular_momentum, cross(body.position_m, momentum));
        kinetic_energy_j += 0.5 * body.mass_kg * norm_squared(body.velocity_mps);
    }

    let mut potential_energy_j = 0.0_f64;
    for i in 0..bodies.len() {
        for j in (i + 1)..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let distance_m = norm(delta).max(MIN_DISTANCE_M2.sqrt());
            potential_energy_j -=
                G_M3_PER_KG_S2 * bodies[i].mass_kg * bodies[j].mass_kg / distance_m;
        }
    }

    PhysicsInvariants {
        total_energy_j: kinetic_energy_j + potential_energy_j,
        linear_momentum_kg_mps: linear_momentum,
        angular_momentum_kg_m2ps: angular_momentum,
        barycenter_m: if total_mass > 0.0 {
            scale(barycenter_numerator, 1.0 / total_mass)
        } else {
            Vector3d::default()
        },
    }
}

/// Performs a single Kick-Drift-Kick (Leapfrog) integration substep.
/// 
/// The sequence is:
/// 1. Compute initial accelerations (a0) based on current positions.
/// 2. Kick: Update velocities by half a time step (v = v + a0 * dt/2).
/// 3. Drift: Update positions by a full time step (r = r + v * dt).
/// 4. Recompute accelerations (a1) at the new positions.
/// 5. Kick: Update velocities by the remaining half time step (v = v + a1 * dt/2).
/// 
/// This method is second-order accurate and symplectic, ensuring that the 
/// orbital energy of the system remains stable (non-drifting) over many steps.
fn integrate_substep(bodies: &mut [MassiveBodyState], dt_seconds: f64) {
    let a0 = pairwise_gravity_accelerations(bodies);

    for i in 0..bodies.len() {
        bodies[i].velocity_mps = add(bodies[i].velocity_mps, scale(a0[i], 0.5 * dt_seconds));
        bodies[i].position_m = add(
            bodies[i].position_m,
            scale(bodies[i].velocity_mps, dt_seconds),
        );
    }

    let a1 = pairwise_gravity_accelerations(bodies);
    for i in 0..bodies.len() {
        bodies[i].velocity_mps = add(bodies[i].velocity_mps, scale(a1[i], 0.5 * dt_seconds));
    }
}

fn pairwise_gravity_accelerations(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    for i in 0..bodies.len() {
        for j in (i + 1)..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let distance_sq = norm_squared(delta).max(MIN_DISTANCE_M2);
            let inv_distance = distance_sq.sqrt().recip();
            let inv_distance_cubed = inv_distance * inv_distance * inv_distance;

            let accel_i = scale(
                delta,
                G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_distance_cubed,
            );
            let accel_j = scale(
                delta,
                -G_M3_PER_KG_S2 * bodies[i].mass_kg * inv_distance_cubed,
            );

            accelerations[i] = add(accelerations[i], accel_i);
            accelerations[j] = add(accelerations[j], accel_j);
        }
    }
    accelerations
}

fn add(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x + b.x,
        y: a.y + b.y,
        z: a.z + b.z,
    }
}

fn subtract(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}

fn scale(v: Vector3d, scalar: f64) -> Vector3d {
    Vector3d {
        x: v.x * scalar,
        y: v.y * scalar,
        z: v.z * scalar,
    }
}

fn cross(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.y * b.z - a.z * b.y,
        y: a.z * b.x - a.x * b.z,
        z: a.x * b.y - a.y * b.x,
    }
}

fn norm(v: Vector3d) -> f64 {
    norm_squared(v).sqrt()
}

fn norm_squared(v: Vector3d) -> f64 {
    v.x * v.x + v.y * v.y + v.z * v.z
}

#[cfg(test)]
mod tests {
    use super::{
        advance_authoritative_scalar, compute_invariants, CollisionModel, G_M3_PER_KG_S2,
        IntegratorKind, MIN_DISTANCE_M2, MassiveBodyState, PhysicsPolicy, SolverBackend,
        pairwise_gravity_accelerations,
    };
    use solarlab_domain::Vector3d;

    #[test]
    fn scalar_authoritative_solver_moves_bodies_toward_each_other() {
        let policy = test_policy();
        let mut bodies = vec![
            MassiveBodyState {
                mass_kg: 5.972e24,
                position_m: Vector3d {
                    x: -2.0e7,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 7.348e22,
                position_m: Vector3d {
                    x: 2.0e7,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ];

        let initial_left_x = bodies[0].position_m.x;
        let initial_right_x = bodies[1].position_m.x;

        let invariants = advance_authoritative_scalar(&policy, &mut bodies, 60.0);

        assert!(bodies[0].position_m.x > initial_left_x);
        assert!(bodies[1].position_m.x < initial_right_x);
        assert!(invariants.total_energy_j.is_finite());
    }

    #[test]
    fn invariants_capture_barycenter_and_linear_momentum() {
        let bodies = vec![
            MassiveBodyState {
                mass_kg: 2.0,
                position_m: Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: 3.0,
                    y: 0.0,
                    z: 0.0,
                },
            },
            MassiveBodyState {
                mass_kg: 1.0,
                position_m: Vector3d {
                    x: 2.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
            },
        ];

        let invariants = compute_invariants(&bodies);

        assert!((invariants.barycenter_m.x - 0.0).abs() < 1.0e-12);
        assert!((invariants.linear_momentum_kg_mps.x - 5.0).abs() < 1.0e-12);
    }

    #[test]
    fn pairwise_kernel_matches_reference_mixed_loop() {
        let bodies = mixed_gravity_scenario_for_parity();
        let actual = pairwise_gravity_accelerations(&bodies);
        let expected = legacy_reference_accelerations(&bodies);

        for i in 0..actual.len() {
            assert_vector_close(actual[i], expected[i], 1e-15);
        }
    }

    #[test]
    fn pairwise_kernel_returns_zero_with_no_secondary_sources() {
        let body = MassiveBodyState {
            mass_kg: 1.0e9,
            position_m: Vector3d {
                x: 42.0,
                y: -7.0,
                z: 13.0,
            },
            velocity_mps: Vector3d {
                x: 1.0,
                y: -0.5,
                z: 2.0,
            },
        };
        let policy = test_policy();

        let mut only_body = vec![body];
        let before = only_body.clone();
        let accelerations = pairwise_gravity_accelerations(&only_body);
        let mut stationary_body = vec![MassiveBodyState {
            mass_kg: body.mass_kg,
            position_m: body.position_m,
            velocity_mps: Vector3d::default(),
        }];

        for component in [&accelerations[0].x, &accelerations[0].y, &accelerations[0].z] {
            assert_eq!(*component, 0.0);
        }

        advance_authoritative_scalar(&policy, &mut only_body, 10.0);
        advance_authoritative_scalar(&policy, &mut stationary_body, 10.0);

        assert_eq!(before[0].velocity_mps.x, only_body[0].velocity_mps.x);
        assert_eq!(before[0].velocity_mps.y, only_body[0].velocity_mps.y);
        assert_eq!(before[0].velocity_mps.z, only_body[0].velocity_mps.z);
        assert_eq!(before[0].position_m.x + 10.0 * before[0].velocity_mps.x, only_body[0].position_m.x);
        assert_eq!(before[0].position_m.y + 10.0 * before[0].velocity_mps.y, only_body[0].position_m.y);
        assert_eq!(before[0].position_m.z + 10.0 * before[0].velocity_mps.z, only_body[0].position_m.z);

        assert_eq!(42.0, stationary_body[0].position_m.x);
        assert_eq!(-7.0, stationary_body[0].position_m.y);
        assert_eq!(13.0, stationary_body[0].position_m.z);
        assert_eq!(0.0, stationary_body[0].velocity_mps.x);
        assert_eq!(0.0, stationary_body[0].velocity_mps.y);
        assert_eq!(0.0, stationary_body[0].velocity_mps.z);
    }

    #[test]
    fn pairwise_kernel_stabilizes_edge_distance_and_conserves_pairwise_force() {
        let bodies = vec![
            MassiveBodyState {
                mass_kg: 1.0e24,
                position_m: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 1.0e20,
                position_m: Vector3d {
                    x: 1.0e-8,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ];

        let accelerations = pairwise_gravity_accelerations(&bodies);

        for body_accel in &accelerations {
            assert!(body_accel.x.is_finite());
            assert!(body_accel.y.is_finite());
            assert!(body_accel.z.is_finite());
        }

        let force_balance_x = bodies[0].mass_kg * accelerations[0].x + bodies[1].mass_kg * accelerations[1].x;
        let force_balance_y = bodies[0].mass_kg * accelerations[0].y + bodies[1].mass_kg * accelerations[1].y;
        let force_balance_z = bodies[0].mass_kg * accelerations[0].z + bodies[1].mass_kg * accelerations[1].z;

        assert!(force_balance_x.abs() < 1e-6);
        assert!(force_balance_y.abs() < 1e-6);
        assert!(force_balance_z.abs() < 1e-6);
    }

    fn assert_vector_close(actual: Vector3d, expected: Vector3d, eps: f64) {
        assert!((actual.x - expected.x).abs() <= eps);
        assert!((actual.y - expected.y).abs() <= eps);
        assert!((actual.z - expected.z).abs() <= eps);
    }

    fn legacy_reference_accelerations(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
        let mut accelerations = vec![Vector3d::default(); bodies.len()];
        for i in 0..bodies.len() {
            let body = bodies[i];
            let body_position = body.position_m;
            let mut acceleration_x = 0.0;
            let mut acceleration_y = 0.0;
            let mut acceleration_z = 0.0;

            for source_index in 0..bodies.len() {
                if source_index == i {
                    continue;
                }

                let source = bodies[source_index];
                let dx = source.position_m.x - body_position.x;
                let dy = source.position_m.y - body_position.y;
                let dz = source.position_m.z - body_position.z;
                let distance_squared = (dx * dx) + (dy * dy) + (dz * dz) + MIN_DISTANCE_M2;

                if distance_squared == 0.0 {
                    continue;
                }

                let inv_distance = distance_squared.sqrt().recip();
                let inv_distance_cubed = inv_distance * inv_distance * inv_distance;
                let scale = G_M3_PER_KG_S2 * source.mass_kg * inv_distance_cubed;
                acceleration_x += dx * scale;
                acceleration_y += dy * scale;
                acceleration_z += dz * scale;
            }

            accelerations[i] = Vector3d {
                x: acceleration_x,
                y: acceleration_y,
                z: acceleration_z,
            };
        }
        accelerations
    }

    fn mixed_gravity_scenario_for_parity() -> Vec<MassiveBodyState> {
        vec![
            MassiveBodyState {
                mass_kg: 1.98847e30,
                position_m: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 5.972168e24,
                position_m: Vector3d {
                    x: 1.496e11,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 7.342e22,
                position_m: Vector3d {
                    x: 1.499844e11,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 10.0,
                position_m: Vector3d {
                    x: 1.0408e11,
                    y: 3.0e10,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ]
    }

    fn test_policy() -> PhysicsPolicy {
        PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: 1.0,
        }
    }
}
