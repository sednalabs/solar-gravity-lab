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

#[derive(Clone, Debug, PartialEq)]
pub struct CheckpointDescriptor {
    pub checkpoint_id: CheckpointId,
    pub branch_id: BranchId,
    pub scenario_id: ScenarioId,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
}
