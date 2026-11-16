//! Draft probes are propagated on owned copies, never inserted into live history.
//! Commit uses a captured ordinal and an atomic checkpoint+spawn transaction;
//! undo forks from that checkpoint and retains the abandoned future.
use super::*;

const PREVIEW_INTERVALS: usize = 96;
const MAX_PREVIEW_STEPS: usize = 4096;
const MAX_PREVIEW_BODIES: usize = 64;
const MAX_PREVIEW_HORIZON_SECONDS: f64 = 43_200.0;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProbeAuthoringError {
    NotPaused,
    Busy,
    StalePreview,
    InvalidCandidate(&'static str),
    InvalidReference,
    InvalidHorizon,
    WorkLimit,
    NumericalRange,
    StaleUndo,
    HistoryLimit,
    TransactionFailed,
}

impl std::fmt::Display for ProbeAuthoringError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::NotPaused => "Pause the experiment before placing a probe",
            Self::Busy => "Finish or cancel the current time advance first",
            Self::StalePreview => {
                "The experiment changed. Preview this probe again before placing it"
            }
            Self::InvalidCandidate(reason) => reason,
            Self::InvalidReference => "The reference body is no longer available",
            Self::InvalidHorizon => "The prediction duration must be a finite positive number",
            Self::WorkLimit => "This draft exceeds the bounded preview workload",
            Self::NumericalRange => "This draft cannot be propagated safely with the current model",
            Self::StaleUndo => {
                "A later edit changed this experiment; the previous placement cannot be undone here"
            }
            Self::HistoryLimit => "This experiment has reached its authoring history limit",
            Self::TransactionFailed => "The probe change could not be applied",
        })
    }
}
impl std::error::Error for ProbeAuthoringError {}

#[derive(Clone, Debug, PartialEq)]
pub struct ProbePreviewSample {
    pub elapsed_seconds: f64,
    /// Candidate minus reference at the SAME predicted epoch, in fixed world axes.
    pub position_m: Vector3d,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ProbePreview {
    pub baseline_revision: u64,
    pub epoch_seconds: f64,
    pub reference_body: BodyState,
    pub samples: Vec<ProbePreviewSample>,
    pub horizon_seconds: f64,
    pub limited: bool,
    /// Sampled path/segment intersects the reference sphere; not an impact simulation.
    pub intersects_reference_body: bool,
    pub integration_steps: usize,
}

/// Owns only the current body's numerical state, not the runtime's branch history.
/// Preparing under the session lock and running after releasing it keeps rendering
/// responsive. A concurrent world change makes commit stale, not the preview unsafe.
pub struct ProbePreviewPlan {
    baseline_revision: u64,
    epoch_seconds: f64,
    reference_body: BodyState,
    reference_index: usize,
    candidate_radius_m: f64,
    bodies: Vec<BodyState>,
    configured_policy: PhysicsPolicy,
    lab_policy: bool,
    cpu_features: Vec<String>,
    requested_horizon_seconds: f64,
    interval_seconds: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ProbeCommitReceipt {
    pub checkpoint_id: CheckpointId,
    pub body_id: BodyId,
    pub epoch_seconds: f64,
    pub committed_revision: u64,
}

impl WorldRuntime {
    pub fn prepare_probe_preview(
        &self,
        candidate: BodyState,
        reference_body_id: &BodyId,
        horizon_seconds: f64,
    ) -> Result<ProbePreviewPlan, ProbeAuthoringError> {
        self.check_probe_editable()?;
        if !horizon_seconds.is_finite() || horizon_seconds <= 0.0 {
            return Err(ProbeAuthoringError::InvalidHorizon);
        }
        let world = &self.active_branch().world;
        if world.bodies.len() >= MAX_PREVIEW_BODIES {
            return Err(ProbeAuthoringError::WorkLimit);
        }
        self.validate_probe_candidate(&candidate)?;
        let reference_index = world
            .bodies
            .iter()
            .position(|body| &body.body_id == reference_body_id)
            .ok_or(ProbeAuthoringError::InvalidReference)?;
        let reference_body = world.bodies[reference_index].clone();
        if distance(candidate.position_m, reference_body.position_m)
            <= candidate.radius_m + reference_body.radius_m
        {
            return Err(ProbeAuthoringError::InvalidCandidate(
                "Place the probe outside the reference body's surface",
            ));
        }
        let mut bodies = world.bodies.clone();
        let candidate_radius_m = candidate.radius_m;
        bodies.push(candidate);
        let lab_policy = self.scenario_id.0 == solarlab_data::EARTH_MOON_LAB_ID;
        let policy = preview_policy(&self.config.physics, lab_policy, &bodies);
        let horizon = horizon_seconds.min(MAX_PREVIEW_HORIZON_SECONDS).min(
            lab_prediction::shortest_added_body_local_period(&bodies)
                .map_or(f64::INFINITY, |period| period * 1.5),
        );
        // Reserve at most 42 initial substeps per interval. Re-evaluate the existing
        // world-based step ceiling each interval and stop early if it tightens.
        let work_horizon = policy.max_substep_seconds
            * (MAX_PREVIEW_STEPS / PREVIEW_INTERVALS) as f64
            * PREVIEW_INTERVALS as f64;
        let interval_seconds = horizon.min(work_horizon) / PREVIEW_INTERVALS as f64;
        if !interval_seconds.is_finite()
            || interval_seconds <= 0.0
            || next_epoch(world.epoch_seconds, interval_seconds).is_none()
        {
            return Err(ProbeAuthoringError::NumericalRange);
        }
        Ok(ProbePreviewPlan {
            baseline_revision: self.snapshot_revision(),
            epoch_seconds: world.epoch_seconds,
            reference_body,
            reference_index,
            candidate_radius_m,
            bodies,
            configured_policy: self.config.physics.clone(),
            lab_policy,
            cpu_features: self.hardware_profile.cpu_features.clone(),
            requested_horizon_seconds: horizon_seconds,
            interval_seconds,
        })
    }

    /// The clone makes validation/checkpoint/spawn all-or-nothing, including sequence
    /// counters and history. It is used once on commit, never while dragging a draft.
    pub fn commit_probe(
        &mut self,
        candidate: BodyState,
        expected_revision: u64,
        expected_epoch_seconds: f64,
        recorded_at_unix_ms: i64,
    ) -> Result<ProbeCommitReceipt, ProbeAuthoringError> {
        self.check_probe_editable()?;
        if self.snapshot_revision() != expected_revision
            || self.active_branch().world.epoch_seconds.to_bits()
                != expected_epoch_seconds.to_bits()
        {
            return Err(ProbeAuthoringError::StalePreview);
        }
        self.validate_probe_candidate(&candidate)?;
        if self.active_branch().checkpoints.len() >= 256
            || self.next_command_ordinal > i64::MAX as u64 - 2
        {
            return Err(ProbeAuthoringError::HistoryLimit);
        }
        let mut staged = self.clone();
        staged
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some(format!("Before placing {}", candidate.body_id.0)),
                },
                recorded_at_unix_ms,
            )
            .map_err(|_| ProbeAuthoringError::TransactionFailed)?;
        let checkpoint_id = staged
            .active_branch()
            .last_checkpoint
            .as_ref()
            .ok_or(ProbeAuthoringError::TransactionFailed)?
            .checkpoint_id
            .clone();
        let body_id = candidate.body_id.clone();
        staged
            .apply_command(
                WorldCommand::SpawnBody { body: candidate },
                recorded_at_unix_ms,
            )
            .map_err(|_| ProbeAuthoringError::TransactionFailed)?;
        let receipt = ProbeCommitReceipt {
            checkpoint_id,
            body_id,
            epoch_seconds: expected_epoch_seconds,
            committed_revision: staged.snapshot_revision(),
        };
        *self = staged;
        Ok(receipt)
    }

    /// Playback/camera changes may follow a placement. Other authored changes may
    /// not: refusing them prevents Undo from silently losing an unrelated edit.
    pub fn undo_probe(
        &mut self,
        checkpoint_id: &CheckpointId,
        committed_revision: u64,
        recorded_at_unix_ms: i64,
    ) -> Result<(), ProbeAuthoringError> {
        if self.advance_running() {
            return Err(ProbeAuthoringError::Busy);
        }
        let (branch_id, checkpoint) = self
            .find_checkpoint_record(checkpoint_id)
            .ok_or(ProbeAuthoringError::StaleUndo)?;
        let branch = self.active_branch();
        if branch_id != self.active_branch_id
            || checkpoint.descriptor.command_sequence.checked_add(1) != Some(committed_revision)
            || !branch.command_log.iter().any(|record| {
                record.header.sequence == committed_revision
                    && record.header.command_kind == "body.spawn"
            })
            || branch
                .command_log
                .iter()
                .filter(|record| record.header.sequence > committed_revision)
                .any(|record| {
                    !matches!(
                        record.header.command_kind.as_str(),
                        "timeline.advance_epoch"
                            | "playback.pause"
                            | "playback.resume"
                            | "playback.set_rate"
                            | "observer.set_mode"
                            | "observer.focus_body"
                    )
                })
        {
            return Err(ProbeAuthoringError::StaleUndo);
        }
        if self.branches.len() >= 64 || self.next_command_ordinal >= i64::MAX as u64 {
            return Err(ProbeAuthoringError::HistoryLimit);
        }
        let mut staged = self.clone();
        staged
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: checkpoint_id.clone(),
                    new_branch_id: None,
                },
                recorded_at_unix_ms,
            )
            .map_err(|_| ProbeAuthoringError::TransactionFailed)?;
        staged.active_branch_mut().world.playback.paused = true;
        *self = staged;
        Ok(())
    }

    fn check_probe_editable(&self) -> Result<(), ProbeAuthoringError> {
        if self.advance_running() {
            return Err(ProbeAuthoringError::Busy);
        }
        if !self.active_branch().world.playback.paused {
            return Err(ProbeAuthoringError::NotPaused);
        }
        Ok(())
    }

    fn validate_probe_candidate(&self, candidate: &BodyState) -> Result<(), ProbeAuthoringError> {
        use ProbeAuthoringError::InvalidCandidate as invalid;
        let id = &candidate.body_id.0;
        if id.trim().is_empty() || id.len() > 96 || id.chars().any(char::is_control) {
            return Err(invalid("The probe name is invalid"));
        }
        if self
            .active_branch()
            .world
            .bodies
            .iter()
            .any(|body| body.body_id.0.eq_ignore_ascii_case(id))
        {
            return Err(invalid("A body already has this probe name"));
        }
        if !candidate.mass_kg.is_finite()
            || candidate.mass_kg <= 0.0
            || !candidate.source_mass_kg.is_finite()
            || candidate.source_mass_kg < 0.0
            || !candidate.radius_m.is_finite()
            || candidate.radius_m <= 0.0
            || !finite_vector(candidate.position_m)
            || !finite_vector(candidate.velocity_mps)
        {
            return Err(invalid(
                "Probe position, velocity, mass and radius must be finite and physically valid",
            ));
        }
        let mut bodies = self.active_branch().world.bodies.clone();
        bodies.push(candidate.clone());
        if !finite_invariants(compute_world_invariants(&bodies)) {
            return Err(ProbeAuthoringError::NumericalRange);
        }
        Ok(())
    }
}

impl ProbePreviewPlan {
    pub fn run(mut self) -> Result<ProbePreview, ProbeAuthoringError> {
        let mut solver_bodies = world_bodies_to_solver_state(&self.bodies);
        let candidate_index = self.bodies.len() - 1;
        let mut previous = relative_position(&self.bodies, candidate_index, self.reference_index);
        let mut samples = vec![ProbePreviewSample {
            elapsed_seconds: 0.0,
            position_m: previous,
        }];
        let radius = self.reference_body.radius_m + self.candidate_radius_m;
        let mut intersects = false;
        let mut steps = 0;
        let mut epoch = self.epoch_seconds;
        for _ in 0..PREVIEW_INTERVALS {
            let policy = preview_policy(&self.configured_policy, self.lab_policy, &self.bodies);
            let count = checked_substep_count(&policy, self.interval_seconds)
                .ok_or(ProbeAuthoringError::NumericalRange)?;
            if count > MAX_PREVIEW_STEPS - steps {
                break;
            }
            let next = next_epoch(epoch, self.interval_seconds)
                .ok_or(ProbeAuthoringError::NumericalRange)?;
            let (invariants, _) = advance_authoritative_with_features(
                &policy,
                &mut solver_bodies,
                self.interval_seconds,
                &self.cpu_features,
            );
            if !finite_invariants(invariants)
                || solver_bodies.iter().any(|body| {
                    !finite_vector(body.position_m) || !finite_vector(body.velocity_mps)
                })
            {
                return Err(ProbeAuthoringError::NumericalRange);
            }
            apply_solver_state_to_world_bodies(&mut self.bodies, &solver_bodies);
            let position = relative_position(&self.bodies, candidate_index, self.reference_index);
            if !finite_vector(position) {
                return Err(ProbeAuthoringError::NumericalRange);
            }
            intersects |= segment_nears_origin(previous, position, radius);
            epoch = next;
            steps += count;
            samples.push(ProbePreviewSample {
                elapsed_seconds: epoch - self.epoch_seconds,
                position_m: position,
            });
            previous = position;
            // Do not draw propagation through a solid-looking planet as a resolved
            // encounter: stop the display forecast at its first sampled crossing.
            if intersects {
                break;
            }
        }
        if samples.len() < 2 {
            return Err(ProbeAuthoringError::WorkLimit);
        }
        let horizon = epoch - self.epoch_seconds;
        Ok(ProbePreview {
            baseline_revision: self.baseline_revision,
            epoch_seconds: self.epoch_seconds,
            reference_body: self.reference_body,
            samples,
            horizon_seconds: horizon,
            limited: horizon + self.requested_horizon_seconds * 1e-12
                < self.requested_horizon_seconds,
            intersects_reference_body: intersects,
            integration_steps: steps,
        })
    }
}

fn preview_policy(configured: &PhysicsPolicy, lab: bool, bodies: &[BodyState]) -> PhysicsPolicy {
    if lab {
        lab_step::for_world(configured, bodies)
    } else {
        configured.clone()
    }
}
fn finite_vector(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}
fn finite_invariants(v: PhysicsInvariants) -> bool {
    v.total_energy_j.is_finite()
        && finite_vector(v.linear_momentum_kg_mps)
        && finite_vector(v.angular_momentum_kg_m2ps)
        && finite_vector(v.barycenter_m)
}
fn difference(a: Vector3d, b: Vector3d) -> Vector3d {
    Vector3d {
        x: a.x - b.x,
        y: a.y - b.y,
        z: a.z - b.z,
    }
}
fn distance(a: Vector3d, b: Vector3d) -> f64 {
    let d = difference(a, b);
    d.x.hypot(d.y).hypot(d.z)
}
fn relative_position(bodies: &[BodyState], candidate: usize, reference: usize) -> Vector3d {
    difference(bodies[candidate].position_m, bodies[reference].position_m)
}
fn segment_nears_origin(a: Vector3d, b: Vector3d, radius: f64) -> bool {
    let d = difference(b, a);
    let length_squared = d.x * d.x + d.y * d.y + d.z * d.z;
    let t = if length_squared > 0.0 {
        (-(a.x * d.x + a.y * d.y + a.z * d.z) / length_squared).clamp(0.0, 1.0)
    } else {
        0.0
    };
    (a.x + t * d.x).hypot(a.y + t * d.y).hypot(a.z + t * d.z) <= radius
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lab() -> WorldRuntime {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime.config.physics.max_substep_seconds = 60.0;
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        runtime
    }

    fn candidate(runtime: &WorldRuntime) -> BodyState {
        let earth = runtime
            .active_branch()
            .world
            .bodies
            .iter()
            .find(|body| body.body_id.0 == "earth")
            .unwrap();
        let distance = earth.radius_m + 800_000.0;
        BodyState {
            body_id: BodyId("probe-1".into()),
            body_class: BodyClass::Spacecraft,
            mass_kg: 1_000.0,
            source_mass_kg: 0.0,
            radius_m: 2.0,
            position_m: Vector3d {
                x: earth.position_m.x + distance,
                ..earth.position_m
            },
            velocity_mps: Vector3d {
                y: earth.velocity_mps.y + (6.67430e-11 * earth.source_mass_kg / distance).sqrt(),
                ..earth.velocity_mps
            },
        }
    }

    fn preview(runtime: &WorldRuntime, body: BodyState) -> ProbePreview {
        runtime
            .prepare_probe_preview(body, &BodyId("earth".into()), 43_200.0)
            .unwrap()
            .run()
            .unwrap()
    }

    #[test]
    fn preview_is_bounded_and_leaves_all_world_history_and_counters_unchanged() {
        let runtime = lab();
        let before = runtime.export_saved_session().unwrap();
        let body = candidate(&runtime);
        let expected_start = difference(
            body.position_m,
            runtime.active_branch().world.bodies[1].position_m,
        );
        let result = preview(&runtime, body);
        assert_eq!(before, runtime.export_saved_session().unwrap());
        assert_eq!(result.baseline_revision, runtime.snapshot_revision());
        assert_eq!(
            result.epoch_seconds,
            runtime.active_branch().world.epoch_seconds
        );
        assert_eq!(result.samples.len(), PREVIEW_INTERVALS + 1);
        assert_eq!(result.samples[0].elapsed_seconds, 0.0);
        assert_eq!(result.samples[0].position_m, expected_start);
        assert!(result
            .samples
            .windows(2)
            .all(|pair| pair[1].elapsed_seconds > pair[0].elapsed_seconds));
        assert!(result.integration_steps <= MAX_PREVIEW_STEPS);
        assert!(result.horizon_seconds > 8_000.0 && result.horizon_seconds < 10_000.0);
        assert!(result.limited);
        assert!(!result.intersects_reference_body);
    }

    #[test]
    fn preview_samples_match_committed_propagation_including_explicit_source_mass() {
        for source_mass in [0.0, 1.0e20] {
            let mut runtime = lab();
            let mut body = candidate(&runtime);
            body.source_mass_kg = source_mass;
            let result = preview(&runtime, body.clone());
            runtime
                .commit_probe(body, result.baseline_revision, result.epoch_seconds, 2)
                .unwrap();
            let dt = result.samples[1].elapsed_seconds;
            for sample in result.samples.iter().skip(1) {
                runtime
                    .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: dt }, 3)
                    .unwrap();
                let bodies = &runtime.active_branch().world.bodies;
                assert_eq!(
                    relative_position(bodies, bodies.len() - 1, 1),
                    sample.position_m
                );
                assert_eq!(
                    runtime.active_branch().world.epoch_seconds,
                    result.epoch_seconds + sample.elapsed_seconds
                );
            }
        }
    }

    #[test]
    fn copied_plan_survives_world_change_but_its_commit_is_stale_and_atomic() {
        let mut runtime = lab();
        let body = candidate(&runtime);
        let plan = runtime
            .prepare_probe_preview(body.clone(), &BodyId("earth".into()), 3600.0)
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 60.0,
                },
                3,
            )
            .unwrap();
        let changed = runtime.export_saved_session().unwrap();
        let result = plan.run().unwrap();
        assert_eq!(result.epoch_seconds, 0.0);
        assert_eq!(
            runtime.commit_probe(body, result.baseline_revision, result.epoch_seconds, 4),
            Err(ProbeAuthoringError::StalePreview)
        );
        assert_eq!(changed, runtime.export_saved_session().unwrap());
    }

    #[test]
    fn malformed_and_overflowing_candidates_cannot_preview_or_commit() {
        let mut runtime = lab();
        let before = runtime.export_saved_session().unwrap();
        let original = candidate(&runtime);
        for mode in 0..8 {
            let mut body = original.clone();
            match mode {
                0 => body.mass_kg = f64::INFINITY,
                1 => body.source_mass_kg = -1.0,
                2 => body.position_m.x = f64::NAN,
                3 => body.velocity_mps.z = f64::INFINITY,
                4 => body.radius_m = 0.0,
                5 => body.body_id = BodyId("EARTH".into()),
                6 => body.body_id = BodyId("bad\nname".into()),
                _ => body.source_mass_kg = 1e300,
            }
            assert!(runtime
                .prepare_probe_preview(body.clone(), &BodyId("earth".into()), 3600.0)
                .is_err());
            assert!(runtime
                .commit_probe(body, runtime.snapshot_revision(), 0.0, 2)
                .is_err());
            assert_eq!(before, runtime.export_saved_session().unwrap());
        }
    }

    #[test]
    fn missing_reference_surface_overlap_running_time_and_bad_horizons_are_rejected() {
        let mut runtime = lab();
        let original = candidate(&runtime);
        assert!(matches!(
            runtime.prepare_probe_preview(original.clone(), &BodyId("missing".into()), 60.0),
            Err(ProbeAuthoringError::InvalidReference)
        ));
        for horizon in [0.0, -1.0, f64::INFINITY, f64::NAN] {
            assert!(matches!(
                runtime.prepare_probe_preview(original.clone(), &BodyId("earth".into()), horizon),
                Err(ProbeAuthoringError::InvalidHorizon)
            ));
        }
        let mut overlapping = original.clone();
        overlapping.position_m = runtime.active_branch().world.bodies[1].position_m;
        assert!(matches!(
            runtime.prepare_probe_preview(overlapping, &BodyId("earth".into()), 60.0),
            Err(ProbeAuthoringError::InvalidCandidate(_))
        ));
        runtime
            .apply_command(WorldCommand::ResumePlayback, 2)
            .unwrap();
        assert!(matches!(
            runtime.prepare_probe_preview(original.clone(), &BodyId("earth".into()), 60.0),
            Err(ProbeAuthoringError::NotPaused)
        ));
        assert_eq!(
            runtime.commit_probe(original, runtime.snapshot_revision(), 0.0, 3),
            Err(ProbeAuthoringError::NotPaused)
        );
    }

    #[test]
    fn preview_shortens_for_work_budget_and_stops_at_reference_surface_crossing() {
        let mut runtime = lab();
        runtime.config.physics.max_substep_seconds = 0.05;
        let result = preview(&runtime, candidate(&runtime));
        assert!(result.horizon_seconds <= 0.05 * MAX_PREVIEW_STEPS as f64);
        assert!(result.integration_steps <= MAX_PREVIEW_STEPS);
        assert_eq!(result.samples.len(), PREVIEW_INTERVALS + 1);
        let mut runtime = lab();
        let earth = runtime.active_branch().world.bodies[1].clone();
        let mut falling = candidate(&runtime);
        falling.velocity_mps = earth.velocity_mps;
        let result = preview(&runtime, falling);
        assert!(result.intersects_reference_body);
        assert!(result.limited);
        assert!(result.samples.len() < PREVIEW_INTERVALS + 1);
        assert!(result.integration_steps <= MAX_PREVIEW_STEPS);
        // The live state is unaffected even though the draft's path crosses Earth.
        assert_eq!(runtime.active_branch().world.bodies.len(), 3);
        runtime
            .apply_command(WorldCommand::PausePlayback, 2)
            .unwrap();
    }

    #[test]
    fn undo_restores_pre_edit_world_paused_and_preserves_played_future() {
        let mut runtime = lab();
        let before = runtime.active_branch().world.clone();
        let original_branch = runtime.active_branch_id.clone();
        let body = candidate(&runtime);
        let result = preview(&runtime, body.clone());
        let receipt = runtime
            .commit_probe(body, result.baseline_revision, result.epoch_seconds, 2)
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(receipt.body_id.clone()),
                },
                3,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                4,
            )
            .unwrap();
        runtime
            .apply_command(WorldCommand::ResumePlayback, 5)
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 60.0,
                },
                6,
            )
            .unwrap();
        let future = runtime.active_branch().clone();
        runtime
            .undo_probe(&receipt.checkpoint_id, receipt.committed_revision, 7)
            .unwrap();
        assert_ne!(runtime.active_branch_id, original_branch);
        assert_eq!(runtime.active_branch().world, before);
        assert_eq!(runtime.branches[&original_branch], future);
        assert!(runtime.active_branch().world.playback.paused);
        let saved = runtime.export_saved_session().unwrap();
        assert_eq!(
            runtime.undo_probe(&receipt.checkpoint_id, receipt.committed_revision, 8),
            Err(ProbeAuthoringError::StaleUndo)
        );
        assert_eq!(runtime.export_saved_session().unwrap(), saved);
    }

    #[test]
    fn undo_refuses_unrelated_later_body_edit_without_discarding_it() {
        let mut runtime = lab();
        let body = candidate(&runtime);
        let result = preview(&runtime, body.clone());
        let receipt = runtime
            .commit_probe(body, result.baseline_revision, result.epoch_seconds, 2)
            .unwrap();
        let mut second = candidate(&runtime);
        second.body_id = BodyId("another-probe".into());
        runtime
            .apply_command(WorldCommand::SpawnBody { body: second }, 3)
            .unwrap();
        let before = runtime.export_saved_session().unwrap();
        assert_eq!(
            runtime.undo_probe(&receipt.checkpoint_id, receipt.committed_revision, 4),
            Err(ProbeAuthoringError::StaleUndo)
        );
        assert_eq!(runtime.export_saved_session().unwrap(), before);
    }
}
