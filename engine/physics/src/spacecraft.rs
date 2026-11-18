//! Symmetric force splitting around the canonical gravity drift/kick integrator.
//! Propulsion integrates changing inertial mass analytically for each midpoint
//! direction; the gravity solver continues to see only gravitational source mass.
use crate::{advance_fixed_steps, FixedStepPlan, MassiveBodyState, MAX_INCREMENTAL_STEPS};
use solarlab_domain::spacecraft::{add, cross, finite, magnitude, scale, sub, unit};
use solarlab_domain::{AttitudeMode, Quaterniond, SpacecraftState, Vector3d};

pub const MAX_FLIGHT_SUBSTEP_SECONDS: f64 = 0.05;

#[derive(Clone, Debug)]
pub struct SpacecraftBinding {
    pub body_index: usize,
    pub reference_body_index: Option<usize>,
    pub state: SpacecraftState,
}

#[must_use]
pub fn max_substep_seconds(states: &[SpacecraftState], coast_step: f64) -> f64 {
    let mut step = coast_step;
    for s in states {
        if s.has_powered_input() || s.needs_attitude_steps() {
            step = step.min(MAX_FLIGHT_SUBSTEP_SECONDS);
            let spin = magnitude(s.angular_velocity_body_radps);
            if spin > 0.0 {
                step = step.min(0.05 / spin);
            }
        }
    }
    step
}

/// Mutates only caller-owned staged solver/component buffers. Runtime publishes
/// these together once all requested steps and numeric checks have succeeded.
pub fn advance_spacecraft_steps(
    plan: &FixedStepPlan,
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    count: usize,
) -> Result<(), ()> {
    if count > MAX_INCREMENTAL_STEPS
        || ships.iter().any(|s| {
            s.body_index >= bodies.len()
                || s.reference_body_index.is_some_and(|i| i >= bodies.len())
        })
    {
        return Err(());
    }
    if ships
        .iter()
        .all(|s| !s.state.has_powered_input() && !s.state.needs_attitude_steps())
    {
        advance_fixed_steps(plan, bodies, count)?;
    } else {
        for _ in 0..count {
            let mut remaining = plan.dt_seconds;
            // Split exactly at depletion so no thrust is applied beyond the last fuel.
            while remaining > 0.0 {
                let mut dt = remaining;
                for ship in ships.iter() {
                    let (_, _, flow) = engine_wrench(&ship.state);
                    if flow > 0.0 {
                        dt = dt.min(ship.state.propellant_mass_kg / flow);
                    }
                }
                if !dt.is_finite() || dt <= 0.0 {
                    return Err(());
                }
                for ship in ships.iter_mut() {
                    propulsion_rotation_halfstep(bodies, ship, dt * 0.5);
                }
                let mut segment = plan.clone();
                segment.dt_seconds = dt;
                advance_fixed_steps(&segment, bodies, 1)?;
                for ship in ships.iter_mut() {
                    propulsion_rotation_halfstep(bodies, ship, dt * 0.5);
                }
                let next = remaining - dt;
                remaining = if next < remaining {
                    next
                } else {
                    return Err(());
                };
            }
        }
    }
    if bodies
        .iter()
        .any(|b| !finite(b.position_m) || !finite(b.velocity_mps))
        || ships.iter().any(|s| !s.state.valid())
    {
        return Err(());
    }
    Ok(())
}

fn engine_wrench(s: &SpacecraftState) -> (Vector3d, Vector3d, f64) {
    let mut force = Vector3d::default();
    let mut torque = Vector3d::default();
    let mut flow = 0.0;
    if s.propellant_mass_kg <= 0.0 {
        return (force, torque, flow);
    }
    for engine in &s.engines {
        let demand = engine.demand(s.control);
        let f = scale(engine.direction_body, engine.max_thrust_n * demand);
        force = add(force, f);
        torque = add(torque, cross(engine.position_body_m, f));
        flow += engine.max_mass_flow_kgps * demand;
    }
    (force, torque, flow)
}

fn assist_error(bodies: &[MassiveBodyState], ship: &SpacecraftBinding) -> Vector3d {
    let s = &ship.state;
    let Some(host_index) = ship.reference_body_index else {
        return Vector3d::default();
    };
    let body = &bodies[ship.body_index];
    let host = &bodies[host_index];
    let velocity = sub(body.velocity_mps, host.velocity_mps);
    let normal = cross(sub(body.position_m, host.position_m), velocity);
    let desired = match s.control.attitude_mode {
        AttitudeMode::Prograde => velocity,
        AttitudeMode::Retrograde => scale(velocity, -1.0),
        AttitudeMode::Normal => normal,
        AttitudeMode::AntiNormal => scale(normal, -1.0),
        _ => return Vector3d::default(),
    };
    let Some(target) = unit(s.attitude.conjugate().rotate(desired)) else {
        return Vector3d::default();
    };
    let axis = Vector3d {
        x: -target.y,
        y: target.x,
        z: 0.0,
    };
    let sine = magnitude(axis);
    if sine < 1e-12 {
        if target.z < 0.0 {
            Vector3d {
                x: std::f64::consts::PI,
                y: 0.0,
                z: 0.0,
            }
        } else {
            Vector3d::default()
        }
    } else {
        scale(axis, sine.atan2(target.z) / sine)
    }
}

fn angular_acceleration(omega: Vector3d, inertia: Vector3d, torque: Vector3d) -> Vector3d {
    let momentum = Vector3d {
        x: inertia.x * omega.x,
        y: inertia.y * omega.y,
        z: inertia.z * omega.z,
    };
    let net = sub(torque, cross(omega, momentum));
    Vector3d {
        x: net.x / inertia.x,
        y: net.y / inertia.y,
        z: net.z / inertia.z,
    }
}

fn control_torque(s: &SpacecraftState, omega: Vector3d, error: Vector3d) -> Vector3d {
    let manual = s.control.rotation_body;
    let assisted = s.control.attitude_mode != AttitudeMode::Manual;
    let feedback = if assisted {
        sub(scale(error, 0.7), scale(omega, 1.6))
    } else {
        Vector3d::default()
    };
    let max = s.max_control_torque_nm;
    Vector3d {
        x: (manual.x * max.x + s.inertia_kg_m2.x * feedback.x).clamp(-max.x, max.x),
        y: (manual.y * max.y + s.inertia_kg_m2.y * feedback.y).clamp(-max.y, max.y),
        z: (manual.z * max.z + s.inertia_kg_m2.z * feedback.z).clamp(-max.z, max.z),
    }
}

fn propulsion_rotation_halfstep(
    bodies: &mut [MassiveBodyState],
    ship: &mut SpacecraftBinding,
    dt: f64,
) {
    let error = assist_error(bodies, ship);
    let s = &mut ship.state;
    let (force, engine_torque, flow) = engine_wrench(s);
    let initial_omega = s.angular_velocity_body_radps;
    let initial_torque = add(engine_torque, control_torque(s, initial_omega, error));
    let a0 = angular_acceleration(initial_omega, s.inertia_kg_m2, initial_torque);
    let mid_omega = add(initial_omega, scale(a0, 0.5 * dt));
    let mid_torque = add(engine_torque, control_torque(s, mid_omega, error));
    let mid_accel = angular_acceleration(mid_omega, s.inertia_kg_m2, mid_torque);
    let mid_attitude = s
        .attitude
        .multiply(Quaterniond::from_rotation_vector(scale(
            mid_omega,
            0.5 * dt,
        )))
        .normalized();
    s.attitude = s
        .attitude
        .multiply(Quaterniond::from_rotation_vector(scale(mid_omega, dt)))
        .normalized();
    s.angular_velocity_body_radps = add(initial_omega, scale(mid_accel, dt));
    if s.control.attitude_mode == AttitudeMode::Hold
        && magnitude(s.angular_velocity_body_radps) < 1e-12
        && magnitude(s.control.rotation_body) == 0.0
    {
        s.angular_velocity_body_radps = Vector3d::default();
    }
    if flow > 0.0 {
        let fuel_used = (flow * dt).min(s.propellant_mass_kg);
        let initial_mass = s.mass_kg();
        // log1p avoids cancellation for small timesteps / low throttle.
        let impulse_per_force = -(-fuel_used / initial_mass).ln_1p() / flow;
        let delta_v = scale(mid_attitude.rotate(force), impulse_per_force);
        bodies[ship.body_index].velocity_mps = add(bodies[ship.body_index].velocity_mps, delta_v);
        s.delivered_delta_v_mps += magnitude(delta_v);
        s.propellant_mass_kg = (s.propellant_mass_kg - fuel_used).max(0.0);
        // Exact depletion can leave a few ulps after two half steps.
        if s.propellant_mass_kg < 4.0 * f64::EPSILON * initial_mass {
            s.propellant_mass_kg = 0.0;
        }
    }
}
