#![allow(clippy::cast_possible_truncation)]

use solarlab_domain::{BodyId, ObserverMode, TimelineSemantics, Vector3d};
use solarlab_scene::{
    CameraPose, ColorRgba, LightSource, RenderDiagnostics, RenderScene, SceneBody,
    SceneProvenanceRef, SceneTracer, SceneTrail,
};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PackedVec3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PackedColor {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrameOriginStrategy {
    CameraTarget,
    CameraPosition,
}

impl Default for FrameOriginStrategy {
    fn default() -> Self {
        Self::CameraTarget
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct VulkanSceneAdapter {
    pub origin_strategy: FrameOriginStrategy,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanCameraPacket {
    pub frame_origin_m: Vector3d,
    pub position_from_origin_m: PackedVec3,
    pub target_from_origin_m: PackedVec3,
    pub up: PackedVec3,
    pub vertical_fov_degrees: f32,
    pub exposure: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanBodyInstance {
    pub body_id: BodyId,
    pub display_name: String,
    pub position_from_origin_m: PackedVec3,
    pub radius_m: f32,
    pub albedo: PackedColor,
    pub emissive_luminance: f32,
    pub selected: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanTracerInstance {
    pub tracer_id: String,
    pub source_body_id: BodyId,
    pub position_from_origin_m: PackedVec3,
    pub color: PackedColor,
    pub size_px: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanTrailVertex {
    pub trail_index: u32,
    pub sample_index: u32,
    pub position_from_origin_m: PackedVec3,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanTrailSpan {
    pub trail_id: String,
    pub source_body_id: BodyId,
    pub vertex_offset: u32,
    pub vertex_count: u32,
    pub color: PackedColor,
    pub max_samples: u32,
    pub head_highlighted: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanDirectionalLight {
    pub light_id: String,
    pub direction_ws: PackedVec3,
    pub illuminance_lux: f32,
    pub color: PackedColor,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VulkanScenePacket {
    pub scene_revision: String,
    pub epoch_seconds: f64,
    pub observer_mode: ObserverMode,
    pub timeline_semantics: TimelineSemantics,
    pub provenance: Option<SceneProvenanceRef>,
    pub diagnostics: RenderDiagnostics,
    pub camera: VulkanCameraPacket,
    pub body_instances: Vec<VulkanBodyInstance>,
    pub tracer_instances: Vec<VulkanTracerInstance>,
    pub trail_spans: Vec<VulkanTrailSpan>,
    pub trail_vertices: Vec<VulkanTrailVertex>,
    pub directional_lights: Vec<VulkanDirectionalLight>,
}

impl VulkanSceneAdapter {
    #[must_use]
    pub fn adapt(&self, scene: &RenderScene) -> VulkanScenePacket {
        let frame_origin_m = self.frame_origin_for(&scene.camera);
        let camera = adapt_camera(&scene.camera, frame_origin_m);

        let body_instances = scene
            .bodies
            .iter()
            .map(|body| adapt_body(body, frame_origin_m))
            .collect();

        let tracer_instances = scene
            .tracers
            .iter()
            .map(|tracer| adapt_tracer(tracer, frame_origin_m))
            .collect();

        let (trail_spans, trail_vertices) = adapt_trails(&scene.trails, frame_origin_m);

        let directional_lights = scene.lights.iter().map(adapt_light).collect();

        VulkanScenePacket {
            scene_revision: scene.scene_revision.clone(),
            epoch_seconds: scene.epoch_seconds,
            observer_mode: scene.observer_mode.clone(),
            timeline_semantics: scene.timeline_semantics.clone(),
            provenance: scene.provenance.clone(),
            diagnostics: scene.diagnostics,
            camera,
            body_instances,
            tracer_instances,
            trail_spans,
            trail_vertices,
            directional_lights,
        }
    }

    fn frame_origin_for(&self, camera: &CameraPose) -> Vector3d {
        match self.origin_strategy {
            FrameOriginStrategy::CameraTarget => camera.target_m,
            FrameOriginStrategy::CameraPosition => camera.position_m,
        }
    }
}

#[must_use]
pub fn adapt_render_scene(scene: &RenderScene) -> VulkanScenePacket {
    VulkanSceneAdapter::default().adapt(scene)
}

fn adapt_camera(camera: &CameraPose, frame_origin_m: Vector3d) -> VulkanCameraPacket {
    VulkanCameraPacket {
        frame_origin_m,
        position_from_origin_m: relative_vec(camera.position_m, frame_origin_m),
        target_from_origin_m: relative_vec(camera.target_m, frame_origin_m),
        up: pack_vec3(camera.up),
        vertical_fov_degrees: camera.vertical_fov_degrees as f32,
        exposure: camera.exposure as f32,
    }
}

fn adapt_body(body: &SceneBody, frame_origin_m: Vector3d) -> VulkanBodyInstance {
    VulkanBodyInstance {
        body_id: body.body_id.clone(),
        display_name: body.display_name.clone(),
        position_from_origin_m: relative_vec(body.position_m, frame_origin_m),
        radius_m: body.radius_m as f32,
        albedo: pack_color(body.albedo),
        emissive_luminance: body.emissive_luminance as f32,
        selected: body.selected,
    }
}

fn adapt_tracer(tracer: &SceneTracer, frame_origin_m: Vector3d) -> VulkanTracerInstance {
    VulkanTracerInstance {
        tracer_id: tracer.tracer_id.clone(),
        source_body_id: tracer.source_body_id.clone(),
        position_from_origin_m: relative_vec(tracer.position_m, frame_origin_m),
        color: pack_color(tracer.color),
        size_px: tracer.size_px,
    }
}

fn adapt_trails(
    trails: &[SceneTrail],
    frame_origin_m: Vector3d,
) -> (Vec<VulkanTrailSpan>, Vec<VulkanTrailVertex>) {
    let mut spans = Vec::with_capacity(trails.len());
    let mut vertices = Vec::new();

    for (trail_index, trail) in trails.iter().enumerate() {
        let vertex_offset = vertices.len() as u32;
        for (sample_index, sample) in trail.samples_m.iter().enumerate() {
            vertices.push(VulkanTrailVertex {
                trail_index: trail_index as u32,
                sample_index: sample_index as u32,
                position_from_origin_m: relative_vec(*sample, frame_origin_m),
            });
        }

        spans.push(VulkanTrailSpan {
            trail_id: trail.trail_id.clone(),
            source_body_id: trail.source_body_id.clone(),
            vertex_offset,
            vertex_count: (vertices.len() as u32) - vertex_offset,
            color: pack_color(trail.color),
            max_samples: trail.max_samples,
            head_highlighted: trail.head_highlighted,
        });
    }

    (spans, vertices)
}

fn adapt_light(light: &LightSource) -> VulkanDirectionalLight {
    VulkanDirectionalLight {
        light_id: light.light_id.clone(),
        direction_ws: pack_vec3(light.direction_ws),
        illuminance_lux: light.illuminance_lux as f32,
        color: pack_color(light.color),
    }
}

fn relative_vec(value_m: Vector3d, origin_m: Vector3d) -> PackedVec3 {
    pack_vec3(Vector3d {
        x: value_m.x - origin_m.x,
        y: value_m.y - origin_m.y,
        z: value_m.z - origin_m.z,
    })
}

fn pack_vec3(value: Vector3d) -> PackedVec3 {
    PackedVec3 {
        x: value.x as f32,
        y: value.y as f32,
        z: value.z as f32,
    }
}

fn pack_color(color: ColorRgba) -> PackedColor {
    PackedColor {
        r: color.r,
        g: color.g,
        b: color.b,
        a: color.a,
    }
}

#[cfg(test)]
mod tests {
    use solarlab_domain::{BodyId, ObserverMode, TimelineSemantics, Vector3d};
    use solarlab_scene::{
        CameraPose, ColorRgba, LightSource, RenderDiagnostics, RenderScene, SceneBody,
        SceneProvenanceRef, SceneTracer, SceneTrail,
    };

    use super::{adapt_render_scene, FrameOriginStrategy, PackedVec3, VulkanSceneAdapter};

    #[test]
    fn default_adapter_uses_camera_target_as_frame_origin() {
        let packet = adapt_render_scene(&sample_scene());

        assert_eq!(packet.camera.frame_origin_m.x, 100.0);
        assert_eq!(
            packet.camera.position_from_origin_m,
            PackedVec3 {
                x: 5.0,
                y: 0.0,
                z: 20.0,
            }
        );
        assert_eq!(
            packet.body_instances[0].position_from_origin_m,
            PackedVec3 {
                x: 0.0,
                y: 10.0,
                z: 0.0,
            }
        );
        assert_eq!(
            packet.tracer_instances[0].position_from_origin_m,
            PackedVec3 {
                x: 6.0,
                y: 0.0,
                z: 4.0,
            }
        );
    }

    #[test]
    fn adapter_flattens_trails_and_preserves_provenance() {
        let packet = adapt_render_scene(&sample_scene());

        assert_eq!(packet.trail_spans.len(), 1);
        assert_eq!(packet.trail_vertices.len(), 2);
        assert_eq!(packet.trail_spans[0].vertex_offset, 0);
        assert_eq!(packet.trail_spans[0].vertex_count, 2);
        assert!(packet.trail_spans[0].head_highlighted);
        assert_eq!(
            packet
                .provenance
                .as_ref()
                .map(|value| value.manifest_id.as_str()),
            Some("alpha-manifest")
        );
        assert_eq!(packet.directional_lights.len(), 1);
    }

    #[test]
    fn adapter_can_switch_to_camera_position_origin() {
        let packet = VulkanSceneAdapter {
            origin_strategy: FrameOriginStrategy::CameraPosition,
        }
        .adapt(&sample_scene());

        assert_eq!(packet.camera.frame_origin_m.z, 20.0);
        assert_eq!(
            packet.camera.target_from_origin_m,
            PackedVec3 {
                x: -5.0,
                y: 0.0,
                z: -20.0,
            }
        );
    }

    fn sample_scene() -> RenderScene {
        RenderScene {
            observer_mode: ObserverMode::FollowSelected,
            body_count: 1,
            tracer_count: 1,
            trail_count: 1,
            scene_revision: "scene-rev-42".to_owned(),
            epoch_seconds: 123.0,
            timeline_semantics: TimelineSemantics::BranchedSandbox,
            camera: CameraPose {
                position_m: Vector3d {
                    x: 105.0,
                    y: 0.0,
                    z: 20.0,
                },
                target_m: Vector3d {
                    x: 100.0,
                    y: 0.0,
                    z: 0.0,
                },
                up: Vector3d {
                    x: 0.0,
                    y: 1.0,
                    z: 0.0,
                },
                vertical_fov_degrees: 60.0,
                exposure: 1.5,
            },
            bodies: vec![SceneBody {
                body_id: BodyId("earth".to_owned()),
                display_name: "Earth".to_owned(),
                position_m: Vector3d {
                    x: 100.0,
                    y: 10.0,
                    z: 0.0,
                },
                radius_m: 6_371_000.0,
                albedo: ColorRgba {
                    r: 0.2,
                    g: 0.4,
                    b: 1.0,
                    a: 1.0,
                },
                emissive_luminance: 0.0,
                selected: true,
            }],
            tracers: vec![SceneTracer {
                tracer_id: "trace-1".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                position_m: Vector3d {
                    x: 106.0,
                    y: 0.0,
                    z: 4.0,
                },
                color: ColorRgba {
                    r: 1.0,
                    g: 1.0,
                    b: 1.0,
                    a: 0.6,
                },
                size_px: 2.5,
            }],
            trails: vec![SceneTrail {
                trail_id: "trail-1".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                samples_m: vec![
                    Vector3d {
                        x: 100.0,
                        y: 10.0,
                        z: 0.0,
                    },
                    Vector3d {
                        x: 101.0,
                        y: 10.5,
                        z: 0.0,
                    },
                ],
                color: ColorRgba {
                    r: 0.8,
                    g: 0.9,
                    b: 1.0,
                    a: 0.9,
                },
                max_samples: 64,
                head_highlighted: true,
            }],
            lights: vec![LightSource {
                light_id: "sun".to_owned(),
                direction_ws: Vector3d {
                    x: -1.0,
                    y: 0.0,
                    z: 0.0,
                },
                illuminance_lux: 120_000.0,
                color: ColorRgba {
                    r: 1.0,
                    g: 0.98,
                    b: 0.9,
                    a: 1.0,
                },
            }],
            provenance: Some(SceneProvenanceRef {
                source: "stable".to_owned(),
                version: "1.2.3".to_owned(),
                manifest_id: "alpha-manifest".to_owned(),
                manifest_digest: Some("sha256:abc123".to_owned()),
                package_digest: None,
            }),
            diagnostics: RenderDiagnostics {
                frame_number: 42,
                cpu_extract_ms: 0.5,
                gpu_upload_ms: 0.25,
                dropped_frames: 0,
            },
        }
    }
}
