//! Bounded time advancement, staged propagation, cancellation, and playback.
//! Authoritative mutations remain on the single WorldRuntime; no shadow world is created.
use super::*;

impl WorldRuntime {
    pub(super) fn advance_running(&self) -> bool {
        self.advance_operation
            .as_ref()
            .is_some_and(|op| op.state == AdvanceState::Running)
    }

    #[must_use]
    pub fn query_advance(&self) -> Option<&AdvanceOperation> {
        self.advance_operation.as_ref()
    }

    /// Gravity-only prediction shares the scientific orbital step ceiling,
    /// without importing powered-flight attitude work into a coast forecast.
    pub(super) fn coast_physics_policy(&self) -> PhysicsPolicy {
        let mut policy = if self.scenario_id.0 == solarlab_data::EARTH_MOON_LAB_ID {
            self.lab_step_policy.for_world(&self.config.physics, &self.active_branch().world.bodies)
        } else { self.config.physics.clone() };
        policy.integrator = self.scientific_settings().integrator;
        policy
    }

    pub(super) fn effective_physics_policy(&self) -> PhysicsPolicy {
        let mut policy = self.coast_physics_policy();
        policy.max_substep_seconds = solarlab_physics::spacecraft::max_substep_seconds(
            &self.active_branch().world.spacecraft, policy.max_substep_seconds);
        policy
    }

    pub fn begin_advance(
        &mut self,
        intent: [u64; 2],
        expected_generation: u64,
        expected_base_ordinal: u64,
        delta: f64,
        recorded_at_unix_ms: i64,
    ) -> Result<&AdvanceOperation, RuntimeError> {
        if let Some(op) = self.advance_operation.as_ref() {
            if op.intent == intent {
                if op.generation.checked_sub(1) == Some(expected_generation)
                    && op.base_ordinal == expected_base_ordinal
                    && op.requested_seconds.to_bits() == delta.to_bits()
                    && op.recorded_at_unix_ms == recorded_at_unix_ms
                {
                    // Returning `op` directly holds its borrow across the later
                    // `advance_operation` assignment (E0506). Reborrow only at
                    // the return boundary, without an unnecessary panic path.
                    return self.advance_operation.as_ref().ok_or(RuntimeError::AdvanceStale);
                }
                return Err(RuntimeError::AdvanceStale);
            }
        }
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        if expected_generation != self.advance_generation
            || expected_base_ordinal != self.next_command_ordinal
        {
            return Err(RuntimeError::AdvanceStale);
        }
        if !self.active_branch().world.playback.paused {
            return Err(RuntimeError::AdvanceBusy);
        }
        let world = &self.active_branch().world;
        if !delta.is_finite() || delta <= 0.0 || next_epoch(world.epoch_seconds, delta).is_none() {
            return Err(RuntimeError::InvalidEpochDelta(delta));
        }
        if self.next_command_ordinal >= i64::MAX as u64 {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        let generation = self
            .advance_generation
            .checked_add(1)
            .filter(|g| *g <= i64::MAX as u64)
            .ok_or(RuntimeError::AdvanceStale)?;
        let bodies = world_bodies_to_solver_state(&world.bodies);
        let spacecraft = spacecraft::bindings(world);
        let plan = if bodies.is_empty() {
            None
        } else {
            Some(scientific_flight::bind_plan(&self.effective_physics_policy(),
                &world.scientific_settings, &world.bodies, &world.spacecraft,
                delta, &self.hardware_profile.cpu_features)?)
        };
        let total_steps = plan.as_ref().map_or(0, |p| p.total_steps);
        if total_steps > i64::MAX as usize {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        self.advance_generation = generation;
        self.advance_operation = Some(AdvanceOperation {
            state: AdvanceState::Running,
            generation,
            intent,
            base_ordinal: self.next_command_ordinal,
            requested_seconds: delta,
            recorded_at_unix_ms,
            completed_steps: 0,
            total_steps,
            plan,
            bodies,
            spacecraft,
        });
        Ok(self.advance_operation.as_ref().expect("operation exists"))
    }

    /// Returns commit events only on the call that first settles the operation.
    /// Later terminal readback returns an empty event vector.
    pub fn continue_advance(
        &mut self,
        generation: u64,
        expected_completed: usize,
    ) -> Result<(&AdvanceOperation, Vec<RuntimeEvent>), RuntimeError> {
        let op = self
            .advance_operation
            .as_mut()
            .ok_or(RuntimeError::AdvanceStale)?;
        if op.generation != generation || op.completed_steps != expected_completed {
            return Err(RuntimeError::AdvanceStale);
        }
        if op.state != AdvanceState::Running {
            return Ok((
                self.advance_operation.as_ref().expect("operation exists"),
                Vec::new(),
            ));
        }
        let mut events = Vec::new();
        let count = (op.total_steps - op.completed_steps).min(MAX_INCREMENTAL_STEPS);
        if let Some(plan) = &op.plan {
            if solarlab_physics::spacecraft::advance_spacecraft_steps(plan, &mut op.bodies, &mut op.spacecraft, count).is_err()
                || plan.compute_invariants(&op.bodies).is_err() {
                op.state = AdvanceState::Cancelled;
                op.bodies.clear();
                op.spacecraft.clear();
                op.plan = None;
                return Err(RuntimeError::InvalidFlightState);
            }
        }
        op.completed_steps += count;
        if op.completed_steps == op.total_steps {
            let mut op = self.advance_operation.take().expect("operation exists");
            let report = op
                .plan
                .as_ref()
                .map(FixedStepPlan::report)
                .unwrap_or_else(|| self.active_branch().world.solver_execution.clone());
            let staged = std::mem::take(&mut op.bodies);
            events = self.apply_command_inner(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: op.requested_seconds,
                },
                op.recorded_at_unix_ms,
                Some((staged, report, std::mem::take(&mut op.spacecraft))),
            )?;
            op.state = AdvanceState::Committed;
            self.advance_operation = Some(op);
        }
        Ok((
            self.advance_operation.as_ref().expect("operation exists"),
            events,
        ))
    }

    pub fn cancel_advance(&mut self, generation: u64) -> Result<&AdvanceOperation, RuntimeError> {
        let op = self
            .advance_operation
            .as_mut()
            .ok_or(RuntimeError::AdvanceStale)?;
        if op.generation != generation {
            return Err(RuntimeError::AdvanceStale);
        }
        if op.state == AdvanceState::Running {
            op.state = AdvanceState::Cancelled;
            op.bodies.clear();
            op.spacecraft.clear();
            op.plan = None;
        }
        Ok(self.advance_operation.as_ref().expect("operation exists"))
    }

    pub fn playback_tick(
        &mut self,
        real_seconds: f64,
        recorded_at_unix_ms: i64,
    ) -> Result<PlaybackTickResult, RuntimeError> {
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        if !real_seconds.is_finite() || real_seconds <= 0.0 {
            return Err(RuntimeError::InvalidEpochDelta(real_seconds));
        }
        let world = &self.active_branch().world;
        if self.snapshot_revision() > i64::MAX as u64 {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        if world.playback.paused {
            return Ok(PlaybackTickResult {
                requested_seconds: 0.0,
                applied_seconds: 0.0,
                limited: false,
                committed_epoch_seconds: world.epoch_seconds,
                committed_revision: self.snapshot_revision(),
            });
        }
        let requested = real_seconds * world.playback.sim_seconds_per_real_second;
        if !requested.is_finite() || requested <= 0.0 {
            return Err(RuntimeError::InvalidEpochDelta(requested));
        }
        let max_step = self.effective_physics_policy().max_substep_seconds;
        let max_delta = if max_step.is_finite() && max_step > 0.0 {
            max_step * MAX_INCREMENTAL_STEPS as f64
        } else {
            requested
        };
        let allowance = if world.bodies.is_empty() {
            requested
        } else {
            requested.min(max_delta)
        };
        let epoch = world.epoch_seconds;
        if self.next_command_ordinal >= i64::MAX as u64 {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        let next = representable_epoch_within(epoch, allowance)
            .ok_or(RuntimeError::InvalidEpochDelta(allowance))?;
        let applied = next - epoch;
        self.apply_command(
            WorldCommand::AdvanceEpoch {
                delta_seconds: applied,
            },
            recorded_at_unix_ms,
        )?;
        Ok(PlaybackTickResult {
            requested_seconds: requested,
            applied_seconds: applied,
            limited: applied < requested,
            committed_epoch_seconds: next,
            committed_revision: self.snapshot_revision(),
        })
    }
}
