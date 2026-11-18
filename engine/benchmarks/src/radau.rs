//! Eight-stage Radau IIA collocation (formal order 15), solved by bounded
//! fixed-point iteration for these small non-stiff test systems. This is not
//! a port of IAS15: it does not claim IAS15's predictor or adaptive error rule.
use crate::integrators::Stats;
use crate::kernels::{ForceEvaluator, ForceMode};
use crate::model::{add, error, scale, Body};
use crate::radau_coefficients::{A, C};

pub fn advance(bodies: &mut [Body], steps: usize, dt: f64) -> Result<Stats, &'static str> {
    let mut stats = Stats::default();
    for _ in 0..steps {
        interval(bodies, dt, 0, &mut stats)?;
    }
    Ok(stats)
}

fn interval(
    bodies: &mut [Body],
    dt: f64,
    depth: u32,
    stats: &mut Stats,
) -> Result<(), &'static str> {
    let mut evaluator = ForceEvaluator::new(ForceMode::Direct, bodies);
    let mut initial_a = vec![[0.0; 3]; bodies.len()];
    evaluator.evaluate(bodies, &mut initial_a);
    let mut stages: [Vec<Body>; 8] = std::array::from_fn(|k| {
        bodies
            .iter()
            .zip(&initial_a)
            .map(|(body, a)| Body {
                p: add(body.p, scale(body.v, dt * C[k])),
                v: add(body.v, scale(*a, dt * C[k])),
                ..*body
            })
            .collect()
    });
    let mut accelerations: [Vec<[f64; 3]>; 8] =
        std::array::from_fn(|_| vec![[0.0; 3]; bodies.len()]);
    let mut next = stages.clone();
    let mut converged = false;
    for _ in 0..48 {
        stats.nonlinear_iterations += 1;
        for j in 0..8 {
            evaluator.evaluate(&stages[j], &mut accelerations[j]);
        }
        let mut change = 0.0_f64;
        for i in 0..8 {
            for body_index in 0..bodies.len() {
                let mut dp = [0.0; 3];
                let mut dv = [0.0; 3];
                for j in 0..8 {
                    dp = add(dp, scale(stages[j][body_index].v, A[i][j]));
                    dv = add(dv, scale(accelerations[j][body_index], A[i][j]));
                }
                next[i][body_index].p = add(bodies[body_index].p, scale(dp, dt));
                next[i][body_index].v = add(bodies[body_index].v, scale(dv, dt));
            }
            change = change.max(error(&next[i], &stages[i]).scaled_state_error);
        }
        std::mem::swap(&mut next, &mut stages);
        if change <= 8.0e-15 {
            converged = true;
            break;
        }
    }
    stats.force_evaluations += evaluator.evaluations;
    if converged {
        // Radau IIA is stiffly accurate: its last collocation state is y(n+1).
        bodies.copy_from_slice(&stages[7]);
        stats.accepted_steps += 1;
        return Ok(());
    }
    stats.rejected_steps += 1;
    if depth >= 6 || stats.force_evaluations > 100_000 {
        return Err("Radau nonlinear solve work budget exhausted");
    }
    interval(bodies, dt * 0.5, depth + 1, stats)?;
    interval(bodies, dt * 0.5, depth + 1, stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{fixture, norm, sub};

    #[test]
    fn radau_quadrature_integrates_degree_fourteen_and_rows_match_nodes() {
        for degree in 0..15 {
            let q: f64 = (0..8).map(|j| A[7][j] * C[j].powi(degree)).sum();
            assert!((q - 1.0 / f64::from(degree + 1)).abs() < 4.0e-15);
        }
        for i in 0..8 {
            assert!((A[i].iter().sum::<f64>() - C[i]).abs() < 2.0e-15);
        }
    }

    #[test]
    fn radau_matches_analytic_circular_orbit_over_one_radian() {
        let mut f = fixture("kepler").unwrap();
        let stats = advance(&mut f.bodies, 16, 1.0 / 16.0).unwrap();
        assert!(norm(sub(f.bodies[1].p, [1.0_f64.cos(), 1.0_f64.sin(), 0.0])) < 3.0e-13);
        assert!(norm(sub(f.bodies[1].v, [-1.0_f64.sin(), 1.0_f64.cos(), 0.0])) < 3.0e-13);
        assert_eq!(stats.accepted_steps, 16);
        assert_eq!(stats.rejected_steps, 0);
    }
}
