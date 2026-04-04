use solarlab_domain::{BodyId, ObserverMode, Vector3d};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PrecisionBucket {
    Near,
    Medium,
    Far,
}

#[derive(Clone, Debug, PartialEq)]
pub struct BodyGlyph {
    pub body_id: BodyId,
    pub position_m: Vector3d,
    pub radius_m: f64,
    pub precision_bucket: PrecisionBucket,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TracerCloud {
    pub precision_bucket: PrecisionBucket,
    pub particle_count: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TrailStrip {
    pub body_id: BodyId,
    pub vertex_count: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ObserverRig {
    pub mode: ObserverMode,
    pub focus_body_id: Option<BodyId>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RenderScene {
    pub observer: ObserverRig,
    pub bodies: Vec<BodyGlyph>,
    pub tracer_clouds: Vec<TracerCloud>,
    pub trails: Vec<TrailStrip>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RenderSceneDelta {
    pub scene_revision: String,
    pub updated_body_count: u32,
    pub updated_tracer_count: u32,
}
