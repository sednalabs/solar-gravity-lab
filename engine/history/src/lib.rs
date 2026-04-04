use solarlab_domain::{BranchId, CheckpointId, ScenarioId, TimelineSemantics};

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct CommandId(pub String);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommandRecordHeader {
    pub command_id: CommandId,
    pub branch_id: BranchId,
    pub sequence: u64,
    pub recorded_at_unix_ms: i64,
    pub command_kind: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommandRecord {
    pub header: CommandRecordHeader,
    pub summary: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BranchDescriptor {
    pub branch_id: BranchId,
    pub scenario_id: ScenarioId,
    pub created_at_unix_ms: i64,
    pub parent_branch_id: Option<BranchId>,
    pub parent_checkpoint_id: Option<CheckpointId>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CheckpointDescriptor {
    pub checkpoint_id: CheckpointId,
    pub branch_id: BranchId,
    pub scenario_id: ScenarioId,
    pub command_sequence: u64,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
    pub label: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum HistoryEvent {
    CommandAppended(CommandRecordHeader),
    CheckpointCreated(CheckpointDescriptor),
    BranchCreated(BranchDescriptor),
}

#[cfg(test)]
mod tests {
    use super::{
        BranchDescriptor, BranchId, CheckpointDescriptor, CheckpointId, HistoryEvent, ScenarioId,
        TimelineSemantics,
    };

    #[test]
    fn branch_descriptor_captures_lineage() {
        let descriptor = BranchDescriptor {
            branch_id: BranchId("branch-002".into()),
            scenario_id: ScenarioId("sol".into()),
            created_at_unix_ms: 1_709_400_000_000,
            parent_branch_id: Some(BranchId("main".into())),
            parent_checkpoint_id: Some(CheckpointId("cp-001".into())),
        };

        assert_eq!(descriptor.parent_branch_id, Some(BranchId("main".into())));
        assert_eq!(
            descriptor.parent_checkpoint_id,
            Some(CheckpointId("cp-001".into()))
        );
    }

    #[test]
    fn history_event_preserves_checkpoint_payload() {
        let checkpoint = CheckpointDescriptor {
            checkpoint_id: CheckpointId("cp-002".into()),
            branch_id: BranchId("branch-002".into()),
            scenario_id: ScenarioId("sol".into()),
            command_sequence: 9,
            epoch_seconds: 12_000.0,
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            label: Some("post-burn".into()),
        };

        let event = HistoryEvent::CheckpointCreated(checkpoint.clone());

        assert_eq!(event, HistoryEvent::CheckpointCreated(checkpoint));
    }
}
