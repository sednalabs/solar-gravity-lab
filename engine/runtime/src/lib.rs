use std::collections::HashMap;

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
}

#[derive(Clone, Debug, PartialEq)]
struct BranchWorldState {
    epoch_seconds: f64,
    bodies: Vec<BodyState>,
    observer: ObserverState,
    playback: PlaybackState,
    invariants: PhysicsInvariants,
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
        }
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

#[cfg(test)]
mod tests {
    use solarlab_domain::{
        BodyClass, BodyId, BranchId, CheckpointId, ObserverMode, ScenarioId, TimelineSemantics,
        Vector3d,
    };
    use solarlab_hardware::HardwareProfile;
    use solarlab_history::HistoryEvent;
    use solarlab_physics::{CollisionModel, IntegratorKind, PhysicsPolicy, SolverBackend};

    use super::{BodyState, RuntimeConfig, RuntimeError, RuntimeEvent, WorldCommand, WorldRuntime};

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
}
