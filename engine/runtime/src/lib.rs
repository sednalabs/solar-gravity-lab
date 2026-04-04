use std::collections::{BTreeMap, BTreeSet, HashMap};

use solarlab_data::{
    apply_update_plan, plan_manifest_update, ApplyPackageInputs, ApplyProvenance, ApplyUpdateError,
    CompatibilityTarget, Digest, LocalDataState, PackageKind, SemVer, StoredPackage,
    UpdateManifest, UpdatePlan, UpdatePlanError,
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
use solarlab_physics::{PhysicsInvariants, PhysicsPolicy};
use solarlab_scene::{
    CameraPose, ColorRgba, RenderDiagnostics, RenderScene, SceneBody, SceneProvenanceRef,
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
    pub paused: bool,
    pub sim_seconds_per_real_second: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ObserverState {
    pub mode: ObserverMode,
    pub focus_body_id: Option<BodyId>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RuntimeConfig {
    pub physics: PhysicsPolicy,
    pub timeline_semantics: TimelineSemantics,
    pub live_updates_enabled: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountedPackageState {
    pub package_id: String,
    pub kind: PackageKind,
    pub package_version: SemVer,
    pub schema_version: String,
    pub digest: Digest,
    pub local_store_uri: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountedManifestState {
    pub manifest_id: String,
    pub manifest_version: SemVer,
    pub channel: String,
    pub manifest_digest: Option<Digest>,
    pub mounted_packages: Vec<MountedPackageState>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WorldSnapshot {
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
}

#[derive(Clone, Debug, PartialEq)]
pub enum WorldCommand {
    SpawnBody {
        body: BodyState,
    },
    RemoveBody {
        body_id: BodyId,
    },
    SetBodyKinematics {
        body_id: BodyId,
        position_m: Vector3d,
        velocity_mps: Vector3d,
    },
    AdvanceEpoch {
        delta_seconds: f64,
    },
    PausePlayback,
    ResumePlayback,
    SetPlaybackRate {
        sim_seconds_per_real_second: f64,
    },
    SetObserverMode {
        mode: ObserverMode,
    },
    FocusBody {
        body_id: Option<BodyId>,
    },
    CreateCheckpoint {
        checkpoint_id: Option<CheckpointId>,
        label: Option<String>,
    },
    CreateBranchFromCheckpoint {
        checkpoint_id: CheckpointId,
        new_branch_id: Option<BranchId>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub enum RuntimeEvent {
    HistoryAppended(HistoryEvent),
    SnapshotPublished(WorldSnapshot),
}

#[derive(Clone, Debug, PartialEq)]
pub enum RuntimeError {
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
    pub manifest: UpdateManifest,
    pub target: CompatibilityTarget,
    pub fetched_packages_by_id: BTreeMap<String, StoredPackage>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MountPackageCommand {
    pub package_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplyUpdateManifestResult {
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

#[derive(Clone, Debug)]
pub struct WorldRuntime {
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

    pub fn mount_package(
        &mut self,
        command: MountPackageCommand,
    ) -> Result<MountedManifestState, RuntimeError> {
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
            }
            WorldCommand::AdvanceEpoch { delta_seconds } => {
                if *delta_seconds <= 0.0 {
                    return Err(RuntimeError::InvalidEpochDelta(*delta_seconds));
                }
                let branch = self.active_branch_mut();
                branch.world.epoch_seconds += *delta_seconds;
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
        }
    }

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
    let selected_body = snapshot.observer.focus_body_id.as_ref();
    let bodies = snapshot
        .bodies
        .iter()
        .map(|body| SceneBody {
            body_id: body.body_id.clone(),
            display_name: body.body_id.0.clone(),
            position_m: body.position_m,
            radius_m: body.radius_m,
            albedo: ColorRgba {
                r: 1.0,
                g: 1.0,
                b: 1.0,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            selected: selected_body
                .map(|focused| focused == &body.body_id)
                .unwrap_or(false),
        })
        .collect();

    RenderScene {
        observer_mode: snapshot.observer.mode.clone(),
        body_count: 0,
        tracer_count: 0,
        trail_count: 0,
        scene_revision: scene_revision_from_snapshot(snapshot),
        epoch_seconds: snapshot.epoch_seconds,
        timeline_semantics: snapshot.timeline_semantics.clone(),
        camera: camera_pose_from_snapshot(snapshot),
        bodies,
        tracers: Vec::new(),
        trails: Vec::new(),
        lights: Vec::new(),
        provenance: scene_provenance(snapshot),
        diagnostics: RenderDiagnostics::default(),
    }
    .with_derived_counts()
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

fn scene_revision_from_snapshot(snapshot: &WorldSnapshot) -> String {
    let selected_body_id = snapshot.observer.focus_body_id.as_ref();
    let mut revision = format!(
        "scenario={}|branch={}|epoch={:.6}|observer={:?}",
        snapshot.scenario_id.0,
        snapshot.branch_id.0,
        snapshot.epoch_seconds,
        snapshot.observer.mode
    );
    for body in &snapshot.bodies {
        let selected = selected_body_id == Some(&body.body_id);
        use std::fmt::Write as _;
        let _ = write!(
            &mut revision,
            "|{}|selected={selected}|r={:.6}|p=({:.6},{:.6},{:.6})|v=({:.6},{:.6},{:.6})",
            body.body_id.0,
            body.radius_m,
            body.position_m.x,
            body.position_m.y,
            body.position_m.z,
            body.velocity_mps.x,
            body.velocity_mps.y,
            body.velocity_mps.z
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

    revision
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
    use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};

    use super::{
        extract_render_scene, ApplyUpdateManifestCommand, BodyState, MountPackageCommand,
        RuntimeConfig, RuntimeError, RuntimeEvent, WorldCommand, WorldRuntime,
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
    fn render_scene_extracts_selected_body_counts_and_camera_from_runtime() {
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
                WorldCommand::SetObserverMode {
                    mode: ObserverMode::FollowSelected,
                },
                3,
            )
            .expect("setting observer mode should succeed");
        runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(moon.clone()),
                },
                4,
            )
            .expect("focus body should succeed");

        let scene = runtime.render_scene();
        assert_eq!(scene.body_count, 2);
        assert_eq!(scene.tracer_count, 0);
        assert_eq!(scene.trail_count, 0);
        assert_eq!(scene.observer_mode, ObserverMode::FollowSelected);
        assert_eq!(scene.bodies.len(), 2);
        assert_eq!(scene.tracers.len(), 0);
        assert_eq!(scene.trails.len(), 0);
        assert_eq!(scene.lights.len(), 0);
        assert!(scene.scene_revision.contains("branch=main"));
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
}
