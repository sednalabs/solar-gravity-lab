//! Session construction and installed data-package lifecycle.
//! Authoritative mutations remain on the single WorldRuntime; no shadow world is created.
use super::*;

impl WorldRuntime {
    /// Session constructor initializes the root branch, baseline counters, and
    /// empty mutable state for deterministic branch/counter progression.
    #[must_use]
    pub fn new(
        scenario_id: ScenarioId,
        root_branch_id: BranchId,
        config: RuntimeConfig,
        hardware_profile: HardwareProfile,
        created_at_unix_ms: i64,
    ) -> Self {
        let initial_solver_execution = solver_execution_report_for_backend_with_source_count(
            &config.physics.solver_backend,
            &hardware_profile.cpu_features,
            0,
            0,
        );
        let root_descriptor = BranchDescriptor {
            branch_id: root_branch_id.clone(),
            scenario_id: scenario_id.clone(),
            created_at_unix_ms,
            parent_branch_id: None,
            parent_checkpoint_id: None,
        };
        let root_state = BranchState {
            descriptor: root_descriptor,
            world: BranchWorldState {
                epoch_seconds: 0.0,
                bodies: Vec::new(),
                spacecraft: Vec::new(),
                observer: ObserverState {
                    mode: ObserverMode::Free,
                    focus_body_id: None,
                },
                playback: PlaybackState {
                    paused: true,
                    sim_seconds_per_real_second: 1.0,
                },
                invariants: PhysicsInvariants::default(),
                local_data_state: LocalDataState::empty(),
                mounted_package_ids: BTreeSet::new(),
                initial_conditions_provenance: None,
                trail_history_by_body: HashMap::new(),
                solver_execution: initial_solver_execution,
            },
            command_log: Vec::new(),
            checkpoints: Vec::new(),
            last_checkpoint: None,
        };

        let mut branches = HashMap::new();
        branches.insert(root_branch_id.clone(), root_state);

        let mut runtime = Self {
            scenario_id,
            config,
            lab_step_policy: lab_step::LabStepPolicy::PeriapsisV2,
            hardware_profile,
            branches,
            active_branch_id: root_branch_id,
            next_command_ordinal: 1,
            next_checkpoint_ordinal: 1,
            next_branch_ordinal: 1,
            advance_generation: 0,
            advance_operation: None,
            flight_prediction_cache: flight_prediction::FlightPredictionCache::default(),
        };

        let active_branch_id = runtime.active_branch_id.clone();
        runtime.bump_next_branch_ordinal_if_needed(&active_branch_id);
        runtime
    }

    #[must_use]
    pub fn active_branch_id(&self) -> &BranchId {
        &self.active_branch_id
    }

    #[must_use]
    pub fn active_solver_execution_report(&self) -> &SolverExecutionReport {
        &self.active_branch().world.solver_execution
    }

    /// Apply a package manifest to the active branch data state.
    /// This can change installed package state and mounted-package visibility, but
    /// does not directly mutate simulation motion state.
    pub fn apply_update_manifest(
        &mut self,
        command: ApplyUpdateManifestCommand,
    ) -> Result<ApplyUpdateManifestResult, RuntimeError> {
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        let branch = self.active_branch_mut();

        let plan = plan_manifest_update(
            &command.manifest,
            &command.target,
            &branch.world.local_data_state,
        )
        .map_err(RuntimeError::PackagePlanFailed)?;

        let applied_update = apply_update_plan(
            &plan,
            &branch.world.local_data_state,
            &ApplyPackageInputs {
                fetched_packages_by_id: command.fetched_packages_by_id,
            },
        )
        .map_err(RuntimeError::PackageApplyFailed)?;

        branch.world.local_data_state = applied_update.committed_state;
        if let Some(installed_manifest) = &branch.world.local_data_state.installed_manifest {
            branch.world.mounted_package_ids.retain(|package_id| {
                installed_manifest
                    .installed_package_ids
                    .contains(package_id)
            });
        } else {
            branch.world.mounted_package_ids.clear();
        }

        let mounted_manifest = mounted_manifest_from_state(
            &branch.world.local_data_state,
            &branch.world.mounted_package_ids,
        )?;

        Ok(ApplyUpdateManifestResult {
            plan,
            provenance: applied_update.provenance,
            mounted_manifest,
        })
    }

    /// Explicitly mark an installed package as mounted.
    /// Runtime tracks mount state independently from command replay to keep shell
    /// intent explicit and observable.
    pub fn mount_package(
        &mut self,
        command: MountPackageCommand,
    ) -> Result<MountedManifestState, RuntimeError> {
        if self.advance_running() {
            return Err(RuntimeError::AdvanceBusy);
        }
        // Mark a package as explicitly mounted for shell intent visibility.
        // Mount intent is tracked by runtime and contributes to snapshot/reporting.
        let branch = self.active_branch_mut();
        let Some(installed_manifest) = &branch.world.local_data_state.installed_manifest else {
            return Err(RuntimeError::NoInstalledManifestAvailable);
        };

        if !installed_manifest
            .installed_package_ids
            .contains(&command.package_id)
        {
            return Err(RuntimeError::PackageNotInstalled(command.package_id));
        }
        if !branch
            .world
            .local_data_state
            .package_store
            .packages_by_id
            .contains_key(&command.package_id)
        {
            return Err(RuntimeError::MountedPackageMissingFromStore(
                command.package_id,
            ));
        }

        branch.world.mounted_package_ids.insert(command.package_id);
        let Some(mounted_manifest) = mounted_manifest_from_state(
            &branch.world.local_data_state,
            &branch.world.mounted_package_ids,
        )?
        else {
            return Err(RuntimeError::NoInstalledManifestAvailable);
        };

        Ok(mounted_manifest)
    }
}
