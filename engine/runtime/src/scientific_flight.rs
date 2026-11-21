//! Branch-owned numerical models and read-only finite burn previews.
use super::*;
use solarlab_domain::AttitudeMode;

fn burn_receipt(
    before: &FlightTelemetry,
    after: &FlightTelemetry,
    fuel_used: f64,
    remaining_fuel: f64,
    request: FlightBurnRequest,
    split: bool,
) -> FlightBurnPreview {
    let peri = |t: &FlightTelemetry| t.orbit.map(|o| o.periapsis_radius_m - t.reference_radius_m);
    let apo = |t: &FlightTelemetry| {
        t.orbit
            .and_then(|o| o.apoapsis_radius_m)
            .map(|r| r - t.reference_radius_m)
    };
    let mut warnings = vec!["Periapsis and apoapsis are instantaneous two-body estimates; the finite burn uses the full configured gravity model.".into(),
        "No atmosphere or terrain collision is simulated by this preview.".into()];
    if split {
        warnings.push("Thrust and attitude motion use the positive second-order split. Higher-order methods apply to conservative coast.".into());
    }
    if remaining_fuel <= 1e-9 && request.throttle > 0.0 {
        warnings.push("The spacecraft runs out of propellant during this request.".into());
    }
    if peri(after).is_some_and(|p| p < 0.0) {
        warnings
            .push("The resulting osculating orbit intersects the reference body's surface.".into());
    }
    if apo(after).is_none() {
        warnings.push("No bound apoapsis is defined relative to this reference body.".into());
    }
    FlightBurnPreview {
        reference_radius_m: before.reference_radius_m,
        before_periapsis_m: peri(before),
        after_periapsis_m: peri(after),
        before_apoapsis_m: apo(before),
        after_apoapsis_m: apo(after),
        fuel_used_kg: fuel_used.max(0.0),
        elapsed_seconds: request.duration_seconds,
        warnings,
        epoch_seconds: before.epoch_seconds,
        revision: before.revision,
        before_guidance: before.guidance,
        after_guidance: after.guidance,
    }
}

pub(super) fn bind_plan(
    policy: &PhysicsPolicy,
    settings: &ScientificSettings,
    bodies: &[BodyState],
    _ships: &[SpacecraftState],
    delta: f64,
    features: &[String],
) -> Result<FixedStepPlan, RuntimeError> {
    let plan = fixed_step_plan_with_source_count(
        policy,
        delta,
        bodies.len(),
        bodies.iter().filter(|b| b.source_mass_kg > 0.0).count(),
        features,
    )
    .ok_or(RuntimeError::InvalidEpochDelta(delta))?
    .with_scientific_settings(
        settings,
        &bodies.iter().map(|b| b.body_id.clone()).collect::<Vec<_>>(),
    )
    .map_err(RuntimeError::ScientificFailure)?;
    Ok(plan)
}

pub(super) fn world_invariants(
    bodies: &[BodyState],
    settings: &ScientificSettings,
) -> Result<PhysicsInvariants, RuntimeError> {
    let policy = PhysicsPolicy {
        solver_backend: solarlab_physics::SolverBackend::ReferenceScalar,
        integrator: settings.integrator,
        collision_model: solarlab_physics::CollisionModel::None,
        max_substep_seconds: 1.0,
    };
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
    /// Surface radius of the same primary used for this preview's altitudes.
    pub reference_radius_m: f64,
    /// Altitudes above the reference surface, not radii from its centre.
    pub before_periapsis_m: Option<f64>,
    pub after_periapsis_m: Option<f64>,
    pub before_apoapsis_m: Option<f64>,
    pub after_apoapsis_m: Option<f64>,
    pub fuel_used_kg: f64,
    pub elapsed_seconds: f64,
    pub warnings: Vec<String>,
    pub epoch_seconds: f64,
    pub revision: u64,
    /// Native two-body navigation at each end of the full-model finite burn.
    pub before_guidance: Option<OrbitGuidance>,
    pub after_guidance: Option<OrbitGuidance>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use solarlab_domain::science::{IntegratorKind, ZonalHarmonic};

    fn world() -> WorldRuntime {
        let mut runtime = crate::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 0)
            .unwrap();
        runtime
            .spawn_reference_spacecraft("earth", "pilot", 400_000.0, 1)
            .unwrap();
        runtime
    }

    #[test]
    fn staged_harmonic_domain_failure_preserves_world_and_reason() {
        let mut runtime = world();
        let earth = runtime.body_by_id("earth").unwrap().clone();
        let radius = 6_378_137.0;
        runtime.apply_command(WorldCommand::SetBodyKinematics { body_id: BodyId("pilot".into()),
            position_m: Vector3d { x: earth.position_m.x + radius + 1.0, ..earth.position_m },
            velocity_mps: Vector3d { x: earth.velocity_mps.x - 1000.0, ..earth.velocity_mps },
        }, 2).unwrap();
        runtime.apply_command(WorldCommand::SetScientificSettings { settings: ScientificSettings {
            harmonics: vec![ZonalHarmonic { body_id: BodyId("earth".into()), j2: 1.08263e-3,
                reference_radius_m: radius, axis_ws: Vector3d { x: 0.0, y: 0.0, z: 1.0 } }],
            ..ScientificSettings::default()
        } }, 3).unwrap();
        let original = runtime.export_saved_session().unwrap();
        let ordinal = runtime.next_command_ordinal();
        let generation = runtime.begin_advance([41, 57], 0, ordinal, 0.1, 4).unwrap().generation;
        assert!(matches!(runtime.continue_advance(generation, 0), Err(RuntimeError::ScientificFailure(
            solarlab_physics::science::ScientificError::InsideHarmonicReferenceRadius))));
        assert_eq!(runtime.query_advance().unwrap().state, AdvanceState::Cancelled);
        assert_eq!(runtime.export_saved_session().unwrap(), original);
    }

    #[test]
    fn science_choice_survives_save_and_checkpoint_branch() {
        let mut runtime = world();
        let settings = ScientificSettings {
            integrator: IntegratorKind::YoshidaFourthOrder,
            ..ScientificSettings::default()
        };
        runtime
            .apply_command(
                WorldCommand::SetScientificSettings {
                    settings: settings.clone(),
                },
                2,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("science".into())),
                    label: None,
                },
                3,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::SetScientificSettings {
                    settings: ScientificSettings::default(),
                },
                4,
            )
            .unwrap();
        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: CheckpointId("science".into()),
                    new_branch_id: None,
                },
                5,
            )
            .unwrap();
        assert_eq!(runtime.scientific_settings(), &settings);
        let bytes = runtime.export_saved_session().unwrap();
        let restored =
            WorldRuntime::import_saved_session(&bytes, runtime.hardware_profile.clone()).unwrap();
        assert_eq!(restored.scientific_settings(), &settings);
        assert_eq!(restored.active_solver_execution_report().requested_integrator, settings.integrator);
        assert_eq!(restored.active_solver_execution_report().effective_integrator, settings.integrator);
        let mut document: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        document.as_object_mut().unwrap().remove("scientific_model");
        assert!(WorldRuntime::import_saved_session(
            &serde_json::to_vec(&document).unwrap(),
            runtime.hardware_profile.clone()
        )
        .is_err());
    }

    #[test]
    fn harmonic_source_removal_and_running_edits_are_atomic() {
        let mut runtime = world();
        let settings = ScientificSettings {
            harmonics: vec![ZonalHarmonic {
                body_id: BodyId("earth".into()),
                j2: 1.08263e-3,
                reference_radius_m: 6_378_137.0,
                axis_ws: Vector3d {
                    x: 0.0,
                    y: 0.0,
                    z: 1.0,
                },
            }],
            ..ScientificSettings::default()
        };
        runtime
            .apply_command(
                WorldCommand::SetScientificSettings {
                    settings: settings.clone(),
                },
                2,
            )
            .unwrap();
        let before = runtime.export_saved_session().unwrap();
        assert!(runtime
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId("earth".into())
                },
                3
            )
            .is_err());
        assert_eq!(runtime.export_saved_session().unwrap(), before);
        runtime
            .apply_command(WorldCommand::ResumePlayback, 4)
            .unwrap();
        let before = runtime.export_saved_session().unwrap();
        assert_eq!(
            runtime.apply_command(WorldCommand::SetScientificSettings { settings }, 5),
            Err(RuntimeError::BodyEditRequiresPause)
        );
        assert_eq!(runtime.export_saved_session().unwrap(), before);
    }

    #[test]
    fn preview_is_read_only_and_agrees_with_live_finite_burn() {
        let mut runtime = world();
        let original = runtime.export_saved_session().unwrap();
        let before_guidance = runtime.flight_telemetry("pilot").unwrap().guidance;
        let request = FlightBurnRequest {
            duration_seconds: 3.0,
            throttle: 0.3,
            attitude_mode: AttitudeMode::Prograde,
        };
        let preview = runtime.preview_flight_burn("pilot", request).unwrap();
        assert_eq!(runtime.export_saved_session().unwrap(), original);
        assert_eq!(preview.revision, runtime.snapshot_revision());
        assert_eq!(preview.before_guidance, before_guidance);
        let fuel = runtime
            .spacecraft_by_id("pilot")
            .unwrap()
            .propellant_mass_kg;
        runtime
            .apply_command(
                WorldCommand::SetFlightControl {
                    body_id: BodyId("pilot".into()),
                    control: FlightControl {
                        throttle: request.throttle,
                        attitude_mode: request.attitude_mode,
                        ..FlightControl::default()
                    },
                },
                2,
            )
            .unwrap();
        runtime
            .apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 3.0 }, 3)
            .unwrap();
        let after = runtime.flight_telemetry("pilot").unwrap();
        assert_eq!(preview.after_guidance, after.guidance);
        assert_eq!(
            preview.fuel_used_kg,
            fuel - runtime
                .spacecraft_by_id("pilot")
                .unwrap()
                .propellant_mass_kg
        );
        assert!(
            (preview.after_periapsis_m.unwrap()
                - (after.orbit.unwrap().periapsis_radius_m - after.reference_radius_m))
                .abs()
                < 1e-6
        );
        assert!(preview.fuel_used_kg > 0.0);
        let report = runtime.active_solver_execution_report().clone();
        assert!(report.nonconservative_split);
        runtime.apply_command(WorldCommand::PausePlayback, 4).unwrap();
        runtime.apply_command(WorldCommand::SetObserverMode { mode: ObserverMode::Free }, 5).unwrap();
        assert_eq!(runtime.active_solver_execution_report(), &report);
    }

    #[test]
    fn portable_constructor_method_is_saveable() {
        let base = world();
        let mut config = base.config.clone();
        config.physics.integrator = IntegratorKind::DormandPrince54;
        let runtime = WorldRuntime::new(ScenarioId("custom.portable".into()), BranchId("main".into()),
            config, base.hardware_profile.clone(), 0);
        let saved = runtime.export_saved_session().unwrap();
        let restored = WorldRuntime::import_saved_session(&saved, base.hardware_profile).unwrap();
        assert_eq!(restored.scientific_settings().integrator, IntegratorKind::DormandPrince54);
        assert_eq!(restored.active_solver_execution_report().requested_integrator, IntegratorKind::DormandPrince54);
    }
}

impl WorldRuntime {
    /// Calculates in private buffers at a paused committed boundary.
    pub fn preview_flight_burn(
        &self,
        body_id: &str,
        request: FlightBurnRequest,
    ) -> Result<FlightBurnPreview, RuntimeError> {
        let world = &self.active_branch().world;
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        if !world.playback.paused {
            return Err(RuntimeError::BodyEditRequiresPause);
        }
        if !request.duration_seconds.is_finite()
            || request.duration_seconds <= 0.0
            || request.duration_seconds > 600.0
            || !request.throttle.is_finite()
            || !(0.0..=1.0).contains(&request.throttle)
            || world.bodies.len() > 64
        {
            return Err(RuntimeError::InvalidFlightState);
        }
        let ship = self
            .spacecraft_by_id(body_id)
            .ok_or_else(|| RuntimeError::UnknownBody(BodyId(body_id.into())))?;
        if ship.docking.is_some() {
            return Err(RuntimeError::InvalidFlightState);
        }
        let before = self
            .flight_telemetry(body_id)
            .ok_or(RuntimeError::InvalidFlightState)?;
        let before_fuel = ship.propellant_mass_kg;
        let mut preview = Self::new(
            self.scenario_id.clone(),
            self.active_branch_id.clone(),
            self.config.clone(),
            self.hardware_profile.clone(),
            0,
        );
        preview.lab_step_policy = self.lab_step_policy;
        preview.active_branch_mut().world = world.clone();
        preview.apply_command(
            WorldCommand::SetFlightControl {
                body_id: BodyId(body_id.into()),
                control: FlightControl {
                    throttle: request.throttle,
                    attitude_mode: request.attitude_mode,
                    ..FlightControl::default()
                },
            },
            0,
        )?;
        let staged = &preview.active_branch().world;
        let plan = bind_plan(
            &preview.effective_physics_policy(),
            &staged.scientific_settings,
            &staged.bodies,
            &staged.spacecraft,
            request.duration_seconds,
            &self.hardware_profile.cpu_features,
        )?;
        if plan.total_steps > 16_384
            || plan
                .total_steps
                .saturating_mul(staged.bodies.len().saturating_pow(2))
                > 2_000_000
        {
            return Err(RuntimeError::AdvanceWorkLimit);
        }
        let mut bodies = world_bodies_to_solver_state(&staged.bodies);
        let mut ships = spacecraft::bindings(staged);
        let mut scientific_report = plan.empty_scientific_report();
        let mut remaining = plan.total_steps;
        while remaining > 0 {
            let count = remaining.min(MAX_INCREMENTAL_STEPS);
            scientific_report.accumulate(
                &solarlab_physics::spacecraft::advance_spacecraft_steps_checked(
                    &plan,
                    &mut bodies,
                    &mut ships,
                    count,
                )
                .map_err(RuntimeError::ScientificFailure)?,
            );
            remaining -= count;
        }
        plan.compute_invariants(&bodies)
            .map_err(RuntimeError::ScientificFailure)?;
        let staged = &mut preview.active_branch_mut().world;
        spacecraft::apply_bindings(staged, ships);
        apply_solver_state_to_world_bodies(&mut staged.bodies, &bodies);
        staged.epoch_seconds += request.duration_seconds;
        let after = preview
            .flight_telemetry(body_id)
            .ok_or(RuntimeError::InvalidFlightState)?;
        let after_fuel = preview
            .spacecraft_by_id(body_id)
            .ok_or(RuntimeError::InvalidFlightState)?
            .propellant_mass_kg;
        Ok(burn_receipt(
            &before,
            &after,
            before_fuel - after_fuel,
            after_fuel,
            request,
            scientific_report.nonconservative_split,
        ))
    }

    #[must_use]
    pub fn scientific_settings(&self) -> &ScientificSettings {
        &self.active_branch().world.scientific_settings
    }

    pub(super) fn scientific_command_invariants(
        &self,
        command: &WorldCommand,
    ) -> Result<Option<PhysicsInvariants>, RuntimeError> {
        let world = &self.active_branch().world;
        if let WorldCommand::SetScientificSettings { settings } = command {
            if !world.playback.paused {
                return Err(RuntimeError::BodyEditRequiresPause);
            }
            return world_invariants(&world.bodies, settings).map(Some);
        }
        if world.scientific_settings.harmonics.is_empty() {
            return Ok(None);
        }
        let mut bodies = world.bodies.clone();
        match command {
            WorldCommand::SpawnBody { body } | WorldCommand::SpawnSpacecraft { body, .. } => {
                bodies.push(body.clone())
            }
            WorldCommand::RemoveBody { body_id } => bodies.retain(|b| b.body_id != *body_id),
            WorldCommand::SetBodyKinematics {
                body_id,
                position_m,
                velocity_mps,
            } => {
                let b = bodies
                    .iter_mut()
                    .find(|b| b.body_id == *body_id)
                    .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                b.position_m = *position_m;
                b.velocity_mps = *velocity_mps;
            }
            WorldCommand::SetBodyProperties {
                body_id,
                mass_kg,
                source_mass_kg,
                radius_m,
                position_m,
                velocity_mps,
            } => {
                let b = bodies
                    .iter_mut()
                    .find(|b| b.body_id == *body_id)
                    .ok_or_else(|| RuntimeError::UnknownBody(body_id.clone()))?;
                b.mass_kg = *mass_kg;
                b.source_mass_kg = *source_mass_kg;
                b.radius_m = *radius_m;
                b.position_m = *position_m;
                b.velocity_mps = *velocity_mps;
            }
            _ => return Ok(None),
        }
        world_invariants(&bodies, &world.scientific_settings).map(Some)
    }
}
