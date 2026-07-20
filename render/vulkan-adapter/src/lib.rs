#![allow(clippy::cast_possible_truncation)]

use solarlab_domain::{BodyId, ObserverMode, TimelineSemantics, Vector3d};
use solarlab_scene::{
    CameraPose, ColorRgba, LightSource, RenderDiagnostics, RenderScene, SceneBody, SceneDetailBand,
    SceneBodyKind, ScenePacketMetadata, SceneProvenanceRef, SceneTracer, SceneTrail,
    SceneTrailFamily,
};
use std::collections::HashSet;

const MEDIUM_HORIZON_TRAIL_SIMPLIFICATION_CAP: usize = 768;
const FAR_HORIZON_TRAIL_SIMPLIFICATION_CAP: usize = 384;
const HIGHLIGHTED_TRAIL_SIMPLIFICATION_FLOOR: usize = 192;
const DEFAULT_TRAIL_SIMPLIFICATION_FLOOR: usize = 96;

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

#[derive(Clone, Debug, PartialEq, Eq)]
struct ScenePacketCacheKey {
    scene_revision: String,
    origin_strategy: FrameOriginStrategy,
}

impl Default for FrameOriginStrategy {
    fn default() -> Self {
        Self::CameraTarget
    }
}

#[derive(Clone, Debug)]
pub struct VulkanSceneAdapter {
    pub origin_strategy: FrameOriginStrategy,
    cache_key: Option<ScenePacketCacheKey>,
    cached_packet: Option<VulkanScenePacket>,
}

impl Default for VulkanSceneAdapter {
    fn default() -> Self {
        Self {
            origin_strategy: FrameOriginStrategy::default(),
            cache_key: None,
            cached_packet: None,
        }
    }
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
    pub kind: SceneBodyKind,
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
    pub family: SceneTrailFamily,
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
    pub packet_metadata: ScenePacketMetadata,
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
    pub fn adapt(&mut self, scene: &RenderScene) -> VulkanScenePacket {
        let stable_revision = stable_scene_revision(&scene.scene_revision);
        let next_key = ScenePacketCacheKey {
            scene_revision: stable_revision.to_owned(),
            origin_strategy: self.origin_strategy,
        };

        if let Some(cached_key) = &self.cache_key {
            if *cached_key == next_key {
                if let Some(cached_packet) = self.cached_packet.as_mut() {
                    // Keep live diagnostics and revision in sync while reusing cached
                    // flattened topology when only volatile diagnostics fields changed.
                    cached_packet.scene_revision = scene.scene_revision.clone();
                    cached_packet.diagnostics = scene.diagnostics;
                    return cached_packet.clone();
                }
            }
        }

        let packet = self.adapt_uncached(scene);
        self.cache_key = Some(next_key);
        self.cached_packet = Some(packet.clone());
        packet
    }

    fn adapt_uncached(&self, scene: &RenderScene) -> VulkanScenePacket {
        let frame_origin_m = self.frame_origin_for(&scene.camera);
        let camera = adapt_camera(&scene.camera, frame_origin_m);
        let selected_source_body_ids = scene
            .bodies
            .iter()
            .filter(|body| body.selected)
            .map(|body| body.body_id.clone())
            .collect::<HashSet<_>>();

        let body_instances = scene
            .bodies
            .iter()
            .map(|body| adapt_body(body, frame_origin_m))
            .collect();

        let mut tracer_refs = scene.tracers.iter().collect::<Vec<_>>();
        tracer_refs.sort_by(|left, right| {
            tracer_detail_tier(left, &selected_source_body_ids)
                .cmp(&tracer_detail_tier(right, &selected_source_body_ids))
                .then_with(|| left.source_body_id.0.cmp(&right.source_body_id.0))
                .then_with(|| left.tracer_id.cmp(&right.tracer_id))
        });
        let tracer_instances = tracer_refs
            .into_iter()
            .map(|tracer| adapt_tracer(tracer, frame_origin_m))
            .collect();

        let mut trail_refs = scene.trails.iter().collect::<Vec<_>>();
        trail_refs.sort_by(|left, right| {
            trail_family_rank(left.family)
                .cmp(&trail_family_rank(right.family))
                .then_with(|| {
                    trail_detail_tier(left, &selected_source_body_ids)
                        .cmp(&trail_detail_tier(right, &selected_source_body_ids))
                })
                .then_with(|| left.source_body_id.0.cmp(&right.source_body_id.0))
                .then_with(|| left.trail_id.cmp(&right.trail_id))
        });
        let (trail_spans, trail_vertices) = adapt_trails(
            &trail_refs,
            frame_origin_m,
            scene.packet_metadata.trail_horizon_band,
            &selected_source_body_ids,
        );

        let directional_lights = scene.lights.iter().map(adapt_light).collect();

        VulkanScenePacket {
            scene_revision: scene.scene_revision.clone(),
            epoch_seconds: scene.epoch_seconds,
            observer_mode: scene.observer_mode.clone(),
            timeline_semantics: scene.timeline_semantics.clone(),
            provenance: scene.provenance.clone(),
            packet_metadata: scene.packet_metadata.clone(),
            diagnostics: scene.diagnostics,
            camera,
            body_instances,
            tracer_instances,
            trail_spans,
            trail_vertices,
            directional_lights,
        }
    }

    #[must_use]
    pub fn cached_scene_revision(&self) -> Option<&str> {
        self.cached_packet
            .as_ref()
            .map(|packet| packet.scene_revision.as_str())
    }

    fn frame_origin_for(&self, camera: &CameraPose) -> Vector3d {
        match self.origin_strategy {
            FrameOriginStrategy::CameraTarget => camera.target_m,
            FrameOriginStrategy::CameraPosition => camera.position_m,
        }
    }
}

fn stable_scene_revision(scene_revision: &str) -> &str {
    scene_revision.split("|diag:").next().unwrap_or_default()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum RenderDetailTier {
    Far,
    Medium,
    Near,
    Highlighted,
}

fn tracer_detail_tier(
    tracer: &SceneTracer,
    selected_source_body_ids: &HashSet<BodyId>,
) -> RenderDetailTier {
    if selected_source_body_ids.contains(&tracer.source_body_id) {
        return RenderDetailTier::Highlighted;
    }

    if tracer.size_px >= 4.0 {
        RenderDetailTier::Near
    } else if tracer.size_px >= 2.0 {
        RenderDetailTier::Medium
    } else {
        RenderDetailTier::Far
    }
}

fn trail_detail_tier(
    trail: &SceneTrail,
    selected_source_body_ids: &HashSet<BodyId>,
) -> RenderDetailTier {
    if trail.head_highlighted || selected_source_body_ids.contains(&trail.source_body_id) {
        return RenderDetailTier::Highlighted;
    }

    if trail.max_samples > 0 {
        let near_threshold = (trail.max_samples as usize * 3) / 4;
        let medium_threshold = trail.max_samples as usize / 2;
        if trail.samples_m.len() >= near_threshold {
            RenderDetailTier::Near
        } else if trail.samples_m.len() >= medium_threshold {
            RenderDetailTier::Medium
        } else {
            RenderDetailTier::Far
        }
    } else if trail.samples_m.len() >= 256 {
        RenderDetailTier::Near
    } else if trail.samples_m.len() >= 128 {
        RenderDetailTier::Medium
    } else {
        RenderDetailTier::Far
    }
}

fn trail_render_sample_budget(
    trail: &SceneTrail,
    horizon_band: SceneDetailBand,
    selected_source_body_ids: &HashSet<BodyId>,
) -> usize {
    let band_cap = match horizon_band {
        SceneDetailBand::Near => usize::MAX,
        SceneDetailBand::Medium => MEDIUM_HORIZON_TRAIL_SIMPLIFICATION_CAP,
        SceneDetailBand::Far => FAR_HORIZON_TRAIL_SIMPLIFICATION_CAP,
    };
    let floor =
        if trail.head_highlighted || selected_source_body_ids.contains(&trail.source_body_id) {
            HIGHLIGHTED_TRAIL_SIMPLIFICATION_FLOOR
        } else {
            DEFAULT_TRAIL_SIMPLIFICATION_FLOOR
        };
    let trail_capacity = (trail.max_samples as usize).max(trail.samples_m.len());
    trail_capacity.min(band_cap).max(floor)
}

fn simplify_trail_samples(samples: &[Vector3d], max_samples: usize) -> Vec<Vector3d> {
    if max_samples == 0 || samples.len() <= max_samples {
        return samples.to_vec();
    }
    if samples.len() <= 1 {
        return samples.to_vec();
    }

    let capped = max_samples.max(2);
    let mut simplified = Vec::with_capacity(capped);
    let last_sample_index = samples.len() - 1;
    let mut previous_index: Option<usize> = None;

    for slot in 0..capped {
        let slot_ratio = slot as f64 / (capped - 1) as f64;
        let index = (slot_ratio * last_sample_index as f64).round() as usize;
        if previous_index == Some(index) {
            continue;
        }
        simplified.push(samples[index]);
        previous_index = Some(index);
    }

    if simplified.last().copied() != Some(samples[last_sample_index]) {
        simplified.push(samples[last_sample_index]);
    }

    simplified
}

#[must_use]
pub fn adapt_render_scene(scene: &RenderScene) -> VulkanScenePacket {
    let mut adapter = VulkanSceneAdapter::default();
    adapter.adapt(scene)
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
        kind: body.kind,
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
    trails: &[&SceneTrail],
    frame_origin_m: Vector3d,
    horizon_band: SceneDetailBand,
    selected_source_body_ids: &HashSet<BodyId>,
) -> (Vec<VulkanTrailSpan>, Vec<VulkanTrailVertex>) {
    let mut spans = Vec::with_capacity(trails.len());
    let mut vertices = Vec::new();

    for (trail_index, trail) in trails.iter().enumerate() {
        let sample_budget =
            trail_render_sample_budget(trail, horizon_band, selected_source_body_ids);
        let simplified_samples = simplify_trail_samples(&trail.samples_m, sample_budget);
        let vertex_offset = vertices.len() as u32;
        for (sample_index, sample) in simplified_samples.iter().enumerate() {
            vertices.push(VulkanTrailVertex {
                trail_index: trail_index as u32,
                sample_index: sample_index as u32,
                position_from_origin_m: relative_vec(*sample, frame_origin_m),
            });
        }

        spans.push(VulkanTrailSpan {
            trail_id: trail.trail_id.clone(),
            source_body_id: trail.source_body_id.clone(),
            family: trail.family,
            vertex_offset,
            vertex_count: (vertices.len() as u32) - vertex_offset,
            color: pack_color(trail.color),
            max_samples: sample_budget as u32,
            head_highlighted: trail.head_highlighted,
        });
    }

    (spans, vertices)
}

fn trail_family_rank(family: SceneTrailFamily) -> u8 {
    match family {
        SceneTrailFamily::HistoricalOrbit => 0,
        SceneTrailFamily::Trajectory => 1,
        SceneTrailFamily::Prediction => 2,
    }
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
        SceneBodyKind, SceneDetailBand, SceneItemFamily, ScenePacketMetadata, SceneProvenanceRef,
        SceneTracer, SceneTrail, SceneTrailFamily,
    };

    use super::{
        adapt_render_scene, FrameOriginStrategy, PackedVec3, VulkanSceneAdapter,
        HIGHLIGHTED_TRAIL_SIMPLIFICATION_FLOOR,
    };

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
        assert_eq!(packet.body_instances[0].kind, SceneBodyKind::Planet);
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
            packet.packet_metadata.tracer_family,
            SceneItemFamily::Tracer
        );
        assert_eq!(
            packet.packet_metadata.tracer_resolution_band,
            SceneDetailBand::Medium
        );
        assert_eq!(packet.packet_metadata.trail_family, SceneItemFamily::Trail);
        assert_eq!(
            packet.packet_metadata.trail_horizon_band,
            SceneDetailBand::Far
        );
        assert_eq!(
            packet.packet_metadata.trail_simplification_budget_samples,
            64
        );
        assert_eq!(
            packet.trail_spans[0].max_samples,
            HIGHLIGHTED_TRAIL_SIMPLIFICATION_FLOOR as u32
        );
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
        let mut adapter = VulkanSceneAdapter::default();
        adapter.origin_strategy = FrameOriginStrategy::CameraPosition;
        let packet = adapter.adapt(&sample_scene());

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

    #[test]
    fn adapter_reuses_cached_packet_when_scene_revision_and_strategy_are_unchanged() {
        let mut adapter = VulkanSceneAdapter::default();
        let mut scene = sample_scene();

        let first_packet = adapter.adapt(&scene);
        scene.camera.position_m.x += 10.0;
        scene.tracers[0].position_m.z = 99.0;
        let second_packet = adapter.adapt(&scene);

        assert_eq!(first_packet, second_packet);
    }

    #[test]
    fn adapter_invalidates_cache_when_scene_revision_changes() {
        let mut adapter = VulkanSceneAdapter::default();
        let mut scene = sample_scene();

        let first_packet = adapter.adapt(&scene);
        scene.scene_revision = "scene-rev-43".to_owned();
        let second_packet = adapter.adapt(&scene);

        assert_ne!(first_packet.scene_revision, second_packet.scene_revision);
        assert_eq!(second_packet.scene_revision, "scene-rev-43");
    }

    #[test]
    fn adapter_invalidates_cache_when_origin_strategy_changes() {
        let mut adapter = VulkanSceneAdapter::default();
        let scene = sample_scene();

        let first_packet = adapter.adapt(&scene);
        adapter.origin_strategy = FrameOriginStrategy::CameraPosition;
        let second_packet = adapter.adapt(&scene);

        assert_ne!(
            first_packet.camera.position_from_origin_m,
            second_packet.camera.position_from_origin_m
        );
        assert_ne!(
            first_packet.camera.frame_origin_m,
            second_packet.camera.frame_origin_m
        );
    }

    #[test]
    fn adapter_orders_tracers_and_trails_deterministically_by_tier_and_identity() {
        let mut scene = sample_scene();
        scene.bodies[0].selected = true;
        scene.bodies.push(SceneBody {
            body_id: BodyId("mars".to_owned()),
            display_name: "Mars".to_owned(),
            kind: SceneBodyKind::Planet,
            position_m: Vector3d {
                x: 220.0,
                y: 4.0,
                z: -12.0,
            },
            radius_m: 3_389_500.0,
            albedo: ColorRgba {
                r: 0.7,
                g: 0.3,
                b: 0.2,
                a: 1.0,
            },
            emissive_luminance: 0.0,
            selected: false,
        });
        scene.tracers = vec![
            SceneTracer {
                tracer_id: "selected-4".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                position_m: Vector3d {
                    x: 112.0,
                    y: 0.0,
                    z: 0.0,
                },
                color: ColorRgba {
                    r: 1.0,
                    g: 0.9,
                    b: 0.2,
                    a: 0.8,
                },
                size_px: 1.0,
            },
            SceneTracer {
                tracer_id: "near-3".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                position_m: Vector3d {
                    x: 111.0,
                    y: 0.0,
                    z: 0.0,
                },
                color: ColorRgba {
                    r: 0.5,
                    g: 0.7,
                    b: 1.0,
                    a: 0.7,
                },
                size_px: 4.5,
            },
            SceneTracer {
                tracer_id: "far-1".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                position_m: Vector3d {
                    x: 109.0,
                    y: 0.0,
                    z: 0.0,
                },
                color: ColorRgba {
                    r: 0.4,
                    g: 0.6,
                    b: 0.9,
                    a: 0.5,
                },
                size_px: 1.0,
            },
            SceneTracer {
                tracer_id: "medium-2".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                position_m: Vector3d {
                    x: 110.0,
                    y: 0.0,
                    z: 0.0,
                },
                color: ColorRgba {
                    r: 0.45,
                    g: 0.65,
                    b: 0.95,
                    a: 0.6,
                },
                size_px: 2.5,
            },
        ];
        scene.trails = vec![
            SceneTrail {
                trail_id: "selected-trail-4".to_owned(),
                source_body_id: BodyId("earth".to_owned()),
                family: SceneTrailFamily::Trajectory,
                samples_m: trail_samples(8, 100.0),
                color: ColorRgba {
                    r: 1.0,
                    g: 1.0,
                    b: 0.2,
                    a: 0.9,
                },
                max_samples: 128,
                head_highlighted: false,
            },
            SceneTrail {
                trail_id: "near-trail-3".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                family: SceneTrailFamily::Trajectory,
                samples_m: trail_samples(90, 100.0),
                color: ColorRgba {
                    r: 0.6,
                    g: 0.8,
                    b: 1.0,
                    a: 0.8,
                },
                max_samples: 120,
                head_highlighted: false,
            },
            SceneTrail {
                trail_id: "far-trail-1".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                family: SceneTrailFamily::Trajectory,
                samples_m: trail_samples(12, 100.0),
                color: ColorRgba {
                    r: 0.3,
                    g: 0.5,
                    b: 0.8,
                    a: 0.7,
                },
                max_samples: 120,
                head_highlighted: false,
            },
            SceneTrail {
                trail_id: "medium-trail-2".to_owned(),
                source_body_id: BodyId("mars".to_owned()),
                family: SceneTrailFamily::Trajectory,
                samples_m: trail_samples(65, 100.0),
                color: ColorRgba {
                    r: 0.5,
                    g: 0.7,
                    b: 0.95,
                    a: 0.75,
                },
                max_samples: 120,
                head_highlighted: false,
            },
        ];
        scene.packet_metadata.trail_horizon_band = SceneDetailBand::Near;

        let packet = adapt_render_scene(&scene);
        let tracer_ids = packet
            .tracer_instances
            .iter()
            .map(|tracer| tracer.tracer_id.as_str())
            .collect::<Vec<_>>();
        let trail_ids = packet
            .trail_spans
            .iter()
            .map(|trail| trail.trail_id.as_str())
            .collect::<Vec<_>>();

        assert_eq!(
            tracer_ids,
            vec!["far-1", "medium-2", "near-3", "selected-4"]
        );
        assert_eq!(
            trail_ids,
            vec![
                "far-trail-1",
                "medium-trail-2",
                "near-trail-3",
                "selected-trail-4"
            ]
        );
    }

    #[test]
    fn adapter_applies_far_horizon_trail_budget_and_preserves_endpoints() {
        let mut scene = sample_scene();
        scene.bodies[0].selected = false;
        scene.packet_metadata.trail_horizon_band = SceneDetailBand::Far;
        scene.trails = vec![SceneTrail {
            trail_id: "long-horizon-trail".to_owned(),
            source_body_id: BodyId("earth".to_owned()),
            family: SceneTrailFamily::Trajectory,
            samples_m: trail_samples(1_200, 100.0),
            color: ColorRgba {
                r: 0.8,
                g: 0.9,
                b: 1.0,
                a: 0.9,
            },
            max_samples: 2_000,
            head_highlighted: false,
        }];

        let packet = adapt_render_scene(&scene);
        let span = &packet.trail_spans[0];
        let first_vertex = &packet.trail_vertices[span.vertex_offset as usize];
        let last_vertex = &packet.trail_vertices
            [(span.vertex_offset + span.vertex_count.saturating_sub(1)) as usize];

        assert_eq!(span.max_samples, 384);
        assert!(span.vertex_count <= 384);
        assert!(span.vertex_count >= 2);
        assert_eq!(first_vertex.position_from_origin_m.x, 0.0);
        assert_eq!(last_vertex.position_from_origin_m.x, 1199.0);
    }

    fn trail_samples(sample_count: usize, start_x: f64) -> Vec<Vector3d> {
        (0..sample_count)
            .map(|sample_index| Vector3d {
                x: start_x + sample_index as f64,
                y: sample_index as f64 * 0.01,
                z: 0.0,
            })
            .collect()
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
                kind: SceneBodyKind::Planet,
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
                family: SceneTrailFamily::Trajectory,
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
            packet_metadata: ScenePacketMetadata::default(),
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
        .with_derived_counts()
    }
}
