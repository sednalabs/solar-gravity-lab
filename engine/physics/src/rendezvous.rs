//! Shared bounded RCS controller and target-relative observations.
//! Reads kinematics and produces forces through FlightControl only.
use solarlab_domain::rendezvous::MAX_RENDEZVOUS_GUIDANCE_RANGE_M;
use solarlab_domain::spacecraft::{add, cross, dot, finite, magnitude, scale, sub};
use solarlab_domain::{
    AttitudeMode, EngineControl, FlightControl, LocalReferenceFrame, RendezvousGuidance,
    RendezvousStatus, SpacecraftState, Vector3d,
};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RendezvousObservation {
    pub position_target_m: Vector3d,
    pub velocity_target_mps: Vector3d,
    pub relative_velocity_inertial_mps: Vector3d,
    pub range_m: f64,
    pub closing_speed_mps: f64,
    pub relative_speed_mps: f64,
    pub keep_out_distance_m: f64,
}

pub fn observe(
    position_m: Vector3d,
    velocity_mps: Vector3d,
    radius_m: f64,
    frame: LocalReferenceFrame,
    target_radius_m: f64,
) -> Result<RendezvousObservation, ()> {
    let (position, velocity) = frame.to_local(position_m, velocity_mps);
    let relative_velocity = sub(velocity_mps, frame.velocity_mps);
    let displacement = sub(position_m, frame.origin_m);
    let range = magnitude(displacement);
    let relative_speed = magnitude(relative_velocity);
    let keep_out = target_radius_m + radius_m + 5.0;
    if !finite(position)
        || !finite(velocity)
        || !range.is_finite()
        || !relative_speed.is_finite()
        || !keep_out.is_finite()
        || keep_out <= 0.0
    {
        return Err(());
    }
    Ok(RendezvousObservation {
        position_target_m: position,
        velocity_target_mps: velocity,
        relative_velocity_inertial_mps: relative_velocity,
        range_m: range,
        closing_speed_mps: if range > 0.0 {
            -dot(displacement, relative_velocity) / range
        } else {
            0.0
        },
        relative_speed_mps: relative_speed,
        keep_out_distance_m: keep_out,
    })
}

#[must_use]
pub fn neutral_control() -> FlightControl {
    FlightControl {
        attitude_mode: AttitudeMode::Hold,
        ..FlightControl::default()
    }
}

/// Check controller compatibility without constraining the general spacecraft
/// engine model. Active persisted intent must satisfy this before publication.
#[must_use]
pub fn supports_guidance(ship: &SpacecraftState) -> bool {
    axis_rcs_capacities(ship).is_ok()
}

/// Shared by explicit callers and persistent guidance. A blocked approach
/// brakes; this controller does not plan collision-free paths.
pub fn control(
    ship: &SpacecraftState,
    chaser_velocity_mps: Vector3d,
    frame: LocalReferenceFrame,
    t: &RendezvousObservation,
    guidance: RendezvousGuidance,
) -> Result<(FlightControl, RendezvousStatus), ()> {
    if !guidance.valid() {
        return Err(());
    }
    let neutral = neutral_control();
    if guidance.approach
        && magnitude(guidance.stand_off_target_m)
            <= t.keep_out_distance_m + guidance.arrival_distance_m
    {
        return Err(());
    }
    if t.range_m > MAX_RENDEZVOUS_GUIDANCE_RANGE_M {
        return Ok((neutral, RendezvousStatus::OutOfRange));
    }
    if ship.propellant_mass_kg <= 0.0 {
        return Ok((neutral, RendezvousStatus::OutOfFuel));
    }
    let error = sub(t.position_target_m, guidance.stand_off_target_m);
    let (target_position, desired_local_velocity, status) = if t.range_m < t.keep_out_distance_m {
        let outward = if t.range_m > 1e-9 {
            scale(
                t.position_target_m,
                guidance.max_approach_speed_mps / t.range_m,
            )
        } else {
            Vector3d {
                x: guidance.max_approach_speed_mps,
                y: 0.0,
                z: 0.0,
            }
        };
        (t.position_target_m, outward, RendezvousStatus::KeepOut)
    } else if guidance.approach
        && segment_clearance_m(t.position_target_m, guidance.stand_off_target_m)
            <= t.keep_out_distance_m + guidance.arrival_distance_m
    {
        // Brake rather than approach through the station. A clear waypoint
        // is required; this controller does not plan collision-free paths.
        (
            t.position_target_m,
            Vector3d::default(),
            RendezvousStatus::KeepOut,
        )
    } else if guidance.approach {
        let desired = bounded_magnitude(
            scale(error, -guidance.position_gain_per_second),
            guidance.max_approach_speed_mps,
        );
        let status = if magnitude(error) <= guidance.arrival_distance_m
            && magnitude(t.velocity_target_mps) <= guidance.arrival_speed_mps
        {
            RendezvousStatus::Holding
        } else {
            RendezvousStatus::Approaching
        };
        (guidance.stand_off_target_m, desired, status)
    } else {
        (
            t.position_target_m,
            Vector3d::default(),
            RendezvousStatus::VelocityMatching,
        )
    };
    let (_, desired_world_velocity) = frame.to_world(target_position, desired_local_velocity);
    let acceleration = scale(
        sub(desired_world_velocity, chaser_velocity_mps),
        1.0 / guidance.velocity_response_seconds,
    );
    let force = scale(
        ship.attitude.conjugate().rotate(acceleration),
        ship.mass_kg(),
    );
    let capacities = axis_rcs_capacities(ship)?;
    let axis = |f: f64, positive: usize, negative: usize| {
        (f / capacities[if f >= 0.0 { positive } else { negative }]).clamp(-1.0, 1.0)
    };
    let control = FlightControl {
        translation_body: Vector3d {
            x: axis(force.x, 0, 1),
            y: axis(force.y, 2, 3),
            z: axis(force.z, 4, 5),
        },
        ..neutral
    };
    if !control.valid() {
        return Err(());
    }
    Ok((control, status))
}

fn bounded_magnitude(v: Vector3d, limit: f64) -> Vector3d {
    let m = magnitude(v);
    if m > limit {
        scale(v, limit / m)
    } else {
        v
    }
}

fn segment_clearance_m(start: Vector3d, end: Vector3d) -> f64 {
    let direction = sub(end, start);
    let squared = dot(direction, direction);
    let fraction = if squared > 0.0 {
        (-dot(start, direction) / squared).clamp(0.0, 1.0)
    } else {
        0.0
    };
    magnitude(add(start, scale(direction, fraction)))
}

pub(crate) fn axis_rcs_capacities(ship: &SpacecraftState) -> Result<[f64; 6], ()> {
    let mut capacity = [0.0; 6];
    let mut torque = [Vector3d::default(); 6];
    for engine in &ship.engines {
        let (index, direction) = match engine.control {
            EngineControl::Main => continue,
            EngineControl::PositiveX => (
                0,
                Vector3d {
                    x: 1.0,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            EngineControl::NegativeX => (
                1,
                Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
            ),
            EngineControl::PositiveY => (
                2,
                Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
            ),
            EngineControl::NegativeY => (
                3,
                Vector3d {
                    x: 0.0,
                    y: -1.0,
                    z: 0.0,
                },
            ),
            EngineControl::PositiveZ => (
                4,
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 1.0,
                },
            ),
            EngineControl::NegativeZ => (
                5,
                Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: -1.0,
                },
            ),
        };
        if magnitude(sub(engine.direction_body, direction)) > 1e-8 {
            return Err(());
        }
        capacity[index] += engine.max_thrust_n;
        torque[index] = add(
            torque[index],
            cross(
                engine.position_body_m,
                scale(direction, engine.max_thrust_n),
            ),
        );
    }
    if capacity
        .iter()
        .enumerate()
        .any(|(i, f)| !f.is_finite() || *f <= 0.0 || magnitude(torque[i]) > f * 1e-8)
    {
        return Err(());
    }
    Ok(capacity)
}

fn target_observation(
    bodies: &[crate::MassiveBodyState],
    ships: &[crate::spacecraft::SpacecraftBinding],
    index: usize,
) -> Result<Option<(LocalReferenceFrame, RendezvousObservation)>, ()> {
    let ship = &ships[index];
    let Some(target_body_index) = ship.rendezvous_target_body_index else {
        return Ok(None);
    };
    let Some(target_spacecraft_index) = ship.rendezvous_target_spacecraft_index else {
        return Ok(None);
    };
    let target_ship = &ships[target_spacecraft_index];
    let Some(rendezvous) = ship.state.rendezvous.as_ref() else {
        return Ok(None);
    };
    if target_ship.body_index != target_body_index
        || target_ship.state.body_id != rendezvous.target_body_id
    {
        return Err(());
    }
    let target = &bodies[target_body_index];
    let frame = LocalReferenceFrame {
        origin_m: target.position_m,
        velocity_mps: target.velocity_mps,
        attitude: target_ship.state.attitude,
        angular_velocity_world_radps: target_ship
            .state
            .attitude
            .rotate(target_ship.state.angular_velocity_body_radps),
    };
    let body = &bodies[ship.body_index];
    let observation = observe(
        body.position_m,
        body.velocity_mps,
        ship.body_radius_m,
        frame,
        ship.rendezvous_target_radius_m,
    )?;
    Ok(Some((frame, observation)))
}

/// Re-evaluate current native intent before every force segment. All target
/// frames are sampled before any component is mutated, so ordering is immaterial.
pub(crate) fn update_guidance(
    bodies: &[crate::MassiveBodyState],
    ships: &mut [crate::spacecraft::SpacecraftBinding],
) -> Result<(), ()> {
    let mut updates = Vec::new();
    for (index, ship) in ships.iter().enumerate() {
        let Some(rendezvous) = ship.state.rendezvous.as_ref() else {
            continue;
        };
        if !rendezvous.guidance_mode.active() {
            continue;
        }
        let result = if let Some((frame, observation)) = target_observation(bodies, ships, index)? {
            let guidance =
                if rendezvous.guidance_mode == solarlab_domain::RendezvousGuidanceMode::Approach {
                    RendezvousGuidance::default()
                } else {
                    RendezvousGuidance::velocity_match()
                };
            control(
                &ship.state,
                bodies[ship.body_index].velocity_mps,
                frame,
                &observation,
                guidance,
            )?
        } else {
            (neutral_control(), RendezvousStatus::TargetUnavailable)
        };
        updates.push((index, result));
    }
    for (index, (control, status)) in updates {
        let ship = &mut ships[index].state;
        ship.control = control;
        let rendezvous = ship.rendezvous.as_mut().ok_or(())?;
        rendezvous.status = status;
        if matches!(
            status,
            RendezvousStatus::TargetUnavailable
                | RendezvousStatus::OutOfRange
                | RendezvousStatus::OutOfFuel
        ) {
            rendezvous.guidance_mode = solarlab_domain::RendezvousGuidanceMode::Manual;
        }
    }
    Ok(())
}

/// Progress is sampled from actual solver bodies after both force halfsteps.
/// No render or trajectory query can change these retained observations.
pub(crate) fn record_observations(
    bodies: &[crate::MassiveBodyState],
    ships: &mut [crate::spacecraft::SpacecraftBinding],
) -> Result<(), ()> {
    for index in 0..ships.len() {
        if ships[index].state.rendezvous.is_none() {
            continue;
        }
        let observation = target_observation(bodies, ships, index)?;
        let ship = &mut ships[index];
        let rendezvous = ship.state.rendezvous.as_mut().ok_or(())?;
        let Some((_, observation)) = observation else {
            rendezvous.guidance_mode = solarlab_domain::RendezvousGuidanceMode::Manual;
            rendezvous.status = RendezvousStatus::TargetUnavailable;
            ship.state.control = neutral_control();
            continue;
        };
        if observation.range_m < rendezvous.closest_observed_range_m {
            rendezvous.closest_observed_range_m = observation.range_m;
            rendezvous.closest_observed_epoch_seconds = ship.epoch_seconds;
        }
        rendezvous.keep_out_entered |= observation.range_m <= observation.keep_out_distance_m;
        let guidance = RendezvousGuidance::default();
        let arrived = magnitude(sub(
            observation.position_target_m,
            guidance.stand_off_target_m,
        )) <= guidance.arrival_distance_m
            && magnitude(observation.velocity_target_mps) <= guidance.arrival_speed_mps
            && observation.range_m > observation.keep_out_distance_m;
        rendezvous.stand_off_achieved |= arrived;
        if rendezvous.guidance_mode == solarlab_domain::RendezvousGuidanceMode::Approach {
            if arrived {
                rendezvous.status = RendezvousStatus::Holding;
            } else if rendezvous.status == RendezvousStatus::Holding {
                rendezvous.status = RendezvousStatus::Approaching;
            }
        }
        if rendezvous.guidance_mode.active() && ship.state.propellant_mass_kg <= 0.0 {
            rendezvous.guidance_mode = solarlab_domain::RendezvousGuidanceMode::Manual;
            rendezvous.status = RendezvousStatus::OutOfFuel;
            ship.state.control = neutral_control();
        }
    }
    Ok(())
}
