use super::*;

fn fixture(count: usize, sparse: bool) -> Vec<MassiveBodyState> {
    (0..count)
        .map(|index| MassiveBodyState {
            mass_kg: if sparse && index % 7 != 2 {
                0.0
            } else {
                1.0e20 + index as f64 * 1.0e17
            },
            position_m: Vector3d {
                x: index as f64 * 8.0e8,
                y: (index % 11) as f64 * 3.0e8,
                z: (index % 5) as f64 * -2.0e8,
            },
            velocity_mps: Vector3d {
                x: (index % 3) as f64 * 2.0,
                y: -1.0,
                z: 0.25,
            },
        })
        .collect()
}

// Frozen pre-optimization scalar loop: independent of the cached integration
// and source traversal, including the otherwise redundant zero-source pairs.
fn original_accelerations(bodies: &[MassiveBodyState]) -> Vec<Vector3d> {
    let mut accelerations = vec![Vector3d::default(); bodies.len()];
    for i in 0..bodies.len() {
        for j in i + 1..bodies.len() {
            let delta = subtract(bodies[j].position_m, bodies[i].position_m);
            let inv = norm_squared(delta).max(MIN_DISTANCE_M2).sqrt().recip();
            let inv_cubed = inv * inv * inv;
            accelerations[i] = add(
                accelerations[i],
                scale(delta, G_M3_PER_KG_S2 * bodies[j].mass_kg * inv_cubed),
            );
            accelerations[j] = add(
                accelerations[j],
                scale(delta, -G_M3_PER_KG_S2 * bodies[i].mass_kg * inv_cubed),
            );
        }
    }
    accelerations
}

fn original_step(bodies: &mut [MassiveBodyState], dt: f64) {
    let a0 = original_accelerations(bodies);
    for (body, acceleration) in bodies.iter_mut().zip(a0) {
        body.velocity_mps = add(body.velocity_mps, scale(acceleration, 0.5 * dt));
        body.position_m = add(body.position_m, scale(body.velocity_mps, dt));
    }
    let a1 = original_accelerations(bodies);
    for (body, acceleration) in bodies.iter_mut().zip(a1) {
        body.velocity_mps = add(body.velocity_mps, scale(acceleration, 0.5 * dt));
    }
}

#[test]
fn cached_sparse_kdk_preserves_original_state_for_dense_sparse_and_empty_worlds() {
    for count in [0, 1, 4, 17, 128] {
        for sparse in [false, true] {
            let initial = fixture(count, sparse);
            for steps in [0, 1, 2, 64, 129] {
                let mut expected = initial.clone();
                let mut actual = initial.clone();
                for _ in 0..steps {
                    original_step(&mut expected, 0.375);
                }
                integrate_fixed_steps(&mut actual, 0.375, steps, None);
                assert_eq!(
                    actual, expected,
                    "count={count}, sparse={sparse}, steps={steps}"
                );
            }
        }
    }
}

#[test]
fn sparse_traversal_visits_only_physical_pairs_in_original_order() {
    let mut bodies = fixture(1024, true);
    for body in &mut bodies {
        body.mass_kg = 0.0;
    }
    for i in [1, 413, 1023] {
        bodies[i].mass_kg = 1.0;
    }
    let sources = source_indices(&bodies);
    let mut actual = Vec::new();
    for i in 0..bodies.len() {
        interaction_partners(&bodies, &sources, i, i + 1, bodies.len(), |j| {
            actual.push((i, j))
        });
    }
    let expected: Vec<_> = (0..bodies.len())
        .flat_map(|i| (i + 1..bodies.len()).map(move |j| (i, j)))
        .filter(|&(i, j)| bodies[i].mass_kg != 0.0 || bodies[j].mass_kg != 0.0)
        .collect();
    assert_eq!(actual, expected);
    assert_eq!(actual.len(), 3 * 1021 + 3);
}

#[test]
fn cached_fma_schedule_preserves_uncached_kernel_results() {
    let mut kernels = vec![Arm64GravityKernel::PortableScalarOracle];
    if cfg!(target_arch = "aarch64") && arm64_neon_runtime_available() {
        kernels.extend([
            Arm64GravityKernel::NeonF64Pairwise,
            Arm64GravityKernel::NeonF64TiledPairwise,
            Arm64GravityKernel::NeonF64ParallelTiledPairwise,
        ]);
    }
    for kernel in kernels {
        let initial = fixture(128, true);
        let mut actual = initial.clone();
        let mut expected = initial;
        for _ in 0..9 {
            integrate_substep_arm64(&mut expected, 0.375, kernel);
        }
        integrate_fixed_steps(&mut actual, 0.375, 9, Some(kernel));
        assert_eq!(actual, expected, "kernel={kernel:?}");
    }
}

#[test]
fn scalar_single_step_reference_remains_available() {
    let mut actual = fixture(17, true);
    let mut expected = actual.clone();
    original_step(&mut expected, 0.125);
    integrate_substep(&mut actual, 0.125);
    assert_eq!(actual, expected);
}

#[test]
fn diagnostics_retain_small_momentum_and_barycenter_after_large_cancellation() {
    let bodies: Vec<_> = [1.0e16, 1.0, -1.0e16]
        .into_iter()
        .map(|value| MassiveBodyState {
            mass_kg: 1.0,
            position_m: Vector3d {
                x: value,
                y: 1.0,
                z: 0.0,
            },
            velocity_mps: Vector3d {
                x: value,
                y: 0.0,
                z: 0.0,
            },
        })
        .collect();
    let actual = compute_invariants(&bodies);
    assert_eq!(actual.linear_momentum_kg_mps.x, 1.0);
    assert_eq!(actual.angular_momentum_kg_m2ps.z, -1.0);
    assert_eq!(actual.barycenter_m.x, 1.0 / 3.0);
    let mut reversed = bodies;
    reversed.reverse();
    assert_eq!(compute_invariants(&reversed), actual);
}

#[test]
fn clipped_force_and_energy_gradient_agree_inside_and_outside_the_core() {
    let epsilon = MIN_DISTANCE_M2.sqrt();
    for fraction in [0.25, 0.75, 1.0, 1.5] {
        let radius = epsilon * fraction;
        let mut bodies = vec![
            MassiveBodyState {
                mass_kg: 2.0e4,
                position_m: Vector3d::default(),
                velocity_mps: Vector3d::default(),
            },
            MassiveBodyState {
                mass_kg: 3.0e4,
                position_m: Vector3d {
                    x: radius,
                    y: 0.0,
                    z: 0.0,
                },
                velocity_mps: Vector3d::default(),
            },
        ];
        let force = original_accelerations(&bodies)[0].x * bodies[0].mass_kg;
        let offset = epsilon * 1.0e-6;
        bodies[1].position_m.x = radius + offset;
        let upper = compute_invariants(&bodies).total_energy_j;
        bodies[1].position_m.x = radius - offset;
        let lower = compute_invariants(&bodies).total_energy_j;
        let energy_gradient = (upper - lower) / (2.0 * offset);
        assert!(
            ((energy_gradient - force) / force).abs() < 1.0e-6,
            "radius={radius}: gradient={energy_gradient}, force={force}"
        );
    }
}

#[test]
fn nonperturbing_particles_do_not_change_source_invariants() {
    let bodies = fixture(5, false);
    let expected = compute_invariants(&bodies);
    let mut with_tracers = bodies;
    let mut tracers = fixture(1024, true);
    for body in &mut tracers {
        body.mass_kg = 0.0;
    }
    with_tracers.extend(tracers);
    assert_eq!(compute_invariants(&with_tracers), expected);
}
