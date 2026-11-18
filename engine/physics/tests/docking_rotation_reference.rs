//! Independent science regression for the opt-in rigid contact path.
//!
//! Rapier owns this path's free rotation. Its finite-step gyroscopic integration
//! is a local rigid-body approximation, not a claim of symplectic six-degree-
//! of-freedom evolution. These non-principal cases expose its measured first-
//! order refinement while bounding energy and angular momentum drift. The
//! laboratory's existing unopted spacecraft integrator remains independent.

use solarlab_domain::spacecraft::{add, magnitude, scale, sub};
use solarlab_domain::{
    AttitudeMode, BodyId, FlightControl, Quaterniond, SpacecraftAppearance, SpacecraftDockingState,
    SpacecraftState, Vector3d,
};
use solarlab_physics::spacecraft::{advance_spacecraft_steps, SpacecraftBinding};
use solarlab_physics::{
    fixed_step_plan, CollisionModel, IntegratorKind, MassiveBodyState, PhysicsPolicy,
    SolverBackend, MAX_INCREMENTAL_STEPS,
};

const DURATION_SECONDS: f64 = 10.0;
const REFERENCE_DT_SECONDS: f64 = 0.0001;

fn vector(x: f64, y: f64, z: f64) -> Vector3d {
    Vector3d { x, y, z }
}

fn binding(index: usize, state: SpacecraftState) -> SpacecraftBinding {
    SpacecraftBinding {
        body_index: index,
        reference_body_index: None,
        mission_target_index: None,
        rendezvous_target_body_index: None,
        rendezvous_target_spacecraft_index: None,
        rendezvous_target_radius_m: 0.0,
        reference_radius_m: 0.0,
        target_radius_m: 0.0,
        body_radius_m: if index == 0 { 8.0 } else { 12.0 },
        epoch_seconds: 0.0,
        epoch_compensation_seconds: 0.0,
        state,
    }
}

fn initial_ship(torqued: bool) -> SpacecraftState {
    let mut ship = SpacecraftState::reference(BodyId("rotation.ship".into()), None);
    ship.attitude = Quaterniond::from_rotation_vector(vector(0.4, -0.2, 0.1));
    ship.angular_velocity_body_radps = vector(0.2, 0.3, 0.4);
    ship.inertia_kg_m2 = vector(50_000.0, 70_000.0, 90_000.0);
    ship.control = FlightControl {
        attitude_mode: AttitudeMode::Manual,
        rotation_body: if torqued {
            vector(0.3, -0.2, 0.1)
        } else {
            Vector3d::default()
        },
        ..FlightControl::default()
    };
    ship.docking = Some(SpacecraftDockingState::new(BodyId(
        "rotation.station".into(),
    )));
    ship
}

fn advance_native(initial: &SpacecraftState, dt: f64) -> SpacecraftState {
    let mut station = SpacecraftState::reference(BodyId("rotation.station".into()), None);
    station.appearance = SpacecraftAppearance::RelayStation;
    station.dry_mass_kg = 20_000.0;
    station.propellant_mass_kg = 0.0;
    station.engines.clear();
    station.inertia_kg_m2 = vector(800_000.0, 800_000.0, 800_000.0);
    station.max_control_torque_nm = Vector3d::default();
    station.control = FlightControl {
        attitude_mode: AttitudeMode::Manual,
        ..FlightControl::default()
    };
    let mut ships = vec![binding(0, initial.clone()), binding(1, station)];
    // Both bodies opt into the real contact drift, but stay far enough apart
    // that contact impulses cannot hide a free-rotation integration defect.
    let mut bodies = vec![
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: vector(0.0, -100.0, 0.0),
            velocity_mps: Vector3d::default(),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        },
    ];
    let plan = fixed_step_plan(
        &PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: dt,
        },
        dt,
        bodies.len(),
        &[],
    )
    .unwrap();
    let mut steps = (DURATION_SECONDS / dt).round() as usize;
    while steps > 0 {
        let count = steps.min(MAX_INCREMENTAL_STEPS);
        advance_spacecraft_steps(&plan, &mut bodies, &mut ships, count).unwrap();
        steps -= count;
    }
    assert!(ships[0].state.valid());
    assert_eq!(
        ships[0].state.propellant_mass_kg,
        initial.propellant_mass_kg
    );
    assert!(ships[0]
        .state
        .docking
        .as_ref()
        .unwrap()
        .attachment
        .is_none());
    assert!(!ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    ships.remove(0).state
}

fn body_torque(ship: &SpacecraftState) -> Vector3d {
    vector(
        ship.max_control_torque_nm.x * ship.control.rotation_body.x,
        ship.max_control_torque_nm.y * ship.control.rotation_body.y,
        ship.max_control_torque_nm.z * ship.control.rotation_body.z,
    )
}

/// Euler's rigid-body equations coded directly, without the production angular
/// acceleration, quaternion update, contact solver or force-splitting routines.
fn euler_derivative(omega: Vector3d, inertia: Vector3d, torque: Vector3d) -> Vector3d {
    vector(
        ((inertia.y - inertia.z) * omega.y * omega.z + torque.x) / inertia.x,
        ((inertia.z - inertia.x) * omega.z * omega.x + torque.y) / inertia.y,
        ((inertia.x - inertia.y) * omega.x * omega.y + torque.z) / inertia.z,
    )
}

fn reference_omega(initial: &SpacecraftState) -> Vector3d {
    let mut omega = initial.angular_velocity_body_radps;
    let inertia = initial.inertia_kg_m2;
    let torque = body_torque(initial);
    let dt = REFERENCE_DT_SECONDS;
    for _ in 0..(DURATION_SECONDS / dt).round() as usize {
        let a = euler_derivative(omega, inertia, torque);
        let b = euler_derivative(add(omega, scale(a, 0.5 * dt)), inertia, torque);
        let c = euler_derivative(add(omega, scale(b, 0.5 * dt)), inertia, torque);
        let d = euler_derivative(add(omega, scale(c, dt)), inertia, torque);
        omega = add(
            omega,
            scale(add(add(a, scale(b, 2.0)), add(scale(c, 2.0), d)), dt / 6.0),
        );
    }
    omega
}

fn energy(omega: Vector3d, inertia: Vector3d) -> f64 {
    0.5 * (inertia.x * omega.x * omega.x
        + inertia.y * omega.y * omega.y
        + inertia.z * omega.z * omega.z)
}

fn angular_momentum_world(ship: &SpacecraftState) -> Vector3d {
    let omega = ship.angular_velocity_body_radps;
    let inertia = ship.inertia_kg_m2;
    ship.attitude.rotate(vector(
        inertia.x * omega.x,
        inertia.y * omega.y,
        inertia.z * omega.z,
    ))
}

#[test]
fn nonprincipal_free_rotation_refines_against_rk4_and_bounds_invariant_drift() {
    let initial = initial_ship(false);
    let reference = reference_omega(&initial);
    let coarse = advance_native(&initial, 0.01);
    let fine = advance_native(&initial, 0.005);
    let coarse_error = magnitude(sub(coarse.angular_velocity_body_radps, reference));
    let fine_error = magnitude(sub(fine.angular_velocity_body_radps, reference));
    assert!(coarse_error < 6e-5 && fine_error < 3e-5);
    assert!(
        fine_error < 0.65 * coarse_error,
        "angular velocity must refine: {coarse_error} -> {fine_error}"
    );

    let initial_energy = energy(initial.angular_velocity_body_radps, initial.inertia_kg_m2);
    let energy_error = |ship: &SpacecraftState| {
        (energy(ship.angular_velocity_body_radps, ship.inertia_kg_m2) / initial_energy - 1.0).abs()
    };
    let initial_momentum = angular_momentum_world(&initial);
    let momentum_error = |ship: &SpacecraftState| {
        magnitude(sub(angular_momentum_world(ship), initial_momentum)) / magnitude(initial_momentum)
    };
    assert!(energy_error(&coarse) < 3e-5 && energy_error(&fine) < 1.5e-5);
    assert!(momentum_error(&coarse) < 6e-5 && momentum_error(&fine) < 3e-5);
    assert!(energy_error(&fine) < 0.65 * energy_error(&coarse));
    assert!(momentum_error(&fine) < 0.65 * momentum_error(&coarse));
}

#[test]
fn nonprincipal_actuator_torque_refines_against_independent_euler_equations() {
    let initial = initial_ship(true);
    let reference = reference_omega(&initial);
    let coarse = advance_native(&initial, 0.01);
    let fine = advance_native(&initial, 0.005);
    let coarse_error = magnitude(sub(coarse.angular_velocity_body_radps, reference));
    let fine_error = magnitude(sub(fine.angular_velocity_body_radps, reference));
    assert!(coarse_error < 6e-5 && fine_error < 3e-5);
    assert!(
        fine_error < 0.65 * coarse_error,
        "torque response must refine: {coarse_error} -> {fine_error}"
    );
    // External torque does work, so energy must follow the independent solution
    // instead of being spuriously held constant or doubled by two integrators.
    let expected_energy = energy(reference, initial.inertia_kg_m2);
    assert!(
        (energy(fine.angular_velocity_body_radps, fine.inertia_kg_m2) / expected_energy - 1.0)
            .abs()
            < 2e-5
    );
}
