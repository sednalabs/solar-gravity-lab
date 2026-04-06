//! Authoritative v2 runtime core for simulation state.
//!
//! This crate owns all mutable world state and branch history for the Rust
//! runtime architecture. All external integrations (FFI, JNI, adapters,
//! services) should treat this crate as the single source of truth for world
//! semantics.
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::mem::size_of;
use std::time::Instant;

use solarlab_data::{
    apply_update_plan, canonical_startup_seed, plan_manifest_update, ApplyPackageInputs,
    ApplyProvenance, ApplyUpdateError, CompatibilityTarget, Digest, LocalDataState, PackageKind,
    SemVer, StoredPackage, UpdateManifest, UpdatePlan, UpdatePlanError,
};

use solarlab_domain::{
    BodyClass, BodyId, BranchId, CheckpointId, ObserverMode, ScenarioId, TimelineSemantics,
    Vector3d,
};
use solarlab_hardware::HardwareProfile;
use solarlab_history::{
    BranchDescriptor, CheckpointDescriptor, CommandId, CommandRecord, CommandRecordHeader,
    HistoryEvent,
};
use solarlab_physics::{
    advance_authoritative_with_features, compute_invariants, MassiveBodyState, PhysicsInvariants,
    PhysicsPolicy, SolverExecutionReport,
};
use solarlab_scene::{
    CameraPose, ColorRgba, LightSource, RenderDiagnostics, RenderScene, SceneBody,
    ScenePacketMetadata, SceneProvenanceRef, SceneTracer, SceneTrail,
};

#[derive(Clone, Debug, PartialEq)]
pub struct BodyState {
    pub body_id: BodyId,
    pub body_class: BodyClass,
    pub mass_kg: f64,
    pub radius_m: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlaybackState {
    /// Playback controls are intentionally small and copy-friendly so shell snapshots
    /// can transport sim-speed intent without runtime coupling.
    pub paused: bool,
    pub sim_seconds_per_real_second: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ObserverState {
    /// Observer state is persisted in snapshots to preserve viewer semantics when
    /// replaying snapshots or restoring checkpoints.
    pub mode: ObserverMode,
    pub focus_body_id: Option<BodyId>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RuntimeConfig {
    /// Immutable runtime policy selected for this session.
    /// Changes to these knobs are represented as explicit commands or data updates.
    pub physics: PhysicsPolicy,
    pub timeline_semantics: TimelineSemantics,
    pub live_updates_enabled: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountedPackageState {
    /// Snapshot-friendly package metadata used for diagnostics, checkpoints, and
    /// restore assertions. Runtime keeps authoritative package state.
    pub package_id: String,
    pub kind: PackageKind,
    pub package_version: SemVer,
    pub schema_version: String,
    pub digest: Digest,
    pub local_store_uri: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountedManifestState {
    /// Snapshot-friendly view of the currently installed manifest.
    pub manifest_id: String,
    pub manifest_version: SemVer,
    pub channel: String,
    pub manifest_digest: Option<Digest>,
    pub mounted_packages: Vec<MountedPackageState>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WorldSnapshot {
    /// Copy-based snapshot boundary: external callers only get immutable
    /// world-facing data; they cannot mutate runtime directly.
    ///
    /// Snapshots are published after every authoritative state transition (command
    /// application or simulation step). The data is decoupled from the internal
    /// tree-based branch state to ensure stable observation across FFI.
    pub scenario_id: ScenarioId,
    pub branch_id: BranchId,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
    pub bodies: Vec<BodyState>,
    pub active_checkpoint: Option<CheckpointDescriptor>,
    pub hardware_profile: HardwareProfile,
    pub invariants: PhysicsInvariants,
    pub observer: ObserverState,
    pub playback: PlaybackState,
    pub mounted_manifest: Option<MountedManifestState>,
    pub trail_history_by_body: HashMap<BodyId, Vec<Vector3d>>,
    pub solver_execution: SolverExecutionReport,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RuntimeTrailHistoryCount {
    /// Per-body trail sample totals for telemetry consumers.
    pub body_id: BodyId,
    pub sample_count: usize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct MountedManifestTelemetry {
    pub manifest_id: String,
    pub manifest_version: String,
    pub channel: String,
    pub has_manifest_digest: bool,
    pub mounted_packages: usize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RuntimeTelemetryReport {
    /// Compact, runtime-native report for diagnostics and telemetry surfaces.
    pub scenario_id: ScenarioId,
    pub branch_id: BranchId,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
    pub active_checkpoint_id: Option<CheckpointId>,
    pub total_bodies: usize,
    pub total_tracers: usize,
    pub trail_history_counts: Vec<RuntimeTrailHistoryCount>,
    pub total_trail_samples: usize,
    pub invariants: PhysicsInvariants,
    pub hardware_profile: HardwareProfile,
    pub playback: PlaybackState,
    pub observer: ObserverState,
    pub mounted_manifest: Option<MountedManifestTelemetry>,
    pub scene_revision: String,
    pub diagnostics: RenderDiagnostics,
    pub solver_execution: SolverExecutionReport,
}

#[derive(Clone, Debug, PartialEq)]
pub enum WorldCommand {
    /// Seeds the canonical startup solar-system catalog into an empty world.
    /// Runtime owns the catalog truth; host clients only request this action.
    SeedCanonicalSolarSystem,
    /// Spawns a new body into the simulation.
    /// This triggers a recomputation of total system mass and barycenter invariants.
    SpawnBody { body: BodyState },
    /// Removes an existing body.
    /// This is a material state change that will branch the sandbox if current
    /// semantics are based on a fixed catalog.
    RemoveBody { body_id: BodyId },
    /// Directly updates a body's position or velocity.
    /// Intended for user "grab and launch" interactions or small corrections.
    SetBodyKinematics {
        body_id: BodyId,
        position_m: Vector3d,
        velocity_mps: Vector3d,
    },
    /// Advances the system epoch.
    /// This invokes the authoritative physics solver for the delta duration.
    AdvanceEpoch { delta_seconds: f64 },
    /// Pauses simulation propagation.
    PausePlayback,
    /// Resumes simulation propagation.
    ResumePlayback,
    /// Sets the time-scale multiplier for real-time playback.
    SetPlaybackRate { sim_seconds_per_real_second: f64 },
    /// Changes the camera mode (e.g., from Free to Follow).
    SetObserverMode { mode: ObserverMode },
    /// Selects a specific body for the observer focus.
    FocusBody { body_id: Option<BodyId> },
    /// Captures the current authoritative state as a named or ID-based checkpoint.
    /// Checkpoints are required before a branch can be created.
    CreateCheckpoint {
        checkpoint_id: Option<CheckpointId>,
        label: Option<String>,
    },
    /// Forks the timeline into a new branch starting from a checkpoint.
    /// This preserves the command log of the parent up to the checkpoint and
    /// enables non-destructive "what-if" explorations.
    CreateBranchFromCheckpoint {
        checkpoint_id: CheckpointId,
        new_branch_id: Option<BranchId>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub enum RuntimeEvent {
    /// Event emissions are the stable boundary for lifecycle tracking and tests.
    HistoryAppended(HistoryEvent),
    SnapshotPublished(WorldSnapshot),
}

#[derive(Clone, Debug, PartialEq)]
pub enum RuntimeError {
    /// Stable runtime-facing invalid-state errors intended to map cleanly to ABI status
    /// responses.
    DuplicateBody(BodyId),
    UnknownBody(BodyId),
    InvalidEpochDelta(f64),
    InvalidPlaybackRate(f64),
    UnknownCheckpoint(CheckpointId),
    DuplicateCheckpoint(CheckpointId),
    DuplicateBranch(BranchId),
    PackagePlanFailed(UpdatePlanError),
    PackageApplyFailed(ApplyUpdateError),
    NoInstalledManifestAvailable,
    PackageNotInstalled(String),
    MountedPackageMissingFromStore(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplyUpdateManifestCommand {
    /// One explicit package update transaction request.
    /// Runtime owns package manifests and update application details; this is the
    /// immutable input boundary.
    pub manifest: UpdateManifest,
    pub target: CompatibilityTarget,
    pub fetched_packages_by_id: BTreeMap<String, StoredPackage>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountPackageCommand {
    /// Opaque package identity request from shells to mark installed data as mounted.
    pub package_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplyUpdateManifestResult {
    /// Result is intentionally explicit to keep shell-facing diagnostics stable.
    pub plan: UpdatePlan,
    pub provenance: ApplyProvenance,
    pub mounted_manifest: Option<MountedManifestState>,
}

#[derive(Clone, Debug, PartialEq)]
struct BranchWorldState {
    epoch_seconds: f64,
    bodies: Vec<BodyState>,
    observer: ObserverState,
    playback: PlaybackState,
    invariants: PhysicsInvariants,
    local_data_state: LocalDataState,
    mounted_package_ids: BTreeSet<String>,
    trail_history_by_body: HashMap<BodyId, Vec<Vector3d>>,
    solver_execution: SolverExecutionReport,
}

#[derive(Clone, Debug, PartialEq)]
struct CheckpointRecord {
    descriptor: CheckpointDescriptor,
    state: BranchWorldState,
}

#[derive(Clone, Debug, PartialEq)]
struct BranchState {
    descriptor: BranchDescriptor,
    world: BranchWorldState,
    command_log: Vec<CommandRecord>,
    checkpoints: Vec<CheckpointRecord>,
    last_checkpoint: Option<CheckpointDescriptor>,
}

const TRAIL_HISTORY_MAX_SAMPLES: usize = 96;

#[derive(Clone, Debug)]
pub struct WorldRuntime {
    /// Simulation-owned mutable state and history.
    ///
    /// The runtime implements a tree-based branching model where the "Active Branch"
    /// represents the current writable timeline. Users can capture Checkpoints at any
    /// point and later create new Branches from those checkpoints to explore alternative
    /// scenarios without losing the original history.
    ///
    /// Every branch maintains its own authoritative WorldState and a CommandLog
    /// that records every material change since the branch was created. This log
    /// ensures that any state can be deterministically replayed or verified.
    scenario_id: ScenarioId,
    config: RuntimeConfig,
    hardware_profile: HardwareProfile,
    branches: HashMap<BranchId, BranchState>,
    active_branch_id: BranchId,
    next_command_ordinal: u64,
    next_checkpoint_ordinal: u64,
    next_branch_ordinal: u64,
}

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
                trail_history_by_body: HashMap::new(),
                solver_execution: SolverExecutionReport::reference_scalar(),
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
            hardware_profile,
            branches,
            active_branch_id: root_branch_id,
            next_command_ordinal: 1,
            next_checkpoint_ordinal: 1,
            next_branch_ordinal: 1,
        };

        let active_branch_id = runtime.active_branch_id.clone();
        runtime.bump_next_branch_ordinal_if_needed(&active_branch_id);
        runtime
    }

    #[must_use]
    pub fn active_branch_id(&self) -> &BranchId {
        &self.active_branch_id
    }

    /// Apply a package manifest to the active branch data state.
    /// This can change installed package state and mounted-package visibility, but
    /// does not directly mutate simulation motion state.
    pub fn apply_update_manifest(
        &mut self,
        command: ApplyUpdateManifestCommand,
    ) -> Result<ApplyUpdateManifestResult, RuntimeError> {
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

    /// Main mutation pipeline for simulation and history state.
    /// Every command is converted into recorded events and an authoritative state
    /// transition for the active branch.
    pub fn apply_command(
        &mut self,
        command: WorldCommand,
        recorded_at_unix_ms: i64,
    ) -> Result<Vec<RuntimeEvent>, RuntimeError> {
        let mut events = Vec::new();
        let mut header = self.new_command_header(&command, recorded_at_unix_ms);
        let summary = self.command_summary(&command);
        let is_branch_creation =
            matches!(&command, WorldCommand::CreateBranchFromCheckpoint { .. });
        self.next_command_ordinal += 1;

        match &command {
            WorldCommand::SeedCanonicalSolarSystem => {
                let branch = self.active_branch_mut();
                if branch.world.bodies.is_empty() {
                    let seed = canonical_startup_seed();
                    branch.world.bodies = seed
                        .bodies
                        .into_iter()
                        .map(|body| BodyState {
                            body_id: BodyId(body.body_id),
                            body_class: body.body_class,
                            mass_kg: body.mass_kg,
                            radius_m: body.radius_m,
                            position_m: body.position_m,
                            velocity_mps: body.velocity_mps,
                        })
                        .collect();
                    branch.world.invariants = compute_world_invariants(&branch.world.bodies);
                    branch.world.trail_history_by_body.clear();
                    record_trail_samples_from_bodies(
                        &branch.world.bodies,
                        &mut branch.world.trail_history_by_body,
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
                    *position_m,
                );
            }
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                if *delta_seconds <= 0.0 {
                    return Err(RuntimeError::InvalidEpochDelta(*delta_seconds));
                }

                let physics_policy = self.config.physics.clone();
                let active_cpu_features = self.hardware_profile.cpu_features.clone();
                let branch = self.active_branch_mut();
                let mut solver_bodies = world_bodies_to_solver_state(&branch.world.bodies);
                let (invariants, solver_execution) = advance_authoritative_with_features(
                    &physics_policy,
                    &mut solver_bodies,
                    *delta_seconds,
                    &active_cpu_features,
                );
                apply_solver_state_to_world_bodies(&mut branch.world.bodies, &solver_bodies);
                branch.world.invariants = invariants;
                branch.world.solver_execution = solver_execution;
                branch.world.epoch_seconds += *delta_seconds;
                record_trail_samples_from_bodies(
                    &branch.world.bodies,
                    &mut branch.world.trail_history_by_body,
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
                if *sim_seconds_per_real_second <= 0.0 {
                    return Err(RuntimeError::InvalidPlaybackRate(
                        *sim_seconds_per_real_second,
                    ));
                }
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

    /// Return an ownership-safe world snapshot for host-visible status and
    /// checkpoint provenance capture.
    /// This method is a pure projection: it does not mutate runtime state.
    #[must_use]
    pub fn snapshot(&self) -> WorldSnapshot {
        let active = self.active_branch();
        let checkpoint = active.last_checkpoint.clone();
        let mounted_manifest = mounted_manifest_from_state(
            &active.world.local_data_state,
            &active.world.mounted_package_ids,
        )
        .expect("runtime mounted package state must be internally consistent");

        WorldSnapshot {
            scenario_id: self.scenario_id.clone(),
            branch_id: self.active_branch_id.clone(),
            epoch_seconds: active.world.epoch_seconds,
            timeline_semantics: self.config.timeline_semantics.clone(),
            bodies: active.world.bodies.clone(),
            active_checkpoint: checkpoint,
            hardware_profile: self.hardware_profile.clone(),
            invariants: active.world.invariants,
            observer: active.world.observer.clone(),
            playback: active.world.playback.clone(),
            mounted_manifest,
            trail_history_by_body: active.world.trail_history_by_body.clone(),
            solver_execution: active.world.solver_execution.clone(),
        }
    }

    /// Project authoritative runtime state into a compact telemetry surface.
    #[must_use]
    pub fn telemetry_report(&self) -> RuntimeTelemetryReport {
        let snapshot = self.snapshot();
        let scene = extract_render_scene(&snapshot);
        let trail_history_counts = runtime_trail_history_counts(&snapshot.trail_history_by_body);
        let total_trail_samples = trail_history_counts
            .iter()
            .map(|entry| entry.sample_count)
            .sum();

        RuntimeTelemetryReport {
            scenario_id: snapshot.scenario_id,
            branch_id: snapshot.branch_id,
            epoch_seconds: snapshot.epoch_seconds,
            timeline_semantics: snapshot.timeline_semantics,
            active_checkpoint_id: snapshot
                .active_checkpoint
                .map(|checkpoint| checkpoint.checkpoint_id),
            total_bodies: snapshot.bodies.len(),
            total_tracers: scene.tracer_count as usize,
            trail_history_counts,
            total_trail_samples,
            invariants: snapshot.invariants,
            hardware_profile: snapshot.hardware_profile,
            playback: snapshot.playback,
            observer: snapshot.observer,
            mounted_manifest: snapshot
                .mounted_manifest
                .as_ref()
                .map(mounted_manifest_telemetry_from_state),
            scene_revision: scene.scene_revision,
            diagnostics: scene.diagnostics,
            solver_execution: snapshot.solver_execution,
        }
    }

    /// Build a renderer-agnostic scene contract from the current runtime state.
    /// This pure extraction path is intentionally read-only and used by FFI
    /// scene export.
    #[must_use]
    pub fn render_scene(&self) -> RenderScene {
        extract_render_scene(&self.snapshot())
    }

    fn new_command_header(
        &self,
        command: &WorldCommand,
        recorded_at_unix_ms: i64,
    ) -> CommandRecordHeader {
        CommandRecordHeader {
            command_id: CommandId(format!("cmd-{:06}", self.next_command_ordinal)),
            branch_id: self.active_branch_id.clone(),
            sequence: self.next_command_ordinal,
            recorded_at_unix_ms,
            command_kind: self.command_kind(command),
        }
    }

    fn command_kind(&self, command: &WorldCommand) -> String {
        match command {
            WorldCommand::SeedCanonicalSolarSystem => "catalog.seed_canonical_solar_system",
            WorldCommand::SpawnBody { .. } => "body.spawn",
            WorldCommand::RemoveBody { .. } => "body.remove",
            WorldCommand::SetBodyKinematics { .. } => "body.set_kinematics",
            WorldCommand::AdvanceEpoch { .. } => "timeline.advance_epoch",
            WorldCommand::PausePlayback => "playback.pause",
            WorldCommand::ResumePlayback => "playback.resume",
            WorldCommand::SetPlaybackRate { .. } => "playback.set_rate",
            WorldCommand::SetObserverMode { .. } => "observer.set_mode",
            WorldCommand::FocusBody { .. } => "observer.focus_body",
            WorldCommand::CreateCheckpoint { .. } => "history.create_checkpoint",
            WorldCommand::CreateBranchFromCheckpoint { .. } => {
                "history.create_branch_from_checkpoint"
            }
        }
        .to_owned()
    }

    fn command_summary(&self, command: &WorldCommand) -> String {
        match command {
            WorldCommand::SeedCanonicalSolarSystem => "seed canonical startup solar system".into(),
            WorldCommand::SpawnBody { body } => format!("spawn {}", body.body_id.0),
            WorldCommand::RemoveBody { body_id } => format!("remove {}", body_id.0),
            WorldCommand::SetBodyKinematics { body_id, .. } => {
                format!("set kinematics {}", body_id.0)
            }
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                format!("advance epoch by {:.3}s", delta_seconds)
            }
            WorldCommand::PausePlayback => "pause playback".into(),
            WorldCommand::ResumePlayback => "resume playback".into(),
            WorldCommand::SetPlaybackRate {
                sim_seconds_per_real_second,
            } => format!("set playback rate {:.3}", sim_seconds_per_real_second),
            WorldCommand::SetObserverMode { mode } => format!("set observer mode {mode:?}"),
            WorldCommand::FocusBody { body_id } => {
                let target = body_id.as_ref().map_or("none", |id| id.0.as_str());
                format!("focus {target}")
            }
            WorldCommand::CreateCheckpoint {
                checkpoint_id,
                label,
            } => {
                let id = checkpoint_id.as_ref().map_or("auto", |v| v.0.as_str());
                let note = label.as_deref().unwrap_or("unlabeled");
                format!("checkpoint {id} ({note})")
            }
            WorldCommand::CreateBranchFromCheckpoint {
                checkpoint_id,
                new_branch_id,
            } => {
                let id = new_branch_id.as_ref().map_or("auto", |v| v.0.as_str());
                format!("branch {id} from {}", checkpoint_id.0)
            }
        }
    }

    fn generate_checkpoint_id(&mut self) -> CheckpointId {
        let id = CheckpointId(format!("cp-{:06}", self.next_checkpoint_ordinal));
        self.next_checkpoint_ordinal += 1;
        id
    }

    fn generate_branch_id(&mut self) -> BranchId {
        let id = BranchId(format!("branch-{:03}", self.next_branch_ordinal));
        self.next_branch_ordinal += 1;
        id
    }

    fn bump_next_checkpoint_ordinal_if_needed(&mut self, checkpoint_id: &CheckpointId) {
        if let Some(sequence) = checkpoint_id.0.strip_prefix("cp-") {
            if let Ok(value) = sequence.parse::<u64>() {
                self.next_checkpoint_ordinal = self.next_checkpoint_ordinal.max(value + 1);
            }
        }
    }

    fn bump_next_branch_ordinal_if_needed(&mut self, branch_id: &BranchId) {
        if let Some(sequence) = branch_id.0.strip_prefix("branch-") {
            if let Ok(value) = sequence.parse::<u64>() {
                self.next_branch_ordinal = self.next_branch_ordinal.max(value + 1);
            }
        }
    }

    fn active_branch(&self) -> &BranchState {
        self.branches
            .get(&self.active_branch_id)
            .expect("active branch must exist")
    }

    fn active_branch_mut(&mut self) -> &mut BranchState {
        self.branches
            .get_mut(&self.active_branch_id)
            .expect("active branch must exist")
    }

    fn is_checkpoint_id_in_use(&self, checkpoint_id: &CheckpointId) -> bool {
        self.branches.values().any(|branch| {
            branch
                .checkpoints
                .iter()
                .any(|record| record.descriptor.checkpoint_id == *checkpoint_id)
        })
    }

    fn find_checkpoint_record(
        &self,
        checkpoint_id: &CheckpointId,
    ) -> Option<(BranchId, CheckpointRecord)> {
        self.branches.iter().find_map(|(branch_id, branch)| {
            branch
                .checkpoints
                .iter()
                .find(|record| record.descriptor.checkpoint_id == *checkpoint_id)
                .map(|record| (branch_id.clone(), record.clone()))
        })
    }
}

#[must_use]
pub fn extract_render_scene(snapshot: &WorldSnapshot) -> RenderScene {
    let extract_started_at = Instant::now();
    let selected_body = snapshot.observer.focus_body_id.as_ref();
    let bodies: Vec<SceneBody> = snapshot
        .bodies
        .iter()
        .map(|body| {
            let style = body_style(body.body_class.clone());
            SceneBody {
                body_id: body.body_id.clone(),
                display_name: body.body_id.0.clone(),
                position_m: body.position_m,
                radius_m: body.radius_m,
                albedo: style.albedo,
                emissive_luminance: style.emissive_luminance,
                selected: selected_body
                    .map(|focused| focused == &body.body_id)
                    .unwrap_or(false),
            }
        })
        .collect();
    let tracers = extract_scene_tracers(snapshot);
    let trails = extract_scene_trails(snapshot);
    let lights = extract_lights(snapshot);
    let diagnostics = render_diagnostics(
        snapshot,
        &bodies,
        &tracers,
        &trails,
        &lights,
        extract_started_at,
    );

    RenderScene {
        observer_mode: snapshot.observer.mode.clone(),
        body_count: 0,
        tracer_count: 0,
        trail_count: 0,
        scene_revision: scene_revision_from_snapshot(
            snapshot,
            &bodies,
            &tracers,
            &trails,
            &lights,
            &diagnostics,
        ),
        epoch_seconds: snapshot.epoch_seconds,
        timeline_semantics: snapshot.timeline_semantics.clone(),
        camera: camera_pose_from_snapshot(snapshot),
        bodies,
        tracers,
        trails,
        packet_metadata: ScenePacketMetadata::default(),
        lights,
        provenance: scene_provenance(snapshot),
        diagnostics,
    }
    .with_derived_counts()
}

#[derive(Clone, Copy)]
struct BodyRenderStyle {
    albedo: ColorRgba,
    emissive_luminance: f64,
    tracer_color: ColorRgba,
    light_illuminance_lux: f64,
}

fn body_style(body_class: BodyClass) -> BodyRenderStyle {
    match body_class {
        BodyClass::Star => BodyRenderStyle {
            albedo: ColorRgba {
                r: 1.0,
                g: 0.93,
                b: 0.78,
                a: 1.0,
            },
            emissive_luminance: 1_500_000.0,
            tracer_color: ColorRgba {
                r: 1.0,
                g: 0.95,
                b: 0.8,
                a: 0.85,
            },
            light_illuminance_lux: 120_000.0,
        },
        BodyClass::Planet => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.28,
                g: 0.42,
                b: 0.78,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.58,
                g: 0.74,
                b: 1.0,
                a: 0.6,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::DwarfPlanet => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.56,
                g: 0.54,
                b: 0.64,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.75,
                g: 0.75,
                b: 0.88,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Moon => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.67,
                g: 0.67,
                b: 0.7,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.9,
                g: 0.9,
                b: 0.96,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::SmallBody => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.51,
                g: 0.43,
                b: 0.34,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.78,
                g: 0.66,
                b: 0.54,
                a: 0.55,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Tracer => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.95,
                g: 0.95,
                b: 0.98,
                a: 1.0,
            },
            emissive_luminance: 5_000.0,
            tracer_color: ColorRgba {
                r: 1.0,
                g: 1.0,
                b: 1.0,
                a: 0.9,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Spacecraft => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.82,
                g: 0.84,
                b: 0.9,
                a: 1.0,
            },
            emissive_luminance: 400.0,
            tracer_color: ColorRgba {
                r: 0.72,
                g: 0.88,
                b: 1.0,
                a: 0.8,
            },
            light_illuminance_lux: 0.0,
        },
        BodyClass::Custom => BodyRenderStyle {
            albedo: ColorRgba {
                r: 0.88,
                g: 0.88,
                b: 0.9,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            tracer_color: ColorRgba {
                r: 0.9,
                g: 0.9,
                b: 1.0,
                a: 0.6,
            },
            light_illuminance_lux: 0.0,
        },
    }
}

fn extract_scene_tracers(snapshot: &WorldSnapshot) -> Vec<SceneTracer> {
    snapshot
        .bodies
        .iter()
        .filter(|body| body.body_class == BodyClass::Tracer)
        .map(|body| {
            let style = body_style(body.body_class.clone());
            SceneTracer {
                tracer_id: format!("tracer:{}", body.body_id.0),
                source_body_id: body.body_id.clone(),
                position_m: body.position_m,
                color: style.tracer_color,
                size_px: ((body.radius_m.abs().sqrt() / 20.0).clamp(1.25, 8.0)) as f32,
            }
        })
        .collect()
}

fn extract_scene_trails(snapshot: &WorldSnapshot) -> Vec<SceneTrail> {
    let focused_body_id = snapshot.observer.focus_body_id.as_ref();
    snapshot
        .bodies
        .iter()
        .filter_map(|body| {
            let samples_m = snapshot
                .trail_history_by_body
                .get(&body.body_id)
                .filter(|samples| !samples.is_empty())?
                .clone();
            let style = body_style(body.body_class.clone());
            Some(SceneTrail {
                trail_id: format!("trail:{}", body.body_id.0),
                source_body_id: body.body_id.clone(),
                samples_m,
                color: style.tracer_color,
                max_samples: TRAIL_HISTORY_MAX_SAMPLES as u32,
                head_highlighted: body.body_class == BodyClass::Tracer
                    || focused_body_id == Some(&body.body_id),
            })
        })
        .collect()
}

fn extract_lights(snapshot: &WorldSnapshot) -> Vec<LightSource> {
    snapshot
        .bodies
        .iter()
        .filter_map(|body| {
            let style = body_style(body.body_class.clone());
            if style.light_illuminance_lux <= 0.0 {
                return None;
            }
            let magnitude = vec_magnitude(body.position_m);
            let direction = if magnitude > 0.0 {
                Vector3d {
                    x: -body.position_m.x / magnitude,
                    y: -body.position_m.y / magnitude,
                    z: -body.position_m.z / magnitude,
                }
            } else {
                Vector3d {
                    x: 0.0,
                    y: -1.0,
                    z: 0.0,
                }
            };
            Some(LightSource {
                light_id: format!("light:{}", body.body_id.0),
                direction_ws: direction,
                illuminance_lux: style.light_illuminance_lux,
                color: style.albedo,
            })
        })
        .collect()
}

fn render_diagnostics(
    snapshot: &WorldSnapshot,
    bodies: &[SceneBody],
    tracers: &[SceneTracer],
    trails: &[SceneTrail],
    lights: &[LightSource],
    extract_started_at: Instant,
) -> RenderDiagnostics {
    let trail_samples = trails
        .iter()
        .map(|trail| trail.samples_m.len())
        .sum::<usize>();
    let upload_bytes_estimate = bodies.len() * size_of::<SceneBody>()
        + tracers.len() * size_of::<SceneTracer>()
        + lights.len() * size_of::<LightSource>()
        + trail_samples * size_of::<Vector3d>();

    RenderDiagnostics {
        frame_number: ((snapshot.epoch_seconds * 60.0).round().max(0.0)) as u64,
        cpu_extract_ms: extract_started_at.elapsed().as_secs_f32() * 1_000.0,
        gpu_upload_ms: (upload_bytes_estimate as f32) / 25_000.0,
        dropped_frames: 0,
    }
}

fn camera_pose_from_snapshot(snapshot: &WorldSnapshot) -> CameraPose {
    let focused_body = snapshot
        .observer
        .focus_body_id
        .as_ref()
        .and_then(|focused_id| {
            snapshot
                .bodies
                .iter()
                .find(|body| &body.body_id == focused_id)
        });
    let target_m = focused_body
        .map(|body| body.position_m)
        .or_else(|| snapshot.bodies.first().map(|body| body.position_m))
        .unwrap_or_default();

    let camera_distance = focused_body
        .map(|body| (body.radius_m * 6.0).max(1.0))
        .unwrap_or(1_000.0);

    CameraPose {
        position_m: Vector3d {
            x: target_m.x,
            y: target_m.y,
            z: target_m.z + camera_distance,
        },
        target_m,
        up: Vector3d {
            x: 0.0,
            y: 1.0,
            z: 0.0,
        },
        vertical_fov_degrees: 60.0,
        exposure: 1.0,
    }
}

fn format_manifest_digest(digest: &Digest) -> String {
    format!("{}:{}", digest.algorithm, digest.hex_value())
}

fn mounted_manifest_telemetry_from_state(
    mounted_manifest: &MountedManifestState,
) -> MountedManifestTelemetry {
    MountedManifestTelemetry {
        manifest_id: mounted_manifest.manifest_id.clone(),
        manifest_version: semver_to_string(&mounted_manifest.manifest_version),
        channel: mounted_manifest.channel.clone(),
        has_manifest_digest: mounted_manifest.manifest_digest.is_some(),
        mounted_packages: mounted_manifest.mounted_packages.len(),
    }
}

fn runtime_trail_history_counts(
    trail_history_by_body: &HashMap<BodyId, Vec<Vector3d>>,
) -> Vec<RuntimeTrailHistoryCount> {
    let mut counts: Vec<RuntimeTrailHistoryCount> = trail_history_by_body
        .iter()
        .map(|(body_id, trail)| RuntimeTrailHistoryCount {
            body_id: body_id.clone(),
            sample_count: trail.len(),
        })
        .collect();
    counts.sort_by(|left, right| left.body_id.0.cmp(&right.body_id.0));
    counts
}

fn scene_provenance(snapshot: &WorldSnapshot) -> Option<SceneProvenanceRef> {
    let mounted_manifest = snapshot.mounted_manifest.as_ref()?;
    Some(SceneProvenanceRef {
        source: mounted_manifest.channel.clone(),
        version: semver_to_string(&mounted_manifest.manifest_version),
        manifest_id: mounted_manifest.manifest_id.clone(),
        manifest_digest: mounted_manifest
            .manifest_digest
            .as_ref()
            .map(format_manifest_digest),
        package_digest: package_provenance_digest(&mounted_manifest.mounted_packages),
    })
}

fn semver_to_string(version: &SemVer) -> String {
    let mut rendered = format!("{}.{}.{}", version.major, version.minor, version.patch);
    if let Some(prerelease) = &version.prerelease {
        rendered.push('-');
        rendered.push_str(prerelease);
    }
    if let Some(build_metadata) = &version.build_metadata {
        rendered.push('+');
        rendered.push_str(build_metadata);
    }
    rendered
}

fn scene_revision_from_snapshot(
    snapshot: &WorldSnapshot,
    bodies: &[SceneBody],
    tracers: &[SceneTracer],
    trails: &[SceneTrail],
    lights: &[LightSource],
    diagnostics: &RenderDiagnostics,
) -> String {
    let selected_body_id = snapshot.observer.focus_body_id.as_ref();
    let mut revision = format!(
        "scenario={}|branch={}|epoch={:.6}|observer={:?}",
        snapshot.scenario_id.0,
        snapshot.branch_id.0,
        snapshot.epoch_seconds,
        snapshot.observer.mode
    );
    for (body_state, body_scene) in snapshot.bodies.iter().zip(bodies.iter()) {
        let selected = selected_body_id == Some(&body_state.body_id);
        use std::fmt::Write as _;
        let _ = write!(
            &mut revision,
            "|{}|class={:?}|selected={selected}|r={:.6}|p=({:.6},{:.6},{:.6})|v=({:.6},{:.6},{:.6})|e={:.3}",
            body_state.body_id.0,
            body_state.body_class,
            body_state.radius_m,
            body_state.position_m.x,
            body_state.position_m.y,
            body_state.position_m.z,
            body_state.velocity_mps.x,
            body_state.velocity_mps.y,
            body_state.velocity_mps.z,
            body_scene.emissive_luminance
        );
    }
    if let Some(mounted_manifest) = &snapshot.mounted_manifest {
        use std::fmt::Write as _;
        let _ = write!(
            &mut revision,
            "|manifest={}|channel={}|version={}|manifest_digest={}",
            mounted_manifest.manifest_id,
            mounted_manifest.channel,
            semver_to_string(&mounted_manifest.manifest_version),
            mounted_manifest
                .manifest_digest
                .as_ref()
                .map_or_else(|| "none".to_owned(), format_manifest_digest)
        );
        for package in &mounted_manifest.mounted_packages {
            let _ = write!(
                &mut revision,
                "|package={}|digest={}:{}",
                package.package_id,
                package.digest.algorithm,
                package.digest.hex_value()
            );
        }
    } else {
        revision.push_str("|manifest=none");
    }

    use std::fmt::Write as _;
    let _ = write!(
        &mut revision,
        "|families:bodies={}|tracers={}|trails={}|lights={}",
        bodies.len(),
        tracers.len(),
        trails.len(),
        lights.len()
    );
    for tracer in tracers {
        let _ = write!(
            &mut revision,
            "|tracer:{}@({:.6},{:.6},{:.6})",
            tracer.source_body_id.0, tracer.position_m.x, tracer.position_m.y, tracer.position_m.z
        );
    }
    for trail in trails {
        let sample_count = trail.samples_m.len();
        let last_sample = trail.samples_m.last().copied().unwrap_or_default();
        let _ = write!(
            &mut revision,
            "|trail:{}:{}@({:.6},{:.6},{:.6})",
            trail.source_body_id.0, sample_count, last_sample.x, last_sample.y, last_sample.z
        );
    }
    for light in lights {
        let _ = write!(
            &mut revision,
            "|light:{}:lux={:.3}:dir=({:.6},{:.6},{:.6})",
            light.light_id,
            light.illuminance_lux,
            light.direction_ws.x,
            light.direction_ws.y,
            light.direction_ws.z
        );
    }
    let _ = write!(
        &mut revision,
        "|diag:frame={}:cpu={:.3}:gpu={:.3}:drop={}",
        diagnostics.frame_number,
        diagnostics.cpu_extract_ms,
        diagnostics.gpu_upload_ms,
        diagnostics.dropped_frames
    );

    revision
}

fn vec_magnitude(vector: Vector3d) -> f64 {
    (vector.x * vector.x + vector.y * vector.y + vector.z * vector.z).sqrt()
}

fn push_trail_sample(
    trail_history_by_body: &mut HashMap<BodyId, Vec<Vector3d>>,
    body_id: &BodyId,
    sample: Vector3d,
) {
    let history = trail_history_by_body.entry(body_id.clone()).or_default();
    history.push(sample);
    if history.len() > TRAIL_HISTORY_MAX_SAMPLES {
        let trim_count = history.len() - TRAIL_HISTORY_MAX_SAMPLES;
        history.drain(0..trim_count);
    }
}

fn record_trail_samples_from_bodies(
    bodies: &[BodyState],
    trail_history_by_body: &mut HashMap<BodyId, Vec<Vector3d>>,
) {
    for body in bodies {
        push_trail_sample(trail_history_by_body, &body.body_id, body.position_m);
    }
}

fn package_provenance_digest(mounted_packages: &[MountedPackageState]) -> Option<Digest> {
    match mounted_packages {
        [] => None,
        [only_package] => Some(only_package.digest.clone()),
        multiple_packages => {
            let mut value = Vec::new();
            for package in multiple_packages {
                value.extend_from_slice(package.package_id.as_bytes());
                value.push(0);
                value.extend_from_slice(package.digest.algorithm.as_bytes());
                value.push(0);
                value.extend_from_slice(package.digest.hex_value().as_bytes());
                value.push(b'\n');
            }
            Some(Digest {
                algorithm: "mounted-set/v1".to_owned(),
                value,
            })
        }
    }
}

fn mounted_manifest_from_state(
    local_data_state: &LocalDataState,
    mounted_package_ids: &BTreeSet<String>,
) -> Result<Option<MountedManifestState>, RuntimeError> {
    let Some(installed_manifest) = &local_data_state.installed_manifest else {
        return Ok(None);
    };

    let mut mounted_packages = Vec::with_capacity(mounted_package_ids.len());
    for package_id in mounted_package_ids {
        if !installed_manifest
            .installed_package_ids
            .contains(package_id)
        {
            return Err(RuntimeError::PackageNotInstalled(package_id.clone()));
        }

        let stored = local_data_state
            .package_store
            .packages_by_id
            .get(package_id)
            .ok_or_else(|| RuntimeError::MountedPackageMissingFromStore(package_id.clone()))?;

        mounted_packages.push(MountedPackageState {
            package_id: stored.package_id.clone(),
            kind: stored.kind.clone(),
            package_version: stored.package_version.clone(),
            schema_version: stored.schema_version.clone(),
            digest: stored.digest.clone(),
            local_store_uri: stored.local_store_uri.clone(),
        });
    }

    Ok(Some(MountedManifestState {
        manifest_id: installed_manifest.manifest_id.clone(),
        manifest_version: installed_manifest.manifest_version.clone(),
        channel: installed_manifest.channel.clone(),
        manifest_digest: installed_manifest.manifest_digest.clone(),
        mounted_packages,
    }))
}

fn world_bodies_to_solver_state(bodies: &[BodyState]) -> Vec<MassiveBodyState> {
    bodies
        .iter()
        .map(|body| MassiveBodyState {
            mass_kg: body.mass_kg,
            position_m: body.position_m,
            velocity_mps: body.velocity_mps,
        })
        .collect()
}

fn apply_solver_state_to_world_bodies(bodies: &mut [BodyState], solved: &[MassiveBodyState]) {
    for (body, solved_body) in bodies.iter_mut().zip(solved.iter()) {
        body.position_m = solved_body.position_m;
        body.velocity_mps = solved_body.velocity_mps;
    }
}

fn compute_world_invariants(bodies: &[BodyState]) -> PhysicsInvariants {
    let solver_bodies = world_bodies_to_solver_state(bodies);
    compute_invariants(&solver_bodies)
}

#[cfg(test)]
mod tests {
    use std::collections::{BTreeMap, BTreeSet};

    use solarlab_data::{
        CompatibilityTarget, Digest, PackageCompatibility, PackageKind, PackageLocator, SemVer,
        StoredPackage, UpdateManifest,
    };
    use solarlab_domain::{
        BodyClass, BodyId, BranchId, CheckpointId, ObserverMode, ScenarioId, TimelineSemantics,
        Vector3d,
    };
    use solarlab_hardware::HardwareProfile;
    use solarlab_history::HistoryEvent;
    use solarlab_physics::{
        CollisionModel, IntegratorKind, PhysicsInvariants, PhysicsPolicy, SolverBackend,
    };
    use solarlab_scene::{SceneDetailBand, SceneItemFamily};

    use super::{
        compute_world_invariants, extract_render_scene, record_trail_samples_from_bodies,
        vec_magnitude, ApplyUpdateManifestCommand, BodyState, MountPackageCommand, RuntimeConfig,
        RuntimeError, RuntimeEvent, RuntimeTrailHistoryCount, WorldCommand, WorldRuntime,
        WorldSnapshot,
    };

    #[test]
    fn applies_body_commands_and_publishes_snapshot() {
        let mut runtime = new_runtime();

        let body = BodyState {
            body_id: BodyId("earth".into()),
            body_class: BodyClass::Planet,
            mass_kg: 5.972e24,
            radius_m: 6_371_000.0,
            position_m: Vector3d::default(),
            velocity_mps: Vector3d::default(),
        };

        let events = runtime
            .apply_command(WorldCommand::SpawnBody { body: body.clone() }, 10)
            .expect("spawn command should succeed");

        assert!(matches!(
            events[0],
            RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(_))
        ));
        assert!(matches!(events[1], RuntimeEvent::SnapshotPublished(_)));
        assert_eq!(runtime.snapshot().bodies, vec![body.clone()]);

        runtime
            .apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: BodyId("earth".into()),
                    position_m: Vector3d {
                        x: 10.0,
                        y: 0.0,
                        z: 0.0,
                    },
                    velocity_mps: Vector3d {
                        x: 0.0,
                        y: 30_000.0,
                        z: 0.0,
                    },
                },
                11,
            )
            .expect("kinematic update should succeed");

        assert_eq!(runtime.snapshot().bodies[0].position_m.x, 10.0);

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                12,
            )
            .expect("observer mode update should succeed");

        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("earth".into())),
                },
                13,
            )
            .expect("observer focus should succeed");

        runtime
            .apply_command(
                WorldCommand::RemoveBody {
                    body_id: BodyId("earth".into()),
                },
                14,
            )
            .expect("remove body should succeed");

        assert!(runtime.snapshot().bodies.is_empty());
        assert_eq!(runtime.snapshot().observer.focus_body_id, None);
    }

    #[test]
    fn seed_canonical_solar_system_populates_authoritative_startup_world() {
        let mut runtime = new_runtime();
        assert!(runtime.snapshot().bodies.is_empty());

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("seed command should succeed");

        let snapshot = runtime.snapshot();
        assert_eq!(snapshot.bodies.len(), 365);
        assert!(snapshot.bodies.iter().any(|body| body.body_id.0 == "sun"));
        assert!(snapshot.bodies.iter().any(|body| body.body_id.0 == "moon"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "halley"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "belt-239"));
        assert!(snapshot
            .bodies
            .iter()
            .any(|body| body.body_id.0 == "oort-95"));
        assert_eq!(snapshot.trail_history_by_body.len(), 365);
    }

    #[test]
    fn seed_canonical_solar_system_is_idempotent_for_non_empty_world() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 1)
            .expect("first seed command should succeed");
        let first_count = runtime.snapshot().bodies.len();

        runtime
            .apply_command(WorldCommand::SeedCanonicalSolarSystem, 2)
            .expect("second seed command should succeed");
        let second_count = runtime.snapshot().bodies.len();

        assert_eq!(first_count, 365);
        assert_eq!(second_count, 365);
    }

    #[test]
    fn render_scene_extracts_selected_body_counts_and_camera_from_runtime() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());
        let spark = BodyId("spark".into());
        let sun = BodyId("sun".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth.clone(),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: 100.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: spark.clone(),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        radius_m: 25.0,
                        position_m: Vector3d {
                            x: 410_000_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                3,
            )
            .expect("spawn tracer should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: sun,
                        body_class: BodyClass::Star,
                        mass_kg: 1.989e30,
                        radius_m: 696_000_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                4,
            )
            .expect("spawn star should succeed");

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                5,
            )
            .expect("setting observer mode should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon.clone()),
                },
                6,
            )
            .expect("focus body should succeed");

        let scene = runtime.render_scene();
        assert_eq!(scene.body_count, 4);
        assert_eq!(scene.tracer_count, 1);
        assert_eq!(scene.trail_count, 4);
        assert_eq!(scene.observer_mode, ObserverMode::FollowSelected);
        assert_eq!(scene.bodies.len(), 4);
        assert_eq!(scene.tracers.len(), 1);
        assert_eq!(scene.trails.len(), 4);
        assert_eq!(scene.lights.len(), 1);
        assert_eq!(scene.packet_metadata.tracer_family, SceneItemFamily::Tracer);
        assert_eq!(
            scene.packet_metadata.tracer_resolution_band,
            SceneDetailBand::Far
        );
        assert_eq!(scene.packet_metadata.trail_family, SceneItemFamily::Trail);
        assert_eq!(
            scene.packet_metadata.trail_horizon_band,
            SceneDetailBand::Far
        );
        assert_eq!(
            scene.packet_metadata.trail_simplification_budget_samples,
            96
        );
        assert!(scene.scene_revision.contains("branch=main"));
        assert!(scene
            .tracers
            .iter()
            .any(|tracer| tracer.source_body_id == spark));
        assert!(scene
            .lights
            .iter()
            .any(|light| light.light_id == "light:sun"));
        assert!(scene.trails.iter().all(|trail| !trail.samples_m.is_empty()));
        assert!(
            scene
                .trails
                .iter()
                .find(|trail| trail.source_body_id == moon)
                .expect("moon trail should be present")
                .head_highlighted
        );
        assert!(
            scene
                .bodies
                .iter()
                .find(|body| body.body_id == moon)
                .expect("moon should be present")
                .selected
        );
        assert_eq!(scene.camera.target_m.x, 384_400_000.0);
        assert!(scene.camera.position_m.z > scene.camera.target_m.z);
        assert_eq!(scene.diagnostics.frame_number, 0);
        assert!(scene.diagnostics.gpu_upload_ms > 0.0);
        assert!(scene.scene_revision.contains("|diag:frame=0"));
    }

    #[test]
    fn telemetry_report_captures_runtime_state_and_scene_diagnostics() {
        let mut runtime = new_runtime();
        let package_digest = digest(0x80);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-telemetry",
                    vec![package_locator(
                        PackageKind::Scenario,
                        semver(1, 2, 0),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 2, 0),
                    package_digest,
                    "cache://pkg-scenario-telemetry-v120",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("manifest package should mount");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("planet".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: 0.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("planet spawn should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("tracer".into()),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        radius_m: 10.0,
                        position_m: Vector3d {
                            x: 100.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 0.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("tracer spawn should succeed");

        runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 2.0,
                },
                3,
            )
            .expect("playback rate should change");
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                4,
            )
            .expect("observer mode set should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("tracer".into())),
                },
                5,
            )
            .expect("focus body should succeed");
        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 10.0,
                },
                6,
            )
            .expect("advance should succeed");

        let report = runtime.telemetry_report();
        let rendered_scene = runtime.render_scene();

        assert_eq!(report.scenario_id, ScenarioId("sol-system".into()));
        assert_eq!(report.branch_id, BranchId("main".into()));
        assert_eq!(report.total_bodies, 2);
        assert_eq!(report.total_tracers, 1);
        assert_eq!(
            report.timeline_semantics,
            TimelineSemantics::BranchedSandbox
        );
        assert_eq!(report.total_trail_samples, 4);
        assert_eq!(
            report.hardware_profile,
            HardwareProfile::offline_reference()
        );
        assert_eq!(report.active_checkpoint_id, None);
        assert_eq!(report.playback.sim_seconds_per_real_second, 2.0);
        assert_eq!(report.observer.mode, ObserverMode::FollowSelected);
        assert_eq!(report.observer.focus_body_id, Some(BodyId("tracer".into())));
        assert_eq!(
            report.total_trail_samples,
            report
                .trail_history_counts
                .iter()
                .map(|entry| entry.sample_count)
                .sum()
        );
        assert_eq!(report.trail_history_counts.len(), 2);
        assert_eq!(
            report.trail_history_counts[0].body_id,
            BodyId("planet".into())
        );
        assert_eq!(report.trail_history_counts[0].sample_count, 2);
        assert_eq!(
            report.trail_history_counts[1].body_id,
            BodyId("tracer".into())
        );
        assert_eq!(report.trail_history_counts[1].sample_count, 2);
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .manifest_id,
            "manifest-telemetry"
        );
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .manifest_version,
            "1.0.0"
        );
        assert_eq!(
            report
                .mounted_manifest
                .as_ref()
                .expect("manifest should be present")
                .mounted_packages,
            1
        );
        assert!(report.scene_revision.starts_with(
            "scenario=sol-system|branch=main|epoch=10.000000|observer=FollowSelected"
        ));
        assert!(report.scene_revision.starts_with(
            &rendered_scene
                .scene_revision
                .split("|diag:")
                .next()
                .unwrap_or_default()
        ));
        assert_eq!(
            report.diagnostics.frame_number,
            rendered_scene.diagnostics.frame_number
        );

        assert!(report.invariants.total_energy_j.is_finite());
    }

    #[test]
    fn telemetry_report_uses_manifest_presence_none_when_unmounted() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("solo".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 1_000.0,
                        radius_m: 1_000.0,
                        position_m: Vector3d {
                            x: 42.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("body spawn should succeed");

        let report = runtime.telemetry_report();
        assert_eq!(report.total_bodies, 1);
        assert_eq!(report.total_tracers, 0);
        assert_eq!(report.total_trail_samples, 1);
        assert!(report.mounted_manifest.is_none());
        assert_eq!(report.active_checkpoint_id, None);
        assert_eq!(
            report.trail_history_counts,
            vec![RuntimeTrailHistoryCount {
                body_id: BodyId("solo".into()),
                sample_count: 1,
            }]
        );
    }

    #[test]
    fn checkpoint_and_branch_restore_checkpoint_state() {
        let mut runtime = new_runtime();
        let body_id = BodyId("probe-1".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: body_id.clone(),
                        body_class: BodyClass::Spacecraft,
                        mass_kg: 1_500.0,
                        radius_m: 2.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn should succeed");

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 120.0,
                },
                2,
            )
            .expect("epoch advance should succeed");

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some("coast-start".into()),
                },
                3,
            )
            .expect("checkpoint creation should succeed");

        let checkpoint_id = checkpoint_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.checkpoint_id.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should be emitted");

        runtime
            .apply_command(
                WorldCommand::SetBodyKinematics {
                    body_id: body_id.clone(),
                    position_m: Vector3d {
                        x: 200.0,
                        y: 300.0,
                        z: 400.0,
                    },
                    velocity_mps: Vector3d {
                        x: 1.0,
                        y: 2.0,
                        z: 3.0,
                    },
                },
                4,
            )
            .expect("state mutation should succeed");

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("mission-alt".into())),
                },
                5,
            )
            .expect("branch creation should succeed");

        assert_eq!(runtime.active_branch_id(), &BranchId("mission-alt".into()));
        assert_eq!(runtime.snapshot().epoch_seconds, 120.0);
        assert_eq!(runtime.snapshot().bodies[0].position_m, Vector3d::default());
    }

    #[test]
    fn branch_snapshot_reports_origin_checkpoint() {
        let mut runtime = new_runtime();
        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: Some("origin".into()),
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let checkpoint_descriptor = checkpoint_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should exist");

        let branch_events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: checkpoint_descriptor.checkpoint_id.clone(),
                    new_branch_id: Some(BranchId("provenance".into())),
                },
                2,
            )
            .expect("branch creation should succeed");

        let snapshot = branch_events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::SnapshotPublished(snapshot) => Some(snapshot.clone()),
                _ => None,
            })
            .expect("snapshot should be published after branch creation");

        assert_eq!(
            snapshot.active_checkpoint,
            Some(checkpoint_descriptor.clone())
        );
    }

    #[test]
    fn checkpoint_descriptor_tracks_command_sequence() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 10.0,
                },
                1,
            )
            .expect("advance should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(solarlab_domain::CheckpointId("manual-01".into())),
                    label: None,
                },
                2,
            )
            .expect("checkpoint should succeed");

        let descriptor = events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.clone())
                }
                _ => None,
            })
            .expect("checkpoint descriptor event should exist");

        assert_eq!(descriptor.command_sequence, 2);
        assert_eq!(descriptor.epoch_seconds, 10.0);
        assert_eq!(descriptor.checkpoint_id.0, "manual-01");
    }

    #[test]
    fn advance_epoch_moves_bodies_and_updates_invariants() {
        let mut runtime = new_runtime();
        spawn_two_body_system(&mut runtime);

        let before = runtime.snapshot();
        let before_distance = body_separation_x(&before);

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 30.0,
                },
                3,
            )
            .expect("advance should succeed");

        let after = runtime.snapshot();
        let after_distance = body_separation_x(&after);

        assert_eq!(after.epoch_seconds, 30.0);
        assert!(after_distance < before_distance);
        assert!(after.invariants.total_energy_j.is_finite());
    }

    #[test]
    fn moon_like_bodies_move_between_epoch_snapshots() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("sun".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.98847e30,
                        radius_m: 696_340_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn sun should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("moon".into()),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 1_022.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");

        let snapshot_t0 = runtime.snapshot();
        let moon_t0 = body_position(&snapshot_t0, &BodyId("moon".into())).clone();

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 86_400.0,
                },
                3,
            )
            .expect("advance epoch should succeed");

        let snapshot_t1 = runtime.snapshot();
        let moon_t1 = body_position(&snapshot_t1, &BodyId("moon".into()));
        let movement = displacement_magnitude(&moon_t1, &moon_t0);

        assert!(
            movement > 1.0e6,
            "Expected Moon-like body to move between epochs, movement={movement}"
        );
    }

    #[test]
    fn fallback_pluto_like_orbit_changes_relative_position_with_epoch_delta() {
        let mut runtime = new_runtime();

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("sun".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.98847e30,
                        radius_m: 696_340_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn sun should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("pluto".into()),
                        body_class: BodyClass::DwarfPlanet,
                        mass_kg: 1.309e22,
                        radius_m: 1_188_000.0,
                        position_m: Vector3d {
                            x: 5_900_000_000_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d {
                            x: 0.0,
                            y: 4_700.0,
                            z: 0.0,
                        },
                    },
                },
                2,
            )
            .expect("spawn pluto should succeed");

        let snapshot_t0 = runtime.snapshot();
        let sun_t0 = body_position(&snapshot_t0, &BodyId("sun".into()));
        let pluto_t0 = body_position(&snapshot_t0, &BodyId("pluto".into()));
        let relative_t0 = relative_position(&pluto_t0, &sun_t0);

        runtime
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 31_536_000.0,
                },
                3,
            )
            .expect("advance epoch should succeed");

        let snapshot_t1 = runtime.snapshot();
        let sun_t1 = body_position(&snapshot_t1, &BodyId("sun".into()));
        let pluto_t1 = body_position(&snapshot_t1, &BodyId("pluto".into()));
        let relative_t1 = relative_position(&pluto_t1, &sun_t1);
        let movement = displacement_magnitude(&relative_t1, &relative_t0);

        assert!(
            movement > 1.0e6,
            "Expected Pluto fallback-style orbit to propagate across epochs, movement={movement}"
        );
    }

    #[test]
    fn advance_epoch_is_deterministic_for_identical_command_streams() {
        let mut runtime_a = new_runtime();
        let mut runtime_b = new_runtime();
        spawn_two_body_system(&mut runtime_a);
        spawn_two_body_system(&mut runtime_b);

        runtime_a
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 45.0,
                },
                3,
            )
            .expect("advance should succeed");
        runtime_b
            .apply_command(
                WorldCommand::AdvanceEpoch {
                    delta_seconds: 45.0,
                },
                3,
            )
            .expect("advance should succeed");

        assert_eq!(runtime_a.snapshot(), runtime_b.snapshot());
    }

    #[test]
    fn rejects_invalid_commands() {
        let mut runtime = new_runtime();

        let err = runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId("missing".into())),
                },
                1,
            )
            .expect_err("focus should fail for unknown body");

        assert_eq!(err, RuntimeError::UnknownBody(BodyId("missing".into())));

        let err = runtime
            .apply_command(
                WorldCommand::SetPlaybackRate {
                    sim_seconds_per_real_second: 0.0,
                },
                2,
            )
            .expect_err("zero playback rate must fail");

        assert_eq!(err, RuntimeError::InvalidPlaybackRate(0.0));
    }

    #[test]
    fn rejects_duplicate_checkpoint_ids_across_branches() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("manual-dup".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("initial checkpoint should succeed");

        let err = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                2,
            )
            .expect_err("duplicate checkpoint id must fail");

        assert_eq!(err, RuntimeError::DuplicateCheckpoint(checkpoint_id));
    }

    #[test]
    fn branch_creation_command_headers_use_new_branch() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("branch-source".into());
        let new_branch_id = BranchId("split-path".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(new_branch_id.clone()),
                },
                2,
            )
            .expect("branch creation should succeed");

        let header_branch = events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(header)) => {
                    Some(header.branch_id.clone())
                }
                _ => None,
            })
            .expect("command event should exist");

        assert_eq!(header_branch, new_branch_id.clone());

        let branch_state = runtime
            .branches
            .get(&new_branch_id)
            .expect("new branch should exist");

        let command_entry = branch_state
            .command_log
            .last()
            .expect("history entry should be logged on new branch");

        assert_eq!(command_entry.header.branch_id, new_branch_id);
    }

    #[test]
    fn automatic_ids_skip_manual_overlaps() {
        let mut runtime = new_runtime();
        let manual_checkpoint_id = CheckpointId("cp-005".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(manual_checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("manual checkpoint creation should succeed");

        let auto_checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                2,
            )
            .expect("automatic checkpoint creation should succeed");

        let auto_checkpoint_id = checkpoint_id_from_events(&auto_checkpoint_events);
        assert_eq!(auto_checkpoint_id, CheckpointId("cp-000006".into()));

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: manual_checkpoint_id.clone(),
                    new_branch_id: Some(BranchId("branch-010".into())),
                },
                3,
            )
            .expect("manual branch creation should succeed");

        let branch_checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: None,
                    label: None,
                },
                4,
            )
            .expect("checkpoint on manual branch should succeed");

        let branch_checkpoint_id = checkpoint_id_from_events(&branch_checkpoint_events);

        let auto_branch_events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id: branch_checkpoint_id,
                    new_branch_id: None,
                },
                5,
            )
            .expect("automatic branch creation should succeed");

        let auto_branch_id = branch_id_from_events(&auto_branch_events);
        assert_eq!(auto_branch_id, BranchId("branch-011".into()));
    }

    #[test]
    fn branch_creation_emits_branch_event_before_command() {
        let mut runtime = new_runtime();
        let checkpoint_id = CheckpointId("order-source".into());

        runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(checkpoint_id.clone()),
                    label: None,
                },
                1,
            )
            .expect("checkpoint creation should succeed");

        let events = runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("order-branch".into())),
                },
                2,
            )
            .expect("branch creation should succeed");

        assert!(matches!(
            events[0],
            RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(_))
        ));
        assert!(matches!(
            events[1],
            RuntimeEvent::HistoryAppended(HistoryEvent::CommandAppended(_))
        ));
        assert!(matches!(events[2], RuntimeEvent::SnapshotPublished(_)));
    }

    #[test]
    fn apply_update_manifest_commits_installed_state_but_keeps_mount_explicit() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x11);
        let package_id = package_id_for(&package_kind, &package_digest);
        let manifest = manifest(
            "manifest-alpha",
            vec![package_locator(
                package_kind.clone(),
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        let fetched = vec![stored_package(
            package_kind,
            semver(1, 0, 0),
            package_digest,
            "cache://pkg-scenario-alpha-v1",
        )];

        let apply_result = runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(fetched),
            })
            .expect("manifest apply should succeed");

        assert_eq!(apply_result.plan.selected_package_ids, vec![package_id]);
        assert_eq!(
            apply_result
                .mounted_manifest
                .expect("installed manifest should exist")
                .mounted_packages
                .len(),
            0
        );
        assert_eq!(
            runtime
                .snapshot()
                .mounted_manifest
                .expect("snapshot should include installed manifest metadata")
                .mounted_packages
                .len(),
            0
        );
    }

    #[test]
    fn mount_package_updates_runtime_snapshot_and_rejects_uninstalled_package() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x12);
        let package_id = package_id_for(&package_kind, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        package_kind.clone(),
                        semver(1, 0, 0),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 0, 0),
                    package_digest,
                    "cache://pkg-scenario-alpha-v1",
                )]),
            })
            .expect("manifest apply should seed local data state");

        let err = runtime
            .mount_package(MountPackageCommand {
                package_id: "pkg-missing".to_owned(),
            })
            .expect_err("mounting unknown package should fail");
        assert_eq!(
            err,
            RuntimeError::PackageNotInstalled("pkg-missing".to_owned())
        );

        let mounted = runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed for installed package");

        assert_eq!(mounted.mounted_packages.len(), 1);
        assert_eq!(mounted.mounted_packages[0].package_id, package_id);
        assert_eq!(
            runtime
                .snapshot()
                .mounted_manifest
                .expect("snapshot should include mounted package")
                .mounted_packages
                .len(),
            1
        );
    }

    #[test]
    fn mounted_packages_are_checkpoint_and_branch_state() {
        let mut runtime = new_runtime();
        let initial_kind = PackageKind::Scenario;
        let initial_digest = digest(0x31);
        let initial_package_id = package_id_for(&initial_kind, &initial_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        initial_kind.clone(),
                        semver(1, 0, 0),
                        initial_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    initial_kind,
                    semver(1, 0, 0),
                    initial_digest,
                    "cache://pkg-scenario-alpha-v1",
                )]),
            })
            .expect("initial apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: initial_package_id.clone(),
            })
            .expect("initial mount should succeed");

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("pkg-cp".into())),
                    label: Some("pkg-mounted".into()),
                },
                1,
            )
            .expect("checkpoint creation should succeed");
        let checkpoint_id = checkpoint_id_from_events(&checkpoint_events);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-beta",
                    vec![package_locator(
                        PackageKind::CatalogPack,
                        semver(2, 0, 0),
                        digest(0x32),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::CatalogPack,
                    semver(2, 0, 0),
                    digest(0x32),
                    "cache://pkg-catalog-beta-v2",
                )]),
            })
            .expect("second apply should supersede installed state");
        let second_package_id = package_id_for(&PackageKind::CatalogPack, &digest(0x32));
        runtime
            .mount_package(MountPackageCommand {
                package_id: second_package_id,
            })
            .expect("second mount should succeed");

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("pkg-restored".into())),
                },
                2,
            )
            .expect("branch from checkpoint should succeed");

        let snapshot = runtime.snapshot();
        let mounted_manifest = snapshot
            .mounted_manifest
            .expect("mounted manifest should be restored from checkpoint");
        assert_eq!(mounted_manifest.manifest_id, "manifest-alpha".to_owned());
        assert_eq!(
            mounted_manifest
                .mounted_packages
                .iter()
                .map(|pkg| pkg.package_id.as_str())
                .collect::<Vec<_>>(),
            vec![initial_package_id.as_str()]
        );
    }

    #[test]
    fn extract_render_scene_uses_manifest_metadata_as_provenance_when_available() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x22);
        let package_id = package_id_for(&package_kind, &package_digest);
        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-alpha",
                    vec![package_locator(
                        package_kind.clone(),
                        semver(1, 2, 3),
                        package_digest.clone(),
                        true,
                    )],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 2, 3),
                    package_digest,
                    "cache://pkg-scenario-alpha-v123",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");

        let scene = extract_render_scene(&runtime.snapshot());
        let provenance = scene.provenance.expect("provenance should be present");

        assert_eq!(provenance.source, "stable".to_owned());
        assert_eq!(provenance.version, "1.0.0".to_owned());
        assert_eq!(provenance.manifest_id, "manifest-alpha".to_owned());
        assert_eq!(provenance.manifest_digest, None);
        assert_eq!(provenance.package_digest, Some(digest(0x22)));
    }

    #[test]
    fn extract_render_scene_includes_manifest_digest_when_available() {
        let mut runtime = new_runtime();
        let package_kind = PackageKind::Scenario;
        let package_digest = digest(0x23);
        let package_id = package_id_for(&package_kind, &package_digest);
        let manifest_digest = digest(0x44);
        let mut manifest = manifest(
            "manifest-beta",
            vec![package_locator(
                package_kind.clone(),
                semver(1, 2, 3),
                package_digest.clone(),
                true,
            )],
        );
        manifest.manifest_digest = Some(manifest_digest.clone());

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    package_kind,
                    semver(1, 2, 3),
                    package_digest,
                    "cache://pkg-scenario-beta-v123",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");

        let scene = extract_render_scene(&runtime.snapshot());
        let provenance = scene.provenance.expect("provenance should be present");

        assert_eq!(provenance.manifest_id, "manifest-beta".to_owned());
        assert_eq!(
            provenance.manifest_digest,
            Some(super::format_manifest_digest(&manifest_digest))
        );
        assert_eq!(provenance.package_digest, Some(digest(0x23)));
    }

    #[test]
    fn scene_revision_changes_when_observer_mode_changes() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth,
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon),
                },
                3,
            )
            .expect("focus body should succeed");
        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                4,
            )
            .expect("observer mode should succeed");

        let initial_revision = runtime.render_scene().scene_revision;

        runtime
            .apply_command(
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowHost,
                },
                5,
            )
            .expect("observer mode change should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn scene_revision_changes_when_selected_body_changes() {
        let mut runtime = new_runtime();
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: earth.clone(),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn earth should succeed");
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: moon.clone(),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.35e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 384_400_000.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn moon should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon),
                },
                3,
            )
            .expect("focus body should succeed");

        let moon_revision = runtime.render_scene().scene_revision;

        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(earth),
                },
                4,
            )
            .expect("focus body change should succeed");

        assert_ne!(runtime.render_scene().scene_revision, moon_revision);
    }

    #[test]
    fn extract_render_scene_trail_history_is_bounded_and_checkpoint_scoped() {
        let mut runtime = new_runtime();
        let tracer = BodyId("trail-probe".into());
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: tracer.clone(),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        radius_m: 20.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn tracer should succeed");

        for i in 0..(super::TRAIL_HISTORY_MAX_SAMPLES + 20) {
            runtime
                .apply_command(
                    WorldCommand::SetBodyKinematics {
                        body_id: tracer.clone(),
                        position_m: Vector3d {
                            x: i as f64,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                    (i + 2) as i64,
                )
                .expect("kinematics update should succeed");
        }

        let checkpoint_events = runtime
            .apply_command(
                WorldCommand::CreateCheckpoint {
                    checkpoint_id: Some(CheckpointId("trail-cp".into())),
                    label: Some("trail checkpoint".into()),
                },
                500,
            )
            .expect("checkpoint creation should succeed");
        let checkpoint_id = checkpoint_id_from_events(&checkpoint_events);

        for i in 0..5 {
            runtime
                .apply_command(
                    WorldCommand::SetBodyKinematics {
                        body_id: tracer.clone(),
                        position_m: Vector3d {
                            x: 1_000.0 + i as f64,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                    600 + i as i64,
                )
                .expect("post-checkpoint update should succeed");
        }

        let scene_after_extra_updates = runtime.render_scene();
        let trail_after_extra_updates = scene_after_extra_updates
            .trails
            .iter()
            .find(|trail| trail.source_body_id == tracer)
            .expect("trail should exist after updates");
        assert_eq!(
            trail_after_extra_updates.samples_m.len(),
            super::TRAIL_HISTORY_MAX_SAMPLES
        );

        runtime
            .apply_command(
                WorldCommand::CreateBranchFromCheckpoint {
                    checkpoint_id,
                    new_branch_id: Some(BranchId("trail-restored".into())),
                },
                700,
            )
            .expect("branch restore should succeed");

        let restored_scene = runtime.render_scene();
        let restored_trail = restored_scene
            .trails
            .iter()
            .find(|trail| trail.source_body_id == tracer)
            .expect("restored trail should exist");
        assert_eq!(
            restored_trail.samples_m.len(),
            super::TRAIL_HISTORY_MAX_SAMPLES
        );
        let restored_last_x = restored_trail
            .samples_m
            .last()
            .expect("restored trail should have a last sample")
            .x;
        assert!(restored_last_x < 1_000.0);
    }

    #[test]
    fn scene_revision_changes_when_tracer_or_light_families_change() {
        let mut runtime = new_runtime();
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("planet-a".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d::default(),
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("spawn planet should succeed");

        let base_revision = runtime.render_scene().scene_revision;
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("tracer-a".into()),
                        body_class: BodyClass::Tracer,
                        mass_kg: 1.0,
                        radius_m: 10.0,
                        position_m: Vector3d {
                            x: 10.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("spawn tracer should succeed");
        let tracer_revision = runtime.render_scene().scene_revision;
        assert_ne!(tracer_revision, base_revision);

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("star-a".into()),
                        body_class: BodyClass::Star,
                        mass_kg: 1.989e30,
                        radius_m: 696_000_000.0,
                        position_m: Vector3d {
                            x: -10.0,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                3,
            )
            .expect("spawn star should succeed");
        let light_revision = runtime.render_scene().scene_revision;
        assert_ne!(light_revision, tracer_revision);
    }

    #[test]
    fn extract_render_scene_uses_deterministic_set_digest_for_multi_package_manifests() {
        let mut runtime = new_runtime();
        let scenario_digest = digest(0x51);
        let catalog_digest = digest(0x52);
        let scenario_id = package_id_for(&PackageKind::Scenario, &scenario_digest);
        let catalog_id = package_id_for(&PackageKind::CatalogPack, &catalog_digest);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest(
                    "manifest-gamma",
                    vec![
                        package_locator(
                            PackageKind::Scenario,
                            semver(1, 0, 0),
                            scenario_digest.clone(),
                            true,
                        ),
                        package_locator(
                            PackageKind::CatalogPack,
                            semver(1, 0, 0),
                            catalog_digest.clone(),
                            true,
                        ),
                    ],
                ),
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![
                    stored_package(
                        PackageKind::Scenario,
                        semver(1, 0, 0),
                        scenario_digest,
                        "cache://pkg-scenario-gamma-v100",
                    ),
                    stored_package(
                        PackageKind::CatalogPack,
                        semver(1, 0, 0),
                        catalog_digest,
                        "cache://pkg-catalog-gamma-v100",
                    ),
                ]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: scenario_id,
            })
            .expect("scenario mount should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: catalog_id,
            })
            .expect("catalog mount should succeed");

        let provenance = runtime
            .render_scene()
            .provenance
            .expect("provenance should be present");

        let package_digest = provenance
            .package_digest
            .expect("set digest should be present for multiple mounted packages");
        assert_eq!(package_digest.algorithm, "mounted-set/v1".to_owned());
        assert!(!package_digest.value.is_empty());
    }

    #[test]
    fn scene_revision_changes_when_manifest_provenance_changes() {
        let mut runtime = new_runtime();
        let initial_revision = runtime.render_scene().scene_revision;
        let package_digest = digest(0x61);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);
        let mut manifest = manifest(
            "manifest-delta",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        manifest.manifest_digest = Some(digest(0x62));

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 0),
                    package_digest,
                    "cache://pkg-scenario-delta-v100",
                )]),
            })
            .expect("manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand { package_id })
            .expect("mount should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn scene_revision_changes_when_manifest_channel_or_version_changes() {
        let mut runtime = new_runtime();
        let package_digest = digest(0x71);
        let package_id = package_id_for(&PackageKind::Scenario, &package_digest);

        let mut manifest_alpha = manifest(
            "manifest-shared",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 0),
                package_digest.clone(),
                true,
            )],
        );
        manifest_alpha.channel = "stable".to_owned();

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest_alpha,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 0),
                    package_digest.clone(),
                    "cache://pkg-scenario-shared-v100",
                )]),
            })
            .expect("first manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand {
                package_id: package_id.clone(),
            })
            .expect("mount should succeed");
        let initial_revision = runtime.render_scene().scene_revision;

        let mut manifest_beta = manifest(
            "manifest-shared",
            vec![package_locator(
                PackageKind::Scenario,
                semver(1, 0, 1),
                package_digest.clone(),
                true,
            )],
        );
        manifest_beta.channel = "beta".to_owned();
        manifest_beta.manifest_version = semver(1, 0, 1);

        runtime
            .apply_update_manifest(ApplyUpdateManifestCommand {
                manifest: manifest_beta,
                target: compatibility_target(),
                fetched_packages_by_id: fetched_map(vec![stored_package(
                    PackageKind::Scenario,
                    semver(1, 0, 1),
                    package_digest,
                    "cache://pkg-scenario-shared-v100",
                )]),
            })
            .expect("second manifest apply should succeed");
        runtime
            .mount_package(MountPackageCommand { package_id })
            .expect("remount should succeed");

        assert_ne!(runtime.render_scene().scene_revision, initial_revision);
    }

    #[test]
    fn major_body_orbit_telemetry_stays_within_legacy_thresholds() {
        let metrics = run_major_body_telemetry(900.0, 32);

        const RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX: f64 = 1.0e-6;
        const BARYCENTER_DRIFT_M_MAX: f64 = 50.0;
        const BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX: f64 = 10.0;
        const MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX: f64 = 1.0e-3;

        assert!(
            metrics.relative_angular_momentum_drift <= RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX,
            "relative_angular_momentum_drift={} exceeded {}",
            metrics.relative_angular_momentum_drift,
            RELATIVE_ANGULAR_MOMENTUM_DRIFT_MAX
        );
        assert!(
            metrics.barycenter_drift_m <= BARYCENTER_DRIFT_M_MAX,
            "barycenter_drift_m={} exceeded {}",
            metrics.barycenter_drift_m,
            BARYCENTER_DRIFT_M_MAX
        );
        assert!(
            metrics.barycenter_fine_baseline_distance_error_m
                <= BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX,
            "barycenter_fine_baseline_distance_error_m={} exceeded {}",
            metrics.barycenter_fine_baseline_distance_error_m,
            BARYCENTER_FINE_BASELINE_DISTANCE_ERROR_M_MAX
        );
        assert!(
            metrics.moon_earth_fine_baseline_error_ratio
                <= MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX,
            "moon_earth_fine_baseline_error_ratio={} exceeded {}",
            metrics.moon_earth_fine_baseline_error_ratio,
            MOON_EARTH_FINE_BASELINE_ERROR_RATIO_MAX
        );
    }

    fn checkpoint_id_from_events(events: &[RuntimeEvent]) -> CheckpointId {
        events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::CheckpointCreated(descriptor)) => {
                    Some(descriptor.checkpoint_id.clone())
                }
                _ => None,
            })
            .expect("checkpoint event should exist")
    }

    fn branch_id_from_events(events: &[RuntimeEvent]) -> BranchId {
        events
            .iter()
            .find_map(|event| match event {
                RuntimeEvent::HistoryAppended(HistoryEvent::BranchCreated(descriptor)) => {
                    Some(descriptor.branch_id.clone())
                }
                _ => None,
            })
            .expect("branch event should exist")
    }

    fn new_runtime() -> WorldRuntime {
        WorldRuntime::new(
            ScenarioId("sol-system".into()),
            BranchId("main".into()),
            RuntimeConfig {
                physics: PhysicsPolicy {
                    solver_backend: SolverBackend::ReferenceScalar,
                    integrator: IntegratorKind::LeapfrogKickDriftKick,
                    collision_model: CollisionModel::None,
                    max_substep_seconds: 1.0,
                },
                timeline_semantics: TimelineSemantics::BranchedSandbox,
                live_updates_enabled: false,
            },
            HardwareProfile::offline_reference(),
            0,
        )
    }

    fn semver(major: u32, minor: u32, patch: u32) -> SemVer {
        SemVer::new(major, minor, patch)
    }

    fn digest(fill: u8) -> Digest {
        Digest {
            algorithm: "sha256".to_owned(),
            value: vec![fill; 32],
        }
    }

    fn compatibility_target() -> CompatibilityTarget {
        CompatibilityTarget {
            runtime_contract: semver(1, 0, 0),
            schema_version: "schema.v1".to_owned(),
            capabilities: BTreeSet::from(["render.3d".to_owned(), "gravity.nbody".to_owned()]),
            platform: "android-arm64".to_owned(),
        }
    }

    fn package_locator(
        kind: PackageKind,
        package_version: SemVer,
        digest: Digest,
        required: bool,
    ) -> PackageLocator {
        let package_id = package_id_for(&kind, &digest);
        PackageLocator {
            package_id: package_id.clone(),
            kind,
            package_version,
            schema_version: "schema.v1".to_owned(),
            digest,
            relative_uri: format!("packages/{package_id}.zip"),
            uncompressed_size_bytes: 1_024,
            required,
            compatibility: PackageCompatibility {
                runtime_contract_min: semver(1, 0, 0),
                runtime_contract_max: semver(1, 2, 0),
                required_capabilities: BTreeSet::from(["gravity.nbody".to_owned()]),
                supported_platforms: BTreeSet::from(["android-arm64".to_owned()]),
            },
        }
    }

    fn stored_package(
        kind: PackageKind,
        package_version: SemVer,
        digest: Digest,
        local_store_uri: &str,
    ) -> StoredPackage {
        let package_id = package_id_for(&kind, &digest);
        StoredPackage {
            package_id,
            kind,
            digest,
            package_version,
            schema_version: "schema.v1".to_owned(),
            uncompressed_size_bytes: 1_024,
            local_store_uri: local_store_uri.to_owned(),
        }
    }

    fn manifest(manifest_id: &str, packages: Vec<PackageLocator>) -> UpdateManifest {
        UpdateManifest {
            manifest_id: manifest_id.to_owned(),
            manifest_version: semver(1, 0, 0),
            channel: "stable".to_owned(),
            packages,
            manifest_digest: None,
            full_snapshot: true,
            supersedes_manifest_ids: Vec::new(),
        }
    }

    fn fetched_map(packages: Vec<StoredPackage>) -> BTreeMap<String, StoredPackage> {
        packages
            .into_iter()
            .map(|package| (package.package_id.clone(), package))
            .collect()
    }

    fn package_id_for(kind: &PackageKind, digest: &Digest) -> String {
        digest.content_id(kind)
    }

    fn spawn_two_body_system(runtime: &mut WorldRuntime) {
        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("primary".into()),
                        body_class: BodyClass::Planet,
                        mass_kg: 5.972e24,
                        radius_m: 6_371_000.0,
                        position_m: Vector3d {
                            x: -2.0e7,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                1,
            )
            .expect("primary spawn should succeed");

        runtime
            .apply_command(
                WorldCommand::SpawnBody {
                    body: BodyState {
                        body_id: BodyId("secondary".into()),
                        body_class: BodyClass::Moon,
                        mass_kg: 7.348e22,
                        radius_m: 1_737_000.0,
                        position_m: Vector3d {
                            x: 2.0e7,
                            y: 0.0,
                            z: 0.0,
                        },
                        velocity_mps: Vector3d::default(),
                    },
                },
                2,
            )
            .expect("secondary spawn should succeed");
    }

    fn body_separation_x(snapshot: &super::WorldSnapshot) -> f64 {
        let primary = BodyId("primary".into());
        let secondary = BodyId("secondary".into());
        let left = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == primary)
            .expect("primary should exist");
        let right = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == secondary)
            .expect("secondary should exist");
        (right.position_m.x - left.position_m.x).abs()
    }

    fn body_position(snapshot: &super::WorldSnapshot, body_id: &BodyId) -> Vector3d {
        let body = snapshot
            .bodies
            .iter()
            .find(|body| body.body_id == *body_id)
            .expect("body should exist");
        body.position_m
    }

    fn relative_position(from: &Vector3d, to: &Vector3d) -> Vector3d {
        Vector3d {
            x: from.x - to.x,
            y: from.y - to.y,
            z: from.z - to.z,
        }
    }

    fn displacement_magnitude(a: &Vector3d, b: &Vector3d) -> f64 {
        let dx = a.x - b.x;
        let dy = a.y - b.y;
        let dz = a.z - b.z;
        (dx * dx + dy * dy + dz * dz).sqrt()
    }

    struct TelemetryMetrics {
        relative_energy_drift: f64,
        relative_angular_momentum_drift: f64,
        barycenter_drift_m: f64,
        barycenter_fine_baseline_distance_error_m: f64,
        moon_earth_fine_baseline_error_ratio: f64,
    }

    fn run_major_body_telemetry(step_seconds: f64, steps: usize) -> TelemetryMetrics {
        let coarse = propagate_major_bodies(step_seconds, steps);
        let fine_step = step_seconds / 4.0;
        let fine = propagate_major_bodies(fine_step, steps * 4);

        let relative_energy_drift = drift(
            coarse.initial_invariants.total_energy_j,
            coarse.final_invariants.total_energy_j,
        );
        let relative_angular_momentum_drift = drift(
            vec_magnitude(coarse.initial_invariants.angular_momentum_kg_m2ps),
            vec_magnitude(coarse.final_invariants.angular_momentum_kg_m2ps),
        );

        let barycenter_drift_m =
            displacement_magnitude(&coarse.initial_barycenter, &coarse.final_barycenter);
        let barycenter_fine_baseline_distance_error_m =
            displacement_magnitude(&coarse.final_barycenter, &fine.final_barycenter);

        let moon_earth_fine_baseline_error_ratio = {
            let coarse_distance = moon_earth_distance_au(&coarse.final_snapshot);
            let fine_distance = moon_earth_distance_au(&fine.final_snapshot);
            if fine_distance > 0.0 {
                (coarse_distance - fine_distance).abs() / fine_distance
            } else {
                0.0
            }
        };

        TelemetryMetrics {
            relative_energy_drift,
            relative_angular_momentum_drift,
            barycenter_drift_m,
            barycenter_fine_baseline_distance_error_m,
            moon_earth_fine_baseline_error_ratio,
        }
    }

    struct PropagationResult {
        initial_snapshot: WorldSnapshot,
        final_snapshot: WorldSnapshot,
        initial_invariants: PhysicsInvariants,
        final_invariants: PhysicsInvariants,
        initial_barycenter: Vector3d,
        final_barycenter: Vector3d,
    }

    fn propagate_major_bodies(step_seconds: f64, steps: usize) -> PropagationResult {
        let mut runtime = new_runtime();
        runtime.config.physics.max_substep_seconds = step_seconds.min(60.0);
        seed_major_bodies(&mut runtime);

        let initial_snapshot = runtime.snapshot();
        let initial_invariants = compute_world_invariants(&initial_snapshot.bodies);
        let initial_barycenter = initial_invariants.barycenter_m;

        for i in 0..steps {
            runtime
                .apply_command(
                    WorldCommand::AdvanceEpoch {
                        delta_seconds: step_seconds,
                    },
                    (i as i64) + 1_000,
                )
                .expect("advance epoch should succeed");
        }

        let final_snapshot = runtime.snapshot();
        let final_invariants = compute_world_invariants(&final_snapshot.bodies);
        let final_barycenter = final_invariants.barycenter_m;

        PropagationResult {
            initial_snapshot,
            final_snapshot,
            initial_invariants,
            final_invariants,
            initial_barycenter,
            final_barycenter,
        }
    }

    fn seed_major_bodies(runtime: &mut WorldRuntime) {
        use solarlab_data::canonical_startup_seed;

        let seed = canonical_startup_seed();
        let major_bodies: Vec<BodyState> = seed
            .bodies
            .into_iter()
            .filter(|body| body.body_class != BodyClass::SmallBody)
            .map(|body| BodyState {
                body_id: BodyId(body.body_id),
                body_class: body.body_class,
                mass_kg: body.mass_kg,
                radius_m: body.radius_m,
                position_m: body.position_m,
                velocity_mps: body.velocity_mps,
            })
            .collect();

        let branch = runtime.active_branch_mut();
        branch.world.bodies = major_bodies;
        branch.world.invariants = compute_world_invariants(&branch.world.bodies);
        branch.world.trail_history_by_body.clear();
        record_trail_samples_from_bodies(
            &branch.world.bodies,
            &mut branch.world.trail_history_by_body,
        );
    }

    fn drift(initial: f64, final_value: f64) -> f64 {
        if initial.abs() <= f64::EPSILON {
            0.0
        } else {
            (final_value - initial).abs() / initial.abs()
        }
    }

    fn moon_earth_distance_au(snapshot: &WorldSnapshot) -> f64 {
        const AU_M: f64 = 1.495_978_707e11;
        let earth = BodyId("earth".into());
        let moon = BodyId("moon".into());

        let earth_pos = body_position(snapshot, &earth);
        let moon_pos = body_position(snapshot, &moon);
        displacement_magnitude(&earth_pos, &moon_pos) / AU_M
    }
}
