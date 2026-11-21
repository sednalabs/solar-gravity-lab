use solarlab_domain::{
    AttitudeMode, BodyId, ContactPart, ContactShape, DockingPortGeometry, Quaterniond,
    SpacecraftAssembly, SpacecraftContactGeometry, SpacecraftDockingState, SpacecraftState,
    Vector3d,
};
use solarlab_physics::spacecraft::{advance_spacecraft_steps_checked, SpacecraftBinding};
use solarlab_physics::{
    fixed_step_plan, CollisionModel, IntegratorKind, MassiveBodyState, PhysicsPolicy, SolverBackend,
};
fn v(x: f64, y: f64, z: f64) -> Vector3d {
    Vector3d { x, y, z }
}
fn ship(id: &str, target: Option<&str>) -> SpacecraftState {
    let mut s = SpacecraftState::reference(BodyId(id.into()), None);
    s.dry_mass_kg = 1000.0;
    s.propellant_mass_kg = 0.0;
    s.engines.clear();
    s.inertia_kg_m2 = v(400.0, 400.0, 400.0);
    s.max_control_torque_nm = Vector3d::default();
    s.control.attitude_mode = AttitudeMode::Manual;
    s.docking = target.map(|id| SpacecraftDockingState::new(BodyId(id.into())));
    s.authored = Some(SpacecraftAssembly {
        definition_id: "sphere".into(),
        display_name: "sphere".into(),
        visual_asset: None,
        contact: Some(SpacecraftContactGeometry {
            hull: vec![ContactPart {
                position_body_m: Vector3d::default(),
                attitude_body: Quaterniond::IDENTITY,
                shape: ContactShape::Sphere { radius_m: 1.0 },
            }],
            port: DockingPortGeometry {
                position_body_m: v(0.0, 0.0, 2.0),
                attitude_body: Quaterniond::IDENTITY,
                inner_radius_m: 0.3,
                outer_radius_m: 0.4,
                depth_m: 0.2,
            },
        }),
    });
    s
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
        body_radius_m: 3.0,
        epoch_seconds: 0.0,
        epoch_compensation_seconds: 0.0,
        state,
    }
}

fn kinetic_energy(bodies: &[MassiveBodyState], ships: &[SpacecraftBinding]) -> f64 {
    ships
        .iter()
        .map(|ship| {
            let v = bodies[ship.body_index].velocity_mps;
            let w = ship.state.angular_velocity_body_radps;
            let i = ship.state.inertia_kg_m2;
            0.5 * (ship.state.mass_kg() * (v.x * v.x + v.y * v.y + v.z * v.z)
                + i.x * w.x * w.x
                + i.y * w.y * w.y
                + i.z * w.z * w.z)
        })
        .sum()
}
fn fast_crossing(dt: f64, boost: Vector3d, origin: f64, reverse: bool) -> Vec<MassiveBodyState> {
    let mut ships = vec![
        binding(0, ship("a", Some("a-target"))),
        binding(1, ship("a-target", None)),
        binding(2, ship("b", Some("b-target"))),
        binding(3, ship("b-target", None)),
    ];
    let mut bodies = vec![
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: v(-20.0, 0.0, 0.0),
            velocity_mps: v(4000.0, 0.0, 0.0),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: v(-20.0, 200.0, 0.0),
            velocity_mps: Vector3d::default(),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: v(20.0, 0.0, 0.0),
            velocity_mps: v(-4000.0, 0.0, 0.0),
        },
        MassiveBodyState {
            mass_kg: 0.0,
            position_m: v(20.0, -200.0, 0.0),
            velocity_mps: Vector3d::default(),
        },
    ];
    for body in &mut bodies {
        body.position_m.x += origin;
        body.velocity_mps = solarlab_domain::spacecraft::add(body.velocity_mps, boost);
    }
    if reverse {
        ships.reverse();
    }
    let initial_energy = kinetic_energy(&bodies, &ships);
    let plan = fixed_step_plan(
        &PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: dt,
        },
        0.01,
        4,
        &[],
    )
    .unwrap();
    advance_spacecraft_steps_checked(&plan, &mut bodies, &mut ships, plan.total_steps).unwrap();
    assert!(kinetic_energy(&bodies, &ships) <= initial_energy * (1.0 + 1e-12));
    for body in &mut bodies {
        body.position_m.x -= origin;
        body.position_m = solarlab_domain::spacecraft::sub(
            body.position_m,
            solarlab_domain::spacecraft::scale(boost, 0.01),
        );
        body.velocity_mps = solarlab_domain::spacecraft::sub(body.velocity_mps, boost);
    }
    eprintln!(
        "A x={} vx={}; B x={} vx={}",
        bodies[0].position_m.x,
        bodies[0].velocity_mps.x,
        bodies[2].position_m.x,
        bodies[2].velocity_mps.x
    );
    assert!(
        bodies[0].position_m.x < bodies[2].position_m.x,
        "head-on cross-pair collision tunnelled"
    );
    bodies
}

#[test]
fn merged_crossing_matches_impact_time_restitution_and_common_boost() {
    for dt in [0.01, 0.005, 0.0025, 0.00125] {
        let plain = fast_crossing(dt, Vector3d::default(), 0.0, false);
        // Independent equal-mass hard-sphere oracle: contact at 4.75 ms,
        // configured restitution 0.1, then constant separating velocity.
        let contact_time = 38.0 / 8000.0;
        for (index, sign) in [(0, -1.0), (2, 1.0)] {
            let body = plain[index];
            let expected_position = sign * (1.0 + 400.0 * (0.01 - contact_time));
            let initial_position = sign * 20.0;
            let initial_velocity = -sign * 4000.0;
            let inferred_contact_time =
                (body.position_m.x - initial_position - body.velocity_mps.x * 0.01)
                    / (initial_velocity - body.velocity_mps.x);
            eprintln!(
                "dt={dt}: endpoint error={}m, restitution speed={}, impact-time error={}s",
                (body.position_m.x - expected_position).abs(),
                body.velocity_mps.x,
                (inferred_contact_time - contact_time).abs()
            );
            assert!((body.velocity_mps.x - sign * 400.0).abs() < 0.02);
            assert!((body.position_m.x - expected_position).abs() < 0.03);
            assert!((inferred_contact_time - contact_time).abs() < 5e-6);
        }
        assert!((plain[0].velocity_mps.x + plain[2].velocity_mps.x).abs() < 1e-7);
        let reordered = fast_crossing(dt, Vector3d::default(), 0.0, true);
        assert_eq!(
            plain, reordered,
            "spacecraft input order changed physical trajectory"
        );
        for origin in [0.0, 1.5e11] {
            let boosted = fast_crossing(dt, v(30_000.0, -5_000.0, 2_000.0), origin, false);
            for (a, b) in plain.iter().zip(&boosted) {
                let delta = solarlab_domain::spacecraft::sub(a.position_m, b.position_m);
                assert!(
                    delta.x.hypot(delta.y).hypot(delta.z) < 1e-4,
                    "common boost changed relative trajectory"
                );
                let delta = solarlab_domain::spacecraft::sub(a.velocity_mps, b.velocity_mps);
                assert!(
                    delta.x.hypot(delta.y).hypot(delta.z) < 1e-4,
                    "common boost changed contact impulse"
                );
            }
        }
    }
}

fn triplet_scene(triangle: bool) -> (Vec<MassiveBodyState>, Vec<SpacecraftBinding>) {
    let mut bodies = Vec::new();
    let mut ships = Vec::new();
    for i in 0..3 {
        let id = format!("ship-{i}");
        let target = format!("target-{i}");
        let (position, velocity) = if triangle {
            let angle = i as f64 * std::f64::consts::TAU / 3.0;
            (
                v(2.0 * angle.cos(), 2.0 * angle.sin(), 0.0),
                v(-100.0 * angle.cos(), -100.0 * angle.sin(), 0.0),
            )
        } else {
            let sign = i as f64 - 1.0;
            (v(3.0 * sign, 0.0, 0.0), v(-200.0 * sign, 0.0, 0.0))
        };
        ships.push(binding(bodies.len(), ship(&id, Some(&target))));
        bodies.push(MassiveBodyState {
            mass_kg: 0.0,
            position_m: position,
            velocity_mps: velocity,
        });
        ships.push(binding(bodies.len(), ship(&target, None)));
        bodies.push(MassiveBodyState {
            mass_kg: 0.0,
            position_m: v(1000.0, 1000.0 * (i as f64 + 1.0), 0.0),
            velocity_mps: Vector3d::default(),
        });
    }
    (bodies, ships)
}

fn run_triplet(
    triangle: bool,
    dt: f64,
    reverse: bool,
) -> Result<Vec<MassiveBodyState>, solarlab_physics::science::ScientificError> {
    let (mut bodies, mut ships) = triplet_scene(triangle);
    let initial_energy = kinetic_energy(&bodies, &ships);
    if reverse {
        ships.reverse();
    }
    let plan = fixed_step_plan(
        &PhysicsPolicy {
            solver_backend: SolverBackend::ReferenceScalar,
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: dt,
        },
        0.01,
        bodies.len(),
        &[],
    )
    .unwrap();
    if let Err(error) =
        advance_spacecraft_steps_checked(&plan, &mut bodies, &mut ships, plan.total_steps)
    {
        return Err(error);
    }
    let norm = |p: Vector3d| p.x.hypot(p.y).hypot(p.z);
    let mut momentum = Vector3d::default();
    let mut speed_squared = 0.0;
    for i in [0, 2, 4] {
        momentum.x += bodies[i].velocity_mps.x;
        momentum.y += bodies[i].velocity_mps.y;
        momentum.z += bodies[i].velocity_mps.z;
        speed_squared += norm(bodies[i].velocity_mps).powi(2);
        for j in [0, 2, 4].into_iter().filter(|j| *j > i) {
            let d = solarlab_domain::spacecraft::sub(bodies[i].position_m, bodies[j].position_m);
            assert!(
                norm(d) > 1.97,
                "sphere penetration: triangle={triangle} dt={dt} distance={}",
                norm(d)
            );
        }
    }
    assert!(
        norm(momentum) < 1e-6,
        "unbalanced contact momentum: {momentum:?}"
    );
    assert!(
        speed_squared <= if triangle { 30_000.001 } else { 80_000.001 },
        "contact increased translational energy"
    );
    assert!(
        kinetic_energy(&bodies, &ships) <= initial_energy * (1.0 + 1e-12),
        "contact increased total translational plus rotational energy"
    );
    eprintln!(
        "triplet triangle={triangle}, dt={dt}: {:?}",
        [0, 2, 4].map(|i| (bodies[i].position_m, bodies[i].velocity_mps))
    );
    Ok(bodies)
}

#[test]
fn simultaneous_contacts_preserve_conservation_and_deterministic_ordering() {
    for triangle in [false, true] {
        let mut accepted = Vec::new();
        for dt in [0.01, 0.005, 0.0025, 0.00125, 0.000625] {
            match run_triplet(triangle, dt, false) {
                Ok(bodies) => accepted.push((dt, bodies)),
                Err(error) => {
                    assert!(matches!(
                        error,
                        solarlab_physics::science::ScientificError::ContactResolutionLimit
                            | solarlab_physics::science::ScientificError::ContactMotionEnvelope
                    ));
                    eprintln!("explicit bounded rejection: triangle={triangle}, dt={dt}: {error}");
                }
            }
        }
        assert!(
            accepted.len() >= 2,
            "ordinary three-sphere contact needs a usable refinement envelope"
        );
        let fine = &accepted[accepted.len() - 1].1;
        let prior = &accepted[accepted.len() - 2].1;
        // A binary restitution coefficient does not define a unique physical
        // law for exactly simultaneous rigid impacts. Assert conservation and
        // bounded displacement, not a chosen symmetric impulse distribution.
        for i in [0, 2, 4] {
            let delta = solarlab_domain::spacecraft::sub(fine[i].position_m, prior[i].position_m);
            assert!(
                delta.x.hypot(delta.y).hypot(delta.z) < 0.03,
                "refined contact positions differ beyond 3 cm"
            );
        }
        let (dt, original) = &accepted[0];
        assert_eq!(
            *original,
            run_triplet(triangle, *dt, true).unwrap(),
            "input iteration order changed a simultaneous contact outcome"
        );
    }
}

#[test]
fn separated_three_body_impacts_match_independent_event_oracle() {
    for dt in [0.01, 0.005, 0.0025, 0.00125] {
        let (mut bodies, mut ships) = triplet_scene(false);
        for (i, x, speed) in [(0, -6.0, 200.0), (2, 0.0, 0.0), (4, 6.0, 0.0)] {
            bodies[i].position_m = v(x, 0.0, 0.0);
            bodies[i].velocity_mps = v(speed, 0.0, 0.0);
        }
        let plan = fixed_step_plan(
            &PhysicsPolicy {
                solver_backend: SolverBackend::ReferenceScalar,
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: dt,
            },
            0.06,
            bodies.len(),
            &[],
        )
        .unwrap();
        advance_spacecraft_steps_checked(&plan, &mut bodies, &mut ships, plan.total_steps).unwrap();
        // Independent event mechanics, not an engine substep reference:
        // A-B at .02s gives v=[90,110,0]; B-C at .02+4/110s
        // gives [90,49.5,60.5]. No third contact occurs before .06s.
        for (i, expected_x, expected_v) in [(0, 1.6, 90.0), (2, 4.18, 49.5), (4, 6.22, 60.5)] {
            let body = bodies[i];
            eprintln!(
                "sequential impacts dt={dt}, ship={i}: x={}, v={}",
                body.position_m.x, body.velocity_mps.x
            );
            assert!(
                (body.position_m.x - expected_x).abs() < 0.03,
                "nondegenerate collision timing failed"
            );
            assert!(
                (body.velocity_mps.x - expected_v).abs() < 0.02,
                "nondegenerate restitution failed"
            );
            assert!(body.position_m.y.abs() < 1e-9 && body.position_m.z.abs() < 1e-9);
        }
    }
}
