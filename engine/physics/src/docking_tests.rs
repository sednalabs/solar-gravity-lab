use super::*;
use crate::spacecraft::advance_spacecraft_steps;
use crate::{fixed_step_plan, CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};
use solarlab_domain::{BodyId, SpacecraftDockingState};

fn sphere_encounters(
    states: &[(Vector3d, Vector3d)],
) -> (Vec<MassiveBodyState>, Vec<SpacecraftBinding>) {
    use solarlab_domain::{
        ContactPart, DockingPortGeometry, SpacecraftAssembly, SpacecraftContactGeometry,
    };
    let mut bodies = Vec::new();
    let mut ships = Vec::new();
    for (index, &(position_m, velocity_mps)) in states.iter().enumerate() {
        let target_id = BodyId(format!("target-{index}"));
        let mut craft = SpacecraftState::reference(BodyId(format!("ship-{index}")), None);
        craft.dry_mass_kg = 1000.0;
        craft.propellant_mass_kg = 0.0;
        craft.engines.clear();
        craft.control = neutral();
        craft.max_control_torque_nm = Vector3d::default();
        craft.inertia_kg_m2 = Vector3d {
            x: 400.0,
            y: 400.0,
            z: 400.0,
        };
        craft.authored = Some(SpacecraftAssembly {
            definition_id: "contact-sphere".into(),
            display_name: "Contact sphere".into(),
            visual_asset: None,
            contact: Some(SpacecraftContactGeometry {
                hull: vec![ContactPart {
                    position_body_m: Vector3d::default(),
                    attitude_body: Quaterniond::IDENTITY,
                    shape: ContactShape::Sphere { radius_m: 1.0 },
                }],
                port: DockingPortGeometry {
                    position_body_m: Vector3d {
                        x: 0.0,
                        y: 0.0,
                        z: 2.0,
                    },
                    attitude_body: Quaterniond::IDENTITY,
                    inner_radius_m: 0.3,
                    outer_radius_m: 0.4,
                    depth_m: 0.2,
                },
            }),
        });
        let mut target = craft.clone();
        target.body_id = target_id.clone();
        craft.docking = Some(SpacecraftDockingState::new(target_id));
        ships.push(binding(bodies.len(), craft));
        bodies.push(MassiveBodyState {
            mass_kg: 0.0,
            position_m,
            velocity_mps,
        });
        ships.push(binding(bodies.len(), target));
        bodies.push(MassiveBodyState {
            mass_kg: 0.0,
            position_m: Vector3d {
                x: 1000.0,
                y: 1000.0 * (index + 1) as f64,
                z: 0.0,
            },
            velocity_mps: Vector3d::default(),
        });
    }
    (bodies, ships)
}

#[test]
fn merged_island_resolves_cross_pair_head_on_contact() {
    let (mut bodies, mut ships) = sphere_encounters(&[
        (
            Vector3d {
                x: -20.0,
                y: 0.0,
                z: 0.0,
            },
            Vector3d {
                x: 4000.0,
                y: 0.0,
                z: 0.0,
            },
        ),
        (
            Vector3d {
                x: 20.0,
                y: 0.0,
                z: 0.0,
            },
            Vector3d {
                x: -4000.0,
                y: 0.0,
                z: 0.0,
            },
        ),
    ]);
    let initial_momentum = momentum(&bodies, &ships);
    let pairs = contact_pairs(&bodies, &ships).unwrap();
    let geometry = ContactGeometryCache::compile(&ships).unwrap();
    let order = island_order(&pairs, &ships);
    let envelope = MotionEnvelope::new(&bodies, &ships, &pairs, &order, &geometry).unwrap();
    assert!(envelope.free_drift_seconds(&bodies, &ships, &order, &geometry) < 0.01);
    advance(&mut bodies, &mut ships, 0.01, 1);
    assert!(bodies[0].position_m.x < bodies[2].position_m.x);
    assert!(bodies[2].position_m.x - bodies[0].position_m.x > 1.99);
    assert!((bodies[0].velocity_mps.x + 400.0).abs() < 0.02);
    assert!((bodies[2].velocity_mps.x - 400.0).abs() < 0.02);
    // Collision at4.75ms followed by5.25ms at400m/s gives exact endpoints±3.1m.
    assert!((bodies[0].position_m.x + 3.1).abs() < 0.001);
    assert!((bodies[2].position_m.x - 3.1).abs() < 0.001);
    assert!(magnitude(sub(momentum(&bodies, &ships), initial_momentum)) < 1e-6);
    assert!(ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    assert!(ships[2].state.docking.as_ref().unwrap().unsafe_contact);
}

#[test]
fn odd_cycle_contact_resolution_has_a_finite_work_budget_and_rolls_back_island() {
    let states: Vec<_> = (0..3)
        .map(|i| {
            let angle = f64::from(i) * std::f64::consts::TAU / 3.0;
            (
                Vector3d {
                    x: (2.0 / 3.0_f64.sqrt()) * angle.cos(),
                    y: (2.0 / 3.0_f64.sqrt()) * angle.sin(),
                    z: 0.0,
                },
                Vector3d::default(),
            )
        })
        .collect();
    let (mut bodies, mut ships) = sphere_encounters(&states);
    for ship in &mut ships {
        let geometry = ship
            .state
            .authored
            .as_mut()
            .unwrap()
            .contact
            .as_mut()
            .unwrap();
        geometry.port.inner_radius_m = 0.3989;
        geometry.port.depth_m = 0.005;
        ship.state.angular_velocity_body_radps.z = 100.0;
    }
    let pairs = contact_pairs(&bodies, &ships).unwrap();
    let geometry = ContactGeometryCache::compile(&ships).unwrap();
    let order = island_order(&pairs, &ships);
    let envelope = MotionEnvelope::new(&bodies, &ships, &pairs, &order, &geometry).unwrap();
    assert_eq!(
        envelope.free_drift_seconds(&bodies, &ships, &order, &geometry),
        0.0
    );
    let before_bodies = bodies.clone();
    let before_ships: Vec<_> = ships.iter().map(|s| s.state.clone()).collect();
    assert_eq!(
        drift_island(&mut bodies, &mut ships, &pairs, &geometry, 0.01),
        Err(ContactStepError::ResolutionLimit)
    );
    assert_eq!(bodies, before_bodies);
    assert_eq!(
        ships.iter().map(|s| s.state.clone()).collect::<Vec<_>>(),
        before_ships
    );
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

fn pair(gap: f64) -> (Vec<MassiveBodyState>, Vec<SpacecraftBinding>) {
    let mut target = SpacecraftState::reference(BodyId("relay".into()), None);
    target.appearance = SpacecraftAppearance::RelayStation;
    target.dry_mass_kg = 20_000.0;
    target.propellant_mass_kg = 0.0;
    target.engines.clear();
    target.max_control_torque_nm = Vector3d::default();
    target.inertia_kg_m2 = Vector3d {
        x: 800_000.0,
        y: 800_000.0,
        z: 800_000.0,
    };
    target.control = neutral();
    let mut ship = SpacecraftState::reference(BodyId("asteria".into()), None);
    ship.attitude = desired_mating_attitude(ship.appearance, target.appearance, target.attitude);
    ship.docking = Some(SpacecraftDockingState::new(target.body_id.clone()));
    ship.control = neutral();
    let bodies = vec![
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: Vector3d {
                x: 0.0,
                y: -(7.56 + gap),
                z: 0.0,
            },
            velocity_mps: Vector3d::default(),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        },
    ];
    (bodies, vec![binding(0, ship), binding(1, target)])
}

fn plan(dt: f64, len: usize) -> FixedStepPlan {
    fixed_step_plan(
        &PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: dt,
        },
        dt,
        len,
        &[],
    )
    .unwrap()
}

fn advance(
    bodies: &mut [MassiveBodyState],
    ships: &mut [SpacecraftBinding],
    dt: f64,
    steps: usize,
) {
    let plan = plan(dt, bodies.len());
    let mut remaining = steps;
    while remaining > 0 {
        let count = remaining.min(crate::MAX_INCREMENTAL_STEPS);
        advance_spacecraft_steps(&plan, bodies, ships, count).unwrap();
        remaining -= count;
    }
}

fn observation(bodies: &[MassiveBodyState], ships: &[SpacecraftBinding]) -> DockingObservation {
    observe(
        bodies[0].position_m,
        bodies[0].velocity_mps,
        &ships[0].state,
        bodies[1].position_m,
        bodies[1].velocity_mps,
        &ships[1].state,
    )
    .unwrap()
}

fn momentum(bodies: &[MassiveBodyState], ships: &[SpacecraftBinding]) -> Vector3d {
    ships.iter().fold(Vector3d::default(), |p, s| {
        add(
            p,
            scale(bodies[s.body_index].velocity_mps, s.state.mass_kg()),
        )
    })
}

#[test]
fn authored_port_frames_and_capture_gates_include_roll_and_lateral_offset() {
    let (bodies, ships) = pair(0.1);
    let initial = observation(&bodies, &ships);
    assert!(initial.capture_ready, "{initial:?}");
    assert!((initial.position_port_m.z - 0.1).abs() < 1e-12);
    assert!(
        magnitude(sub(
            ships[0].state.attitude.rotate(Vector3d {
                x: 0.0,
                y: 1.0,
                z: 0.0
            }),
            Vector3d {
                x: 0.0,
                y: 0.0,
                z: 1.0
            }
        )) < 1e-12
    );
    let mut shifted = bodies.clone();
    shifted[0].position_m.x += 0.051;
    assert!(!observation(&shifted, &ships).capture_ready);
    let mut rolled = ships.clone();
    rolled[0].state.attitude =
        rolled[0]
            .state
            .attitude
            .multiply(Quaterniond::from_rotation_vector(Vector3d {
                x: 0.0,
                y: 0.0,
                z: 0.11,
            }));
    assert!(!observation(&bodies, &rolled).capture_ready);
    let mut fast = bodies.clone();
    fast[0].velocity_mps.y = 0.101;
    assert!(!observation(&fast, &ships).capture_ready);
    let (penetrated, ships) = pair(-0.006);
    assert!(!observation(&penetrated, &ships).capture_ready);
}

#[test]
fn unopted_coast_retains_exact_canonical_gravity_path() {
    let (mut bodies, mut ships) = pair(30.0);
    ships[0].state.docking = None;
    bodies.push(MassiveBodyState {
        mass_kg: 5.9722e24,
        position_m: Vector3d {
            x: -6_771_000.0,
            y: 0.0,
            z: 0.0,
        },
        velocity_mps: Vector3d::default(),
    });
    let mut expected = bodies.clone();
    let p = plan(0.05, bodies.len());
    crate::advance_fixed_steps(&p, &mut expected, 40).unwrap();
    advance_spacecraft_steps(&p, &mut bodies, &mut ships, 40).unwrap();
    assert_eq!(bodies, expected);
}

#[test]
fn contact_wrench_matches_rocket_equation_and_principal_axis_torque() {
    let (mut bodies, mut ships) = pair(30.0);
    ships[0].state.control.throttle = 0.2;
    ships[0].state.control.rotation_body.z = 0.2;
    let initial = ships[0].state.clone();
    advance(&mut bodies, &mut ships, 0.01, 50);
    let exhaust_velocity = 450.0 * 9.80665;
    let fuel = 18_000.0 / exhaust_velocity * 0.5;
    let expected_dv = exhaust_velocity * (initial.mass_kg() / (initial.mass_kg() - fuel)).ln();
    assert!((ships[0].state.propellant_mass_kg - (initial.propellant_mass_kg - fuel)).abs() < 1e-9);
    assert!((magnitude(bodies[0].velocity_mps) - expected_dv).abs() < 1e-8);
    assert!((ships[0].state.angular_velocity_body_radps.z - 0.025).abs() < 1e-9);
    let relative_q = initial
        .attitude
        .conjugate()
        .multiply(ships[0].state.attitude);
    assert!((magnitude(rotation_vector(relative_q)) - 0.00625).abs() < 1e-8);
}

#[test]
fn assisted_approach_uses_real_rcs_and_captures_without_unsafe_impact() {
    let (mut bodies, mut ships) = pair(22.44); // 30 m centre separation.
    let docking = ships[0].state.docking.as_mut().unwrap();
    docking.guidance_mode = DockingGuidanceMode::Approach;
    docking.capture_armed = true;
    let fuel = ships[0].state.propellant_mass_kg;
    for _ in 0..150 {
        advance(&mut bodies, &mut ships, 0.01, 100);
        if ships[0]
            .state
            .docking
            .as_ref()
            .unwrap()
            .attachment
            .is_some()
        {
            break;
        }
    }
    let t = observation(&bodies, &ships);
    assert_eq!(t.status, DockingStatus::Docked, "{t:?}");
    assert!(ships[0].state.propellant_mass_kg < fuel - 1.0);
    assert!(ships[0].state.delivered_delta_v_mps > 0.5);
    assert!(!ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    assert!(
        t.position_port_m.z.abs() < 0.001,
        "actual collar contact required: {t:?}"
    );
}

#[test]
fn capture_keeps_actual_rest_pose_and_conserves_pair_momentum() {
    let (mut bodies, mut ships) = pair(0.0);
    bodies[0].velocity_mps.y = 0.05;
    ships[0].state.docking.as_mut().unwrap().capture_armed = true;
    let before = momentum(&bodies, &ships);
    let mut attachment = None;
    for _ in 0..400 {
        let before_step = sub(bodies[0].position_m, bodies[1].position_m);
        advance(&mut bodies, &mut ships, 0.01, 1);
        attachment = ships[0].state.docking.as_ref().unwrap().attachment;
        if let Some(a) = attachment {
            let current = ships[1]
                .state
                .attitude
                .conjugate()
                .rotate(sub(bodies[0].position_m, bodies[1].position_m));
            assert_eq!(a.position_target_m, current);
            assert!(
                magnitude(sub(current, before_step)) < 0.001,
                "capture moved discontinuously"
            );
            break;
        }
    }
    let rest = attachment
        .expect("actual collar contact must capture")
        .position_target_m;
    advance(&mut bodies, &mut ships, 0.01, 100);
    assert!(magnitude(sub(momentum(&bodies, &ships), before)) < 1e-7);
    let actual = ships[1]
        .state
        .attitude
        .conjugate()
        .rotate(sub(bodies[0].position_m, bodies[1].position_m));
    assert!(magnitude(sub(actual, rest)) < 1e-5);
    assert!(ships.iter().all(|s| s.state.valid()));
}

#[test]
fn capture_volume_without_collar_contact_does_not_latch() {
    let (mut bodies, mut ships) = pair(0.08);
    ships[0].state.docking.as_mut().unwrap().capture_armed = true;
    assert!(observation(&bodies, &ships).capture_ready);
    advance(&mut bodies, &mut ships, 0.01, 100);
    assert!(ships[0]
        .state
        .docking
        .as_ref()
        .unwrap()
        .attachment
        .is_none());
    assert!((observation(&bodies, &ships).position_port_m.z - 0.08).abs() < 1e-12);
}

#[test]
fn physical_head_on_contact_matches_momentum_and_configured_restitution() {
    let (mut bodies, mut ships) = pair(0.5);
    bodies[0].velocity_mps.y = 2.0;
    let before = momentum(&bodies, &ships);
    advance(&mut bodies, &mut ships, 0.005, 200);
    assert!(magnitude(sub(momentum(&bodies, &ships), before)) < 1e-6);
    // Coaxial port-flange impact, with both bodies dynamic. Analytical 1D
    // restitution velocities for masses12t/20t and configured e=0.1.
    // Polyhedral ring manifolds approximate a continuous annulus; require the
    // axial answer within1.5% of the2m/s incoming speed, with exact momentum.
    assert!(
        (bodies[0].velocity_mps.y - 0.625).abs() < 0.03,
        "{:?}",
        bodies
    );
    assert!(
        (bodies[1].velocity_mps.y - 0.825).abs() < 0.03,
        "{:?}",
        bodies
    );
    assert!(ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    assert!(ships[0]
        .state
        .docking
        .as_ref()
        .unwrap()
        .attachment
        .is_none());
}

fn angular_momentum(bodies: &[MassiveBodyState], ships: &[SpacecraftBinding]) -> Vector3d {
    ships.iter().fold(Vector3d::default(), |total, ship| {
        let s = &ship.state;
        let body = bodies[ship.body_index];
        let spin = s.attitude.rotate(Vector3d {
            x: s.inertia_kg_m2.x * s.angular_velocity_body_radps.x,
            y: s.inertia_kg_m2.y * s.angular_velocity_body_radps.y,
            z: s.inertia_kg_m2.z * s.angular_velocity_body_radps.z,
        });
        add(
            total,
            add(
                spin,
                cross(body.position_m, scale(body.velocity_mps, s.mass_kg())),
            ),
        )
    })
}

#[test]
fn off_axis_hull_contact_transfers_angular_momentum_to_both_bodies() {
    let (mut bodies, mut ships) = pair(0.3);
    bodies[0].position_m.x = 0.8;
    bodies[0].velocity_mps.y = 2.0;
    let linear_before = momentum(&bodies, &ships);
    let angular_before = angular_momentum(&bodies, &ships);
    advance(&mut bodies, &mut ships, 0.005, 200);
    assert!(magnitude(ships[0].state.angular_velocity_body_radps) > 0.001);
    assert!(magnitude(ships[1].state.angular_velocity_body_radps) > 0.0001);
    assert!(magnitude(sub(momentum(&bodies, &ships), linear_before)) < 1e-5);
    let angular_error = magnitude(sub(angular_momentum(&bodies, &ships), angular_before))
        / magnitude(angular_before);
    assert!(
        angular_error < 0.005,
        "angular relative error {angular_error}"
    );
}

#[test]
fn ccd_blocks_fast_crossing_and_never_captures_an_unsafe_arrival() {
    let (mut bodies, mut ships) = pair(30.0);
    bodies[0].velocity_mps.y = 5_000.0; // Crosses the entire station in one dt.
    ships[0].state.docking.as_mut().unwrap().capture_armed = true;
    let before = momentum(&bodies, &ships);
    advance(&mut bodies, &mut ships, 0.01, 4);
    let relative_y = bodies[0].position_m.y - bodies[1].position_m.y;
    assert!(relative_y < -6.0, "tunnelled through station: {relative_y}");
    assert!(magnitude(sub(momentum(&bodies, &ships), before)) < 0.1);
    let docking = ships[0].state.docking.as_ref().unwrap();
    assert!(docking.attachment.is_none());
    assert!(
        !docking.capture_armed,
        "bodies={bodies:?}, state={docking:?}"
    );
    assert!(docking.unsafe_contact);
}

#[test]
fn opted_in_free_orbit_matches_analytic_solution_and_step_refinement() {
    // A dimensionally scaled Kepler orbit exposes timestep error over a short
    // CI horizon instead of masking it with astronomical-coordinate roundoff.
    // The chaser remains 1 km away: no contacts or guidance can alter the orbit.
    const MU: f64 = 10_000.0;
    const RADIUS: f64 = 100.0;
    const SPEED: f64 = 10.0;
    const DURATION: f64 = 5.0;
    let run = |dt: f64, steps: usize| {
        let (mut bodies, mut ships) = pair(992.44);
        for body in &mut bodies {
            body.position_m.x += RADIUS;
            body.velocity_mps.y = SPEED;
        }
        bodies.push(MassiveBodyState {
            mass_kg: MU / 6.67430e-11,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        });
        advance(&mut bodies, &mut ships, dt, steps);
        let angle = SPEED / RADIUS * DURATION;
        let exact_position = Vector3d {
            x: RADIUS * angle.cos(),
            y: RADIUS * angle.sin(),
            z: 0.0,
        };
        let exact_velocity = Vector3d {
            x: -SPEED * angle.sin(),
            y: SPEED * angle.cos(),
            z: 0.0,
        };
        assert_eq!(bodies[2].position_m, Vector3d::default());
        assert_eq!(bodies[2].velocity_mps, Vector3d::default());
        assert_eq!(ships[0].state.propellant_mass_kg, 8_000.0);
        assert!(ships[0]
            .state
            .docking
            .as_ref()
            .unwrap()
            .attachment
            .is_none());
        (
            magnitude(sub(bodies[1].position_m, exact_position)),
            magnitude(sub(bodies[1].velocity_mps, exact_velocity)),
        )
    };
    let coarse = run(0.01, 500);
    let fine = run(0.005, 1_000);
    assert!(
        coarse.0 < 1e-4 && coarse.1 < 1e-5,
        "coarse errors {coarse:?}"
    );
    // A duplicated gravity or drift fails the analytic bound and convergence.
    assert!(
        fine.0 < coarse.0 * 0.3 && fine.1 < coarse.1 * 0.3,
        "coarse {coarse:?}, fine {fine:?}"
    );
}

#[test]
fn inertial_origin_and_bulk_boost_preserve_motion_above_rapier_default_speed_cap() {
    let (mut bodies, mut ships) = pair(992.44);
    bodies[0].velocity_mps.x = 5_000.0;
    let initial = bodies.clone();
    let mut shifted = bodies.clone();
    let mut shifted_ships = ships.clone();
    let origin = Vector3d {
        x: 1.5e11,
        y: -3.0e11,
        z: 4.0e10,
    };
    let boost = Vector3d {
        x: 2.0e7,
        y: -3.0e7,
        z: 5.0e7,
    };
    for body in &mut shifted {
        body.position_m = add(body.position_m, origin);
        body.velocity_mps = add(body.velocity_mps, boost);
    }
    const DURATION: f64 = 0.2;
    advance(&mut bodies, &mut ships, 0.01, 20);
    advance(&mut shifted, &mut shifted_ships, 0.01, 20);
    for ((normal, shifted_body), initial_body) in bodies.iter().zip(&shifted).zip(&initial) {
        let exact = add(
            initial_body.position_m,
            scale(initial_body.velocity_mps, DURATION),
        );
        assert!(magnitude(sub(normal.position_m, exact)) < 1e-9);
        assert!(magnitude(sub(normal.velocity_mps, initial_body.velocity_mps)) < 1e-9);
        assert!(
            magnitude(sub(
                sub(shifted_body.velocity_mps, boost),
                normal.velocity_mps
            )) < 1e-7
        );
        let unshifted_position = sub(shifted_body.position_m, add(origin, scale(boost, DURATION)));
        // 2 mm is below the accumulated f64-ULP bound at this 300 Gm origin.
        assert!(magnitude(sub(unshifted_position, normal.position_m)) < 0.002);
    }
    // 5000 m/s relative motion exceeds the default 400 m/s cap in both COM arms.
    assert!((bodies[0].position_m.x - 1_000.0).abs() < 1e-9);
    assert_eq!(bodies[0].velocity_mps.x, 5_000.0);
}

#[test]
fn rotating_target_spar_cannot_sweep_through_stationary_ship_wing() {
    let (mut bodies, mut ships) = pair(22.44);
    ships[0].state.attitude = Quaterniond::IDENTITY;
    bodies[0].position_m = Vector3d {
        x: 15.0,
        y: 0.2,
        z: 0.0,
    };
    ships[1].state.angular_velocity_body_radps.z = 20.0;
    let dt = crate::spacecraft::max_substep_seconds(
        &ships.iter().map(|s| s.state.clone()).collect::<Vec<_>>(),
        0.01,
    );
    assert_eq!(dt, 0.0025);
    // The Relay's thin +X spar crosses the Asteria's thin -X wing in less than
    // one0.05rad step. Neither centre translates initially, so chaser-only CCD
    // misses the active rotation. Both still have finite dynamic mass/inertia.
    advance(&mut bodies, &mut ships, dt, 1);
    assert!(
        magnitude(bodies[0].velocity_mps) > 0.1,
        "missed rotating spar: {bodies:?}"
    );
    assert!(ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    assert!(magnitude(momentum(&bodies, &ships)) < 1e-5);
    assert!(ships[0]
        .state
        .docking
        .as_ref()
        .unwrap()
        .attachment
        .is_none());
}

#[test]
fn externally_composed_ports_capture_independently_of_visual_asset_identity() {
    use solarlab_domain::{
        ContactPart, ContactShape, DockingPortGeometry, SpacecraftAssembly,
        SpacecraftContactGeometry,
    };
    let (mut bodies, mut ships) = pair(0.0);
    for (index, ship) in ships.iter_mut().enumerate() {
        // Both use Asteria's mesh. Their physical +X ports and compact box hulls
        // come exclusively from the authored components.
        ship.state.appearance = SpacecraftAppearance::Asteria;
        ship.state.authored = Some(SpacecraftAssembly {
            definition_id: format!("external.{index}"),
            display_name: "External utility craft".into(),
            visual_asset: None,
            contact: Some(SpacecraftContactGeometry {
                hull: vec![ContactPart {
                    position_body_m: Vector3d::default(),
                    attitude_body: Quaterniond::IDENTITY,
                    shape: ContactShape::Box {
                        half_extents_m: Vector3d {
                            x: 1.5,
                            y: 0.5,
                            z: 0.5,
                        },
                    },
                }],
                port: DockingPortGeometry {
                    position_body_m: Vector3d {
                        x: 2.0,
                        y: 0.0,
                        z: 0.0,
                    },
                    attitude_body: Quaterniond::from_rotation_vector(Vector3d {
                        x: 0.0,
                        y: std::f64::consts::FRAC_PI_2,
                        z: 0.0,
                    }),
                    inner_radius_m: 0.4,
                    outer_radius_m: 0.7,
                    depth_m: 0.1,
                },
            }),
        });
    }
    ships[0].state.attitude = desired_spacecraft_mating_attitude(&ships[0].state, &ships[1].state);
    ships[0].state.docking.as_mut().unwrap().capture_armed = true;
    bodies[0].position_m = Vector3d {
        x: 4.0,
        y: 0.0,
        z: 0.0,
    };
    bodies[0].velocity_mps = Vector3d {
        x: -0.05,
        y: 0.0,
        z: 0.0,
    };
    let initial_momentum = momentum(&bodies, &ships);
    let initial_observation = observation(&bodies, &ships);
    assert!(initial_observation.position_port_m.z.abs() < 1e-12);
    assert!(initial_observation.capture_ready);
    advance(&mut bodies, &mut ships, 0.01, 100);
    assert!(
        ships[0]
            .state
            .docking
            .as_ref()
            .unwrap()
            .attachment
            .is_some(),
        "{:?}; bodies={bodies:?}",
        observation(&bodies, &ships)
    );
    assert!(!ships[0].state.docking.as_ref().unwrap().unsafe_contact);
    assert!(magnitude(sub(momentum(&bodies, &ships), initial_momentum)) < 1e-7);
}

#[test]
fn distant_encounter_does_not_change_local_contact_trajectory() {
    let (mut local_bodies, mut local_ships) = pair(0.04);
    local_bodies[0].velocity_mps.y = 0.08;
    local_ships[0].state.docking.as_mut().unwrap().capture_armed = true;
    let mut all_bodies = local_bodies.clone();
    let mut all_ships = local_ships.clone();
    let (mut far_bodies, mut far_ships) = pair(0.04);
    for body in &mut far_bodies {
        body.position_m.x = 1.0e15;
        body.velocity_mps.z = 1.0e7;
    }
    for (index, ship) in far_ships.iter_mut().enumerate() {
        ship.body_index += 2;
        ship.state.body_id = BodyId(format!("far.{index}"));
    }
    far_ships[0].state.docking.as_mut().unwrap().target_body_id =
        far_ships[1].state.body_id.clone();
    all_bodies.extend(far_bodies);
    all_ships.extend(far_ships);
    advance(&mut local_bodies, &mut local_ships, 0.01, 100);
    advance(&mut all_bodies, &mut all_ships, 0.01, 100);
    assert_eq!(&all_bodies[..2], local_bodies);
    for (local, together) in local_ships.iter().zip(&all_ships) {
        assert_eq!(local.state, together.state);
    }
    assert!(local_ships[0]
        .state
        .docking
        .as_ref()
        .unwrap()
        .attachment
        .is_some());
}

#[test]
fn nonoverlapping_authored_annular_faces_cannot_report_capture_ready() {
    use solarlab_domain::{
        ContactPart, ContactShape, DockingPortGeometry, SpacecraftAssembly,
        SpacecraftContactGeometry,
    };
    let (bodies, mut ships) = pair(0.01);
    ships[0].state.authored = Some(SpacecraftAssembly {
        definition_id: "narrow-port".into(),
        display_name: "Narrow port".into(),
        visual_asset: None,
        contact: Some(SpacecraftContactGeometry {
            hull: vec![ContactPart {
                position_body_m: Vector3d::default(),
                attitude_body: Quaterniond::IDENTITY,
                shape: ContactShape::Sphere { radius_m: 1.0 },
            }],
            port: DockingPortGeometry {
                position_body_m: port_pose(SpacecraftAppearance::Asteria).0,
                attitude_body: Quaterniond::IDENTITY,
                inner_radius_m: 0.2,
                outer_radius_m: 0.4,
                depth_m: 0.1,
            },
        }),
    });
    assert!(!ports_compatible(&ships[0].state, &ships[1].state));
    assert!(!observation(&bodies, &ships).capture_ready);
}
