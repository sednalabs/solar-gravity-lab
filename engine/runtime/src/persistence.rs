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
const FLIGHT_VERSION: u32 = 2;
const FLIGHT_MODEL: &str = "six-dof-strang-rocket-v1";
const FLIGHT_PROGRAM_MODEL: &str = "six-dof-strang-rocket-v2";
const RENDEZVOUS_FLIGHT_MODEL: &str = "six-dof-strang-rocket-rendezvous-v3";
const DOCKING_FLIGHT_MODEL: &str = "six-dof-strang-rocket-docking-v4";
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
    #[serde(default, skip_serializing_if="Option::is_none")]
    flight_model: Option<String>,
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
        let has_flight = branches.iter().any(|b|!b.world.spacecraft.is_empty()||b.checkpoints.iter().any(|c|!c.state.spacecraft.is_empty()));
        let mut saved = SavedSession {
            format: FORMAT.into(),
            version: if has_flight { FLIGHT_VERSION } else { VERSION },
            flight_model: has_flight.then(|| {
                if branches_have_docking(&branches) { DOCKING_FLIGHT_MODEL }
                else if branches_have_rendezvous(&branches) { RENDEZVOUS_FLIGHT_MODEL }
                else if branches_have_flight_program(&branches) { FLIGHT_PROGRAM_MODEL }
                else { FLIGHT_MODEL }.into()
            }),
            model: self.lab_step_policy.model_id().into(),
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
        // Validate the same derived state that import reconstructs. Work only on the
        // cloned document, so rejection leaves the live session and history untouched.
        saved.restore_computed_states(&self.config, &self.hardware_profile)?;
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
        let mut saved: SavedSession =
            serde_json::from_slice(bytes).map_err(|_| SessionPersistenceError::InvalidDocument)?;
        saved.validate()?;
        let lab_step_policy = lab_step::LabStepPolicy::from_model_id(&saved.model)
            .ok_or(SessionPersistenceError::UnsupportedVersion)?;
        let config = saved.config.to_runtime()?;
        saved.restore_computed_states(&config, &hardware_profile)?;
        let mut runtime = Self {
            scenario_id: ScenarioId(saved.scenario_id),
            config,
            lab_step_policy,
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
            flight_prediction_cache: flight_prediction::FlightPredictionCache::default(),
        };
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
    fn restore_computed_states(
        &mut self,
        config: &RuntimeConfig,
        hardware: &HardwareProfile,
    ) -> Result<(), SessionPersistenceError> {
        for branch in &mut self.branches {
            restore_computed_state(&mut branch.world, config, hardware)?;
            for checkpoint in &mut branch.checkpoints {
                restore_computed_state(&mut checkpoint.state, config, hardware)?;
            }
        }
        Ok(())
    }

    fn validate(&self) -> Result<(), SessionPersistenceError> {
        use SessionPersistenceError::InvalidState as invalid;
        let has_flight = self.branches.iter().any(|b|!b.world.spacecraft.is_empty()||b.checkpoints.iter().any(|c|!c.state.spacecraft.is_empty()));
        let compatible_flight_model = match self.flight_model.as_deref() {
            Some(DOCKING_FLIGHT_MODEL) => true,
            Some(RENDEZVOUS_FLIGHT_MODEL) => !branches_have_docking(&self.branches),
            Some(FLIGHT_PROGRAM_MODEL) => !branches_have_docking(&self.branches) && !branches_have_rendezvous(&self.branches),
            Some(FLIGHT_MODEL) => !branches_have_docking(&self.branches) && !branches_have_rendezvous(&self.branches)
                && !branches_have_flight_program(&self.branches),
            _ => false,
        };
        let compatible_version = (self.version == VERSION && !has_flight && self.flight_model.is_none())
            || (self.version == FLIGHT_VERSION && compatible_flight_model);
        if self.format != FORMAT || !compatible_version
            || lab_step::LabStepPolicy::from_model_id(&self.model).is_none() {
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

fn branches_have_flight_program(branches: &[BranchState]) -> bool {
    branches.iter().flat_map(|b| std::iter::once(&b.world).chain(b.checkpoints.iter().map(|c| &c.state)))
        .flat_map(|w| &w.spacecraft).any(|s| s.burn_program.is_some() || s.mission.is_some())
}

fn branches_have_docking(branches: &[BranchState]) -> bool {
    branches.iter().flat_map(|b| std::iter::once(&b.world).chain(b.checkpoints.iter().map(|c| &c.state)))
        .flat_map(|w| &w.spacecraft).any(|s| s.docking.is_some())
}

fn branches_have_rendezvous(branches: &[BranchState]) -> bool {
    branches.iter().flat_map(|b| std::iter::once(&b.world).chain(b.checkpoints.iter().map(|c| &c.state)))
        .flat_map(|w| &w.spacecraft).any(|s| s.rendezvous.is_some() || s.appearance != solarlab_domain::SpacecraftAppearance::Asteria)
}

fn validate_world(world: &BranchWorldState) -> Result<(), SessionPersistenceError> {
    use SessionPersistenceError::InvalidState as invalid;
    if !world.epoch_seconds.is_finite()
        || world.epoch_seconds < 0.0
        || !world.playback.sim_seconds_per_real_second.is_finite()
        || world.playback.sim_seconds_per_real_second <= 0.0
        || world.bodies.len() > MAX_BODIES
        || (world.spacecraft.iter().any(|s| s.docking.is_some()) && world.playback.sim_seconds_per_real_second > 1.0)
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
    let mut spacecraft_ids = HashSet::new();
    for ship in &world.spacecraft {
        let Some(body)=world.bodies.iter().find(|b|b.body_id==ship.body_id) else {return Err(invalid("spacecraft body missing"));};
        if !spacecraft_ids.insert(&ship.body_id) || !spacecraft::valid_ship_body(body,ship) || !spacecraft::valid_mission(world,body,ship) || ship.reference_body_id.as_ref().is_some_and(|id|*id==ship.body_id || !ids.contains(id)) {
            return Err(invalid("invalid spacecraft component or reference"));
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
    world.solver_execution = solver_execution_report_for_backend_with_source_count(
        &config.physics.solver_backend,
        &hardware.cpu_features,
        world.bodies.len(),
        world.bodies.iter().filter(|body| body.source_mass_kg != 0.0).count(),
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn runtime() -> WorldRuntime {
        super::super::tests::new_runtime()
    }

    fn eccentric_lab(policy: lab_step::LabStepPolicy) -> WorldRuntime {
        let mut world = super::super::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        world.lab_step_policy = policy;
        world.config.physics.max_substep_seconds = 60.0;
        let earth_mass = 5.9722e24;
        let apogee = 40_000_000.0 * (1.0 + 0.82);
        let speed = (6.67430e-11_f64 * earth_mass * (2.0 / apogee - 1.0 / 40_000_000.0)).sqrt();
        for body in [
            BodyState {
                body_id: BodyId("earth".into()), body_class: BodyClass::Planet,
                mass_kg: earth_mass, source_mass_kg: earth_mass, radius_m: 6_371_000.0,
                position_m: Vector3d::default(), velocity_mps: Vector3d::default(),
            },
            BodyState {
                body_id: BodyId("eccentric-probe".into()), body_class: BodyClass::Spacecraft,
                mass_kg: 1_000.0, source_mass_kg: 0.0, radius_m: 1.0,
                position_m: Vector3d { x: apogee, y: 0.0, z: 0.0 },
                velocity_mps: Vector3d { x: 0.0, y: speed, z: 0.0 },
            },
        ] {
            world.apply_command(WorldCommand::SpawnBody { body }, 1).unwrap();
        }
        world
    }

    #[test]
    fn legacy_saved_model_keeps_its_step_policy_and_future_after_reopen() {
        let mut original = eccentric_lab(lab_step::LabStepPolicy::LegacyDistanceV1);
        let document = original.export_saved_session().unwrap();
        let encoded: serde_json::Value = serde_json::from_slice(&document).unwrap();
        assert_eq!(encoded["model"], "newtonian-kdk-f64-lab-step-v1");
        let mut reopened = WorldRuntime::import_saved_session(&document, original.hardware_profile.clone()).unwrap();
        assert_eq!(reopened.lab_step_policy, lab_step::LabStepPolicy::LegacyDistanceV1);
        assert_eq!(original.effective_physics_policy(), reopened.effective_physics_policy());
        original.apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 3_600.0 }, 2).unwrap();
        reopened.apply_command(WorldCommand::AdvanceEpoch { delta_seconds: 3_600.0 }, 2).unwrap();
        assert_eq!(original.snapshot().bodies, reopened.snapshot().bodies);
        let resaved: serde_json::Value = serde_json::from_slice(&reopened.export_saved_session().unwrap()).unwrap();
        assert_eq!(resaved["model"], encoded["model"]);
    }

    #[test]
    fn new_saved_model_preserves_the_refined_policy_and_rejects_unknown_models() {
        let original = eccentric_lab(lab_step::LabStepPolicy::PeriapsisV2);
        let legacy = eccentric_lab(lab_step::LabStepPolicy::LegacyDistanceV1);
        assert!(original.effective_physics_policy().max_substep_seconds < legacy.effective_physics_policy().max_substep_seconds / 5.0);
        let document = original.export_saved_session().unwrap();
        let mut encoded: serde_json::Value = serde_json::from_slice(&document).unwrap();
        assert_eq!(encoded["model"], "newtonian-kdk-f64-lab-step-v2");
        let reopened = WorldRuntime::import_saved_session(&document, original.hardware_profile.clone()).unwrap();
        assert_eq!(reopened.lab_step_policy, lab_step::LabStepPolicy::PeriapsisV2);
        assert_eq!(reopened.effective_physics_policy(), original.effective_physics_policy());
        assert_eq!(reopened.snapshot().bodies, original.snapshot().bodies);
        encoded["model"] = "newtonian-kdk-f64-lab-step-v999".into();
        assert_eq!(WorldRuntime::import_saved_session(&serde_json::to_vec(&encoded).unwrap(), original.hardware_profile.clone()).unwrap_err(),
            SessionPersistenceError::UnsupportedVersion);
    }

    fn spawn_finite_body_with_overflowing_invariants(world: &mut WorldRuntime) {
        let earth = world
            .snapshot()
            .bodies
            .into_iter()
            .find(|body| body.body_id.0 == "earth")
            .unwrap();
        world
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("overflow-probe".into()),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: 1.0e300,
                        source_mass_kg: 1.0e300,
                        radius_m: 1.0,
                        position_m: Vector3d {
                            x: earth.position_m.x + 7.17e6,
                            ..earth.position_m
                        },
                        velocity_mps: earth.velocity_mps,
                    },
                },
                2,
            )
            .unwrap();
    }

    #[test]
    fn failed_save_rejects_finite_body_overflow_without_changing_live_session() {
        let mut world =
            super::super::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
        world
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .unwrap();
        let original_bodies = world.snapshot().bodies;
        spawn_finite_body_with_overflowing_invariants(&mut world);
        let branches_before = serde_json::to_value(&world.branches).unwrap();
        let branch_before = world.active_branch_id.clone();
        let command_ordinal_before = world.next_command_ordinal;
        let energy_before = world.snapshot().invariants.total_energy_j.to_bits();

        assert_eq!(
            world.export_saved_session().unwrap_err(),
            SessionPersistenceError::InvalidState("physical values overflow invariant calculation")
        );

        assert_eq!(
            serde_json::to_value(&world.branches).unwrap(),
            branches_before
        );
        assert_eq!(world.active_branch_id, branch_before);
        assert_eq!(world.next_command_ordinal, command_ordinal_before);
        assert_eq!(
            world.snapshot().invariants.total_energy_j.to_bits(),
            energy_before
        );
        // A rejected save does not replace or poison the session: the user can remove
        // the offending body and immediately save/reopen the original physical state.
        world
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId("overflow-probe".into()),
                },
                3,
            )
            .unwrap();
        let reopened = WorldRuntime::import_saved_session(
            &world.export_saved_session().unwrap(),
            world.hardware_profile.clone(),
        )
        .unwrap();
        assert_eq!(reopened.snapshot().bodies, original_bodies);
    }

    #[test]
    fn save_checks_inactive_branches_and_checkpoints_for_invariant_overflow() {
        for overflow_in_checkpoint in [false, true] {
            let mut world =
                super::super::tests::new_runtime_with_scenario(solarlab_data::EARTH_MOON_LAB_ID);
            world
                .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
                .unwrap();
            world
                .apply_command(
                    WorldCommand::CreateCheckpoint {
                        checkpoint_id: Some(CheckpointId("healthy".into())),
                        label: None,
                    },
                    1,
                )
                .unwrap();
            spawn_finite_body_with_overflowing_invariants(&mut world);
            if overflow_in_checkpoint {
                world
                    .apply_command(
                        WorldCommand::CreateCheckpoint {
                            checkpoint_id: Some(CheckpointId("overflow".into())),
                            label: None,
                        },
                        3,
                    )
                    .unwrap();
                world
                    .apply_command(
                        WorldCommand::RemoveBody {
                            body_id: BodyId("overflow-probe".into()),
                        },
                        4,
                    )
                    .unwrap();
            } else {
                world
                    .apply_command(
                        WorldCommand::CreateBranchFromCheckpoint {
                            checkpoint_id: CheckpointId("healthy".into()),
                            new_branch_id: Some(BranchId("healthy-branch".into())),
                        },
                        3,
                    )
                    .unwrap();
            }
            assert!(world.snapshot().invariants.total_energy_j.is_finite());
            let before = world.snapshot();
            assert_eq!(
                world.export_saved_session().unwrap_err(),
                SessionPersistenceError::InvalidState(
                    "physical values overflow invariant calculation"
                ),
                "checkpoint-only overflow: {overflow_in_checkpoint}"
            );
            assert_eq!(world.snapshot(), before);
        }
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
