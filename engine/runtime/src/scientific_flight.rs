//! Branch-owned numerical models and read-only finite burn previews.
use super::*;
use solarlab_domain::AttitudeMode;

fn burn_receipt(before: &FlightTelemetry, after: &FlightTelemetry, fuel_used: f64,
    remaining_fuel: f64, request: FlightBurnRequest, split: bool) -> FlightBurnPreview {
    let peri = |t: &FlightTelemetry| t.orbit.map(|o| o.periapsis_radius_m - t.reference_radius_m);
    let apo = |t: &FlightTelemetry| t.orbit.and_then(|o| o.apoapsis_radius_m).map(|r| r - t.reference_radius_m);
    let mut warnings = vec!["Periapsis and apoapsis are instantaneous two-body estimates; the finite burn uses the full configured gravity model.".into(),
        "No atmosphere or terrain collision is simulated by this preview.".into()];
    if split {
        warnings.push("Thrust and attitude motion use the positive second-order split. Higher-order methods apply to conservative coast.".into());
    }
    if remaining_fuel <= 1e-9 && request.throttle > 0.0 { warnings.push("The spacecraft runs out of propellant during this request.".into()); }
    if peri(after).is_some_and(|p| p < 0.0) { warnings.push("The resulting osculating orbit intersects the reference body's surface.".into()); }
    if apo(after).is_none() { warnings.push("No bound apoapsis is defined relative to this reference body.".into()); }
    FlightBurnPreview { before_periapsis_m: peri(before), after_periapsis_m: peri(after),
        before_apoapsis_m: apo(before), after_apoapsis_m: apo(after),
        fuel_used_kg: fuel_used.max(0.0), elapsed_seconds: request.duration_seconds,
        warnings, epoch_seconds: before.epoch_seconds, revision: before.revision }
}

pub(super) fn bind_plan(
    policy: &PhysicsPolicy, settings: &ScientificSettings, bodies: &[BodyState],
    ships: &[SpacecraftState], delta: f64, features: &[String],
) -> Result<FixedStepPlan, RuntimeError> {
    let plan = fixed_step_plan_with_source_count(policy, delta, bodies.len(),
        bodies.iter().filter(|b| b.source_mass_kg > 0.0).count(), features)
        .ok_or(RuntimeError::InvalidEpochDelta(delta))?
        .with_scientific_settings(settings,
            &bodies.iter().map(|b| b.body_id.clone()).collect::<Vec<_>>())
        .map_err(RuntimeError::ScientificFailure)?;
    Ok(if ships.iter().any(|s| s.has_powered_input() || s.needs_attitude_steps()
        || s.docking.is_some() || s.rendezvous.as_ref().is_some_and(|r| r.guidance_mode.active())) {
        plan.for_nonconservative_split()
    } else { plan })
}

pub(super) fn world_invariants(
    bodies: &[BodyState], settings: &ScientificSettings,
) -> Result<PhysicsInvariants, RuntimeError> {
    let policy = PhysicsPolicy { solver_backend: solarlab_physics::SolverBackend::ReferenceScalar,
        integrator: settings.integrator, collision_model: solarlab_physics::CollisionModel::None,
        max_substep_seconds: 1.0 };
    bind_plan(&policy, settings, bodies, &[], 1.0, &[])?
        .compute_invariants(&world_bodies_to_solver_state(bodies))
        .map_err(RuntimeError::ScientificFailure)
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FlightBurnRequest {
    pub duration_seconds: f64,
    pub throttle: f64,
    pub attitude_mode: AttitudeMode,
}

#[derive(serde::Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FlightBurnPreview {
    pub before_periapsis_m: Option<f64>,
    pub after_periapsis_m: Option<f64>,
    pub before_apoapsis_m: Option<f64>,
    pub after_apoapsis_m: Option<f64>,
    pub fuel_used_kg: f64,
    pub elapsed_seconds: f64,
    pub warnings: Vec<String>,
    pub epoch_seconds: f64,
    pub revision: u64,
}

impl WorldRuntime {
    /// Calculates in private buffers at a paused committed boundary.
    pub fn preview_flight_burn(&self, body_id: &str, request: FlightBurnRequest)
        -> Result<FlightBurnPreview, RuntimeError> {
        let world = &self.active_branch().world;
        if self.advance_running() { return Err(RuntimeError::AdvanceBusy); }
        if !world.playback.paused { return Err(RuntimeError::BodyEditRequiresPause); }
        if !request.duration_seconds.is_finite() || request.duration_seconds <= 0.0
            || request.duration_seconds > 600.0 || !request.throttle.is_finite()
            || !(0.0..=1.0).contains(&request.throttle) || world.bodies.len() > 64 {
            return Err(RuntimeError::InvalidFlightState);
        }
        let ship = self.spacecraft_by_id(body_id).ok_or_else(|| RuntimeError::UnknownBody(BodyId(body_id.into())))?;
        if ship.docking.is_some() { return Err(RuntimeError::InvalidFlightState); }
        let before = self.flight_telemetry(body_id).ok_or(RuntimeError::InvalidFlightState)?;
        let before_fuel = ship.propellant_mass_kg;
        let mut preview = Self::new(self.scenario_id.clone(), self.active_branch_id.clone(),
            self.config.clone(), self.hardware_profile.clone(), 0);
        preview.lab_step_policy = self.lab_step_policy;
        preview.active_branch_mut().world = world.clone();
        preview.apply_command(WorldCommand::SetFlightControl { body_id: BodyId(body_id.into()),
            control: FlightControl { throttle: request.throttle, attitude_mode: request.attitude_mode,
                ..FlightControl::default() } }, 0)?;
        let staged = &preview.active_branch().world;
        let plan = bind_plan(&preview.effective_physics_policy(), &staged.scientific_settings,
            &staged.bodies, &staged.spacecraft, request.duration_seconds, &self.hardware_profile.cpu_features)?;
        if plan.total_steps > 16_384 || plan.total_steps.saturating_mul(staged.bodies.len().saturating_pow(2)) > 2_000_000 {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        let mut bodies = world_bodies_to_solver_state(&staged.bodies);
        let mut ships = spacecraft::bindings(staged);
        let mut remaining = plan.total_steps;
        while remaining > 0 {
            let count = remaining.min(MAX_INCREMENTAL_STEPS);
            solarlab_physics::spacecraft::advance_spacecraft_steps(&plan, &mut bodies, &mut ships, count)
                .map_err(|_| RuntimeError::InvalidFlightState)?;
            remaining -= count;
        }
        plan.compute_invariants(&bodies).map_err(RuntimeError::ScientificFailure)?;
        let staged = &mut preview.active_branch_mut().world;
        spacecraft::apply_bindings(staged, ships);
        apply_solver_state_to_world_bodies(&mut staged.bodies, &bodies);
        staged.epoch_seconds += request.duration_seconds;
        let after = preview.flight_telemetry(body_id).ok_or(RuntimeError::InvalidFlightState)?;
        let after_fuel = preview.spacecraft_by_id(body_id).ok_or(RuntimeError::InvalidFlightState)?.propellant_mass_kg;
        Ok(burn_receipt(&before, &after, before_fuel - after_fuel, after_fuel,
            request, plan.report().nonconservative_split))
    }

    #[must_use]
    pub fn scientific_settings(&self) -> &ScientificSettings {
        &self.active_branch().world.scientific_settings
    }

    pub(super) fn scientific_command_invariants(
        &self, command: &WorldCommand,
    ) -> Result<Option<PhysicsInvariants>, RuntimeError> {
        let world = &self.active_branch().world;
        if let WorldCommand::SetScientificSettings { settings } = command {
            if !world.playback.paused { return Err(RuntimeError::BodyEditRequiresPause); }
            return world_invariants(&world.bodies, settings).map(Some);
        }
        if world.scientific_settings.harmonics.is_empty() { return Ok(None); }
        let mut bodies = world.bodies.clone();
        match command {
            WorldCommand::SpawnBody { body } | WorldCommand::SpawnSpacecraft { body, .. } => bodies.push(body.clone()),
            WorldCommand::RemoveBody { body_id } => bodies.retain(|b| b.body_id != *body_id),
            WorldCommand::SetBodyKinematics { body_id, position_m, velocity_mps } => {
                let b = bodies.iter_mut().find(|b| b.body_id == *body_id).ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                b.position_m = *position_m; b.velocity_mps = *velocity_mps;
            }
            WorldCommand::SetBodyProperties { body_id, mass_kg, source_mass_kg, radius_m, position_m, velocity_mps } => {
                let b = bodies.iter_mut().find(|b| b.body_id == *body_id).ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                b.mass_kg = *mass_kg; b.source_mass_kg = *source_mass_kg; b.radius_m = *radius_m;
                b.position_m = *position_m; b.velocity_mps = *velocity_mps;
            }
            _ => return Ok(None),
        }
        world_invariants(&bodies, &world.scientific_settings).map(Some)
    }
}
