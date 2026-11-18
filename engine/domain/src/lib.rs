pub mod assembly;
pub mod spacecraft;
pub use assembly::{
    ContactPart, ContactShape, DockingPortGeometry, SpacecraftAssembly, SpacecraftContactGeometry, SpacecraftVisualBinding,
};
pub mod docking;
pub mod science;
pub mod rendezvous;
pub use docking::{DockingAttachment, DockingGuidanceMode, DockingStatus, SpacecraftDockingState};
pub use rendezvous::{
    RendezvousGuidance, RendezvousGuidanceMode, RendezvousStatus, SpacecraftRendezvousState,
};
pub use spacecraft::{
    AttitudeMode, EngineControl, FlightControl, LocalReferenceFrame, Quaterniond,
    SpacecraftAppearance, SpacecraftBurnProgram, SpacecraftEngine, SpacecraftMissionState,
    SpacecraftState,
};

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
pub struct BodyId(pub String);

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
pub struct ScenarioId(pub String);

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
pub struct BranchId(pub String);

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq, Hash)]
pub struct CheckpointId(pub String);

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq)]
pub enum ObserverMode {
    Free,
    FollowSelected,
    FollowHost,
    SystemFrame,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq)]
pub enum TimelineSemantics {
    AbsoluteEpoch,
    BranchedSandbox,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum BodyClass {
    Star,
    Planet,
    DwarfPlanet,
    Moon,
    SmallBody,
    Tracer,
    Spacecraft,
    Custom,
    Comet,
}

/// Stable renderer-facing material families authored by the Rust world model.
///
/// These categories describe how a body should be presented; they never alter
/// mass, integration, collision, ephemeris, or any other physics input.
#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CelestialMaterialFamily {
    StellarPhotosphere,
    Terrestrial,
    Rocky,
    GasGiant,
    IceGiant,
    Icy,
    Lunar,
    Asteroid,
    CometNucleus,
    Spacecraft,
    Neutral,
}

/// Describes how authoritative a visual fact is without overstating accuracy.
#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
pub enum AppearanceProvenance {
    /// Curated for a named canonical body from published physical dimensions.
    CuratedPhysicalGuide,
    /// Curated to produce a recognizable orientation or visual effect.
    CuratedVisualGuide,
    /// Deterministic fallback inferred only from the dynamical body class.
    DerivedClassDefault,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct BodyOrientation {
    /// Unit north-pole direction in the renderer-neutral world frame.
    pub north_pole_ws: Vector3d,
    /// Reference meridian at the scene epoch. This is presentation metadata,
    /// not a rotational-dynamics state variable.
    pub reference_meridian_radians: f64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct RingSystemAppearance {
    pub inner_radius_m: f64,
    pub outer_radius_m: f64,
    pub plane_normal_ws: Vector3d,
    pub optical_depth: f64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct AtmosphereAppearance {
    pub outer_radius_m: f64,
    /// Normalized renderer input. This is intentionally not a pressure value.
    pub optical_density: f64,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct CometAppearanceInputs {
    pub nucleus_radius_m: f64,
    pub coma_radius_m: f64,
    pub dust_tail_length_m: f64,
    pub ion_tail_length_m: f64,
}

/// Renderer-neutral celestial appearance facts owned by Rust.
///
/// Dynamic directions such as a comet's anti-solar vector belong to the scene
/// projection, while this structure contains stable authored inputs.
#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, PartialEq)]
pub struct CelestialAppearanceFacts {
    pub material: CelestialMaterialFamily,
    pub provenance: AppearanceProvenance,
    pub orientation: BodyOrientation,
    pub ring_system: Option<RingSystemAppearance>,
    pub atmosphere: Option<AtmosphereAppearance>,
    pub comet: Option<CometAppearanceInputs>,
}

impl Default for CelestialAppearanceFacts {
    fn default() -> Self {
        Self {
            material: CelestialMaterialFamily::Neutral,
            provenance: AppearanceProvenance::DerivedClassDefault,
            orientation: BodyOrientation {
                north_pole_ws: Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
                reference_meridian_radians: 0.0,
            },
            ring_system: None,
            atmosphere: None,
            comet: None,
        }
    }
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ProvenanceRef {
    pub source: String,
    pub version: String,
    pub manifest_digest: Option<String>,
}

#[derive(serde::Serialize, serde::Deserialize, Clone, Copy, Debug, Default, PartialEq)]
pub struct Vector3d {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}
