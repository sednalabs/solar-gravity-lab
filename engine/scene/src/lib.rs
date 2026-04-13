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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SceneItemFamily {
    Tracer,
    Trail,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum SceneDetailBand {
    Near,
    Medium,
    Far,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SceneTrailFamily {
    Trajectory,
    HistoricalOrbit,
    Prediction,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SceneTrail {
    pub trail_id: String,
    pub source_body_id: BodyId,
    pub family: SceneTrailFamily,
    pub samples_m: Vec<Vector3d>,
    pub color: ColorRgba,
    pub max_samples: u32,
    pub head_highlighted: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScenePacketMetadata {
    pub tracer_family: SceneItemFamily,
    pub tracer_resolution_band: SceneDetailBand,
    pub trail_family: SceneItemFamily,
    pub trail_horizon_band: SceneDetailBand,
    pub trail_simplification_budget_samples: u32,
}

impl Default for ScenePacketMetadata {
    fn default() -> Self {
        Self {
            tracer_family: SceneItemFamily::Tracer,
            tracer_resolution_band: SceneDetailBand::Far,
            trail_family: SceneItemFamily::Trail,
            trail_horizon_band: SceneDetailBand::Far,
            trail_simplification_budget_samples: 0,
        }
    }
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
    pub packet_metadata: ScenePacketMetadata,
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
        self.packet_metadata = ScenePacketMetadata::from_scene(&self);
        self
    }
}

impl ScenePacketMetadata {
    #[must_use]
    pub fn from_scene(scene: &RenderScene) -> Self {
        Self {
            tracer_family: SceneItemFamily::Tracer,
            tracer_resolution_band: tracer_resolution_band(&scene.tracers),
            trail_family: SceneItemFamily::Trail,
            trail_horizon_band: trail_horizon_band(&scene.trails),
            trail_simplification_budget_samples: scene
                .trails
                .iter()
                .map(|trail| trail.max_samples)
                .max()
                .unwrap_or_default(),
        }
    }
}

fn tracer_resolution_band(tracers: &[SceneTracer]) -> SceneDetailBand {
    let max_size_px = tracers
        .iter()
        .map(|tracer| tracer.size_px)
        .fold(0.0_f32, f32::max);

    if max_size_px >= 4.0 {
        SceneDetailBand::Near
    } else if max_size_px >= 2.0 {
        SceneDetailBand::Medium
    } else {
        SceneDetailBand::Far
    }
}

fn trail_horizon_band(trails: &[SceneTrail]) -> SceneDetailBand {
    let mut band = SceneDetailBand::Far;

    for trail in trails {
        if trail.max_samples == 0 {
            continue;
        }

        let coverage = trail.samples_m.len() as f32 / trail.max_samples as f32;
        let trail_band = if coverage >= 0.75 {
            SceneDetailBand::Near
        } else if coverage >= 0.5 {
            SceneDetailBand::Medium
        } else {
            SceneDetailBand::Far
        };

        band = band.max(trail_band);
    }

    band
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
        CameraPose, ColorRgba, RenderDiagnostics, RenderScene, SceneBody, SceneDetailBand,
        SceneItemFamily, ScenePacketMetadata, SceneTracer, SceneTrail, SceneTrailFamily,
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
                family: SceneTrailFamily::Trajectory,
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
            packet_metadata: ScenePacketMetadata::default(),
            lights: Vec::new(),
            provenance: None,
            diagnostics: RenderDiagnostics::default(),
        }
        .with_derived_counts();

        assert_eq!(scene.body_count, 1);
        assert_eq!(scene.tracer_count, 1);
        assert_eq!(scene.trail_count, 1);
        assert_eq!(scene.packet_metadata.tracer_family, SceneItemFamily::Tracer);
        assert_eq!(
            scene.packet_metadata.tracer_resolution_band,
            SceneDetailBand::Medium
        );
        assert_eq!(scene.packet_metadata.trail_family, SceneItemFamily::Trail);
        assert_eq!(
            scene.packet_metadata.trail_horizon_band,
            SceneDetailBand::Far
        );
        assert_eq!(
            scene.packet_metadata.trail_simplification_budget_samples,
            128
        );
        assert_eq!(scene.trails[0].family, SceneTrailFamily::Trajectory);
    }
}
