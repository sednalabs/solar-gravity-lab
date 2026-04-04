#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct BodyId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ScenarioId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct BranchId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct CheckpointId(pub String);

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ObserverMode {
    Free,
    FollowSelected,
    FollowHost,
    SystemFrame,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TimelineSemantics {
    AbsoluteEpoch,
    BranchedSandbox,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BodyClass {
    Star,
    Planet,
    DwarfPlanet,
    Moon,
    SmallBody,
    Tracer,
    Spacecraft,
    Custom,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProvenanceRef {
    pub source: String,
    pub version: String,
    pub manifest_digest: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vector3d {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}
