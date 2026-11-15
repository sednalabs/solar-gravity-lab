//! Durable experiments contain authoritative values, never renderer packets or native handles.
//! Restoring validates the entire document before constructing a new, paused session. Pending
//! staged advances are deliberately not serialised: save only a committed world.
use super::*;
use serde::{Deserialize, Serialize};
use solarlab_hardware::HardwareProfile;
use solarlab_physics::{CollisionModel, IntegratorKind, SolverBackend};

pub const MAX_SAVED_SESSION_BYTES: usize = 16 * 1024 * 1024;
const FORMAT: &str = "solarlab.experiment";
const VERSION: u32 = 1;
const MODEL: &str = "newtonian-kdk-f64-lab-step-v1";
const MAX_BRANCHES: usize = 64;
const MAX_BODIES: usize = 4096;
const MAX_CHECKPOINTS: usize = 256;
const MAX_TOTAL_BODIES: usize = 131_072;
const MAX_COMMANDS: usize = 100_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionPersistenceError {
    AdvanceInProgress,
    TooLarge,
    InvalidDocument,
    UnsupportedVersion,
    InvalidState(&'static str),
}

impl std::fmt::Display for SessionPersistenceError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::AdvanceInProgress => {
                f.write_str("Wait for the advance to finish or cancel it before saving")
            }
            Self::TooLarge => f.write_str("Experiment exceeds the 16 MiB save limit"),
            Self::InvalidDocument => f.write_str("Experiment file is malformed"),
            Self::UnsupportedVersion => {
                f.write_str("Experiment format or physics model is unsupported")
            }
            Self::InvalidState(reason) => write!(f, "Invalid experiment: {reason}"),
        }
    }
}
impl std::error::Error for SessionPersistenceError {}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SavedSession {
    format: String,
    version: u32,
    model: String,
    runtime_version: String,
    source_revision: String,
    scenario_id: String,
    active_branch_id: String,
    config: SavedConfig,
    next_command_ordinal: u64,
    next_checkpoint_ordinal: u64,
    next_branch_ordinal: u64,
    branches: Vec<BranchState>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SavedConfig {
    solver_backend: String,
    integrator: String,
    collision_model: String,
    max_substep_seconds: f64,
    timeline_semantics: TimelineSemantics,
    live_updates_enabled: bool,
}

impl WorldRuntime {
    /// Save the complete branch/checkpoint tree at a committed boundary. This does not
    /// change playback or the live world. The byte representation is versioned separately
    /// from the process-local FFI ABI.
    pub fn export_saved_session(&self) -> Result<Vec<u8>, SessionPersistenceError> {
        if self.advance_running() {
            return Err(SessionPersistenceError::AdvanceInProgress);
        }
        let mut branches: Vec<_> = self.branches.values().cloned().collect();
        branches.sort_by(|a, b| a.descriptor.branch_id.0.cmp(&b.descriptor.branch_id.0));
        let saved = SavedSession {
            format: FORMAT.into(),
            version: VERSION,
            model: MODEL.into(),
            runtime_version: env!("CARGO_PKG_VERSION").into(),
            source_revision: option_env!("SOLARLAB_BUILD_COMMIT")
                .unwrap_or("unknown")
                .into(),
            scenario_id: self.scenario_id.0.clone(),
            active_branch_id: self.active_branch_id.0.clone(),
            config: SavedConfig::from_runtime(&self.config)?,
            next_command_ordinal: self.next_command_ordinal,
            next_checkpoint_ordinal: self.next_checkpoint_ordinal,
            next_branch_ordinal: self.next_branch_ordinal,
            branches,
        };
        saved.validate()?;
        let bytes =
            serde_json::to_vec(&saved).map_err(|_| SessionPersistenceError::InvalidDocument)?;
        if bytes.len() > MAX_SAVED_SESSION_BYTES {
            return Err(SessionPersistenceError::TooLarge);
        }
        Ok(bytes)
    }

    /// Import into a new session, keeping the existing live session untouched on failure.
    /// Hardware capabilities are supplied by this host, not trusted from the saved file.
    pub fn import_saved_session(
        bytes: &[u8],
        hardware_profile: HardwareProfile,
    ) -> Result<Self, SessionPersistenceError> {
        if bytes.len() > MAX_SAVED_SESSION_BYTES {
            return Err(SessionPersistenceError::TooLarge);
        }
        let saved: SavedSession =
            serde_json::from_slice(bytes).map_err(|_| SessionPersistenceError::InvalidDocument)?;
        saved.validate()?;
        let config = saved.config.to_runtime()?;
        let mut runtime = Self {
            scenario_id: ScenarioId(saved.scenario_id),
            config,
            hardware_profile,
            branches: saved
                .branches
                .into_iter()
                .map(|b| (b.descriptor.branch_id.clone(), b))
                .collect(),
            active_branch_id: BranchId(saved.active_branch_id),
            next_command_ordinal: saved.next_command_ordinal,
            next_checkpoint_ordinal: saved.next_checkpoint_ordinal,
            next_branch_ordinal: saved.next_branch_ordinal,
            advance_generation: 0,
            advance_operation: None,
        };
        for branch in runtime.branches.values_mut() {
            restore_computed_state(
                &mut branch.world,
                &runtime.config,
                &runtime.hardware_profile,
            )?;
            for checkpoint in &mut branch.checkpoints {
                restore_computed_state(
                    &mut checkpoint.state,
                    &runtime.config,
                    &runtime.hardware_profile,
                )?;
            }
        }
        runtime.active_branch_mut().world.playback.paused = true;
        Ok(runtime)
    }
}

impl SavedConfig {
    fn from_runtime(config: &RuntimeConfig) -> Result<Self, SessionPersistenceError> {
        if config.physics.integrator != IntegratorKind::LeapfrogKickDriftKick
            || config.physics.collision_model != CollisionModel::None
        {
            return Err(SessionPersistenceError::UnsupportedVersion);
        }
        Ok(Self {
            solver_backend: match config.physics.solver_backend {
                SolverBackend::ReferenceScalar => "scalar",
                SolverBackend::SimdArm64 => "arm64",
                SolverBackend::SimdX64 => "x64",
            }
            .into(),
            integrator: "leapfrog-kdk".into(),
            collision_model: "none".into(),
            max_substep_seconds: config.physics.max_substep_seconds,
            timeline_semantics: config.timeline_semantics.clone(),
            live_updates_enabled: config.live_updates_enabled,
        })
    }
    fn to_runtime(&self) -> Result<RuntimeConfig, SessionPersistenceError> {
        if self.integrator != "leapfrog-kdk" || self.collision_model != "none" {
            return Err(SessionPersistenceError::UnsupportedVersion);
        }
        if !self.max_substep_seconds.is_finite() || self.max_substep_seconds <= 0.0 {
            return Err(SessionPersistenceError::InvalidState(
                "invalid physics timestep",
            ));
        }
        Ok(RuntimeConfig {
            physics: solarlab_physics::PhysicsPolicy {
                solver_backend: match self.solver_backend.as_str() {
                    "scalar" => SolverBackend::ReferenceScalar,
                    "arm64" => SolverBackend::SimdArm64,
                    "x64" => SolverBackend::SimdX64,
                    _ => return Err(SessionPersistenceError::UnsupportedVersion),
                },
                integrator: IntegratorKind::LeapfrogKickDriftKick,
                collision_model: CollisionModel::None,
                max_substep_seconds: self.max_substep_seconds,
            },
            timeline_semantics: self.timeline_semantics.clone(),
            live_updates_enabled: self.live_updates_enabled,
        })
    }
}

fn valid_id(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= 96 && !value.chars().any(char::is_control)
}
fn valid_text(value: &str, max: usize) -> bool {
    value.len() <= max && !value.contains('\0')
}
fn finite_vector(v: Vector3d) -> bool {
    v.x.is_finite() && v.y.is_finite() && v.z.is_finite()
}

impl SavedSession {
    fn validate(&self) -> Result<(), SessionPersistenceError> {
        use SessionPersistenceError::InvalidState as invalid;
        if self.format != FORMAT || self.version != VERSION || self.model != MODEL {
            return Err(SessionPersistenceError::UnsupportedVersion);
        }
        self.config.to_runtime()?;
        if !valid_id(&self.scenario_id)
            || !valid_id(&self.active_branch_id)
            || !valid_text(&self.runtime_version, 128)
            || !valid_text(&self.source_revision, 128)
        {
            return Err(invalid("invalid session identity"));
        }
        if self.branches.is_empty()
            || self.branches.len() > MAX_BRANCHES
            || self.next_command_ordinal == 0
            || self.next_command_ordinal >= i64::MAX as u64
            || self.next_checkpoint_ordinal == 0
            || self.next_checkpoint_ordinal >= i64::MAX as u64
            || self.next_branch_ordinal == 0
            || self.next_branch_ordinal >= i64::MAX as u64
        {
            return Err(invalid("invalid branch count or sequence counters"));
        }
        let mut branch_ids = HashSet::new();
        let mut checkpoint_ids = HashSet::new();
        let mut checkpoint_owners = HashMap::new();
        let mut command_sequences = HashSet::new();
        let mut total_bodies = 0usize;
        let mut total_commands = 0usize;
        for branch in &self.branches {
            let desc = &branch.descriptor;
            if !valid_id(&desc.branch_id.0)
                || !branch_ids.insert(desc.branch_id.0.as_str())
                || desc.scenario_id.0 != self.scenario_id
            {
                return Err(invalid("duplicate or invalid branch identity"));
            }
            validate_world(&branch.world)?;
            total_bodies += branch.world.bodies.len();
            if branch.checkpoints.len() > MAX_CHECKPOINTS {
                return Err(invalid("too many checkpoints"));
            }
            for checkpoint in &branch.checkpoints {
                let d = &checkpoint.descriptor;
                if !valid_id(&d.checkpoint_id.0)
                    || !checkpoint_ids.insert(d.checkpoint_id.0.as_str())
                    || d.branch_id != desc.branch_id
                    || d.scenario_id != desc.scenario_id
                    || d.command_sequence >= self.next_command_ordinal
                    || d.epoch_seconds != checkpoint.state.epoch_seconds
                    || d.timeline_semantics != self.config.timeline_semantics
                    || d.label.as_ref().is_some_and(|s| !valid_text(s, 256))
                {
                    return Err(invalid("invalid checkpoint identity or world"));
                }
                checkpoint_owners.insert(d.checkpoint_id.0.as_str(), d.branch_id.0.as_str());
                validate_world(&checkpoint.state)?;
                total_bodies += checkpoint.state.bodies.len();
            }
            let mut previous = 0;
            for command in &branch.command_log {
                let h = &command.header;
                if h.branch_id != desc.branch_id
                    || h.sequence <= previous
                    || h.sequence >= self.next_command_ordinal
                    || !command_sequences.insert(h.sequence)
                    || !valid_id(&h.command_id.0)
                    || !valid_text(&h.command_kind, 128)
                    || !valid_text(&command.summary, 1024)
                {
                    return Err(invalid("invalid command history"));
                }
                previous = h.sequence;
            }
            total_commands += branch.command_log.len();
        }
        if total_bodies > MAX_TOTAL_BODIES || total_commands > MAX_COMMANDS {
            return Err(invalid("history exceeds experiment limits"));
        }
        if !branch_ids.contains(self.active_branch_id.as_str()) {
            return Err(invalid("active branch missing"));
        }
        for branch in &self.branches {
            let d = &branch.descriptor;
            match (&d.parent_branch_id, &d.parent_checkpoint_id) {
                (None, None) => {}
                (Some(parent), Some(checkpoint))
                    if parent != &d.branch_id
                        && checkpoint_owners.get(checkpoint.0.as_str()).copied()
                            == Some(parent.0.as_str()) => {}
                _ => return Err(invalid("broken branch parent/checkpoint reference")),
            }
            if let Some(last) = &branch.last_checkpoint {
                let exact = self
                    .branches
                    .iter()
                    .flat_map(|b| &b.checkpoints)
                    .any(|cp| cp.descriptor == *last);
                if !exact {
                    return Err(invalid("unknown last checkpoint"));
                }
            }
            // A small bounded walk rejects cycles even if every individual reference exists.
            let mut current = Some(&d.branch_id);
            let mut visited = HashSet::new();
            while let Some(id) = current {
                if !visited.insert(id) {
                    return Err(invalid("cyclic branch history"));
                }
                current = self
                    .branches
                    .iter()
                    .find(|b| &b.descriptor.branch_id == id)
                    .and_then(|b| b.descriptor.parent_branch_id.as_ref());
            }
        }
        Ok(())
    }
}

fn validate_world(world: &BranchWorldState) -> Result<(), SessionPersistenceError> {
    use SessionPersistenceError::InvalidState as invalid;
    if !world.epoch_seconds.is_finite()
        || world.epoch_seconds < 0.0
        || !world.playback.sim_seconds_per_real_second.is_finite()
        || world.playback.sim_seconds_per_real_second <= 0.0
        || world.bodies.len() > MAX_BODIES
    {
        return Err(invalid("invalid time, playback rate or body count"));
    }
    let mut ids = HashSet::new();
    for b in &world.bodies {
        if !valid_id(&b.body_id.0)
            || !ids.insert(&b.body_id)
            || !b.mass_kg.is_finite()
            || b.mass_kg < 0.0
            || !b.source_mass_kg.is_finite()
            || b.source_mass_kg < 0.0
            || !b.radius_m.is_finite()
            || b.radius_m < 0.0
            || !finite_vector(b.position_m)
            || !finite_vector(b.velocity_mps)
        {
            return Err(invalid("duplicate body or invalid physical value"));
        }
    }
    if world
        .observer
        .focus_body_id
        .as_ref()
        .is_some_and(|id| !ids.contains(id))
    {
        return Err(invalid("focus target missing"));
    }
    for (id, samples) in &world.trail_history_by_body {
        if !ids.contains(id) || samples.len() > TRAIL_HISTORY_MAX_SAMPLES {
            return Err(invalid("invalid trail body or sample count"));
        }
        let mut previous = -1.0;
        for s in samples {
            if !s.epoch_seconds.is_finite()
                || s.epoch_seconds < previous
                || s.epoch_seconds > world.epoch_seconds
                || !finite_vector(s.position_m)
            {
                return Err(invalid("invalid trail sample"));
            }
            previous = s.epoch_seconds;
        }
    }
    if let Some(seed) = &world.initial_conditions_provenance {
        if !valid_text(&seed.dataset_id, 256)
            || seed.dataset_id.is_empty()
            || !valid_text(&seed.version, 128)
            || !valid_text(&seed.digest, 256)
            || !seed.epoch_jd_tdb.is_finite()
            || !valid_text(&seed.frame, 128)
            || !valid_text(&seed.center, 128)
            || !valid_text(&seed.time_scale, 64)
        {
            return Err(invalid("invalid initial-condition provenance"));
        }
    }
    let store = &world.local_data_state.package_store.packages_by_id;
    if store.len() > 256 || world.mounted_package_ids.len() > 256 {
        return Err(invalid("too many data packages"));
    }
    for (id, package) in store {
        if id != &package.package_id
            || !valid_text(id, 256)
            || id.is_empty()
            || !valid_text(&package.local_store_uri, 2048)
            || package.digest.value.len() > 128
            || !valid_text(&package.digest.algorithm, 64)
            || !valid_text(&package.schema_version, 128)
        {
            return Err(invalid("invalid package metadata"));
        }
    }
    if let Some(manifest) = &world.local_data_state.installed_manifest {
        if !valid_text(&manifest.manifest_id, 256)
            || !valid_text(&manifest.channel, 128)
            || manifest
                .installed_package_ids
                .iter()
                .any(|id| !store.contains_key(id))
            || !world
                .mounted_package_ids
                .is_subset(&manifest.installed_package_ids)
        {
            return Err(invalid("invalid package provenance"));
        }
    } else if !world.mounted_package_ids.is_empty() {
        return Err(invalid("mounted package without manifest"));
    }
    Ok(())
}

fn restore_computed_state(
    world: &mut BranchWorldState,
    config: &RuntimeConfig,
    hardware: &HardwareProfile,
) -> Result<(), SessionPersistenceError> {
    world.invariants = compute_world_invariants(&world.bodies);
    if !world.invariants.total_energy_j.is_finite()
        || !finite_vector(world.invariants.barycenter_m)
        || !finite_vector(world.invariants.linear_momentum_kg_mps)
        || !finite_vector(world.invariants.angular_momentum_kg_m2ps)
    {
        return Err(SessionPersistenceError::InvalidState(
            "physical values overflow invariant calculation",
        ));
    }
    world.solver_execution = solver_execution_report_for_backend_with_body_count(
        &config.physics.solver_backend,
        &hardware.cpu_features,
        world.bodies.len(),
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn runtime() -> WorldRuntime {
        super::super::tests::new_runtime()
    }

    #[test]
    fn complete_experiment_roundtrip_retains_branches_sources_settings_and_provenance() {
        let mut world = runtime();
        super::super::tests::seed_major_bodies(&mut world);
        world
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("probe".into()),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: 123.0,
                        source_mass_kg: 17.0,
                        radius_m: 0.75,
                        position_m: Vector3d {
                            x: 1.5e11,
                            y: 1234.5,
                            z: -6.25,
                        },
                        velocity_mps: Vector3d {
                            x: -0.25,
                            y: 30000.0,
                            z: 42.0,
                        },
                    },
                },
                0,
            )
            .unwrap();
        world
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("saved-base".into())),
                    label: Some("Before experiment".into()),
                },
                1,
            )
            .unwrap();
        world
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: CheckpointId("saved-base".into()),
                    new_branch_id: Some(BranchId("experiment".into())),
                },
                2,
            )
            .unwrap();
        world
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 600.0,
                },
                3,
            )
            .unwrap();
        world
            .apply_command(WorldCommand::ResumePlayback, 4)
            .unwrap();
        let saved = world.export_saved_session().unwrap();
        let restored =
            WorldRuntime::import_saved_session(&saved, world.hardware_profile.clone()).unwrap();
        assert_eq!(restored.snapshot().bodies, world.snapshot().bodies);
        assert_eq!(restored.branches.len(), 2);
        assert_eq!(restored.active_branch_id, world.active_branch_id);
        assert_eq!(restored.config, world.config);
        assert_eq!(restored.next_command_ordinal, world.next_command_ordinal);
        assert_eq!(
            restored.active_branch().command_log,
            world.active_branch().command_log
        );
        assert_eq!(
            restored.active_branch().last_checkpoint,
            world.active_branch().last_checkpoint
        );
        assert!(restored.snapshot().playback.paused);
        assert_eq!(
            restored.snapshot().playback.sim_seconds_per_real_second,
            600.0
        );
        assert!(restored.query_advance().is_none());
    }

    #[test]
    fn captured_seed_provenance_survives_checkpoint_and_reopen() {
        let mut world =
            super::super::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        world
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        let seed = world.snapshot().initial_conditions_provenance.unwrap();
        assert_eq!(seed.digest, solarlab_data::EARTH_MOON_LAB_DIGEST);
        world
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                2,
            )
            .unwrap();
        let restored = WorldRuntime::import_saved_session(
            &world.export_saved_session().unwrap(),
            world.hardware_profile.clone(),
        )
        .unwrap();
        assert_eq!(
            restored.snapshot().initial_conditions_provenance,
            Some(seed)
        );
        assert_eq!(
            restored.render_scene().provenance.unwrap().manifest_digest,
            Some(solarlab_data::EARTH_MOON_LAB_DIGEST.into())
        );
    }

    #[test]
    fn malformed_versions_duplicates_nonfinite_and_oversize_are_rejected_without_mutation() {
        let mut world = runtime();
        super::super::tests::seed_major_bodies(&mut world);
        let before = world.snapshot();
        let saved = world.export_saved_session().unwrap();
        for edit in ["version", "duplicate", "nan", "focus"] {
            let mut doc: serde_json::Value = serde_json::from_slice(&saved).unwrap();
            match edit {
                "version" => doc["version"] = 999.into(),
                "duplicate" => {
                    let body = doc["branches"][0]["world"]["bodies"][0].clone();
                    doc["branches"][0]["world"]["bodies"]
                        .as_array_mut()
                        .unwrap()
                        .push(body);
                }
                "nan" => {
                    doc["branches"][0]["world"]["bodies"][0]["position_m"]["x"] =
                        serde_json::Value::Null
                }
                _ => doc["branches"][0]["world"]["observer"]["focus_body_id"] = "missing".into(),
            }
            assert!(WorldRuntime::import_saved_session(
                &serde_json::to_vec(&doc).unwrap(),
                world.hardware_profile.clone()
            )
            .is_err());
        }
        assert_eq!(
            WorldRuntime::import_saved_session(
                &vec![0; MAX_SAVED_SESSION_BYTES + 1],
                world.hardware_profile.clone()
            )
            .unwrap_err(),
            SessionPersistenceError::TooLarge
        );
        assert_eq!(world.snapshot(), before);
    }

    #[test]
    fn saving_staged_work_is_rejected_and_cancelled_work_does_not_leak() {
        let mut world = runtime();
        super::super::tests::seed_major_bodies(&mut world);
        let before = world.snapshot().bodies;
        let ordinal = world.next_command_ordinal;
        world.begin_advance([1, 2], 0, ordinal, 10000.0, 1).unwrap();
        world.continue_advance(1, 0).unwrap();
        assert_eq!(
            world.export_saved_session().unwrap_err(),
            SessionPersistenceError::AdvanceInProgress
        );
        world.cancel_advance(1).unwrap();
        let restored = WorldRuntime::import_saved_session(
            &world.export_saved_session().unwrap(),
            world.hardware_profile.clone(),
        )
        .unwrap();
        assert_eq!(restored.snapshot().bodies, before);
    }
}
