//! Authoritative world-command dispatch and atomic history publication.
//! Authoritative mutations remain on the single WorldRuntime; no shadow world is created.
use super::*;

impl WorldRuntime {
    /// Main mutation pipeline for simulation and history state.
    /// Every command is converted into recorded events and an authoritative state
    /// transition for the active branch.
    pub fn apply_command(
        &mut self,
        command: WorldCommand,
        recorded_at_unix_ms: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        self.apply_command_inner(command, recorded_at_unix_ms, None)
    }

    pub(super) fn apply_command_inner(
        &mut self,
        command: WorldCommand,
        recorded_at_unix_ms: i64,
        staged: Option<(Vec<MassiveBodyState>, SolverExecutionReport)>,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        match &command {
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                let world = &self.active_branch().world;
                if !delta_seconds.is_finite()
                    || *delta_seconds <= 0.0
                    || next_epoch(world.epoch_seconds, *delta_seconds).is_none()
                    || (!world.bodies.is_empty()
                        && checked_substep_count(&self.effective_physics_policy(), *delta_seconds).is_none())
                {
                    return Err(RuntimeError::InvalidEpochDelta(*delta_seconds));
                }
                if staged.is_none()
                    && !world.bodies.is_empty()
                    && checked_substep_count(&self.effective_physics_policy(), *delta_seconds)
                        .is_some_and(|n| n > MAX_INCREMENTAL_STEPS)
                {
                    return Err(RuntimeError::AdvanceWorkLimit);
                }
            }
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second,
            } if !sim_seconds_per_real_second.is_finite()
                || *sim_seconds_per_real_second <= 0.0 =>
            {
                return Err(RuntimeError::InvalidPlaybackRate(
                    *sim_seconds_per_real_second,
                ));
            }
            _ => {}
        }

        // Validate before consuming an ordinal or changing history/world state.
        let edited_invariants = if let WorldCommand::SetBodyProperties {
            body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,
        } = &command {
            // Numeric body edits must not overwrite a position captured before
            // an active playback tick. Reject before history/ordinal mutation.
            if !self.active_branch().world.playback.paused {
                return Err(RuntimeError::BodyEditRequiresPause);
            }
            Some(body_properties::validate(
                &self.active_branch().world.bodies, body_id, *mass_kg,
                *source_mass_kg, *radius_m, *position_m, *velocity_mps,
            )?)
        } else { None };
        let mut events = Vec::new();
        let mut header = self.new_command_header(&command, recorded_at_unix_ms);
        let summary = self.command_summary(&command);
        let is_branch_creation =
            matches!(&command, WorldCommand::CreateBranchFromCheckpoint { .. });
        let refresh_solver_execution_after_command =
            !matches!(&command, WorldCommand::AdvanceEpoch { .. });
        self.next_command_ordinal += 1;

        match &command {
            WorldCommand::SeedCanonicalSolarSystem => {
                if self.active_branch().world.bodies.is_empty() {
                    let seed = scenario_pack_seed(&self.scenario_id.0)
                        .ok_or_else(|| RuntimeError::UnknownScenario(self.scenario_id.clone()))?;
                    let initial_conditions_provenance = (self.scenario_id.0 == solarlab_data::EARTH_MOON_LAB_ID).then(|| InitialConditionsProvenance {
                        dataset_id: solarlab_data::EARTH_MOON_LAB_DATASET_ID.into(),
                        version: "1.0.0".into(),
                        digest: solarlab_data::EARTH_MOON_LAB_DIGEST.into(),
                        epoch_jd_tdb: solarlab_data::EARTH_MOON_LAB_EPOCH_JD_TDB,
                        frame: "ICRF".into(),
                        center: "solar-system-barycentre".into(),
                        time_scale: "TDB".into(),
                    });
                    let branch = self.active_branch_mut();
                    branch.world.initial_conditions_provenance = initial_conditions_provenance;
                    branch.world.bodies = seed
                        .bodies
                        .into_iter()
                        .map(|body| {
                            let source_mass_kg =
                                BodyState::default_source_mass_kg(&body.body_class, body.mass_kg);
                            BodyState {
                                body_id: BodyId(body.body_id),
                                body_class: body.body_class,
                                mass_kg: body.mass_kg,
                                source_mass_kg,
                                radius_m: body.radius_m,
                                position_m: body.position_m,
                                velocity_mps: body.velocity_mps,
                            }
                        })
                        .collect();
                    branch.world.observer = ObserverState {
                        mode: seed.default_observer_mode,
                        focus_body_id: seed.default_focus_body_id.map(BodyId),
                    };
                    branch.world.playback = PlaybackState {
                        paused: seed.start_paused,
                        sim_seconds_per_real_second: seed.sim_seconds_per_real_second,
                    };
                    branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                    branch.world.trail_history_by_body.clear();
                    record_trail_samples_from_bodies(
                        &branch.world.bodies,
                        &mut branch.world.trail_history_by_body,
                        branch.world.epoch_seconds,
                    );
                }
            }
            WorldCommand::SpawnBody { body } => {
                let branch = self.active_branch_mut();
                if branch
                    .world
                    .bodies
                    .iter()
                    .any(|b| b.body_id == body.body_id)
                {
                    return Err(RuntimeError::DuplicateBody(body.body_id.clone()));
                }
                branch.world.bodies.push(body.clone());
                branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                push_trail_sample(
                    &mut branch.world.trail_history_by_body,
                    &body.body_id,
                    branch.world.epoch_seconds,
                    body.position_m,
                );
            }
            WorldCommand::RemoveBody { body_id } => {
                let branch = self.active_branch_mut();
                let before = branch.world.bodies.len();
                branch.world.bodies.retain(|b| b.body_id != *body_id);
                if before == branch.world.bodies.len() {
                    return Err(RuntimeError::UnknownBody(body_id.clone()));
                }
                if branch.world.observer.focus_body_id.as_ref() == Some(body_id) {
                    branch.world.observer.focus_body_id = None;
                }
                branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                branch.world.trail_history_by_body.remove(body_id);
            }
            WorldCommand::SetBodyKinematics {
                body_id,
                position_m,
                velocity_mps,
            } => {
                let branch = self.active_branch_mut();
                let Some(body) = branch
                    .world
                    .bodies
                    .iter_mut()
                    .find(|b| b.body_id == *body_id)
                else {
                    return Err(RuntimeError::UnknownBody(body_id.clone()));
                };
                body.position_m = *position_m;
                body.velocity_mps = *velocity_mps;
                branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                push_trail_sample(
                    &mut branch.world.trail_history_by_body,
                    body_id,
                    branch.world.epoch_seconds,
                    *position_m,
                );
            }
            WorldCommand::SetBodyProperties {
                body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps,
            } => {
                let branch = self.active_branch_mut();
                let body = branch.world.bodies.iter_mut()
                    .find(|body| body.body_id == *body_id)
                    .expect("body-properties preflight established identity");
                body.mass_kg = *mass_kg;
                body.source_mass_kg = *source_mass_kg;
                body.radius_m = *radius_m;
                body.position_m = *position_m;
                body.velocity_mps = *velocity_mps;
                branch.world.invariants = edited_invariants.expect("validated property invariants");
                push_trail_sample(&mut branch.world.trail_history_by_body,
                    body_id, branch.world.epoch_seconds, *position_m);
            }
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                let physics_policy = self.effective_physics_policy();
                let active_cpu_features = self.hardware_profile.cpu_features.clone();
                let branch = self.active_branch_mut();
                let (solver_bodies, invariants, solver_execution) =
                    if let Some((solver_bodies, report)) = staged {
                        let invariants = compute_invariants(&solver_bodies);
                        (solver_bodies, invariants, report)
                    } else {
                        let mut solver_bodies = world_bodies_to_solver_state(&branch.world.bodies);
                        let (invariants, report) = advance_authoritative_with_features(
                            &physics_policy,
                            &mut solver_bodies,
                            *delta_seconds,
                            &active_cpu_features,
                        );
                        (solver_bodies, invariants, report)
                    };
                apply_solver_state_to_world_bodies(&mut branch.world.bodies, &solver_bodies);
                branch.world.invariants = invariants;
                branch.world.solver_execution = solver_execution;
                branch.world.epoch_seconds += *delta_seconds;
                record_trail_samples_from_bodies(
                    &branch.world.bodies,
                    &mut branch.world.trail_history_by_body,
                    branch.world.epoch_seconds,
                );
            }
            WorldCommand::PausePlayback => {
                self.active_branch_mut().world.playback.paused = true;
            }
            WorldCommand::ResumePlayback => {
                self.active_branch_mut().world.playback.paused = false;
            }
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second,
            } => {
                self.active_branch_mut()
                    .world
                    .playback
                    .sim_seconds_per_real_second = *sim_seconds_per_real_second;
            }
            WorldCommand::SetObserverMode { mode } => {
                self.active_branch_mut().world.observer.mode = mode.clone();
            }
            WorldCommand::FocusBody { body_id } => {
                if let Some(target) = body_id {
                    let exists = self
                        .active_branch()
                        .world
                        .bodies
                        .iter()
                        .any(|b| b.body_id == *target);
                    if !exists {
                        return Err(RuntimeError::UnknownBody(target.clone()));
                    }
                }
                self.active_branch_mut().world.observer.focus_body_id = body_id.clone();
            }
            WorldCommand::CreateCheckpoint {
                checkpoint_id,
                label,
            } => {
                let checkpoint_id = checkpoint_id
                    .clone()
                    .unwrap_or_else(|| self.generate_checkpoint_id());
                if self.is_checkpoint_id_in_use(&checkpoint_id) {
                    return Err(RuntimeError::DuplicateCheckpoint(checkpoint_id.clone()));
                }
                self.bump_next_checkpoint_ordinal_if_needed(&checkpoint_id);
                let branch_id = self.active_branch_id.clone();
                let descriptor = CheckpointDescriptor {
                    checkpoint_id: checkpoint_id.clone(),
                    branch_id,
                    scenario_id: self.scenario_id.clone(),
                    command_sequence: header.sequence,
                    epoch_seconds: self.active_branch().world.epoch_seconds,
                    timeline_semantics: self.config.timeline_semantics.clone(),
                    label: label.clone(),
                };
                let record = CheckpointRecord {
                    descriptor: descriptor.clone(),
                    state: self.active_branch().world.clone(),
                };
                let branch = self.active_branch_mut();
                branch.checkpoints.push(record);
                branch.last_checkpoint = Some(descriptor.clone());
                events.push(RuntimeEvent::HistoryAppended(
                    HistoryEvent::CheckpointCreated(descriptor),
                ));
            }
            WorldCommand::CreateBranchFromCheckpoint {
                checkpoint_id,
                new_branch_id,
            } => {
                let Some((source_branch_id, source_checkpoint)) =
                    self.find_checkpoint_record(checkpoint_id)
                else {
                    return Err(RuntimeError::UnknownCheckpoint(checkpoint_id.clone()));
                };
                let branch_id = new_branch_id
                    .clone()
                    .unwrap_or_else(|| self.generate_branch_id());
                if self.branches.contains_key(&branch_id) {
                    return Err(RuntimeError::DuplicateBranch(branch_id));
                }
                self.bump_next_branch_ordinal_if_needed(&branch_id);

                let descriptor = BranchDescriptor {
                    branch_id: branch_id.clone(),
                    scenario_id: self.scenario_id.clone(),
                    created_at_unix_ms: recorded_at_unix_ms,
                    parent_branch_id: Some(source_branch_id),
                    parent_checkpoint_id: Some(checkpoint_id.clone()),
                };
                let branch_state = BranchState {
                    descriptor: descriptor.clone(),
                    world: source_checkpoint.state,
                    command_log: Vec::new(),
                    checkpoints: Vec::new(),
                    last_checkpoint: Some(source_checkpoint.descriptor.clone()),
                };
                self.branches.insert(branch_id.clone(), branch_state);
                self.active_branch_id = branch_id.clone();
                header.branch_id = branch_id;

                events.push(RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(
                    descriptor,
                )));
            }
        }

        if refresh_solver_execution_after_command {
            self.refresh_active_solver_execution_report();
        }

        self.active_branch_mut().command_log.push(CommandRecord {
            header: header.clone(),
            summary,
        });
        events.insert(
            0,
            RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(header)),
        );
        events.push(RuntimeEvent::SnapshotPublished(self.snapshot()));

        if is_branch_creation && events.len() >= 2 {
            if matches!(
                &events[1],
                RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(_))
            ) {
                events.swap(0, 1);
            }
        }

        Ok(events)
    }
}
