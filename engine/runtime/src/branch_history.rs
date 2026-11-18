//! Command metadata, branch/checkpoint IDs and branch-state navigation.
//! Authoritative mutations remain on the single WorldRuntime; no shadow world is created.
use super::*;

impl WorldRuntime {
    pub(super) fn new_command_header(
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

    pub(super) fn command_kind(&self, command: &WorldCommand) -> String {
        match command {
            WorldCommand::SeedCanonicalSolarSystem => "catalog.seed_canonical_solar_system",
            WorldCommand::SpawnSpacecraft { .. } => "spacecraft.spawn",
            WorldCommand::SetFlightControl { .. } => "spacecraft.control",
            WorldCommand::StartGuidedBurn { .. } => "spacecraft.guided_burn",
            WorldCommand::SetRendezvousGuidance { .. } => "spacecraft.rendezvous_guidance",
            WorldCommand::SetDockingGuidance { .. } => "spacecraft.docking_guidance",
            WorldCommand::UndockSpacecraft { .. } => "spacecraft.undock",
            WorldCommand::SpawnBody { .. } => "body.spawn",
            WorldCommand::RemoveBody { .. } => "body.remove",
            WorldCommand::SetBodyKinematics { .. } => "body.set_kinematics",
            WorldCommand::SetBodyProperties { .. } => "body.set_properties",
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

    pub(super) fn command_summary(&self, command: &WorldCommand) -> String {
        match command {
            WorldCommand::SeedCanonicalSolarSystem => "seed canonical startup solar system".into(),
            WorldCommand::SpawnSpacecraft { body, .. } => format!("spawn spacecraft {}",body.body_id.0),
            WorldCommand::SetFlightControl { body_id, control } => format!("flight {} throttle={} translation={:?} rotation={:?} assist={:?}",body_id.0,control.throttle,control.translation_body,control.rotation_body,control.attitude_mode),
            WorldCommand::StartGuidedBurn { body_id, full_throttle_seconds } => format!("guided prograde burn {} duration={}s", body_id.0, full_throttle_seconds),
            WorldCommand::SetRendezvousGuidance { body_id, mode } => format!("rendezvous guidance {} mode={mode:?}", body_id.0),
            WorldCommand::SetDockingGuidance { body_id, mode } => format!("docking guidance {} mode={mode:?}", body_id.0),
            WorldCommand::UndockSpacecraft { body_id } => format!("release docking constraint {}", body_id.0),
            WorldCommand::SpawnBody { body } => format!("spawn {}", body.body_id.0),
            WorldCommand::RemoveBody { body_id } => format!("remove {}", body_id.0),
            WorldCommand::SetBodyProperties { body_id, mass_kg, source_mass_kg, .. } =>
                format!("set properties {} mass={} kg source={} kg", body_id.0, mass_kg, source_mass_kg),
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

    pub(super) fn generate_checkpoint_id(&mut self) -> CheckpointId {
        let id = CheckpointId(format!("cp-{:06}", self.next_checkpoint_ordinal));
        self.next_checkpoint_ordinal += 1;
        id
    }

    pub(super) fn generate_branch_id(&mut self) -> BranchId {
        let id = BranchId(format!("branch-{:03}", self.next_branch_ordinal));
        self.next_branch_ordinal += 1;
        id
    }

    pub(super) fn bump_next_checkpoint_ordinal_if_needed(&mut self, checkpoint_id: &CheckpointId) {
        if let Some(sequence) = checkpoint_id.0.strip_prefix("cp-") {
            if let Ok(value) = sequence.parse::<u64>() {
                self.next_checkpoint_ordinal = self.next_checkpoint_ordinal.max(value + 1);
            }
        }
    }

    pub(super) fn bump_next_branch_ordinal_if_needed(&mut self, branch_id: &BranchId) {
        if let Some(sequence) = branch_id.0.strip_prefix("branch-") {
            if let Ok(value) = sequence.parse::<u64>() {
                self.next_branch_ordinal = self.next_branch_ordinal.max(value + 1);
            }
        }
    }

    pub(super) fn active_branch(&self) -> &BranchState {
        self.branches
            .get(&self.active_branch_id)
            .expect("active branch must exist")
    }

    pub(super) fn active_branch_mut(&mut self) -> &mut BranchState {
        self.branches
            .get_mut(&self.active_branch_id)
            .expect("active branch must exist")
    }

    pub(super) fn is_checkpoint_id_in_use(&self, checkpoint_id: &CheckpointId) -> bool {
        self.branches.values().any(|branch| {
            branch
                .checkpoints
                .iter()
                .any(|record| record.descriptor.checkpoint_id == *checkpoint_id)
        })
    }

    pub(super) fn find_checkpoint_record(
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
