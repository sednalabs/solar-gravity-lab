use solarlab_domain::{BodyId, BranchId, CheckpointId, ScenarioId, TimelineSemantics, Vector3d};

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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OrbitArchiveFamily {
    Trajectory,
    HistoricalOrbit,
    Prediction,
}

#[derive(Clone, Debug, PartialEq)]
pub struct OrbitSample {
    pub epoch_seconds: f64,
    pub position_m: Vector3d,
}

#[derive(Clone, Debug, PartialEq)]
pub struct OrbitArchive {
    pub archive_id: String,
    pub source_body_id: BodyId,
    pub family: OrbitArchiveFamily,
    pub max_samples: u32,
    pub samples: Vec<OrbitSample>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct OrbitArchiveQuery {
    pub body_ids: Vec<BodyId>,
    pub include_trajectory: bool,
    pub include_historical_orbits: bool,
    pub include_prediction: bool,
    pub checkpoint_sample_limit: usize,
    pub prediction_step_seconds: f64,
    pub prediction_sample_count: usize,
}

impl Default for OrbitArchiveQuery {
    fn default() -> Self {
        Self {
            body_ids: Vec::new(),
            include_trajectory: true,
            include_historical_orbits: true,
            include_prediction: true,
            checkpoint_sample_limit: 24,
            prediction_step_seconds: 3_600.0,
            prediction_sample_count: 12,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        BranchDescriptor, BranchId, CheckpointDescriptor, CheckpointId, HistoryEvent,
        OrbitArchiveFamily, OrbitArchiveQuery, OrbitSample, ScenarioId, TimelineSemantics,
    };
    use solarlab_domain::{BodyId, Vector3d};

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

    #[test]
    fn default_orbit_archive_query_enables_all_overlay_families() {
        let query = OrbitArchiveQuery::default();

        assert!(query.include_trajectory);
        assert!(query.include_historical_orbits);
        assert!(query.include_prediction);
        assert_eq!(query.checkpoint_sample_limit, 24);
        assert_eq!(query.prediction_step_seconds, 3_600.0);
        assert_eq!(query.prediction_sample_count, 12);
    }

    #[test]
    fn orbit_archive_family_preserves_authoritative_samples() {
        let sample = OrbitSample {
            epoch_seconds: 10.0,
            position_m: Vector3d {
                x: 1.0,
                y: 2.0,
                z: 3.0,
            },
        };

        let archive = super::OrbitArchive {
            archive_id: "prediction:moon".into(),
            source_body_id: BodyId("moon".into()),
            family: OrbitArchiveFamily::Prediction,
            max_samples: 12,
            samples: vec![sample.clone()],
        };

        assert_eq!(archive.family, OrbitArchiveFamily::Prediction);
        assert_eq!(archive.samples, vec![sample]);
    }
}
