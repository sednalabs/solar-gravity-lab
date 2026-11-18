//! Opt-in local rigid contact inside the authoritative orbital integration.
//!
//! Gravity and propulsion kick velocities once; Rapier owns only the intervening
//! local drift, rotation, contacts and fixed constraints. The translating frame
//! is inertially oriented, so no fictitious forces are omitted. A cold solve at
//! every bounded substep makes checkpoints complete without serializing caches.
use crate::spacecraft::SpacecraftBinding;
use crate::{FixedStepPlan, MassiveBodyState};
use rapier3d_f64::prelude::*;
use solarlab_domain::docking::*;
use solarlab_domain::spacecraft::{add, cross, finite, magnitude, scale, sub};
use solarlab_domain::{
    AttitudeMode, ContactShape, DockingAttachment, DockingGuidanceMode, DockingStatus,
    FlightControl, LocalReferenceFrame, Quaterniond, SpacecraftAppearance, SpacecraftState,
    Vector3d,
};
use std::sync::{Mutex, OnceLock};

pub const MAX_CONTACT_SUBSTEP_SECONDS: f64 = 0.01;
const CONTACT_RESTITUTION: f64 = 0.1;
const CONTACT_FRICTION: f64 = 0.4;

#[derive(Clone, Copy)]
struct ContactSample {
    collider1: ColliderHandle,
    collider2: ColliderHandle,
    normal: Vector,
    signed_distance_m: f64,
}

/// CCD may resolve and separate a pair inside one Rapier call. The final
/// manifold set alone loses that impact, so retain force evidence per substep.
#[derive(Default)]
struct ContactEvidence(Mutex<Vec<ContactSample>>);

impl EventHandler for ContactEvidence {
    fn handle_collision_event(
        &self,
        _: &RigidBodySet,
        _: &ColliderSet,
        _: CollisionEvent,
        _: Option<&ContactPair>,
    ) {
    }
    fn handle_contact_force_event(
        &self,
        dt: f64,
        _: &RigidBodySet,
        _: &ColliderSet,
        pair: &ContactPair,
        force: f64,
    ) {
        if dt * force > 1e-7 {
            let signed_distance_m = pair
                .find_deepest_contact()
                .map_or(f64::INFINITY, |(_, c)| c.dist);
            if let Ok(mut samples) = self.0.lock() {
                samples.push(ContactSample {
                    collider1: pair.collider1,
                    collider2: pair.collider2,
                    normal: pair.max_impulse().1,
                    signed_distance_m,
                });
            }
        }
    }
    fn handle_soft_body_tear_event(&self, _: &SoftBodySet, _: &SoftBodyTearEvent) {}
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DockingObservation {
    /// Target port axes: +X right, +Y key-up, +Z outward. z is signed axial gap.
    pub position_port_m: Vector3d,
    pub velocity_port_mps: Vector3d,
    pub port_distance_m: f64,
    pub closing_speed_mps: f64,
    /// Actual inertial relative speed at the two physical port centres.
    pub relative_speed_mps: f64,
    /// Full mating orientation, including roll about the port normal.
    pub alignment_error_rad: f64,
    pub relative_angular_speed_radps: f64,
    pub capture_ready: bool,
    pub status: DockingStatus,
}

/// Authored physical port transforms, shared with the original mesh generators.
#[must_use]
pub fn port_pose(appearance: SpacecraftAppearance) -> (Vector3d, Quaterniond) {
    match appearance {
        SpacecraftAppearance::Asteria => (
            Vector3d {
                x: 0.0,
                y: 0.0,
                z: 3.52,
            },
            Quaterniond::IDENTITY,
        ),
        SpacecraftAppearance::RelayStation => (
            Vector3d {
                x: 0.0,
                y: -4.04,
                z: 0.0,
            },
            Quaterniond::from_rotation_vector(Vector3d {
                x: std::f64::consts::FRAC_PI_2,
                y: 0.0,
                z: 0.0,
            }),
        ),
    }
}

/// The flight component supplies the physical frame; a mesh is only its default.
#[must_use]
pub fn spacecraft_port_pose(ship: &SpacecraftState) -> (Vector3d, Quaterniond) {
    ship.authored
        .as_ref()
        .and_then(|a| a.contact.as_ref())
        .map_or_else(
            || port_pose(ship.appearance),
            |c| (c.port.position_body_m, c.port.attitude_body),
        )
}

/// Both annular faces must overlap. Geometry, rather than a spacecraft name or
/// visual mesh, decides whether the two active ports can physically mate.
#[must_use]
pub fn ports_compatible(ship: &SpacecraftState, target: &SpacecraftState) -> bool {
    let radii = |s: &SpacecraftState| {
        s.authored
            .as_ref()
            .and_then(|a| a.contact.as_ref())
            .map_or_else(
                || match s.appearance {
                    SpacecraftAppearance::Asteria => (0.43_f64, 0.72_f64),
                    SpacecraftAppearance::RelayStation => (0.64_f64, 0.94_f64),
                },
                |c| (c.port.inner_radius_m, c.port.outer_radius_m),
            )
    };
    let (a0, a1) = radii(ship);
    let (b0, b1) = radii(target);
    a1.min(b1) - a0.max(b0) >= 0.001
}

#[must_use]
pub fn desired_spacecraft_mating_attitude(
    ship: &SpacecraftState,
    target: &SpacecraftState,
) -> Quaterniond {
    target
        .attitude
        .multiply(spacecraft_port_pose(target).1)
        .multiply(Quaterniond::from_rotation_vector(Vector3d {
            x: 0.0,
            y: std::f64::consts::PI,
            z: 0.0,
        }))
        .multiply(spacecraft_port_pose(ship).1.conjugate())
        .normalized()
}

#[must_use]
pub fn desired_mating_attitude(
    ship_appearance: SpacecraftAppearance,
    target_appearance: SpacecraftAppearance,
    target_attitude: Quaterniond,
) -> Quaterniond {
    target_attitude
        .multiply(port_pose(target_appearance).1)
        .multiply(Quaterniond::from_rotation_vector(Vector3d {
            x: 0.0,
            y: std::f64::consts::PI,
            z: 0.0,
        }))
        .multiply(port_pose(ship_appearance).1.conjugate())
        .normalized()
}

fn rotation_vector(q: Quaterniond) -> Vector3d {
    let sign = if q.w < 0.0 { -1.0 } else { 1.0 };
    let v = Vector3d {
        x: q.x * sign,
        y: q.y * sign,
        z: q.z * sign,
    };
    let s = magnitude(v);
    if s < 1e-12 {
        scale(v, 2.0)
    } else {
        scale(v, 2.0 * s.atan2(q.w.abs()) / s)
    }
}

pub fn observe(
    ship_position: Vector3d,
    ship_velocity: Vector3d,
    ship: &SpacecraftState,
    target_position: Vector3d,
    target_velocity: Vector3d,
    target: &SpacecraftState,
) -> Result<DockingObservation, ()> {
    let ship_offset = ship.attitude.rotate(spacecraft_port_pose(ship).0);
    let target_offset = target.attitude.rotate(spacecraft_port_pose(target).0);
    let ship_omega = ship.attitude.rotate(ship.angular_velocity_body_radps);
    let target_omega = target.attitude.rotate(target.angular_velocity_body_radps);
    let port_velocity = add(ship_velocity, cross(ship_omega, ship_offset));
    let target_port_velocity = add(target_velocity, cross(target_omega, target_offset));
    let frame = LocalReferenceFrame {
        origin_m: add(target_position, target_offset),
        velocity_mps: target_port_velocity,
        attitude: target
            .attitude
            .multiply(spacecraft_port_pose(target).1)
            .normalized(),
        angular_velocity_world_radps: target_omega,
    };
    // Subtract the astronomical centres first. Adding metre offsets before the
    // subtraction needlessly loses precision at large inertial origins.
    let delta = add(
        sub(ship_position, target_position),
        sub(ship_offset, target_offset),
    );
    let position_port_m = frame.attitude.conjugate().rotate(delta);
    let relative_velocity = sub(port_velocity, target_port_velocity);
    let velocity_port_mps = frame
        .attitude
        .conjugate()
        .rotate(sub(relative_velocity, cross(target_omega, delta)));
    let desired = desired_spacecraft_mating_attitude(ship, target);
    let alignment_error_rad =
        magnitude(rotation_vector(ship.attitude.conjugate().multiply(desired)));
    let relative_speed_mps = magnitude(relative_velocity);
    let relative_angular_speed_radps = magnitude(sub(ship_omega, target_omega));
    let port_distance_m = magnitude(position_port_m);
    let lateral = position_port_m.x.hypot(position_port_m.y);
    let capture_ready = ports_compatible(ship, target)
        && (-DOCKING_CONTACT_PENETRATION_M..=DOCKING_CAPTURE_GAP_M).contains(&position_port_m.z)
        && lateral <= DOCKING_CAPTURE_LATERAL_M
        && alignment_error_rad <= DOCKING_CAPTURE_ALIGNMENT_RAD
        && relative_speed_mps <= DOCKING_CAPTURE_SPEED_MPS
        && relative_angular_speed_radps <= DOCKING_CAPTURE_SPIN_RADPS;
    if !finite(position_port_m)
        || !finite(velocity_port_mps)
        || !alignment_error_rad.is_finite()
        || !relative_speed_mps.is_finite()
        || !relative_angular_speed_radps.is_finite()
    {
        return Err(());
    }
    let docking = ship.docking.as_ref();
    let status = if docking.is_some_and(|d| d.attachment.is_some()) {
        DockingStatus::Docked
    } else if docking.is_some_and(|d| d.unsafe_contact) {
        DockingStatus::UnsafeContact
    } else if port_distance_m > MAX_DOCKING_GUIDANCE_RANGE_M {
        DockingStatus::OutOfRange
    } else if capture_ready {
        DockingStatus::CaptureReady
    } else if ship.propellant_mass_kg <= 0.0 {
        DockingStatus::OutOfFuel
    } else if docking.is_some_and(|d| d.guidance_mode == DockingGuidanceMode::Approach) {
        if alignment_error_rad > DOCKING_CAPTURE_ALIGNMENT_RAD || lateral > 0.5 {
            DockingStatus::Aligning
        } else {
            DockingStatus::Approaching
        }
    } else {
        DockingStatus::Manual
    };
    Ok(DockingObservation {
        position_port_m,
        velocity_port_mps,
        port_distance_m,
        closing_speed_mps: -velocity_port_mps.z,
        relative_speed_mps,
        alignment_error_rad,
        relative_angular_speed_radps,
        capture_ready,
        status,
    })
}

#[must_use]
pub fn supports_guidance(ship: &SpacecraftState) -> bool {
    crate::rendezvous::supports_guidance(ship)
        && ship.max_control_torque_nm.x > 0.0
        && ship.max_control_torque_nm.y > 0.0
        && ship.max_control_torque_nm.z > 0.0
}

fn neutral() -> FlightControl {
    FlightControl {
        attitude_mode: AttitudeMode::Manual,
        ..FlightControl::default()
    }
}

fn guidance_control(
    ship: &SpacecraftState,
    target: &SpacecraftState,
    t: DockingObservation,
) -> Result<FlightControl, ()> {
    let capacities = crate::rendezvous::axis_rcs_capacities(ship)?;
    if !supports_guidance(ship) {
        return Err(());
    }
    let lateral = t.position_port_m.x.hypot(t.position_port_m.y);
    // A small compressive target brings the two real collar faces into contact.
    // Capture still requires actual nonpositive contact separation and impulse.
    let remaining = (t.position_port_m.z + 0.01).max(0.0);
    let closing = (0.18 * remaining).min(0.4).min((0.04 * remaining).sqrt());
    let desired_port_velocity = Vector3d {
        x: (-0.2 * t.position_port_m.x).clamp(-0.25, 0.25),
        y: (-0.2 * t.position_port_m.y).clamp(-0.25, 0.25),
        z: if t.alignment_error_rad <= 2.0 * DOCKING_CAPTURE_ALIGNMENT_RAD && lateral <= 0.5 {
            -closing
        } else {
            0.0
        },
    };
    let acceleration_port = scale(sub(desired_port_velocity, t.velocity_port_mps), 0.5);
    let port_attitude = target.attitude.multiply(spacecraft_port_pose(target).1);
    let force = scale(
        ship.attitude
            .conjugate()
            .rotate(port_attitude.rotate(acceleration_port)),
        ship.mass_kg(),
    );
    let axis = |force: f64, positive: usize, negative: usize| {
        (force / capacities[if force >= 0.0 { positive } else { negative }]).clamp(-1.0, 1.0)
    };
    let desired = desired_spacecraft_mating_attitude(ship, target);
    let error = rotation_vector(ship.attitude.conjugate().multiply(desired));
    let relative_omega = sub(
        ship.angular_velocity_body_radps,
        ship.attitude
            .conjugate()
            .rotate(target.attitude.rotate(target.angular_velocity_body_radps)),
    );
    let angular_acceleration = sub(scale(error, 0.5), scale(relative_omega, 1.4));
    let omega = ship.angular_velocity_body_radps;
    let momentum = Vector3d {
        x: ship.inertia_kg_m2.x * omega.x,
        y: ship.inertia_kg_m2.y * omega.y,
        z: ship.inertia_kg_m2.z * omega.z,
    };
    let torque = add(
        cross(omega, momentum),
        Vector3d {
            x: ship.inertia_kg_m2.x * angular_acceleration.x,
            y: ship.inertia_kg_m2.y * angular_acceleration.y,
            z: ship.inertia_kg_m2.z * angular_acceleration.z,
        },
    );
    let control = FlightControl {
        translation_body: Vector3d {
            x: axis(force.x, 0, 1),
            y: axis(force.y, 2, 3),
            z: axis(force.z, 4, 5),
        },
        rotation_body: Vector3d {
            x: (torque.x / ship.max_control_torque_nm.x).clamp(-1.0, 1.0),
            y: (torque.y / ship.max_control_torque_nm.y).clamp(-1.0, 1.0),
            z: (torque.z / ship.max_control_torque_nm.z).clamp(-1.0, 1.0),
        },
        ..neutral()
    };
    if control.valid() {
        Ok(control)
    } else {
        Err(())
    }
}

/// Validate the staged ID graph. A removed target may leave neutral detached
/// intent so the cockpit can report unavailable; it cannot retain a constraint.
pub fn validate_contact_scene(
    bodies: &[MassiveBodyState],
    ships: &[SpacecraftBinding],
) -> Result<(), ()> {
    contact_pairs(bodies, ships).map(|_| ())
}

fn contact_pairs(
    bodies: &[MassiveBodyState],
    ships: &[SpacecraftBinding],
) -> Result<Vec<(usize, usize)>, ()> {
    let mut result = Vec::new();
    for (index, ship) in ships.iter().enumerate() {
        let Some(docking) = ship.state.docking.as_ref() else {
            continue;
        };
        if !docking.valid(&ship.state.body_id) {
            return Err(());
        }
        let Some(target_index) = ships
            .iter()
            .position(|s| s.state.body_id == docking.target_body_id)
        else {
            if docking.attachment.is_some()
                || docking.capture_armed
                || docking.guidance_mode != DockingGuidanceMode::Manual
            {
                return Err(());
            }
            continue;
        };
        let target = &ships[target_index];
        if index == target_index
            || ship.body_index == target.body_index
            || ship.body_index >= bodies.len()
            || target.body_index >= bodies.len()
            || bodies[ship.body_index].mass_kg != 0.0
            || bodies[target.body_index].mass_kg != 0.0
            || target.state.docking.is_some()
            || result.iter().any(|&(_, t)| t == target_index)
        {
            return Err(());
        }
        result.push((index, target_index));
    }
    Ok(result)
}

pub(crate) fn contact_members(
    bodies: &[MassiveBodyState],
    ships: &[SpacecraftBinding],
) -> Result<Vec<bool>, ()> {
    let mut members = vec![false; ships.len()];
    for (ship, target) in contact_pairs(bodies, ships)? {
        members[ship] = true;
        members[target] = true;
    }
    Ok(members)
}

pub(crate) fn update_guidance(
    bodies: &[MassiveBodyState],
    ships: &mut [SpacecraftBinding],
) -> Result<(), ()> {
    let pairs = contact_pairs(bodies, ships)?;
    for (index, target_index) in pairs {
        let ship = &ships[index];
        let docking = ship.state.docking.as_ref().ok_or(())?;
        if docking.attachment.is_some() {
            ships[index].state.control = neutral();
            continue;
        }
        if docking.guidance_mode != DockingGuidanceMode::Approach {
            continue;
        }
        let target = &ships[target_index];
        let t = observe(
            bodies[ship.body_index].position_m,
            bodies[ship.body_index].velocity_mps,
            &ship.state,
            bodies[target.body_index].position_m,
            bodies[target.body_index].velocity_mps,
            &target.state,
        )?;
        if ship.state.propellant_mass_kg <= 0.0
            || t.port_distance_m > MAX_DOCKING_GUIDANCE_RANGE_M
            || t.position_port_m.z < -DOCKING_CONTACT_PENETRATION_M
            || docking.unsafe_contact
        {
            cancel_approach(&mut ships[index].state);
        } else {
            ships[index].state.control = guidance_control(&ship.state, &target.state, t)?;
        }
    }
    Ok(())
}

fn cancel_approach(ship: &mut SpacecraftState) {
    if let Some(docking) = &mut ship.docking {
        docking.guidance_mode = DockingGuidanceMode::Manual;
        docking.capture_armed = false;
    }
    ship.control = neutral();
}

pub(crate) fn finish_step(ships: &mut [SpacecraftBinding]) {
    for ship in ships {
        if ship
            .state
            .docking
            .as_ref()
            .is_some_and(|d| d.guidance_mode == DockingGuidanceMode::Approach)
            && ship.state.propellant_mass_kg <= 0.0
        {
            cancel_approach(&mut ship.state);
        }
    }
}

fn vector(v: Vector3d) -> Vector {
    Vector::new(v.x, v.y, v.z)
}
fn domain_vector(v: Vector) -> Vector3d {
    Vector3d {
        x: v.x,
        y: v.y,
        z: v.z,
    }
}
fn rotation(q: Quaterniond) -> Rotation {
    Rotation::from_xyzw(q.x, q.y, q.z, q.w)
}
fn domain_rotation(q: Rotation) -> Quaterniond {
    Quaterniond {
        w: q.w,
        x: q.x,
        y: q.y,
        z: q.z,
    }
    .normalized()
}

/// Canonical force evaluation with the same selected scalar/vector kernel as the
/// operation's orbital plan; only opted-in passive bodies replace their drift.
pub(crate) fn advance_contact_step(
    plan: &FixedStepPlan,
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    geometry: &ContactGeometryCache,
    dt: f64,
) -> Result<(), ()> {
    if !dt.is_finite() || dt <= 0.0 || dt > MAX_CONTACT_SUBSTEP_SECONDS * (1.0 + 1e-12) {
        return Err(());
    }
    let pairs = contact_pairs(bodies, ships)?;
    if pairs.is_empty() {
        let mut segment = plan.clone();
        segment.dt_seconds = dt;
        return crate::advance_fixed_steps(&segment, bodies, 1);
    }
    let kernel = if plan.decision.effective_backend == crate::SolverBackend::SimdArm64 {
        crate::GravityKernel::Arm64(
            plan.decision
                .arm64_gravity_kernel
                .unwrap_or(crate::Arm64GravityKernel::PortableScalarOracle),
        )
    } else if plan.decision.effective_backend == crate::SolverBackend::SimdX64 {
        crate::GravityKernel::X64Avx2
    } else {
        crate::GravityKernel::Scalar
    };
    let sources = crate::source_indices(bodies);
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    let mut scratch = crate::GravityScratch::default();
    plan.gravity_accelerations_into(bodies, &sources, &mut accelerations, kernel, &mut scratch)?;
    gravity_kick(bodies, &accelerations, 0.5 * dt, kernel);
    let members = contact_members(bodies, ships)?;
    let mut contact_bodies = vec![false; bodies.len()];
    for (index, member) in members.iter().enumerate() {
        if *member {
            contact_bodies[ships[index].body_index] = true;
        }
    }
    for (body, contact) in bodies.iter_mut().zip(contact_bodies) {
        if !contact {
            if matches!(kernel, crate::GravityKernel::Arm64(_)) {
                body.position_m.x = body.velocity_mps.x.mul_add(dt, body.position_m.x);
                body.position_m.y = body.velocity_mps.y.mul_add(dt, body.position_m.y);
                body.position_m.z = body.velocity_mps.z.mul_add(dt, body.position_m.z);
            } else {
                body.position_m = add(body.position_m, scale(body.velocity_mps, dt));
            }
        }
    }
    local_drift(bodies, ships, &pairs, geometry, dt)?;
    plan.gravity_accelerations_into(bodies, &sources, &mut accelerations, kernel, &mut scratch)?;
    gravity_kick(bodies, &accelerations, 0.5 * dt, kernel);
    Ok(())
}

fn gravity_kick(
    bodies: &mut [MassiveBodyState],
    acceleration: &[Vector3d],
    dt: f64,
    kernel: crate::GravityKernel,
) {
    for (body, a) in bodies.iter_mut().zip(acceleration) {
        if matches!(kernel, crate::GravityKernel::Arm64(_)) {
            body.velocity_mps.x = a.x.mul_add(dt, body.velocity_mps.x);
            body.velocity_mps.y = a.y.mul_add(dt, body.velocity_mps.y);
            body.velocity_mps.z = a.z.mul_add(dt, body.velocity_mps.z);
        } else {
            body.velocity_mps = add(body.velocity_mps, scale(*a, dt));
        }
    }
}

fn local_drift(
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    pairs: &[(usize, usize)],
    geometry: &ContactGeometryCache,
    dt: f64,
) -> Result<(), ()> {
    // A second encounter on the other side of a planetary system must not move
    // this pair's local origin. Merge only groups that can touch during this
    // drift, conservatively including their complete rotating swept spheres.
    let mut groups: Vec<Vec<usize>> = Vec::new();
    for pair_index in 0..pairs.len() {
        let mut group = vec![pair_index];
        let mut index = 0;
        while index < groups.len() {
            let near = group.iter().any(|&a| {
                groups[index].iter().any(|&b| {
                    let (a0, a1) = pairs[a];
                    let (b0, b1) = pairs[b];
                    [a0, a1].into_iter().any(|i| {
                        [b0, b1].into_iter().any(|j| {
                            let left = bodies[ships[i].body_index];
                            let right = bodies[ships[j].body_index];
                            let reach = geometry.0[i].radius_m
                                + geometry.0[j].radius_m
                                + magnitude(sub(left.velocity_mps, right.velocity_mps)) * dt
                                + 0.02;
                            magnitude(sub(left.position_m, right.position_m)) <= reach
                        })
                    })
                })
            });
            if near {
                group.extend(groups.remove(index));
                index = 0;
            } else {
                index += 1;
            }
        }
        groups.push(group);
    }
    for group in groups {
        let island_pairs: Vec<_> = group.into_iter().map(|index| pairs[index]).collect();
        drift_island(bodies, ships, &island_pairs, geometry, dt)?;
    }
    Ok(())
}

fn drift_island(
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    pairs: &[(usize, usize)],
    geometry: &ContactGeometryCache,
    dt: f64,
) -> Result<(), ()> {
    let mut order: Vec<usize> = pairs.iter().flat_map(|&(a, b)| [a, b]).collect();
    order.sort_by(|&a, &b| ships[a].state.body_id.0.cmp(&ships[b].state.body_id.0));
    order.dedup();
    let reference = bodies[ships[*order.first().ok_or(())?].body_index];
    let mut total_mass = 0.0;
    let mut weighted_position = Vector3d::default();
    let mut weighted_velocity = Vector3d::default();
    for &index in &order {
        let mass = ships[index].state.mass_kg();
        let body = bodies[ships[index].body_index];
        total_mass += mass;
        weighted_position = add(
            weighted_position,
            scale(sub(body.position_m, reference.position_m), mass),
        );
        weighted_velocity = add(
            weighted_velocity,
            scale(sub(body.velocity_mps, reference.velocity_mps), mass),
        );
    }
    let origin = add(
        reference.position_m,
        scale(weighted_position, 1.0 / total_mass),
    );
    let bulk_velocity = add(
        reference.velocity_mps,
        scale(weighted_velocity, 1.0 / total_mass),
    );
    if !finite(origin) || !finite(bulk_velocity) {
        return Err(());
    }
    let mut bullets = vec![false; ships.len()];
    for &(chaser, target) in pairs {
        let swept_speed = |index: usize| {
            let ship = &ships[index];
            magnitude(sub(bodies[ship.body_index].velocity_mps, bulk_velocity))
                + magnitude(ship.state.angular_velocity_body_radps) * geometry.0[index].radius_m
        };
        // Rapier 0.36 excludes bullet-vs-bullet sweeps. Choose the body with
        // greater bounded point motion, including target rotation, not a fixed
        // gameplay role. The other remains fully dynamic and receives impulses.
        bullets[if swept_speed(target) > swept_speed(chaser) {
            target
        } else {
            chaser
        }] = true;
    }
    let mut world = PhysicsWorld::new();
    world.gravity = Vector::ZERO;
    world.integration_parameters.dt = dt;
    world.integration_parameters.normalized_max_linear_velocity = f64::MAX;
    world.integration_parameters.normalized_allowed_linear_error = 0.0005;
    world.integration_parameters.normalized_prediction_distance = 0.005;
    world.integration_parameters.num_solver_iterations = 12;
    world.integration_parameters.friction_model = FrictionModel::Coulomb;
    world.integration_parameters.max_ccd_substeps = 4;
    world.integration_parameters.warmstart_coefficient = 0.0;
    world.integration_parameters.warmstart_joints = false;
    let mut handles = vec![None; ships.len()];
    let mut port_handles = vec![None; ships.len()];
    for &index in &order {
        let ship = &ships[index].state;
        let body = bodies[ships[index].body_index];
        let rigid = RigidBodyBuilder::dynamic()
            .pose(Pose::from_parts(
                vector(sub(body.position_m, origin)),
                rotation(ship.attitude),
            ))
            .linvel(vector(sub(body.velocity_mps, bulk_velocity)))
            .angvel(vector(
                ship.attitude.rotate(ship.angular_velocity_body_radps),
            ))
            .additional_mass_properties(MassProperties::new(
                Vector::ZERO,
                ship.mass_kg(),
                vector(ship.inertia_kg_m2),
            ))
            .can_sleep(false)
            .ccd_enabled(bullets[index])
            .gyroscopic_forces_enabled(true);
        let collider = ColliderBuilder::new(geometry.0[index].hull.clone())
            .density(0.0)
            .restitution(CONTACT_RESTITUTION)
            .friction(CONTACT_FRICTION)
            .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
            .contact_force_event_threshold(0.0);
        let (handle, _) = world.insert(rigid, collider);
        let port_handle = world.insert_collider(
            ColliderBuilder::new(geometry.0[index].port.clone())
                .density(0.0)
                .restitution(CONTACT_RESTITUTION)
                .friction(CONTACT_FRICTION)
                .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
                .contact_force_event_threshold(0.0),
            Some(handle),
        );
        handles[index] = Some(handle);
        port_handles[index] = Some(port_handle);
    }
    // Eligibility is sampled BEFORE contact can dissipate an unsafe incoming
    // speed. Only already-existing attachments get a joint for this drift.
    let mut eligible = Vec::with_capacity(pairs.len());
    for &(index, target_index) in pairs {
        let ship = &ships[index];
        let target = &ships[target_index];
        let t = observe(
            bodies[ship.body_index].position_m,
            bodies[ship.body_index].velocity_mps,
            &ship.state,
            bodies[target.body_index].position_m,
            bodies[target.body_index].velocity_mps,
            &target.state,
        )?;
        eligible.push(t.capture_ready);
        if let Some(attachment) = ships[index]
            .state
            .docking
            .as_ref()
            .and_then(|d| d.attachment)
        {
            world.insert_impulse_joint(
                handles[target_index].ok_or(())?,
                handles[index].ok_or(())?,
                FixedJointBuilder::new()
                    .local_frame1(Pose::from_parts(
                        vector(attachment.position_target_m),
                        rotation(attachment.attitude_target),
                    ))
                    .local_frame2(Pose::IDENTITY)
                    .contacts_enabled(false),
            );
        }
    }
    let evidence = ContactEvidence::default();
    world.step_with_events(&(), &evidence);
    let contacts = evidence.0.into_inner().map_err(|_| ())?;
    let advanced_origin = add(origin, scale(bulk_velocity, dt));
    for &index in &order {
        let rigid = &world.bodies[handles[index].ok_or(())?];
        let attitude = domain_rotation(*rigid.rotation());
        let body = &mut bodies[ships[index].body_index];
        body.position_m = add(advanced_origin, domain_vector(rigid.translation()));
        body.velocity_mps = add(bulk_velocity, domain_vector(rigid.linvel()));
        ships[index].state.attitude = attitude;
        ships[index].state.angular_velocity_body_radps =
            attitude.conjugate().rotate(domain_vector(rigid.angvel()));
    }
    for (&(index, target_index), safe_arrival) in pairs.iter().zip(eligible) {
        if ships[index]
            .state
            .docking
            .as_ref()
            .is_some_and(|d| d.attachment.is_some())
        {
            continue;
        }
        let own = handles[index].ok_or(())?;
        let target_handle = handles[target_index].ok_or(())?;
        let own_port = port_handles[index].ok_or(())?;
        let target_port = port_handles[target_index].ok_or(())?;
        let mut impact = false;
        let mut collar_contact = false;
        let mut other_contact = false;
        let target_normal = vector(
            ships[target_index]
                .state
                .attitude
                .multiply(spacecraft_port_pose(&ships[target_index].state).1)
                .rotate(Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 1.0,
                }),
        );
        for p in &contacts {
            let parent1 = world.colliders[p.collider1].parent();
            let parent2 = world.colliders[p.collider2].parent();
            let is_pair = (parent1 == Some(own) && parent2 == Some(target_handle))
                || (parent1 == Some(target_handle) && parent2 == Some(own));
            if !is_pair {
                if [parent1, parent2]
                    .into_iter()
                    .any(|p| p == Some(own) || p == Some(target_handle))
                {
                    impact = true;
                    other_contact = true;
                }
                continue;
            }
            impact = true;
            let is_collar = (p.collider1 == own_port && p.collider2 == target_port)
                || (p.collider2 == own_port && p.collider1 == target_port);
            if !is_collar {
                other_contact = true;
                continue;
            }
            // Predictive contacts may brake before touching. They cannot latch.
            collar_contact |= p.signed_distance_m <= 0.0 && p.normal.dot(target_normal).abs() > 0.9;
        }
        if impact && (!safe_arrival || other_contact) {
            cancel_approach(&mut ships[index].state);
            ships[index]
                .state
                .docking
                .as_mut()
                .ok_or(())?
                .unsafe_contact = true;
        } else if safe_arrival && collar_contact {
            let ship = &ships[index];
            let target = &ships[target_index];
            let d = ship.state.docking.as_ref().ok_or(())?;
            let post = observe(
                bodies[ship.body_index].position_m,
                bodies[ship.body_index].velocity_mps,
                &ship.state,
                bodies[target.body_index].position_m,
                bodies[target.body_index].velocity_mps,
                &target.state,
            )?;
            if d.capture_armed && !d.unsafe_contact && post.capture_ready {
                let attachment = DockingAttachment {
                    position_target_m: target.state.attitude.conjugate().rotate(sub(
                        bodies[ship.body_index].position_m,
                        bodies[target.body_index].position_m,
                    )),
                    attitude_target: target
                        .state
                        .attitude
                        .conjugate()
                        .multiply(ship.state.attitude)
                        .normalized(),
                };
                let d = ships[index].state.docking.as_mut().ok_or(())?;
                d.attachment = Some(attachment);
                d.capture_armed = false;
                d.guidance_mode = DockingGuidanceMode::Manual;
                ships[index].state.control = neutral();
            }
        }
    }
    Ok(())
}

/// Immutable shape compilation is shared by every bounded drift in one
/// operation. No Rapier pose, contact impulse or warm-start state is retained.
pub(crate) struct ContactGeometryCache(Vec<ContactGeometry>);

struct ContactGeometry {
    hull: SharedShape,
    port: SharedShape,
    radius_m: f64,
}

impl ContactGeometryCache {
    pub(crate) fn compile(ships: &[SpacecraftBinding]) -> Result<Self, ()> {
        ships
            .iter()
            .map(|binding| {
                let ship = &binding.state;
                let Some(authored) = ship.authored.as_ref().and_then(|a| a.contact.as_ref()) else {
                    return Ok(ContactGeometry {
                        hull: hull_shape(ship.appearance),
                        port: port_shape(ship.appearance),
                        radius_m: authored_sweep_radius_m(ship.appearance),
                    });
                };
                if !authored.valid() {
                    return Err(());
                }
                let hull = SharedShape::compound(
                    authored
                        .hull
                        .iter()
                        .map(|part| {
                            let shape = match &part.shape {
                                ContactShape::Box { half_extents_m: h } => {
                                    SharedShape::cuboid(h.x, h.y, h.z)
                                }
                                ContactShape::Sphere { radius_m } => SharedShape::ball(*radius_m),
                            };
                            (
                                Pose::from_parts(
                                    vector(part.position_body_m),
                                    rotation(part.attitude_body),
                                ),
                                shape,
                            )
                        })
                        .collect(),
                );
                let port = &authored.port;
                // A bounded 24-pad flange keeps the aperture open. Primitive
                // face contacts also avoid degenerate convex-sector manifolds
                // when two equal, rotated annuli meet exactly coplanar.
                let middle = (port.inner_radius_m + port.outer_radius_m) * 0.5;
                let radial_half = (port.outer_radius_m - port.inner_radius_m) * 0.5;
                let tangential_half = middle * (std::f64::consts::PI / 24.0).tan();
                let mut sectors = Vec::with_capacity(24);
                for sector in 0..24 {
                    let angle = std::f64::consts::TAU * f64::from(sector) / 24.0;
                    let local = Vector3d {
                        x: middle * angle.cos(),
                        y: middle * angle.sin(),
                        z: -0.5 * port.depth_m,
                    };
                    let orientation =
                        port.attitude_body
                            .multiply(Quaterniond::from_rotation_vector(Vector3d {
                                x: 0.0,
                                y: 0.0,
                                z: angle,
                            }));
                    sectors.push((
                        Pose::from_parts(
                            vector(add(port.position_body_m, port.attitude_body.rotate(local))),
                            rotation(orientation),
                        ),
                        SharedShape::cuboid(radial_half, tangential_half, 0.5 * port.depth_m),
                    ));
                }
                Ok(ContactGeometry {
                    hull,
                    port: SharedShape::compound(sectors),
                    radius_m: authored.bounding_radius_m(),
                })
            })
            .collect::<Result<Vec<_>, ()>>()
            .map(Self)
    }
}

fn hull_shape(appearance: SpacecraftAppearance) -> SharedShape {
    static ASTERIA: OnceLock<SharedShape> = OnceLock::new();
    static RELAY: OnceLock<SharedShape> = OnceLock::new();
    match appearance {
        SpacecraftAppearance::Asteria => ASTERIA.get_or_init(asteria_hull).clone(),
        SpacecraftAppearance::RelayStation => RELAY.get_or_init(relay_hull).clone(),
    }
}

/// Bounds of the authored occupied geometry about the physical centre. The
/// furthest Asteria array corner is <7.4m; Relay's array rail corner is <10.9m.
/// Navigation/framing spheres are deliberately not contact or sweep geometry.
fn authored_sweep_radius_m(appearance: SpacecraftAppearance) -> f64 {
    match appearance {
        SpacecraftAppearance::Asteria => 7.5,
        SpacecraftAppearance::RelayStation => 11.0,
    }
}

fn port_shape(appearance: SpacecraftAppearance) -> SharedShape {
    static ASTERIA: OnceLock<SharedShape> = OnceLock::new();
    static RELAY: OnceLock<SharedShape> = OnceLock::new();
    match appearance {
        SpacecraftAppearance::Asteria => ASTERIA
            .get_or_init(|| {
                let mut p = Vec::new();
                annulus_y(&mut p, 0.43, 0.72, 3.38, 3.52);
                let rotate = Pose::from_parts(
                    Vector::ZERO,
                    Rotation::from_rotation_x(std::f64::consts::FRAC_PI_2),
                );
                // y-axis annulus becomes +z-axis annulus without changing its faces.
                SharedShape::compound(
                    p.into_iter()
                        .map(|(pose, shape)| (rotate * pose, shape))
                        .collect(),
                )
            })
            .clone(),
        SpacecraftAppearance::RelayStation => RELAY
            .get_or_init(|| {
                let mut p = Vec::new();
                annulus_y(&mut p, 0.62, 1.05, -3.98, -3.81);
                annulus_y(&mut p, 0.64, 0.94, -4.04, -3.99);
                SharedShape::compound(p)
            })
            .clone(),
    }
}

fn box_part(parts: &mut Vec<(Pose, SharedShape)>, center: Vector, half: Vector) {
    parts.push((
        Pose::from_translation(center),
        SharedShape::cuboid(half.x, half.y, half.z),
    ));
}

/// Twenty-sided authored convex pressure sections; the caps and tapered ends
/// follow mesh dimensions. These are documented collision approximations.
fn axial_section(
    parts: &mut Vec<(Pose, SharedShape)>,
    center: Vector,
    r0: f64,
    r1: f64,
    z0: f64,
    z1: f64,
) {
    let mut points = Vec::with_capacity(40);
    for (radius, z) in [(r0, z0), (r1, z1)] {
        for i in 0..20 {
            let angle = std::f64::consts::TAU * f64::from(i) / 20.0;
            points.push(Vector::new(radius * angle.cos(), radius * angle.sin(), z));
        }
    }
    parts.push((
        Pose::from_translation(center),
        SharedShape::convex_hull(&points).expect("authored convex section"),
    ));
}

fn asteria_hull() -> SharedShape {
    let mut p = Vec::new();
    let zero = Vector::ZERO;
    axial_section(&mut p, zero, 1.15, 1.15, -0.6, 2.15);
    axial_section(&mut p, zero, 1.15, 0.52, 2.15, 3.1);
    axial_section(&mut p, zero, 0.52, 0.52, 3.1, 3.38);
    axial_section(&mut p, zero, 1.27, 1.27, -2.75, -0.75);
    axial_section(&mut p, zero, 1.32, 1.32, -0.85, -0.62);
    axial_section(&mut p, zero, 0.66, 0.45, -3.0, -2.7);
    axial_section(&mut p, zero, 0.91, 0.36, -4.03, -3.0);
    for sign in [-1.0, 1.0] {
        box_part(
            &mut p,
            Vector::new(sign * 4.7, 0.0, -1.7),
            Vector::new(2.05, 0.1, 1.25),
        );
        box_part(
            &mut p,
            Vector::new(sign * 3.8, 0.0, -1.7),
            Vector::new(2.8, 0.055, 0.07),
        );
        box_part(
            &mut p,
            Vector::new(sign * 1.3, 0.0, -0.36),
            Vector::new(0.18, 0.18, 0.32),
        );
        box_part(
            &mut p,
            Vector::new(0.0, sign * 1.3, -0.36),
            Vector::new(0.18, 0.18, 0.32),
        );
    }
    box_part(
        &mut p,
        Vector::new(0.0, 1.65, 0.05),
        Vector::new(0.025, 0.53, 0.025),
    );
    box_part(
        &mut p,
        Vector::new(0.0, 2.2, 0.05),
        Vector::new(0.1, 0.08, 0.1),
    );
    SharedShape::compound(p)
}

fn annulus_y(parts: &mut Vec<(Pose, SharedShape)>, inner: f64, outer: f64, y0: f64, y1: f64) {
    // Separate convex sectors leave the actual docking aperture open.
    for i in 0..24 {
        let a0 = std::f64::consts::TAU * f64::from(i) / 24.0;
        let a1 = std::f64::consts::TAU * f64::from(i + 1) / 24.0;
        let mut points = Vec::with_capacity(8);
        for y in [y0, y1] {
            for r in [inner, outer] {
                for a in [a0, a1] {
                    points.push(Vector::new(r * a.cos(), y, r * a.sin()));
                }
            }
        }
        parts.push((
            Pose::IDENTITY,
            SharedShape::convex_hull(&points).expect("authored ring sector"),
        ));
    }
}

fn relay_hull() -> SharedShape {
    let mut p = Vec::new();
    let zero = Vector::ZERO;
    axial_section(&mut p, zero, 1.9, 1.9, -2.7, 2.7);
    axial_section(&mut p, zero, 1.9, 1.12, 2.7, 3.45);
    axial_section(&mut p, zero, 1.12, 1.9, -3.45, -2.7);
    for sign in [-1.0, 1.0] {
        let center = Vector::new(sign * 3.25, 0.0, 0.0);
        axial_section(&mut p, center, 1.06, 1.06, -2.8, 2.8);
        axial_section(&mut p, center, 1.06, 0.55, 2.8, 3.45);
        axial_section(&mut p, center, 0.55, 1.06, -3.45, -2.8);
        box_part(
            &mut p,
            Vector::new(sign * 2.15, 0.0, 0.0),
            Vector::new(0.72, 0.58, 0.66),
        );
        box_part(
            &mut p,
            Vector::new(sign * 5.25, 0.0, -0.5),
            Vector::new(4.4, 0.085, 0.085),
        );
        box_part(
            &mut p,
            Vector::new(sign * 7.3, 0.0, -0.5),
            Vector::new(0.065, 3.0, 0.065),
        );
        for sy in [-1.0, 1.0] {
            box_part(
                &mut p,
                Vector::new(sign * 7.3, sy * 3.0, -0.5),
                Vector::new(2.475, 1.705, 0.06),
            );
        }
        for z in [-1.3, 1.3] {
            box_part(
                &mut p,
                Vector::new(sign * 2.3, 2.25, z),
                Vector::new(0.85, 0.055, 0.48),
            );
        }
        axial_section(
            &mut p,
            zero,
            0.89,
            0.89,
            sign * 4.98 - 0.14,
            sign * 4.98 + 0.14,
        );
        axial_section(
            &mut p,
            zero,
            0.77,
            0.77,
            sign * 4.25 - 0.63,
            sign * 4.25 + 0.63,
        );
    }
    p.push((
        Pose::from_translation(Vector::new(0.0, -2.75, 0.0)),
        SharedShape::cylinder(1.05, 0.8),
    ));
    SharedShape::compound(p)
}

#[cfg(test)]
#[path = "docking_tests.rs"]
mod tests;
