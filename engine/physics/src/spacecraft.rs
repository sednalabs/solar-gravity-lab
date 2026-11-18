//! Symmetric force splitting around the canonical gravity drift/kick integrator.
//! Propulsion integrates changing inertial mass analytically for each midpoint
//! direction; the gravity solver continues to see only gravitational source mass.
use crate::science::{advance_selected_steps, ScientificStepReport};
use crate::{FixedStepPlan, MassiveBodyState, MAX_INCREMENTAL_STEPS};
use solarlab_domain::spacecraft::{add, cross, finite, magnitude, scale, sub, unit};
use solarlab_domain::{AttitudeMode, EngineControl, Quaterniond, SpacecraftState, Vector3d};

pub const MAX_FLIGHT_SUBSTEP_SECONDS: f64 = 0.05;

#[derive(Clone, Debug)]
pub struct SpacecraftBinding {
    pub body_index: usize,
    pub reference_body_index: Option<usize>,
    pub mission_target_index: Option<usize>,
    pub rendezvous_target_body_index: Option<usize>,
    pub rendezvous_target_spacecraft_index: Option<usize>,
    pub rendezvous_target_radius_m: f64,
    pub reference_radius_m: f64,
    pub target_radius_m: f64,
    pub body_radius_m: f64,
    /// Staged integration clock, published only with the authoritative advance.
    pub epoch_seconds: f64,
    pub epoch_compensation_seconds: f64,
    pub state: SpacecraftState,
}

#[must_use]
pub fn max_substep_seconds(states: &[SpacecraftState], coast_step: f64) -> f64 {
    let mut step = coast_step;
    for s in states {
        if s.docking.is_some() {
            step = step.min(crate::docking::MAX_CONTACT_SUBSTEP_SECONDS);
        }
        if s.has_powered_input()
            || s.needs_attitude_steps()
            || s.rendezvous
                .as_ref()
                .is_some_and(|r| r.guidance_mode.active())
        {
            step = step.min(MAX_FLIGHT_SUBSTEP_SECONDS);
            let spin = magnitude(s.angular_velocity_body_radps);
            if spin > 0.0 {
                step = step.min(0.05 / spin);
            }
        }
        // Nearby physical observations remain bounded while a pilot coasts.
        // This is not continuous collision detection or a contact response.
        if s.rendezvous.is_some() {
            step = step.min(0.5);
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
    advance_spacecraft_steps_reported(plan, bodies, ships, count).map(|_| ())
}

/// Same staged flow with actual numerical-method telemetry. A powered/contact
/// split is reported even if a burn completes before the operation ends.
pub fn advance_spacecraft_steps_reported(
    plan: &FixedStepPlan,
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    count: usize,
) -> Result<ScientificStepReport, ()> {
    if count > MAX_INCREMENTAL_STEPS
        || bodies.len() != plan.body_count
        || ships.iter().any(|s| {
            s.body_index >= bodies.len()
                || s.reference_body_index.is_some_and(|i| i >= bodies.len())
                || s.mission_target_index.is_some_and(|i| i >= bodies.len())
                || (s.state.mission.is_some() && s.mission_target_index.is_none())
                || s.rendezvous_target_body_index
                    .is_some_and(|i| i >= bodies.len())
                || s.rendezvous_target_spacecraft_index
                    .is_some_and(|i| i >= ships.len())
        })
    {
        return Err(());
    }
    let mut report = plan.empty_scientific_report();
    if ships.iter().all(|s| {
        !s.state.has_powered_input()
            && !s.state.needs_attitude_steps()
            && s.state.mission.is_none()
            && s.state.rendezvous.is_none()
            && s.state.docking.is_none()
    }) {
        report.accumulate(
            &advance_selected_steps(plan, bodies, count, plan.kernel()).map_err(|_| ())?,
        );
    } else {
        let contact_geometry = crate::docking::ContactGeometryCache::compile(ships)?;
        for _ in 0..count {
            let mut remaining = plan.dt_seconds;
            // Split exactly at depletion so no thrust is applied beyond the last fuel.
            while remaining > 0.0 {
                crate::rendezvous::update_guidance(bodies, ships)?;
                crate::docking::update_guidance(bodies, ships)?;
                let contact_members = crate::docking::contact_members(bodies, ships)?;
                let contacts = contact_members.iter().any(|member| *member);
                let mut dt = remaining;
                if contacts {
                    dt = dt.min(crate::docking::MAX_CONTACT_SUBSTEP_SECONDS);
                }
                for ship in ships.iter() {
                    let (_, _, flow) = engine_wrench(&ship.state);
                    if flow > 0.0 {
                        dt = dt.min(ship.state.propellant_mass_kg / flow);
                    }
                    if let Some(program) = &ship.state.burn_program {
                        dt = dt.min(
                            program.remaining_full_throttle_seconds / ship.state.control.throttle,
                        );
                    }
                }
                if !dt.is_finite() || dt <= 0.0 {
                    return Err(());
                }
                for (index, ship) in ships.iter_mut().enumerate() {
                    if contact_members[index] {
                        propulsion_wrench_halfstep(bodies, ship, dt * 0.5);
                    } else {
                        propulsion_rotation_halfstep(bodies, ship, dt * 0.5);
                    }
                }
                // Fuel consumption, attitude control and contact impulses are
                // forward-time operators. A signed Yoshida composition or an
                // adaptive gravity trial must not run those operators backward
                // or imply high-order accuracy for the coupled powered flow.
                let mut segment = if contacts
                    || ships.iter().any(|ship| {
                        ship.state.has_powered_input() || ship.state.needs_attitude_steps()
                    }) {
                    plan.for_nonconservative_split()
                } else {
                    plan.clone()
                };
                segment.dt_seconds = dt;
                if contacts {
                    crate::docking::advance_contact_step(
                        &segment,
                        bodies,
                        ships,
                        &contact_geometry,
                        dt,
                    )?;
                    let mut contact_report = segment.empty_scientific_report();
                    contact_report.accepted(dt);
                    contact_report.force_evaluations = 2;
                    report.accumulate(&contact_report);
                } else {
                    report.accumulate(
                        &advance_selected_steps(&segment, bodies, 1, segment.kernel())
                            .map_err(|_| ())?,
                    );
                }
                for (index, ship) in ships.iter_mut().enumerate() {
                    if contact_members[index] {
                        propulsion_wrench_halfstep(bodies, ship, dt * 0.5);
                    } else {
                        propulsion_rotation_halfstep(bodies, ship, dt * 0.5);
                    }
                    // Compensate repeated substep additions: millions of 0.05s
                    // burn-selected steps must not timestamp an observation
                    // after the exact committed operation boundary.
                    let adjusted = dt - ship.epoch_compensation_seconds;
                    let next_epoch = ship.epoch_seconds + adjusted;
                    ship.epoch_compensation_seconds = (next_epoch - ship.epoch_seconds) - adjusted;
                    ship.epoch_seconds = next_epoch;
                    record_mission_observation(bodies, ship);
                }
                crate::rendezvous::record_observations(bodies, ships)?;
                crate::docking::finish_step(ships);
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
    Ok(report)
}

/// Observe actual solver states at every integration boundary, including warp.
/// Prediction buffers and presentation queries never call this function.
fn record_mission_observation(bodies: &[MassiveBodyState], ship: &mut SpacecraftBinding) {
    let Some(mission) = &mut ship.state.mission else {
        return;
    };
    let Some(target_index) = ship.mission_target_index else {
        return;
    };
    let body = &bodies[ship.body_index];
    let target = &bodies[target_index];
    let relative = sub(body.position_m, target.position_m);
    let range = magnitude(relative);
    if range < mission.closest_target_distance_m {
        mission.closest_target_distance_m = range;
        mission.closest_approach_epoch_seconds = ship.epoch_seconds;
        mission.closest_approach_passed = false;
    } else if range > mission.closest_target_distance_m + 0.01
        && solarlab_domain::spacecraft::dot(relative, sub(body.velocity_mps, target.velocity_mps))
            > 0.0
    {
        mission.closest_approach_passed = true;
    }
    mission.target_surface_crossed |= range <= ship.target_radius_m + ship.body_radius_m;
    if let Some(reference_index) = ship.reference_body_index {
        mission.reference_surface_crossed |=
            magnitude(sub(body.position_m, bodies[reference_index].position_m))
                <= ship.reference_radius_m + ship.body_radius_m;
    }
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
        AttitudeMode::RadialOut => sub(body.position_m, host.position_m),
        AttitudeMode::RadialIn => sub(host.position_m, body.position_m),
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
    if !ship.state.has_powered_input() && !ship.state.needs_attitude_steps() {
        return;
    }
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
    propulsion_impulse(
        &mut bodies[ship.body_index],
        s,
        force,
        flow,
        mid_attitude,
        dt,
    );
}

/// External actuator kick at fixed attitude. The contact drift owns free rigid
/// rotation and its gyroscopic term; applying either here would count it twice.
fn propulsion_wrench_halfstep(
    bodies: &mut [MassiveBodyState],
    ship: &mut SpacecraftBinding,
    dt: f64,
) {
    if !ship.state.has_powered_input() && !ship.state.needs_attitude_steps() {
        return;
    }
    let error = assist_error(bodies, ship);
    let s = &mut ship.state;
    let (force, engine_torque, flow) = engine_wrench(s);
    let divide = |torque: Vector3d| Vector3d {
        x: torque.x / s.inertia_kg_m2.x,
        y: torque.y / s.inertia_kg_m2.y,
        z: torque.z / s.inertia_kg_m2.z,
    };
    let initial_omega = s.angular_velocity_body_radps;
    let initial_torque = add(engine_torque, control_torque(s, initial_omega, error));
    let mid_omega = add(initial_omega, scale(divide(initial_torque), 0.5 * dt));
    let mid_torque = add(engine_torque, control_torque(s, mid_omega, error));
    s.angular_velocity_body_radps = add(initial_omega, scale(divide(mid_torque), dt));
    propulsion_impulse(&mut bodies[ship.body_index], s, force, flow, s.attitude, dt);
}

fn propulsion_impulse(
    body: &mut MassiveBodyState,
    s: &mut SpacecraftState,
    force: Vector3d,
    flow: f64,
    attitude: Quaterniond,
    dt: f64,
) {
    if flow > 0.0 {
        let fuel_used = (flow * dt).min(s.propellant_mass_kg);
        let initial_mass = s.mass_kg();
        // log1p avoids cancellation for small timesteps / low throttle.
        let impulse_per_force = -(-fuel_used / initial_mass).ln_1p() / flow;
        let delta_v = scale(attitude.rotate(force), impulse_per_force);
        body.velocity_mps = add(body.velocity_mps, delta_v);
        s.delivered_delta_v_mps += magnitude(delta_v);
        // Actual main firing excludes RCS use of the shared tank. All engines
        // receive the same fuel-limited fraction of this halfstep.
        if s.control.throttle > 0.0 && s.engines.iter().any(|e| e.control == EngineControl::Main) {
            let equivalent_seconds = (fuel_used / flow) * s.control.throttle;
            s.main_engine_full_throttle_seconds += equivalent_seconds;
            if let Some(program) = &mut s.burn_program {
                program.remaining_full_throttle_seconds =
                    (program.remaining_full_throttle_seconds - equivalent_seconds).max(0.0);
            }
        }
        s.propellant_mass_kg = (s.propellant_mass_kg - fuel_used).max(0.0);
        // Exact depletion can leave a few ulps after two half steps.
        if s.propellant_mass_kg < 4.0 * f64::EPSILON * initial_mass {
            s.propellant_mass_kg = 0.0;
        }
    }
    if s.burn_program
        .as_ref()
        .is_some_and(|p| p.remaining_full_throttle_seconds < 1e-10 || s.propellant_mass_kg == 0.0)
    {
        s.burn_program = None;
        s.control.throttle = 0.0;
        s.control.attitude_mode = AttitudeMode::Hold;
    }
}
