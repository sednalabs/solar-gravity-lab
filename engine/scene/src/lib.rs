use solarlab_data::Digest;
use solarlab_domain::{BodyId, ObserverMode, TimelineSemantics, Vector3d};

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct ColorRgba {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CameraPose {
    pub position_m: Vector3d,
    pub target_m: Vector3d,
    pub up: Vector3d,
    pub vertical_fov_degrees: f64,
    pub exposure: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SceneBody {
    pub body_id: BodyId,
    pub display_name: String,
    pub position_m: Vector3d,
    pub radius_m: f64,
    pub albedo: ColorRgba,
    pub emissive_luminance: f64,
    pub selected: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SceneTracer {
    pub tracer_id: String,
    pub source_body_id: BodyId,
    pub position_m: Vector3d,
    pub color: ColorRgba,
    pub size_px: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SceneTrail {
    pub trail_id: String,
    pub source_body_id: BodyId,
    pub samples_m: Vec<Vector3d>,
    pub color: ColorRgba,
    pub max_samples: u32,
    pub head_highlighted: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct LightSource {
    pub light_id: String,
    pub direction_ws: Vector3d,
    pub illuminance_lux: f64,
    pub color: ColorRgba,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct RenderDiagnostics {
    pub frame_number: u64,
    pub cpu_extract_ms: f32,
    pub gpu_upload_ms: f32,
    pub dropped_frames: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SceneProvenanceRef {
    pub source: String,
    pub version: String,
    pub manifest_id: String,
    pub manifest_digest: Option<String>,
    pub package_digest: Option<Digest>,
}

/// Authoritative scene state published by the runtime for rendering.
/// 
/// This structure is backend-neutral and contains all the data required to 
/// reconstruct a physically grounded frame, including camera, bodies, tracers, 
/// and lights.
#[derive(Clone, Debug, PartialEq)]
pub struct RenderScene {
    pub observer_mode: ObserverMode,
    pub body_count: u32,
    pub tracer_count: u32,
    pub trail_count: u32,
    pub scene_revision: String,
    pub epoch_seconds: f64,
    pub timeline_semantics: TimelineSemantics,
    pub camera: CameraPose,
    pub bodies: Vec<SceneBody>,
    pub tracers: Vec<SceneTracer>,
    pub trails: Vec<SceneTrail>,
    pub lights: Vec<LightSource>,
    pub provenance: Option<SceneProvenanceRef>,
    pub diagnostics: RenderDiagnostics,
}

impl RenderScene {
    #[must_use]
    pub fn with_derived_counts(mut self) -> Self {
        self.body_count = self.bodies.len() as u32;
        self.tracer_count = self.tracers.len() as u32;
        self.trail_count = self.trails.len() as u32;
        self
    }
}

/// Represents an incremental update to a [RenderScene].
/// 
/// Used to avoid full scene transfers between the runtime core and 
/// render adapters. The delta is anchored to a `base_revision`.
#[derive(Clone, Debug, PartialEq)]
pub struct RenderSceneDelta {
    pub scene_revision: String,
    pub updated_body_count: u32,
    pub updated_tracer_count: u32,
    pub base_revision: u64,
    pub target_revision: u64,
    pub body_upserts: Vec<SceneBody>,
    pub removed_body_ids: Vec<BodyId>,
    pub tracer_upserts: Vec<SceneTracer>,
    pub removed_tracer_ids: Vec<String>,
    pub trail_upserts: Vec<SceneTrail>,
    pub removed_trail_ids: Vec<String>,
    pub camera_override: Option<CameraPose>,
    pub diagnostics_override: Option<RenderDiagnostics>,
    pub updated_light_count: u32,
    pub light_upserts: Vec<LightSource>,
    pub removed_light_ids: Vec<String>,
}

#[cfg(test)]
mod tests {
    use solarlab_domain::{BodyId, ObserverMode, TimelineSemantics, Vector3d};

    use super::{
        CameraPose, ColorRgba, RenderDiagnostics, RenderScene, SceneBody, SceneTracer, SceneTrail,
    };

    #[test]
    fn with_derived_counts_matches_vector_lengths() {
        let scene = RenderScene {
            observer_mode: ObserverMode::Free,
            body_count: 99,
            tracer_count: 99,
            trail_count: 99,
            scene_revision: "rev-1".to_owned(),
            epoch_seconds: 42.0,
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            camera: CameraPose {
                position_m: Vector3d::default(),
                target_m: Vector3d::default(),
                up: Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
                vertical_fov_degrees: 60.0,
                exposure: 1.0,
            },
            bodies: vec![SceneBody {
                body_id: BodyId("earth".to_owned()),
                display_name: "earth".to_owned(),
                position_m: Vector3d::default(),
                radius_m: 6_371_000.0,
                albedo: ColorRgba {
                    r: 0.7,
                    g: 0.8,
                    b: 1.0,
                    a: 1.0,
                },
                emissive_luminance: 0.0,
                selected: false,
            }],
            tracers: vec![SceneTracer {
                tracer_id: "trace-1".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                position_m: Vector3d::default(),
                color: ColorRgba {
                    r: 1.0,
                    g: 1.0,
                    b: 1.0,
                    a: 1.0,
                },
                size_px: 2.0,
            }],
            trails: vec![SceneTrail {
                trail_id: "trail-1".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                samples_m: vec![Vector3d::default()],
                color: ColorRgba {
                    r: 1.0,
                    g: 1.0,
                    b: 1.0,
                    a: 1.0,
                },
                max_samples: 128,
                head_highlighted: false,
            }],
            lights: Vec::new(),
            provenance: None,
            diagnostics: RenderDiagnostics::default(),
        }
        .with_derived_counts();

        assert_eq!(scene.body_count, 1);
        assert_eq!(scene.tracer_count, 1);
        assert_eq!(scene.trail_count, 1);
    }
}
