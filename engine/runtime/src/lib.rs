use solarlab_domain::{BodyId, BranchId, ObserverMode, ScenarioId, TimelineSemantics};
use solarlab_hardware::HardwareProfile;
use solarlab_history::CheckpointDescriptor;
use solarlab_physics::{PhysicsInvariants, PhysicsPolicy};

#[derive(Clone, Debug, PartialEq)]
pub enum BodyMutation {
    AddPlaceholder { body_id: BodyId },
    Remove { body_id: BodyId },
}

#[derive(Clone, Debug, PartialEq)]
pub enum PlaybackMutation {
    Pause,
    Resume,
    SetRate { sim_seconds_per_real_second: f64 },
    RewindToCheckpoint { checkpoint_id: String },
}

#[derive(Clone, Debug, PartialEq)]
pub enum ObserverMutation {
    SetMode(ObserverMode),
    FocusBody(Option<BodyId>),
}

#[derive(Clone, Debug, PartialEq)]
pub enum WorldCommand {
    Body(BodyMutation),
    Playback(PlaybackMutation),
    Observer(ObserverMutation),
    BranchFromCheckpoint { checkpoint_id: String },
}

#[derive(Clone, Debug, PartialEq)]
pub struct RuntimeConfig {
    pub physics: PhysicsPolicy,
    pub live_updates_enabled: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WorldSnapshot {
    pub scenario_id: ScenarioId,
    pub branch_id: BranchId,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
    pub body_count: u32,
    pub active_checkpoint: Option<CheckpointDescriptor>,
    pub hardware_profile: HardwareProfile,
    pub invariants: PhysicsInvariants,
}

#[derive(Clone, Debug, PartialEq)]
pub enum RuntimeEvent {
    SnapshotPublished(WorldSnapshot),
    BranchCreated(BranchId),
}
