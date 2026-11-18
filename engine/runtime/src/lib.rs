//! Authoritative v2 runtime core for simulation state.
//!
//! This crate owns all mutable world state and branch history for the Rust
//! runtime architecture. All external integrations (FFI, JNI, adapters,
//! services) should treat this crate as the single source of truth for world
//! semantics.
mod lab_prediction;
mod spacecraft;
mod flight_mission;
mod flight_prediction;
pub use flight_prediction::LunarCoastForecastDiagnostics;
pub use flight_mission::{LunarMissionOutcome,LUNAR_FLYBY_SUCCESS_ALTITUDE_M,LUNAR_APPROACH_ALTITUDE_M,LUNAR_MISSION_DEADLINE_SECONDS,LunarMissionPhase,LunarMissionTelemetry,LUNAR_FLIGHT_BODY_ID,LUNAR_FLIGHT_MODEL,LUNAR_DEPARTURE_BURN_SECONDS,LUNAR_INSERTION_PHASE_RADIANS,LUNAR_INSERTION_ALTITUDE_M,LUNAR_EXPECTED_FLYBY_WINDOW_SECONDS};
mod flight_telemetry;
mod rendezvous;
pub use rendezvous::{RendezvousGuidance, RendezvousGuidanceMode, RendezvousScenario, RendezvousStatus, RendezvousTelemetry};
mod rendezvous_flight;
pub use rendezvous_flight::{RendezvousFlightTelemetry, RENDEZVOUS_STATION_BODY_ID};
pub use flight_telemetry::FlightTelemetry;
mod body_properties;
mod lab_step;
mod persistence;
mod probe_authoring;
mod trail_frame;
mod advance;
mod branch_history;
mod command_application;
mod observations;
mod orbit_archives;
mod scene_projection;
mod session;
mod world_support;
#[cfg(test)]
mod tests;
pub use persistence::{SessionPersistenceError, MAX_SAVED_SESSION_BYTES};
pub use probe_authoring::{
    ProbeAuthoringError, ProbeCommitReceipt, ProbePreview, ProbePreviewPlan, ProbePreviewSample,
};

pub use scene_projection::extract_render_scene;

use orbit_archives::{
    build_orbit_archives, build_prediction_archives, next_epoch,
    push_trail_sample, record_trail_samples_from_bodies, render_scene_orbit_body_ids,
    representable_epoch_within, vec_magnitude,
};
use scene_projection::{
    format_manifest_digest, mounted_manifest_telemetry_from_state, runtime_trail_history_counts,
    scene_provenance,
};
use world_support::{
    apply_solver_state_to_world_bodies, compute_world_invariants, mounted_manifest_from_state,
    package_provenance_digest, world_bodies_to_solver_state,
};

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::mem::size_of;
use std::time::Instant;

use solarlab_data::{
    apply_update_plan, canonical_celestial_appearance, plan_manifest_update, scenario_pack_seed,
    ApplyPackageInputs, ApplyProvenance, ApplyUpdateError, CompatibilityTarget, Digest,
    LocalDataState, PackageKind, SemVer, StoredPackage, UpdateManifest, UpdatePlan,
    UpdatePlanError,
};

use solarlab_domain::{
    BodyClass, BodyId, BranchId, CheckpointId, ObserverMode, ScenarioId, TimelineSemantics,
    Vector3d, FlightControl, SpacecraftState, Quaterniond,
};
use solarlab_hardware::HardwareProfile;
use solarlab_history::{
    BranchDescriptor, CheckpointDescriptor, CommandId, CommandRecord, CommandRecordHeader,
    HistoryEvent, OrbitArchive, OrbitArchiveFamily, OrbitArchiveQuery, OrbitSample,
};
use solarlab_physics::{
    advance_authoritative_with_features, checked_substep_count,
    compute_invariants, fixed_step_plan_with_source_count, solver_execution_report_for_backend_with_source_count,
    FixedStepPlan, MassiveBodyState, PhysicsInvariants, PhysicsPolicy, SolverExecutionReport,
    MAX_INCREMENTAL_STEPS,
};
use solarlab_scene::{
    CameraPose, ColorRgba, LightSource, RenderDiagnostics, RenderScene, SceneBody, SceneBodyKind,
    SceneCelestialAppearance, SceneCometVisualGuide, ScenePacketMetadata, SceneProvenanceRef,
    SceneTracer, SceneTrail, SceneTrailFamily,
};

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
pub struct BodyState {
    pub body_id: BodyId,
    pub body_class: BodyClass,
    pub mass_kg: f64,
    pub source_mass_kg: f64,
    pub radius_m: f64,
    pub position_m: Vector3d,
    pub velocity_mps: Vector3d,
}

impl BodyState {
    pub fn default_source_mass_kg(body_class: &BodyClass, mass_kg: f64) -> f64 {
        match body_class {
            BodyClass::Tracer
            | BodyClass::Spacecraft
            | BodyClass::SmallBody
            | BodyClass::Comet => 0.0,
            BodyClass::Star
            | BodyClass::Planet
            | BodyClass::DwarfPlanet
            | BodyClass::Moon
            | BodyClass::Custom => mass_kg,
        }
    }

    /// Mass that participates as a gravity source in the authoritative solver.
    ///
    /// `mass_kg` is still the body's physical/inertial display mass, but
    /// teaching probes, tracer particles, and small-body catalog markers must
    /// not silently perturb the canonical system just because they carry a mass
    /// for UI, density, or momentum displays.
    pub fn source_mass_kg(&self) -> f64 {
        self.source_mass_kg
    }
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
pub struct PlaybackState {
    /// Playback controls are intentionally small and copy-friendly so shell snapshots
    /// can transport sim-speed intent without runtime coupling.
    pub paused: bool,
    pub sim_seconds_per_real_second: f64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
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

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct TrailSample {
    pub epoch_seconds: f64,
    pub position_m: Vector3d,
}

/// Pinned identity of the initial-condition source. Edits and subsequent propagation
/// remain this runtime's model; this metadata never claims that edited states are JPL predictions.
#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
pub struct InitialConditionsProvenance {
    pub dataset_id: String,
    pub version: String,
    pub digest: String,
    pub epoch_jd_tdb: f64,
    pub frame: String,
    pub center: String,
    pub time_scale: String,
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
    /// Flight components keyed by BodyId; position/velocity remain in bodies.
    pub spacecraft: Vec<SpacecraftState>,
    pub active_checkpoint: Option<CheckpointDescriptor>,
    pub hardware_profile: HardwareProfile,
    pub invariants: PhysicsInvariants,
    pub observer: ObserverState,
    pub playback: PlaybackState,
    pub mounted_manifest: Option<MountedManifestState>,
    pub initial_conditions_provenance: Option<InitialConditionsProvenance>,
    pub trail_history_by_body: HashMap<BodyId, Vec<TrailSample>>,
    pub orbit_archives: Vec<OrbitArchive>,
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
    SpawnSpacecraft { body: BodyState, spacecraft: SpacecraftState },
    SetFlightControl { body_id: BodyId, control: FlightControl },
    StartGuidedBurn { body_id: BodyId, full_throttle_seconds: f64 },
    SetRendezvousGuidance { body_id: BodyId, mode: RendezvousGuidanceMode },
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
    /// Atomically edits physical properties and kinematics without replacing identity/history.
    SetBodyProperties {
        body_id: BodyId,
        mass_kg: f64,
        source_mass_kg: f64,
        radius_m: f64,
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
    UnknownScenario(ScenarioId),
    InvalidEpochDelta(f64),
    InvalidPlaybackRate(f64),
    InvalidBodyProperties,
    InvalidFlightState,
    BodyEditRequiresPause,
    AdvanceBusy,
    AdvanceStale,
    AdvanceWorkLimit,
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

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
struct BranchWorldState {
    epoch_seconds: f64,
    bodies: Vec<BodyState>,
    #[serde(default)]
    spacecraft: Vec<SpacecraftState>,
    observer: ObserverState,
    playback: PlaybackState,
    #[serde(skip)]
    invariants: PhysicsInvariants,
    local_data_state: LocalDataState,
    mounted_package_ids: BTreeSet<String>,
    #[serde(default)]
    initial_conditions_provenance: Option<InitialConditionsProvenance>,
    trail_history_by_body: HashMap<BodyId, Vec<TrailSample>>,
    #[serde(skip, default = "SolverExecutionReport::reference_scalar")]
    solver_execution: SolverExecutionReport,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
struct CheckpointRecord {
    descriptor: CheckpointDescriptor,
    state: BranchWorldState,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq)]
struct BranchState {
    descriptor: BranchDescriptor,
    world: BranchWorldState,
    command_log: Vec<CommandRecord>,
    checkpoints: Vec<CheckpointRecord>,
    last_checkpoint: Option<CheckpointDescriptor>,
}

const TRAIL_HISTORY_MAX_SAMPLES: usize = 96;
const RENDER_SCENE_ORBIT_BODY_LIMIT: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdvanceState {
    Running,
    Committed,
    Cancelled,
}

#[derive(Clone, Debug)]
pub struct AdvanceOperation {
    pub state: AdvanceState,
    pub generation: u64,
    pub intent: [u64; 2],
    pub base_ordinal: u64,
    pub requested_seconds: f64,
    pub recorded_at_unix_ms: i64,
    pub completed_steps: usize,
    pub total_steps: usize,
    plan: Option<FixedStepPlan>,
    bodies: Vec<MassiveBodyState>,
    spacecraft: Vec<solarlab_physics::spacecraft::SpacecraftBinding>,
}

#[derive(Clone, Copy, Debug)]
pub struct PlaybackTickResult {
    pub requested_seconds: f64,
    pub applied_seconds: f64,
    pub limited: bool,
    pub committed_epoch_seconds: f64,
    pub committed_revision: u64,
}

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
    lab_step_policy: lab_step::LabStepPolicy,
    hardware_profile: HardwareProfile,
    branches: HashMap<BranchId, BranchState>,
    active_branch_id: BranchId,
    next_command_ordinal: u64,
    next_checkpoint_ordinal: u64,
    next_branch_ordinal: u64,
    advance_generation: u64,
    advance_operation: Option<AdvanceOperation>,
    flight_prediction_cache: flight_prediction::FlightPredictionCache,
}


// Implementation modules share the private runtime state defined above. The
// externally visible WorldRuntime, WorldCommand, snapshot and FFI contracts are
// deliberately unchanged by this organisation-only refactor.
