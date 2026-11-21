//! Isolated numerical experiments; none is selected by the production runtime.
// Conventional r/v/mu/c/s variables follow the equations; all integer-to-FP64
// casts are bounded step/fixture counts, far below the exact-integer limit.
#![allow(clippy::many_single_char_names, clippy::cast_precision_loss)]
use crate::kernels::{acceleration_and_jerk, ForceEvaluator, ForceMode};
use crate::model::{add, dot, error, norm, scale, sub, Body, MIN_R2, V3};

#[derive(Default, serde::Serialize)]
pub struct Stats {
    pub force_evaluations: u64,
    pub accepted_steps: u64,
    pub rejected_steps: u64,
    pub nonlinear_iterations: u64,
    pub passive_force_refreshes: u64,
    pub passive_extrapolations: u64,
    pub segment_workers_started: Option<usize>,
    pub segment_workers_joined: Option<usize>,
    pub source_snapshot_copies: Option<u64>,
}

pub fn persistent_leapfrog(
    bodies: &mut [Body],
    steps: usize,
    dt: f64,
    workers: usize,
) -> Result<Stats, &'static str> {
    let mut pool = crate::workers::SegmentPool::new(bodies, workers)?;
    let started = pool.worker_count();
    let mut a = vec![[0.0; 3]; bodies.len()];
    pool.evaluate(bodies, &mut a)?;
    for _ in 0..steps {
        for (body, acceleration) in bodies.iter_mut().zip(&a) {
            body.v = add(body.v, scale(*acceleration, 0.5 * dt));
            body.p = add(body.p, scale(body.v, dt));
        }
        pool.evaluate(bodies, &mut a)?;
        for (body, acceleration) in bodies.iter_mut().zip(&a) {
            body.v = add(body.v, scale(*acceleration, 0.5 * dt));
        }
    }
    let evaluations = pool.evaluations;
    // Join belongs inside the measured integration, not a detached background task.
    let joined = pool.finish()?;
    Ok(Stats {
        force_evaluations: evaluations,
        accepted_steps: steps as u64,
        segment_workers_started: Some(started),
        segment_workers_joined: Some(joined),
        source_snapshot_copies: Some(evaluations),
        ..Stats::default()
    })
}

// Deliberate FP32 state rounding is confined to the forecast candidate.
#[allow(clippy::cast_possible_truncation)]
pub fn leapfrog(bodies: &mut [Body], mode: ForceMode, steps: usize, dt: f64) -> Stats {
    let mut evaluator = ForceEvaluator::new(mode, bodies);
    let mut a = vec![[0.0; 3]; bodies.len()];
    evaluator.evaluate(bodies, &mut a);
    for _ in 0..steps {
        kdk_step(bodies, &mut evaluator, &mut a, dt);
        if mode == ForceMode::Float32 {
            for body in bodies.iter_mut().filter(|b| b.mu == 0.0) {
                body.p = body.p.map(|v| f64::from(v as f32));
                body.v = body.v.map(|v| f64::from(v as f32));
            }
            evaluator.evaluate(bodies, &mut a);
        }
    }
    Stats {
        force_evaluations: evaluator.evaluations,
        accepted_steps: steps as u64,
        ..Stats::default()
    }
}

fn kdk_step(bodies: &mut [Body], evaluator: &mut ForceEvaluator, a: &mut [V3], dt: f64) {
    for (body, acceleration) in bodies.iter_mut().zip(a.iter()) {
        body.v = add(body.v, scale(*acceleration, 0.5 * dt));
        body.p = add(body.p, scale(body.v, dt));
    }
    evaluator.evaluate(bodies, a);
    for (body, acceleration) in bodies.iter_mut().zip(a.iter()) {
        body.v = add(body.v, scale(*acceleration, 0.5 * dt));
    }
}

pub fn yoshida4(bodies: &mut [Body], steps: usize, dt: f64) -> Stats {
    let c = 2.0_f64.cbrt();
    let outer = 1.0 / (2.0 - c);
    let middle = -c / (2.0 - c);
    let mut evaluator = ForceEvaluator::new(ForceMode::Direct, bodies);
    let mut a = vec![[0.0; 3]; bodies.len()];
    evaluator.evaluate(bodies, &mut a);
    for _ in 0..steps {
        for coefficient in [outer, middle, outer] {
            kdk_step(bodies, &mut evaluator, &mut a, dt * coefficient);
        }
    }
    Stats {
        force_evaluations: evaluator.evaluations,
        accepted_steps: steps as u64,
        ..Stats::default()
    }
}

pub fn rk4(bodies: &mut [Body], mode: ForceMode, steps: usize, dt: f64) -> Stats {
    let mut evaluator = ForceEvaluator::new(mode, bodies);
    let mut a = vec![[0.0; 3]; bodies.len()];
    let mut stages: [Vec<Body>; 3] = std::array::from_fn(|_| bodies.to_vec());
    let mut dv: [Vec<V3>; 4] = std::array::from_fn(|_| vec![[0.0; 3]; bodies.len()]);
    for _ in 0..steps {
        evaluator.evaluate(bodies, &mut a);
        dv[0].copy_from_slice(&a);
        for i in 0..bodies.len() {
            stages[0][i].p = add(bodies[i].p, scale(bodies[i].v, 0.5 * dt));
            stages[0][i].v = add(bodies[i].v, scale(dv[0][i], 0.5 * dt));
        }
        evaluator.evaluate(&stages[0], &mut a);
        dv[1].copy_from_slice(&a);
        for i in 0..bodies.len() {
            stages[1][i].p = add(bodies[i].p, scale(stages[0][i].v, 0.5 * dt));
            stages[1][i].v = add(bodies[i].v, scale(dv[1][i], 0.5 * dt));
        }
        evaluator.evaluate(&stages[1], &mut a);
        dv[2].copy_from_slice(&a);
        for i in 0..bodies.len() {
            stages[2][i].p = add(bodies[i].p, scale(stages[1][i].v, dt));
            stages[2][i].v = add(bodies[i].v, scale(dv[2][i], dt));
        }
        evaluator.evaluate(&stages[2], &mut a);
        dv[3].copy_from_slice(&a);
        for i in 0..bodies.len() {
            let dp = add(
                add(bodies[i].v, scale(stages[0][i].v, 2.0)),
                add(scale(stages[1][i].v, 2.0), stages[2][i].v),
            );
            let da = add(
                add(dv[0][i], scale(dv[1][i], 2.0)),
                add(scale(dv[2][i], 2.0), dv[3][i]),
            );
            bodies[i].p = add(bodies[i].p, scale(dp, dt / 6.0));
            bodies[i].v = add(bodies[i].v, scale(da, dt / 6.0));
        }
    }
    Stats {
        force_evaluations: evaluator.evaluations,
        accepted_steps: steps as u64,
        ..Stats::default()
    }
}

pub fn adaptive_doubling(
    bodies: &mut [Body],
    steps: usize,
    dt: f64,
) -> Result<Stats, &'static str> {
    let mut stats = Stats::default();
    for _ in 0..steps {
        adaptive_interval(bodies, dt, 0, &mut stats)?;
    }
    Ok(stats)
}

fn adaptive_interval(
    bodies: &mut [Body],
    dt: f64,
    depth: u32,
    stats: &mut Stats,
) -> Result<(), &'static str> {
    let mut coarse = bodies.to_vec();
    let mut fine = bodies.to_vec();
    stats.force_evaluations += leapfrog(&mut coarse, ForceMode::Direct, 1, dt).force_evaluations;
    stats.force_evaluations +=
        leapfrog(&mut fine, ForceMode::Direct, 2, dt * 0.5).force_evaluations;
    if error(&coarse, &fine).scaled_state_error / 3.0 <= 1.0e-10 {
        bodies.copy_from_slice(&fine);
        stats.accepted_steps += 2;
        return Ok(());
    }
    stats.rejected_steps += 1;
    if depth >= 10 || stats.force_evaluations > 100_000 {
        return Err("adaptive work budget exhausted");
    }
    adaptive_interval(bodies, dt * 0.5, depth + 1, stats)?;
    adaptive_interval(bodies, dt * 0.5, depth + 1, stats)
}

pub fn close_refinement(bodies: &mut [Body], steps: usize, dt: f64) -> Result<Stats, &'static str> {
    let mut stats = Stats::default();
    for _ in 0..steps {
        let mut allowed_dt = dt;
        for (i, body) in bodies.iter().enumerate() {
            for (j, other) in bodies.iter().enumerate() {
                if i == j || other.mu == 0.0 {
                    continue;
                }
                let r = norm(sub(body.p, other.p)).max(MIN_R2.sqrt());
                let orbital_dt = 0.005 * (r * r * r / (body.mu + other.mu)).sqrt();
                let crossing_dt = 0.005 * r / norm(sub(body.v, other.v)).max(1.0e-30);
                allowed_dt = allowed_dt.min(orbital_dt).min(crossing_dt);
            }
        }
        let mut subdivisions = 1;
        while dt / subdivisions as f64 > allowed_dt && subdivisions < 128 {
            subdivisions *= 2;
        }
        if dt / subdivisions as f64 > allowed_dt {
            return Err("encounter refinement work budget exhausted");
        }
        let local = leapfrog(
            bodies,
            ForceMode::Direct,
            subdivisions,
            dt / subdivisions as f64,
        );
        stats.force_evaluations += local.force_evaluations;
        stats.accepted_steps += local.accepted_steps;
    }
    Ok(stats)
}

pub fn kepler_drift(bodies: &mut [Body], steps: usize, dt: f64) -> Result<Stats, &'static str> {
    if bodies.first().is_none_or(|b| b.mu <= 0.0) || bodies[1..].iter().any(|b| b.mu != 0.0) {
        return Err("Kepler drift requires one primary followed by massless probes");
    }
    let mu = bodies[0].mu;
    let mut iterations = 0;
    for _ in 0..steps {
        let primary = bodies[0];
        for body in &mut bodies[1..] {
            let (r, v, used) =
                universal_kepler(sub(body.p, primary.p), sub(body.v, primary.v), mu, dt)?;
            body.p = add(add(primary.p, scale(primary.v, dt)), r);
            body.v = add(primary.v, v);
            iterations += used;
        }
        bodies[0].p = add(primary.p, scale(primary.v, dt));
    }
    Ok(Stats {
        accepted_steps: steps as u64,
        nonlinear_iterations: iterations,
        ..Stats::default()
    })
}

fn stumpff(z: f64) -> (f64, f64) {
    if z.abs() < 0.1 {
        let mut c = 0.5;
        let mut s = 1.0 / 6.0;
        let mut tc = c;
        let mut ts = s;
        for k in 1..12 {
            tc *= -z / f64::from((2 * k + 1) * (2 * k + 2));
            ts *= -z / f64::from((2 * k + 2) * (2 * k + 3));
            c += tc;
            s += ts;
        }
        (c, s)
    } else if z > 0.0 {
        let root = z.sqrt();
        ((1.0 - root.cos()) / z, (root - root.sin()) / (root * z))
    } else {
        let root = (-z).sqrt();
        ((root.cosh() - 1.0) / -z, (root.sinh() - root) / (root * -z))
    }
}

fn universal_kepler(r: V3, v: V3, mu: f64, dt: f64) -> Result<(V3, V3, u64), &'static str> {
    let radius = norm(r);
    if radius <= MIN_R2.sqrt() || !radius.is_finite() {
        return Err("Kepler radius outside unsoftened domain");
    }
    let sqrt_mu = mu.sqrt();
    let alpha = 2.0 / radius - dot(v, v) / mu;
    let radial = dot(r, v) / sqrt_mu;
    let mut chi = if alpha.abs() > 1.0e-8 {
        sqrt_mu * alpha.abs() * dt
    } else {
        sqrt_mu * dt / radius
    };
    let mut used = 0;
    let mut converged = false;
    for _ in 0..48 {
        used += 1;
        let (c, s) = stumpff(alpha * chi * chi);
        let f =
            radial * chi * chi * c + (1.0 - alpha * radius) * chi * chi * chi * s + radius * chi
                - sqrt_mu * dt;
        let derivative = radial * chi * (1.0 - alpha * chi * chi * s)
            + (1.0 - alpha * radius) * chi * chi * c
            + radius;
        let update = f / derivative;
        chi -= update;
        if !chi.is_finite() {
            return Err("Kepler solve diverged");
        }
        if update.abs() <= 4.0e-15 * chi.abs().max(1.0) {
            converged = true;
            break;
        }
    }
    if !converged {
        return Err("Kepler solve iteration budget exhausted");
    }
    let (c, s) = stumpff(alpha * chi * chi);
    let f = 1.0 - chi * chi * c / radius;
    let g = dt - chi * chi * chi * s / sqrt_mu;
    let next_r = add(scale(r, f), scale(v, g));
    let next_radius = norm(next_r);
    let fdot = sqrt_mu * (alpha * chi * chi * chi * s - chi) / (next_radius * radius);
    let gdot = 1.0 - chi * chi * c / next_radius;
    Ok((next_r, add(scale(r, fdot), scale(v, gdot)), used))
}

pub fn passive_extrapolate(bodies: &mut [Body], steps: usize, dt: f64, interval: usize) -> Stats {
    let mut stats = Stats::default();
    let mut cached_a = vec![[0.0; 3]; bodies.len()];
    let mut cached_j = cached_a.clone();
    let mut a = cached_a.clone();
    for step in 0..steps {
        if step % interval == 0 {
            for (i, body) in bodies.iter().enumerate() {
                if body.mu == 0.0 {
                    (cached_a[i], cached_j[i]) = acceleration_and_jerk(bodies, i);
                    stats.passive_force_refreshes += 1;
                }
            }
        }
        let epoch_time = (step % interval) as f64 * dt;
        for i in 0..bodies.len() {
            if bodies[i].mu > 0.0 {
                a[i] = acceleration_and_jerk(bodies, i).0;
            } else {
                a[i] = add(cached_a[i], scale(cached_j[i], epoch_time));
                stats.passive_extrapolations += 1;
            }
        }
        for (body, acceleration) in bodies.iter_mut().zip(&a) {
            body.v = add(body.v, scale(*acceleration, 0.5 * dt));
            body.p = add(body.p, scale(body.v, dt));
        }
        for i in 0..bodies.len() {
            if bodies[i].mu > 0.0 {
                a[i] = acceleration_and_jerk(bodies, i).0;
            } else {
                a[i] = add(cached_a[i], scale(cached_j[i], epoch_time + dt));
                stats.passive_extrapolations += 1;
            }
        }
        for (body, acceleration) in bodies.iter_mut().zip(&a) {
            body.v = add(body.v, scale(*acceleration, 0.5 * dt));
        }
        stats.accepted_steps += 1;
    }
    stats
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::fixture;

    fn circle_error(bodies: &[Body], time: f64) -> f64 {
        norm(sub(bodies[1].p, [time.cos(), time.sin(), 0.0]))
            + norm(sub(bodies[1].v, [-time.sin(), time.cos(), 0.0]))
    }

    #[test]
    fn kdk_and_yoshida_have_second_and_fourth_order_convergence() {
        let initial = fixture("kepler").unwrap().bodies;
        let mut coarse = initial.clone();
        let mut fine = initial.clone();
        leapfrog(&mut coarse, ForceMode::Direct, 16, 1.0 / 16.0);
        leapfrog(&mut fine, ForceMode::Direct, 32, 1.0 / 32.0);
        let ratio = circle_error(&coarse, 1.0) / circle_error(&fine, 1.0);
        assert!(ratio > 3.8 && ratio < 4.2, "{ratio}");
        let mut high_coarse = initial.clone();
        let mut high_fine = initial;
        yoshida4(&mut high_coarse, 16, 1.0 / 16.0);
        yoshida4(&mut high_fine, 32, 1.0 / 32.0);
        let ratio = circle_error(&high_coarse, 1.0) / circle_error(&high_fine, 1.0);
        assert!(ratio > 14.0 && ratio < 18.0, "{ratio}");
        assert!(circle_error(&high_coarse, 1.0) < circle_error(&coarse, 1.0));
    }

    #[test]
    fn universal_kepler_reverses_elliptic_parabolic_and_hyperbolic_steps() {
        for velocity in [0.5, 1.0, 2.0_f64.sqrt(), 2.0] {
            let initial_r = [1.0, 0.0, 0.0];
            let initial_v = [0.0, velocity, 0.0];
            let (r, v, _) = universal_kepler(initial_r, initial_v, 1.0, 0.3).unwrap();
            let (rb, vb, _) = universal_kepler(r, v, 1.0, -0.3).unwrap();
            assert!(norm(sub(rb, initial_r)) < 2.0e-13);
            assert!(norm(sub(vb, initial_v)) < 2.0e-13);
            assert!(
                (0.5 * dot(initial_v, initial_v) - 1.0 - 0.5 * dot(v, v) + 1.0 / norm(r)).abs()
                    < 2.0e-13
            );
        }
    }

    #[test]
    fn exact_kepler_matches_circle_without_force_evaluations() {
        let mut f = fixture("kepler").unwrap();
        let stats = kepler_drift(&mut f.bodies, 64, 1.0 / 64.0).unwrap();
        assert!(circle_error(&f.bodies, 1.0) < 2.0e-13);
        assert_eq!(stats.force_evaluations, 0);
    }

    #[test]
    fn adaptive_control_refines_and_improves_phase() {
        let mut adaptive = fixture("kepler").unwrap().bodies;
        let mut coarse = adaptive.clone();
        let stats = adaptive_doubling(&mut adaptive, 8, 1.0 / 8.0).unwrap();
        leapfrog(&mut coarse, ForceMode::Direct, 8, 1.0 / 8.0);
        assert!(stats.accepted_steps > 8 && stats.rejected_steps > 0);
        assert!(circle_error(&adaptive, 1.0) < 5.0e-7);
        assert!(circle_error(&adaptive, 1.0) < circle_error(&coarse, 1.0));
    }

    #[test]
    fn encounter_time_criterion_subdivides_coarse_segments() {
        let mut f = fixture("close-encounter").unwrap();
        let stats = close_refinement(&mut f.bodies, 2, 1.0 / 64.0).unwrap();
        assert!(stats.accepted_steps > 2);
    }

    #[test]
    fn passive_forecast_keeps_massive_trajectories_bitwise_equal() {
        let f = fixture("sparse3").unwrap();
        let mut reference = f.bodies.clone();
        let mut mixed = f.bodies.clone();
        leapfrog(&mut reference, ForceMode::Direct, 8, f.dt);
        leapfrog(&mut mixed, ForceMode::Float32, 8, f.dt);
        for (a, b) in reference.iter().zip(&mixed) {
            if a.mu > 0.0 {
                assert_eq!(a, b);
            }
        }
    }
}
