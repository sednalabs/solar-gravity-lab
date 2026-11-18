//! Orbital archive construction, prediction and bounded trail history.
//! Archive sampling and display queries are distinct from authoritative integration.
use super::*;

pub(super) fn vec_magnitude(vector: Vector3d) -> f64 {
    (vector.x * vector.x + vector.y * vector.y + vector.z * vector.z).sqrt()
}

pub(super) fn build_orbit_archives(
    active: &BranchState,
    query: &OrbitArchiveQuery,
    physics_policy: &PhysicsPolicy,
    active_cpu_features: &[String],
) -> Vec<OrbitArchive> {
    let selected_body_ids = orbit_archive_body_ids(query, &active.world.bodies);
    let mut archives = Vec::new();

    if query.include_trajectory {
        archives.extend(build_trajectory_archives(
            &selected_body_ids,
            &active.world.trail_history_by_body,
        ));
    }
    if query.include_historical_orbits {
        archives.extend(build_historical_orbit_archives(
            active,
            &selected_body_ids,
            query.checkpoint_sample_limit,
        ));
    }
    if query.include_prediction {
        archives.extend(build_prediction_archives(
            &active.world.bodies,
            active.world.epoch_seconds,
            &selected_body_ids,
            query.prediction_step_seconds,
            query.prediction_sample_count,
            physics_policy,
            active_cpu_features,
        ));
    }

    archives
}

fn orbit_archive_body_ids(query: &OrbitArchiveQuery, bodies: &[BodyState]) -> Vec<BodyId> {
    let mut body_ids = if query.body_ids.is_empty() {
        bodies
            .iter()
            .map(|body| body.body_id.clone())
            .collect::<Vec<_>>()
    } else {
        let available = bodies
            .iter()
            .map(|body| body.body_id.clone())
            .collect::<HashSet<_>>();
        query
            .body_ids
            .iter()
            .filter(|body_id| available.contains(*body_id))
            .cloned()
            .collect::<Vec<_>>()
    };
    body_ids.sort_by(|left, right| left.0.cmp(&right.0));
    body_ids
}

pub(super) fn render_scene_orbit_body_ids(
    bodies: &[BodyState],
    focused_body_id: Option<&BodyId>,
) -> Vec<BodyId> {
    if bodies.len() <= RENDER_SCENE_ORBIT_BODY_LIMIT {
        return orbit_archive_body_ids(&OrbitArchiveQuery::default(), bodies);
    }

    let mut ranked = bodies.iter().collect::<Vec<_>>();
    ranked.sort_by(|left, right| {
        let left_is_focused = focused_body_id == Some(&left.body_id);
        let right_is_focused = focused_body_id == Some(&right.body_id);

        right_is_focused
            .cmp(&left_is_focused)
            .then_with(|| {
                body_class_priority(&left.body_class).cmp(&body_class_priority(&right.body_class))
            })
            .then_with(|| {
                right
                    .mass_kg
                    .partial_cmp(&left.mass_kg)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| left.body_id.0.cmp(&right.body_id.0))
    });

    let mut body_ids = ranked
        .into_iter()
        .take(RENDER_SCENE_ORBIT_BODY_LIMIT)
        .map(|body| body.body_id.clone())
        .collect::<Vec<_>>();
    body_ids.sort_by(|left, right| left.0.cmp(&right.0));
    body_ids
}

fn body_class_priority(body_class: &BodyClass) -> u8 {
    match body_class {
        BodyClass::Star => 0,
        BodyClass::Planet => 1,
        BodyClass::DwarfPlanet => 2,
        BodyClass::Moon => 3,
        BodyClass::SmallBody => 4,
        BodyClass::Comet => 5,
        BodyClass::Spacecraft => 6,
        BodyClass::Tracer => 7,
        BodyClass::Custom => 8,
    }
}

fn build_trajectory_archives(
    body_ids: &[BodyId],
    trail_history_by_body: &HashMap<BodyId, Vec<TrailSample>>,
) -> Vec<OrbitArchive> {
    body_ids
        .iter()
        .filter_map(|body_id| {
            let samples = trail_history_by_body
                .get(body_id)?
                .iter()
                .map(|sample| OrbitSample {
                    epoch_seconds: sample.epoch_seconds,
                    position_m: sample.position_m,
                })
                .collect::<Vec<_>>();
            if samples.is_empty() {
                return None;
            }
            Some(OrbitArchive {
                archive_id: format!("trajectory:{}", body_id.0),
                source_body_id: body_id.clone(),
                family: OrbitArchiveFamily::Trajectory,
                max_samples: TRAIL_HISTORY_MAX_SAMPLES as u32,
                samples,
            })
        })
        .collect()
}

fn build_historical_orbit_archives(
    active: &BranchState,
    body_ids: &[BodyId],
    checkpoint_sample_limit: usize,
) -> Vec<OrbitArchive> {
    let start_index = active
        .checkpoints
        .len()
        .saturating_sub(checkpoint_sample_limit);
    let checkpoint_records = &active.checkpoints[start_index..];

    body_ids
        .iter()
        .filter_map(|body_id| {
            let mut samples = checkpoint_records
                .iter()
                .filter_map(|record| {
                    record
                        .state
                        .bodies
                        .iter()
                        .find(|body| body.body_id == *body_id)
                        .map(|body| OrbitSample {
                            epoch_seconds: record.descriptor.epoch_seconds,
                            position_m: body.position_m,
                        })
                })
                .collect::<Vec<_>>();

            if let Some(current_body) = active
                .world
                .bodies
                .iter()
                .find(|body| body.body_id == *body_id)
            {
                let current_sample = OrbitSample {
                    epoch_seconds: active.world.epoch_seconds,
                    position_m: current_body.position_m,
                };
                let should_append_current = samples
                    .last()
                    .map(|sample| {
                        sample.epoch_seconds != current_sample.epoch_seconds
                            || sample.position_m != current_sample.position_m
                    })
                    .unwrap_or(true);
                if should_append_current {
                    samples.push(current_sample);
                }
            }

            if samples.len() < 2 {
                return None;
            }

            Some(OrbitArchive {
                archive_id: format!("archive:{}:{}", active.descriptor.branch_id.0, body_id.0),
                source_body_id: body_id.clone(),
                family: OrbitArchiveFamily::HistoricalOrbit,
                max_samples: samples.len() as u32,
                samples,
            })
        })
        .collect()
}

// Select the greatest representable epoch no later than the requested bound.
pub(super) fn representable_epoch_within(epoch: f64, allowance: f64) -> Option<f64> {
    if !epoch.is_finite() || !allowance.is_finite() || allowance <= 0.0 {
        return None;
    }
    let mut next = epoch + allowance;
    if next.is_infinite() {
        next = f64::MAX;
    }
    if next - epoch > allowance {
        next = if next > 0.0 {
            f64::from_bits(next.to_bits() - 1)
        } else if next < 0.0 {
            f64::from_bits(next.to_bits() + 1)
        } else {
            -f64::from_bits(1)
        };
    }
    let delta = next - epoch;
    if !delta.is_finite() || delta <= 0.0 || delta > allowance || epoch + delta != next {
        return None;
    }
    Some(next)
}

// A positive delta can be absorbed by floating-point rounding at large epochs.
pub(super) fn next_epoch(epoch_seconds: f64, delta_seconds: f64) -> Option<f64> {
    let next = epoch_seconds + delta_seconds;
    (epoch_seconds.is_finite() && next.is_finite() && next > epoch_seconds).then_some(next)
}

pub(super) fn build_prediction_archives(
    bodies: &[BodyState],
    starting_epoch_seconds: f64,
    body_ids: &[BodyId],
    prediction_step_seconds: f64,
    prediction_sample_count: usize,
    physics_policy: &PhysicsPolicy,
    active_cpu_features: &[String],
) -> Vec<OrbitArchive> {
    if checked_substep_count(physics_policy, prediction_step_seconds).is_none()
        || prediction_sample_count == 0
        || body_ids.is_empty()
    {
        return Vec::new();
    }

    let tracked_body_ids = body_ids.iter().cloned().collect::<HashSet<_>>();
    let mut projected_bodies = bodies.to_vec();
    let mut solver_bodies = world_bodies_to_solver_state(&projected_bodies);
    let mut epoch_seconds = starting_epoch_seconds;
    let mut samples_by_body = body_ids
        .iter()
        .cloned()
        .map(|body_id| (body_id, Vec::with_capacity(prediction_sample_count)))
        .collect::<HashMap<_, _>>();

    for _ in 0..prediction_sample_count {
        let Some(next_epoch_seconds) = next_epoch(epoch_seconds, prediction_step_seconds) else {
            return Vec::new();
        };
        let _ = advance_authoritative_with_features(
            physics_policy,
            &mut solver_bodies,
            prediction_step_seconds,
            active_cpu_features,
        );
        apply_solver_state_to_world_bodies(&mut projected_bodies, &solver_bodies);
        epoch_seconds = next_epoch_seconds;

        for body in projected_bodies
            .iter()
            .filter(|body| tracked_body_ids.contains(&body.body_id))
        {
            samples_by_body
                .entry(body.body_id.clone())
                .or_default()
                .push(OrbitSample {
                    epoch_seconds,
                    position_m: body.position_m,
                });
        }
    }

    body_ids
        .iter()
        .filter_map(|body_id| {
            let samples = samples_by_body.remove(body_id)?;
            if samples.is_empty() {
                return None;
            }
            Some(OrbitArchive {
                archive_id: format!("prediction:{}", body_id.0),
                source_body_id: body_id.clone(),
                family: OrbitArchiveFamily::Prediction,
                max_samples: prediction_sample_count as u32,
                samples,
            })
        })
        .collect()
}

pub(super) fn push_trail_sample(
    trail_history_by_body: &mut HashMap<BodyId, Vec<TrailSample>>,
    body_id: &BodyId,
    epoch_seconds: f64,
    sample: Vector3d,
) {
    let history = trail_history_by_body.entry(body_id.clone()).or_default();
    history.push(TrailSample {
        epoch_seconds,
        position_m: sample,
    });
    if history.len() > TRAIL_HISTORY_MAX_SAMPLES {
        let trim_count = history.len() - TRAIL_HISTORY_MAX_SAMPLES;
        history.drain(0..trim_count);
    }
}

pub(super) fn record_trail_samples_from_bodies(
    bodies: &[BodyState],
    trail_history_by_body: &mut HashMap<BodyId, Vec<TrailSample>>,
    epoch_seconds: f64,
) {
    for body in bodies {
        push_trail_sample(
            trail_history_by_body,
            &body.body_id,
            epoch_seconds,
            body.position_m,
        );
    }
}
