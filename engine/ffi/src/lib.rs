#![allow(unsafe_code)]

//! C ABI bridge for the V2 runtime seam.
//!
//! Ownership model:
//! - `solarlab_runtime` owns simulation truth and command semantics.
//! - This crate owns opaque, process-local handle registries and marshalled POD
//!   views passed across language boundaries.
//! - JNI consumers must treat `long` handles as opaque capability tokens and
//!   must call destroy/release APIs to free process-side state.
//! - This seam documents the canonical Rust runtime boundary; legacy Kotlin lines
//!   should consume this ABI contract rather than owning any simulation
//!   transition logic.

use std::collections::HashMap;
use std::ffi::c_void;
use std::mem::size_of;
use std::str;
use std::sync::{Mutex, OnceLock};

use solarlab_domain::{
    BodyClass, BodyId, BranchId, CheckpointId, ObserverMode, ScenarioId, TimelineSemantics,
    Vector3d,
};
use solarlab_hardware::{
    BackendFamilyAssignment, CpuBackend, GpuBackend, GpuBackendReport, GpuBackendStateFamily,
    HardwareProfile,
};
use solarlab_physics::{
    cpu_feature_flags, detect_cpu_features, solver_execution_report_for_backend, CollisionModel,
    IntegratorKind, PhysicsPolicy, SolverBackend, SolverExecutionReport, SolverFallbackCode,
};
use solarlab_runtime::{BodyState, RuntimeConfig, RuntimeError, WorldCommand, WorldRuntime};
use solarlab_scene::SceneTrailFamily;
use solarlab_vulkan_adapter::{
    PackedColor, PackedVec3, VulkanBodyInstance, VulkanDirectionalLight, VulkanSceneAdapter,
    VulkanScenePacket, VulkanTracerInstance, VulkanTrailSpan, VulkanTrailVertex,
};

pub const SOLARLAB_V2_ABI_VERSION: u32 = 4;
/// Byte capacity for inline UTF-8 IDs in ABI structs; payloads use `*_len` for
/// exact string extent.
pub const SL_V2_ID_CAPACITY: usize = 96;

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
/// Opaque session handle. A zero-valued handle is invalid.
pub struct SlRuntimeHandle {
    pub raw: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
/// Opaque exported scene packet handle. Packet storage must be released before
/// its buffer views are considered invalid.
pub struct SlRenderPacketHandle {
    pub raw: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlStatusCode {
    Ok = 0,
    InvalidArgument = 1,
    NotReady = 2,
    InternalError = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlResult {
    pub code: SlStatusCode,
    pub detail_length: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlBytesView {
    pub data: *const u8,
    pub length: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlBufferView {
    pub data: *const c_void,
    pub stride_bytes: u32,
    pub element_count: u32,
    pub size_bytes: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlCpuBackend {
    ReferenceScalar = 0,
    SimdArm64 = 1,
    SimdX64 = 2,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlGpuBackend {
    None = 0,
    Vulkan = 1,
    Metal = 2,
    WebGpuClass = 3,
    OpenCl = 4,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlTimelineSemantics {
    AbsoluteEpoch = 0,
    BranchedSandbox = 1,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlObserverMode {
    Free = 0,
    FollowSelected = 1,
    FollowHost = 2,
    SystemFrame = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlVulkanSceneBufferKind {
    BodyInstances = 0,
    TracerInstances = 1,
    TrailSpans = 2,
    TrailVertices = 3,
    DirectionalLights = 4,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlVulkanTrailFamily {
    Trajectory = 0,
    HistoricalOrbit = 1,
    Prediction = 2,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlCommandKind {
    AdvanceEpoch = 0,
    PausePlayback = 1,
    ResumePlayback = 2,
    SetPlaybackRate = 3,
    SetObserverMode = 4,
    FocusBody = 5,
    SpawnBody = 6,
    RemoveBody = 7,
    SetBodyKinematics = 8,
    CreateCheckpoint = 9,
    CreateBranchFromCheckpoint = 10,
    SeedCanonicalSolarSystem = 11,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlBodyClass {
    Star = 0,
    Planet = 1,
    DwarfPlanet = 2,
    Moon = 3,
    SmallBody = 4,
    Tracer = 5,
    Spacecraft = 6,
    Custom = 7,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeInfo {
    pub abi_version: u32,
    pub requested_cpu_backend: SlCpuBackend,
    pub cpu_backend: SlCpuBackend,
    pub gpu_backend: SlGpuBackend,
    pub cpu_feature_flags: u64,
    pub cpu_solver_path: u32,
    pub cpu_fallback_code: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlVector3d {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlPackedVec3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlPackedColor {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub a: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlRenderDiagnostics {
    pub frame_number: u64,
    pub cpu_extract_ms: f32,
    pub gpu_upload_ms: f32,
    pub dropped_frames: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlVulkanCameraPacket {
    pub frame_origin_m: SlVector3d,
    pub position_from_origin_m: SlPackedVec3,
    pub target_from_origin_m: SlPackedVec3,
    pub up: SlPackedVec3,
    pub vertical_fov_degrees: f32,
    pub exposure: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlVulkanBodyInstance {
    pub position_from_origin_m: SlPackedVec3,
    pub radius_m: f32,
    pub albedo: SlPackedColor,
    pub emissive_luminance: f32,
    pub selected: u32,
    pub body_id: [u8; SL_V2_ID_CAPACITY],
    pub body_id_len: u32,
}

impl Default for SlVulkanBodyInstance {
    fn default() -> Self {
        Self {
            position_from_origin_m: SlPackedVec3::default(),
            radius_m: 0.0,
            albedo: SlPackedColor::default(),
            emissive_luminance: 0.0,
            selected: 0,
            body_id: [0; SL_V2_ID_CAPACITY],
            body_id_len: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlVulkanTracerInstance {
    pub position_from_origin_m: SlPackedVec3,
    pub color: SlPackedColor,
    pub size_px: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlVulkanTrailVertex {
    pub trail_index: u32,
    pub sample_index: u32,
    pub position_from_origin_m: SlPackedVec3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlVulkanTrailSpan {
    pub vertex_offset: u32,
    pub vertex_count: u32,
    pub color: SlPackedColor,
    pub max_samples: u32,
    pub head_highlighted: u32,
    pub family: SlVulkanTrailFamily,
    pub source_body_id: [u8; SL_V2_ID_CAPACITY],
    pub source_body_id_len: u32,
}

impl Default for SlVulkanTrailSpan {
    fn default() -> Self {
        Self {
            vertex_offset: 0,
            vertex_count: 0,
            color: SlPackedColor::default(),
            max_samples: 0,
            head_highlighted: 0,
            family: SlVulkanTrailFamily::Trajectory,
            source_body_id: [0; SL_V2_ID_CAPACITY],
            source_body_id_len: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SlVulkanDirectionalLight {
    pub direction_ws: SlPackedVec3,
    pub illuminance_lux: f32,
    pub color: SlPackedColor,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlVulkanScenePacketInfo {
    pub scene_revision: SlBytesView,
    pub epoch_seconds: f64,
    pub observer_mode: SlObserverMode,
    pub timeline_semantics: SlTimelineSemantics,
    pub camera: SlVulkanCameraPacket,
    pub body_instance_count: u32,
    pub tracer_instance_count: u32,
    pub trail_span_count: u32,
    pub trail_vertex_count: u32,
    pub directional_light_count: u32,
    pub diagnostics: SlRenderDiagnostics,
    pub provenance_source: SlBytesView,
    pub provenance_version: SlBytesView,
    pub provenance_manifest_id: SlBytesView,
    pub provenance_manifest_digest: SlBytesView,
    pub provenance_package_digest: SlBytesView,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlSessionCreateParams {
    pub scenario_id: [u8; SL_V2_ID_CAPACITY],
    pub scenario_id_len: u32,
    pub root_branch_id: [u8; SL_V2_ID_CAPACITY],
    pub root_branch_id_len: u32,
    pub created_at_unix_ms: i64,
    pub timeline_semantics: u32,
    pub live_updates_enabled: u8,
    pub cpu_backend: u32,
    pub gpu_backend: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionSnapshotSummary {
    pub scenario_id: [u8; SL_V2_ID_CAPACITY],
    pub scenario_id_len: u32,
    pub active_branch_id: [u8; SL_V2_ID_CAPACITY],
    pub active_branch_id_len: u32,
    pub epoch_seconds: f64,
    pub body_count: u32,
    pub active_checkpoint_present: u8,
    pub paused: u8,
    pub sim_seconds_per_real_second: f64,
    pub timeline_semantics: SlTimelineSemantics,
    pub observer_mode: SlObserverMode,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionCommand {
    pub kind: SlCommandKind,
    pub body_id: [u8; SL_V2_ID_CAPACITY],
    pub body_id_len: u32,
    pub body_class: SlBodyClass,
    pub body_position: SlVector3d,
    pub body_velocity: SlVector3d,
    pub body_mass_kg: f64,
    pub body_radius_m: f64,
    pub checkpoint_id: [u8; SL_V2_ID_CAPACITY],
    pub checkpoint_id_len: u32,
    pub checkpoint_label: [u8; SL_V2_ID_CAPACITY],
    pub checkpoint_label_len: u32,
    pub new_branch_id: [u8; SL_V2_ID_CAPACITY],
    pub new_branch_id_len: u32,
    pub observer_mode: SlObserverMode,
    pub delta_seconds: f64,
    pub sim_seconds_per_real_second: f64,
    pub recorded_at_unix_ms: i64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionCreateResult {
    pub result: SlResult,
    pub handle: SlRuntimeHandle,
    pub runtime_info: SlRuntimeInfo,
    pub snapshot_summary: SlSessionSnapshotSummary,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeInfoResult {
    pub result: SlResult,
    pub info: SlRuntimeInfo,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlSessionSnapshotSummaryResult {
    pub result: SlResult,
    pub summary: SlSessionSnapshotSummary,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SlVulkanScenePacketResult {
    pub result: SlResult,
    pub handle: SlRenderPacketHandle,
    pub info: SlVulkanScenePacketInfo,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlBufferViewResult {
    pub result: SlResult,
    pub view: SlBufferView,
}

#[derive(Debug)]
/// Single runtime session entry in the process registry.
struct RuntimeSession {
    runtime: WorldRuntime,
    runtime_info: SlRuntimeInfo,
    vulkan_scene_adapter: VulkanSceneAdapter,
}

#[derive(Debug, Default)]
/// Registry for live runtime sessions keyed by opaque 64-bit handles.
/// Handles are allocated monotonically (skipping 0) and must be released to
/// avoid leaking process memory and registry state.
struct SessionRegistry {
    next_handle: u64,
    sessions: HashMap<u64, RuntimeSession>,
}

impl SessionRegistry {
    /// Insert returns a process-stable non-zero handle.
    fn insert(&mut self, session: RuntimeSession) -> SlRuntimeHandle {
        if self.next_handle == 0 {
            self.next_handle = 1;
        }

        loop {
            let candidate = self.next_handle;
            self.next_handle = self.next_handle.wrapping_add(1);
            if candidate != 0 && !self.sessions.contains_key(&candidate) {
                self.sessions.insert(candidate, session);
                return SlRuntimeHandle { raw: candidate };
            }
        }
    }

    /// Remove is the canonical session teardown path; stale handles become invalid.
    fn remove(&mut self, handle: SlRuntimeHandle) -> bool {
        self.sessions.remove(&handle.raw).is_some()
    }

    /// Read-only lookup for command/query APIs.
    fn get(&self, handle: SlRuntimeHandle) -> Option<&RuntimeSession> {
        self.sessions.get(&handle.raw)
    }

    /// Mutable lookup for mutation APIs.
    fn get_mut(&mut self, handle: SlRuntimeHandle) -> Option<&mut RuntimeSession> {
        self.sessions.get_mut(&handle.raw)
    }
}

fn registry() -> &'static Mutex<SessionRegistry> {
    static REGISTRY: OnceLock<Mutex<SessionRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(SessionRegistry::default()))
}

/// Heap-owned projection of a Vulkan scene packet.
/// Data is copied from `solarlab_runtime` into a stable C/JNI-friendly shape.
#[derive(Debug)]
struct ExportedVulkanScenePacket {
    scene_revision: Vec<u8>,
    epoch_seconds: f64,
    observer_mode: SlObserverMode,
    timeline_semantics: SlTimelineSemantics,
    camera: SlVulkanCameraPacket,
    diagnostics: SlRenderDiagnostics,
    provenance_source: Vec<u8>,
    provenance_version: Vec<u8>,
    provenance_manifest_id: Vec<u8>,
    provenance_manifest_digest: Vec<u8>,
    provenance_package_digest: Vec<u8>,
    body_instances: Vec<SlVulkanBodyInstance>,
    tracer_instances: Vec<SlVulkanTracerInstance>,
    trail_spans: Vec<SlVulkanTrailSpan>,
    trail_vertices: Vec<SlVulkanTrailVertex>,
    directional_lights: Vec<SlVulkanDirectionalLight>,
}

impl ExportedVulkanScenePacket {
    fn from_scene_packet(packet: VulkanScenePacket) -> Result<Self, SlResult> {
        let provenance_source = packet
            .provenance
            .as_ref()
            .map_or_else(Vec::new, |value| value.source.as_bytes().to_vec());
        let provenance_version = packet
            .provenance
            .as_ref()
            .map_or_else(Vec::new, |value| value.version.as_bytes().to_vec());
        let provenance_manifest_id = packet
            .provenance
            .as_ref()
            .map_or_else(Vec::new, |value| value.manifest_id.as_bytes().to_vec());
        let provenance_manifest_digest = packet
            .provenance
            .as_ref()
            .and_then(|value| value.manifest_digest.as_ref())
            .map_or_else(Vec::new, |value| value.as_bytes().to_vec());
        let provenance_package_digest = packet
            .provenance
            .as_ref()
            .and_then(|value| value.package_digest.as_ref())
            .map_or_else(Vec::new, |digest| {
                format!("{}:{}", digest.algorithm, digest.hex_value()).into_bytes()
            });

        Ok(Self {
            scene_revision: packet.scene_revision.into_bytes(),
            epoch_seconds: packet.epoch_seconds,
            observer_mode: encode_observer_mode(&packet.observer_mode),
            timeline_semantics: encode_timeline_semantics(&packet.timeline_semantics),
            camera: SlVulkanCameraPacket {
                frame_origin_m: encode_vector3d(packet.camera.frame_origin_m),
                position_from_origin_m: encode_packed_vec3(packet.camera.position_from_origin_m),
                target_from_origin_m: encode_packed_vec3(packet.camera.target_from_origin_m),
                up: encode_packed_vec3(packet.camera.up),
                vertical_fov_degrees: packet.camera.vertical_fov_degrees,
                exposure: packet.camera.exposure,
            },
            diagnostics: SlRenderDiagnostics {
                frame_number: packet.diagnostics.frame_number,
                cpu_extract_ms: packet.diagnostics.cpu_extract_ms,
                gpu_upload_ms: packet.diagnostics.gpu_upload_ms,
                dropped_frames: packet.diagnostics.dropped_frames,
            },
            provenance_source,
            provenance_version,
            provenance_manifest_id,
            provenance_manifest_digest,
            provenance_package_digest,
            body_instances: packet
                .body_instances
                .into_iter()
                .map(encode_body_instance)
                .collect(),
            tracer_instances: packet
                .tracer_instances
                .into_iter()
                .map(encode_tracer_instance)
                .collect(),
            trail_spans: packet
                .trail_spans
                .into_iter()
                .map(encode_trail_span)
                .collect::<Result<Vec<_>, _>>()?,
            trail_vertices: packet
                .trail_vertices
                .into_iter()
                .map(encode_trail_vertex)
                .collect(),
            directional_lights: packet
                .directional_lights
                .into_iter()
                .map(encode_directional_light)
                .collect(),
        })
    }

    fn info(&self) -> SlVulkanScenePacketInfo {
        SlVulkanScenePacketInfo {
            scene_revision: bytes_view(&self.scene_revision),
            epoch_seconds: self.epoch_seconds,
            observer_mode: self.observer_mode,
            timeline_semantics: self.timeline_semantics,
            camera: self.camera,
            body_instance_count: usize_to_u32(self.body_instances.len()),
            tracer_instance_count: usize_to_u32(self.tracer_instances.len()),
            trail_span_count: usize_to_u32(self.trail_spans.len()),
            trail_vertex_count: usize_to_u32(self.trail_vertices.len()),
            directional_light_count: usize_to_u32(self.directional_lights.len()),
            diagnostics: self.diagnostics,
            provenance_source: bytes_view(&self.provenance_source),
            provenance_version: bytes_view(&self.provenance_version),
            provenance_manifest_id: bytes_view(&self.provenance_manifest_id),
            provenance_manifest_digest: bytes_view(&self.provenance_manifest_digest),
            provenance_package_digest: bytes_view(&self.provenance_package_digest),
        }
    }

    fn buffer_view(&self, kind: SlVulkanSceneBufferKind) -> SlBufferView {
        match kind {
            SlVulkanSceneBufferKind::BodyInstances => typed_buffer_view(&self.body_instances),
            SlVulkanSceneBufferKind::TracerInstances => typed_buffer_view(&self.tracer_instances),
            SlVulkanSceneBufferKind::TrailSpans => typed_buffer_view(&self.trail_spans),
            SlVulkanSceneBufferKind::TrailVertices => typed_buffer_view(&self.trail_vertices),
            SlVulkanSceneBufferKind::DirectionalLights => {
                typed_buffer_view(&self.directional_lights)
            }
        }
    }
}

/// Process-local registry for exported render packets. Packet buffers remain valid
/// only while this handle remains in the registry.
#[derive(Debug, Default)]
struct RenderPacketRegistry {
    next_handle: u64,
    packets: HashMap<u64, ExportedVulkanScenePacket>,
}

impl RenderPacketRegistry {
    /// Packet handles are process-local and remain valid until explicitly released.
    fn insert(&mut self, packet: ExportedVulkanScenePacket) -> SlRenderPacketHandle {
        if self.next_handle == 0 {
            self.next_handle = 1;
        }

        loop {
            let candidate = self.next_handle;
            self.next_handle = self.next_handle.wrapping_add(1);
            if candidate != 0 && !self.packets.contains_key(&candidate) {
                self.packets.insert(candidate, packet);
                return SlRenderPacketHandle { raw: candidate };
            }
        }
    }

    /// Explicitly remove packet ownership to invalidate read pointers.
    fn remove(&mut self, handle: SlRenderPacketHandle) -> bool {
        self.packets.remove(&handle.raw).is_some()
    }

    /// Read-only borrow for buffer extraction.
    fn get(&self, handle: SlRenderPacketHandle) -> Option<&ExportedVulkanScenePacket> {
        self.packets.get(&handle.raw)
    }
}

fn render_packet_registry() -> &'static Mutex<RenderPacketRegistry> {
    static REGISTRY: OnceLock<Mutex<RenderPacketRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(RenderPacketRegistry::default()))
}

#[must_use]
pub fn abi_version() -> u32 {
    SOLARLAB_V2_ABI_VERSION
}

#[must_use]
pub fn runtime_info(
    requested_cpu_backend: CpuBackend,
    gpu_backend: GpuBackend,
    solver_execution: &SolverExecutionReport,
) -> SlRuntimeInfo {
    let effective_cpu_backend = cpu_backend_for_solver_backend(&solver_execution.effective_backend);
    SlRuntimeInfo {
        abi_version: SOLARLAB_V2_ABI_VERSION,
        requested_cpu_backend: encode_cpu_backend(&requested_cpu_backend),
        cpu_backend: encode_cpu_backend(&effective_cpu_backend),
        gpu_backend: encode_gpu_backend(&gpu_backend),
        cpu_feature_flags: cpu_feature_flags(&solver_execution.active_cpu_features),
        cpu_solver_path: cpu_solver_path_code(&solver_execution.path_id),
        cpu_fallback_code: cpu_fallback_code(&solver_execution.fallback_code),
    }
}

#[unsafe(no_mangle)]
/// FFI version probe for clients that link dynamically and cache ABI behavior.
pub extern "C" fn sl_v2_abi_version() -> u32 {
    abi_version()
}

#[unsafe(no_mangle)]
/// Create a new runtime session and return both session handle and first snapshot.
/// Handle `0` is never returned on success.
pub extern "C" fn sl_v2_session_create(params: SlSessionCreateParams) -> SlSessionCreateResult {
    let build_outcome = build_session(params);
    let (runtime, info, summary) = match build_outcome {
        Ok(value) => value,
        Err(result) => {
            return SlSessionCreateResult {
                result,
                handle: SlRuntimeHandle::default(),
                runtime_info: empty_runtime_info(),
                snapshot_summary: empty_snapshot_summary(),
            };
        }
    };

    let mut registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlSessionCreateResult {
                result: status(SlStatusCode::InternalError),
                handle: SlRuntimeHandle::default(),
                runtime_info: empty_runtime_info(),
                snapshot_summary: empty_snapshot_summary(),
            };
        }
    };

    let handle = registry.insert(RuntimeSession {
        runtime,
        runtime_info: info,
        vulkan_scene_adapter: VulkanSceneAdapter::default(),
    });

    SlSessionCreateResult {
        result: status(SlStatusCode::Ok),
        handle,
        runtime_info: info,
        snapshot_summary: summary,
    }
}

#[unsafe(no_mangle)]
/// Destroying a session invalidates its handle and frees runtime-side state for
/// all in-memory branch and history data.
pub extern "C" fn sl_v2_session_destroy(handle: SlRuntimeHandle) -> SlResult {
    if handle.raw == 0 {
        return status(SlStatusCode::InvalidArgument);
    }

    let mut registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => return status(SlStatusCode::InternalError),
    };

    if registry.remove(handle) {
        status(SlStatusCode::Ok)
    } else {
        status(SlStatusCode::NotReady)
    }
}

#[unsafe(no_mangle)]
/// Query static runtime config metadata; no mutation.
pub extern "C" fn sl_v2_session_runtime_info(handle: SlRuntimeHandle) -> SlRuntimeInfoResult {
    if handle.raw == 0 {
        return SlRuntimeInfoResult {
            result: status(SlStatusCode::InvalidArgument),
            info: empty_runtime_info(),
        };
    }

    let registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlRuntimeInfoResult {
                result: status(SlStatusCode::InternalError),
                info: empty_runtime_info(),
            };
        }
    };

    let Some(session) = registry.get(handle) else {
        return SlRuntimeInfoResult {
            result: status(SlStatusCode::NotReady),
            info: empty_runtime_info(),
        };
    };

    SlRuntimeInfoResult {
        result: status(SlStatusCode::Ok),
        info: session.runtime_info,
    }
}

#[unsafe(no_mangle)]
/// Retrieve latest snapshot summary for an existing session.
/// This read path is used by shells that want deterministic host refresh without
/// mutating runtime state.
pub extern "C" fn sl_v2_session_snapshot_summary(
    handle: SlRuntimeHandle,
) -> SlSessionSnapshotSummaryResult {
    read_session_snapshot_summary(handle)
}

#[unsafe(no_mangle)]
/// Explicit refresh path. Semantically equivalent to a snapshot read in this seam
/// and kept as a dedicated API for host loop clarity.
pub extern "C" fn sl_v2_session_refresh(handle: SlRuntimeHandle) -> SlSessionSnapshotSummaryResult {
    read_session_snapshot_summary(handle)
}

#[unsafe(no_mangle)]
/// Apply one command and return the refreshed snapshot summary.
/// Command decoding happens at the boundary, then runtime applies state transitions.
pub extern "C" fn sl_v2_session_apply_command(
    handle: SlRuntimeHandle,
    command: SlSessionCommand,
) -> SlSessionSnapshotSummaryResult {
    if handle.raw == 0 {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::InvalidArgument),
            summary: empty_snapshot_summary(),
        };
    }

    let mut registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InternalError),
                summary: empty_snapshot_summary(),
            };
        }
    };

    let Some(session) = registry.get_mut(handle) else {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::NotReady),
            summary: empty_snapshot_summary(),
        };
    };

    let world_command = match decode_world_command(command) {
        Ok(value) => value,
        Err(result) => {
            return SlSessionSnapshotSummaryResult {
                result,
                summary: empty_snapshot_summary(),
            };
        }
    };

    if let Err(error) = session
        .runtime
        .apply_command(world_command, command.recorded_at_unix_ms)
    {
        return SlSessionSnapshotSummaryResult {
            result: runtime_error_to_status(error),
            summary: empty_snapshot_summary(),
        };
    }

    let summary = match snapshot_summary(&session.runtime) {
        Ok(summary) => summary,
        Err(result) => {
            return SlSessionSnapshotSummaryResult {
                result,
                summary: empty_snapshot_summary(),
            };
        }
    };

    SlSessionSnapshotSummaryResult {
        result: status(SlStatusCode::Ok),
        summary,
    }
}

fn read_session_snapshot_summary(handle: SlRuntimeHandle) -> SlSessionSnapshotSummaryResult {
    if handle.raw == 0 {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::InvalidArgument),
            summary: empty_snapshot_summary(),
        };
    }

    let registry = match registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InternalError),
                summary: empty_snapshot_summary(),
            };
        }
    };

    let Some(session) = registry.get(handle) else {
        return SlSessionSnapshotSummaryResult {
            result: status(SlStatusCode::NotReady),
            summary: empty_snapshot_summary(),
        };
    };

    let summary = match snapshot_summary(&session.runtime) {
        Ok(summary) => summary,
        Err(result) => {
            return SlSessionSnapshotSummaryResult {
                result,
                summary: empty_snapshot_summary(),
            };
        }
    };

    SlSessionSnapshotSummaryResult {
        result: status(SlStatusCode::Ok),
        summary,
    }
}

#[unsafe(no_mangle)]
/// Render extraction boundary:
/// - lock session
/// - capture pure runtime scene snapshot
/// - adapt to Vulkan packet shape
/// - register packet for buffer reads via a render handle.
pub extern "C" fn sl_v2_session_export_vulkan_scene(
    handle: SlRuntimeHandle,
) -> SlVulkanScenePacketResult {
    if handle.raw == 0 {
        return SlVulkanScenePacketResult {
            result: status(SlStatusCode::InvalidArgument),
            handle: SlRenderPacketHandle::default(),
            info: empty_vulkan_scene_packet_info(),
        };
    }

    let scene_packet = {
        let mut registry = match registry().lock() {
            Ok(lock) => lock,
            Err(_) => {
                return SlVulkanScenePacketResult {
                    result: status(SlStatusCode::InternalError),
                    handle: SlRenderPacketHandle::default(),
                    info: empty_vulkan_scene_packet_info(),
                };
            }
        };

        let Some(session) = registry.get_mut(handle) else {
            return SlVulkanScenePacketResult {
                result: status(SlStatusCode::NotReady),
                handle: SlRenderPacketHandle::default(),
                info: empty_vulkan_scene_packet_info(),
            };
        };

        let scene = session.runtime.render_scene();
        session.vulkan_scene_adapter.adapt(&scene)
    };

    let packet = match ExportedVulkanScenePacket::from_scene_packet(scene_packet) {
        Ok(packet) => packet,
        Err(result) => {
            return SlVulkanScenePacketResult {
                result,
                handle: SlRenderPacketHandle::default(),
                info: empty_vulkan_scene_packet_info(),
            };
        }
    };
    let mut packet_registry = match render_packet_registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlVulkanScenePacketResult {
                result: status(SlStatusCode::InternalError),
                handle: SlRenderPacketHandle::default(),
                info: empty_vulkan_scene_packet_info(),
            };
        }
    };

    let handle = packet_registry.insert(packet);
    let info = packet_registry
        .get(handle)
        .map(ExportedVulkanScenePacket::info)
        .unwrap_or_else(empty_vulkan_scene_packet_info);

    SlVulkanScenePacketResult {
        result: status(SlStatusCode::Ok),
        handle,
        info,
    }
}

#[unsafe(no_mangle)]
/// Borrow exported packet buffers by kind while packet handle is still registered.
/// All returned views become invalid once `sl_v2_vulkan_scene_packet_release` runs.
pub extern "C" fn sl_v2_vulkan_scene_packet_buffer(
    handle: SlRenderPacketHandle,
    buffer_kind: SlVulkanSceneBufferKind,
) -> SlBufferViewResult {
    if handle.raw == 0 {
        return SlBufferViewResult {
            result: status(SlStatusCode::InvalidArgument),
            view: empty_buffer_view(),
        };
    }

    let packet_registry = match render_packet_registry().lock() {
        Ok(lock) => lock,
        Err(_) => {
            return SlBufferViewResult {
                result: status(SlStatusCode::InternalError),
                view: empty_buffer_view(),
            };
        }
    };

    let Some(packet) = packet_registry.get(handle) else {
        return SlBufferViewResult {
            result: status(SlStatusCode::NotReady),
            view: empty_buffer_view(),
        };
    };

    SlBufferViewResult {
        result: status(SlStatusCode::Ok),
        view: packet.buffer_view(buffer_kind),
    }
}

#[unsafe(no_mangle)]
/// Release packet handle and invalidate every previously returned view for that packet.
pub extern "C" fn sl_v2_vulkan_scene_packet_release(handle: SlRenderPacketHandle) -> SlResult {
    if handle.raw == 0 {
        return status(SlStatusCode::InvalidArgument);
    }

    let mut packet_registry = match render_packet_registry().lock() {
        Ok(lock) => lock,
        Err(_) => return status(SlStatusCode::InternalError),
    };

    if packet_registry.remove(handle) {
        status(SlStatusCode::Ok)
    } else {
        status(SlStatusCode::NotReady)
    }
}

fn build_session(
    params: SlSessionCreateParams,
) -> Result<(WorldRuntime, SlRuntimeInfo, SlSessionSnapshotSummary), SlResult> {
    let scenario_id = decode_identifier(&params.scenario_id, params.scenario_id_len)?;
    let root_branch_id = decode_identifier(&params.root_branch_id, params.root_branch_id_len)?;
    let timeline_semantics = decode_timeline_semantics(params.timeline_semantics)?;
    let requested_cpu_backend = decode_cpu_backend(params.cpu_backend)?;
    let gpu_backend = decode_gpu_backend(params.gpu_backend)?;
    let requested_solver_backend = solver_for_cpu_backend(&requested_cpu_backend);

    let runtime_config = RuntimeConfig {
        physics: PhysicsPolicy {
            solver_backend: requested_solver_backend.clone(),
            integrator: IntegratorKind::LeapfrogKickDriftKick,
            collision_model: CollisionModel::None,
            max_substep_seconds: 1.0,
        },
        timeline_semantics,
        live_updates_enabled: params.live_updates_enabled != 0,
    };

    let gpu_backend_report = default_gpu_backend_report(&gpu_backend);
    let cpu_features = detect_cpu_features();
    let solver_execution =
        solver_execution_report_for_backend(&requested_solver_backend, &cpu_features);
    let effective_cpu_backend = cpu_backend_for_solver_backend(&solver_execution.effective_backend);
    let gpu_workload_assignments = gpu_backend_report.workload_assignments();
    let gpu_interop_policy = gpu_backend_report.interop_policy();
    let hardware_profile = HardwareProfile {
        cpu_backend: effective_cpu_backend,
        gpu_backend: gpu_backend.clone(),
        gpu_backend_report,
        gpu_workload_assignments,
        gpu_interop_policy,
        cpu_features: cpu_features.clone(),
        gpu_features: Vec::new(),
        acceleration_modes: default_acceleration_modes(
            &gpu_backend,
            &cpu_backend_for_solver_backend(&solver_execution.effective_backend),
            &cpu_features,
        ),
    };

    let runtime = WorldRuntime::new(
        ScenarioId(scenario_id),
        BranchId(root_branch_id),
        runtime_config,
        hardware_profile,
        params.created_at_unix_ms,
    );

    let info = runtime_info(requested_cpu_backend, gpu_backend, &solver_execution);
    let summary = snapshot_summary(&runtime)?;

    Ok((runtime, info, summary))
}

fn default_gpu_backend_report(gpu_backend: &GpuBackend) -> GpuBackendReport {
    match gpu_backend {
        GpuBackend::None => GpuBackendReport::default(),
        GpuBackend::Vulkan => GpuBackendReport {
            active: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::Vulkan,
            }],
            available: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::Vulkan,
            }],
        },
        // OpenCL selection is treated as the dual-backend profile:
        // OpenCL for simulation and Vulkan for rendering.
        GpuBackend::OpenCl => GpuBackendReport {
            active: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::OpenCl,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Rendering,
                    backend: GpuBackend::Vulkan,
                },
            ],
            available: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::OpenCl,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Rendering,
                    backend: GpuBackend::Vulkan,
                },
            ],
        },
        GpuBackend::Metal => GpuBackendReport {
            active: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::Metal,
            }],
            available: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::Metal,
            }],
        },
        GpuBackend::WebGpuClass => GpuBackendReport {
            active: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::WebGpuClass,
            }],
            available: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Rendering,
                backend: GpuBackend::WebGpuClass,
            }],
        },
    }
}

fn default_acceleration_modes(
    gpu_backend: &GpuBackend,
    cpu_backend: &CpuBackend,
    cpu_features: &[String],
) -> Vec<String> {
    let mut modes = cpu_acceleration_modes(cpu_backend, cpu_features);
    match gpu_backend {
        GpuBackend::OpenCl => vec![
            "dual-gpu".to_owned(),
            "opencl-long-horizon".to_owned(),
            "vulkan-in-frame".to_owned(),
            "interop-error-budget-v1".to_owned(),
        ],
        GpuBackend::Vulkan => vec!["gpu-render".to_owned()],
        GpuBackend::Metal => vec!["gpu-render".to_owned()],
        GpuBackend::WebGpuClass => vec!["gpu-render".to_owned()],
        GpuBackend::None => Vec::new(),
    }
    .into_iter()
    .for_each(|mode| {
        if !modes.contains(&mode) {
            modes.push(mode);
        }
    });

    modes
}

fn cpu_acceleration_modes(cpu_backend: &CpuBackend, cpu_features: &[String]) -> Vec<String> {
    let mut modes = Vec::new();
    match cpu_backend {
        CpuBackend::ReferenceScalar => modes.push("cpu-scalar".to_owned()),
        CpuBackend::SimdArm64 => modes.push("cpu-simd-arm64-active".to_owned()),
        CpuBackend::SimdX64 => modes.push("cpu-simd-x64-active".to_owned()),
    }

    for feature in cpu_features {
        let mode = format!("cpu-isa-{feature}");
        if !modes.contains(&mode) {
            modes.push(mode);
        }
    }
    modes
}

fn snapshot_summary(runtime: &WorldRuntime) -> Result<SlSessionSnapshotSummary, SlResult> {
    let snapshot = runtime.snapshot();

    Ok(SlSessionSnapshotSummary {
        scenario_id: encode_identifier(&snapshot.scenario_id.0)?,
        scenario_id_len: string_length_to_u32(&snapshot.scenario_id.0),
        active_branch_id: encode_identifier(&snapshot.branch_id.0)?,
        active_branch_id_len: string_length_to_u32(&snapshot.branch_id.0),
        epoch_seconds: snapshot.epoch_seconds,
        body_count: usize_to_u32(snapshot.bodies.len()),
        active_checkpoint_present: u8::from(snapshot.active_checkpoint.is_some()),
        paused: u8::from(snapshot.playback.paused),
        sim_seconds_per_real_second: snapshot.playback.sim_seconds_per_real_second,
        timeline_semantics: encode_timeline_semantics(&snapshot.timeline_semantics),
        observer_mode: encode_observer_mode(&snapshot.observer.mode),
    })
}

fn encode_vector3d(value: solarlab_domain::Vector3d) -> SlVector3d {
    SlVector3d {
        x: value.x,
        y: value.y,
        z: value.z,
    }
}

fn encode_packed_vec3(value: PackedVec3) -> SlPackedVec3 {
    SlPackedVec3 {
        x: value.x,
        y: value.y,
        z: value.z,
    }
}

fn encode_packed_color(value: PackedColor) -> SlPackedColor {
    SlPackedColor {
        r: value.r,
        g: value.g,
        b: value.b,
        a: value.a,
    }
}

fn encode_body_instance(value: VulkanBodyInstance) -> SlVulkanBodyInstance {
    SlVulkanBodyInstance {
        position_from_origin_m: encode_packed_vec3(value.position_from_origin_m),
        radius_m: value.radius_m,
        albedo: encode_packed_color(value.albedo),
        emissive_luminance: value.emissive_luminance,
        selected: u32::from(value.selected),
        body_id: encode_identifier(&value.body_id.0).expect("body id should fit into packet"),
        body_id_len: string_length_to_u32(&value.body_id.0),
    }
}

fn encode_tracer_instance(value: VulkanTracerInstance) -> SlVulkanTracerInstance {
    SlVulkanTracerInstance {
        position_from_origin_m: encode_packed_vec3(value.position_from_origin_m),
        color: encode_packed_color(value.color),
        size_px: value.size_px,
    }
}

fn encode_trail_span(value: VulkanTrailSpan) -> Result<SlVulkanTrailSpan, SlResult> {
    Ok(SlVulkanTrailSpan {
        vertex_offset: value.vertex_offset,
        vertex_count: value.vertex_count,
        color: encode_packed_color(value.color),
        max_samples: value.max_samples,
        head_highlighted: u32::from(value.head_highlighted),
        family: encode_trail_family(value.family),
        source_body_id: encode_identifier(&value.source_body_id.0)?,
        source_body_id_len: string_length_to_u32(&value.source_body_id.0),
    })
}

fn encode_trail_family(value: SceneTrailFamily) -> SlVulkanTrailFamily {
    match value {
        SceneTrailFamily::Trajectory => SlVulkanTrailFamily::Trajectory,
        SceneTrailFamily::HistoricalOrbit => SlVulkanTrailFamily::HistoricalOrbit,
        SceneTrailFamily::Prediction => SlVulkanTrailFamily::Prediction,
    }
}

fn encode_trail_vertex(value: VulkanTrailVertex) -> SlVulkanTrailVertex {
    SlVulkanTrailVertex {
        trail_index: value.trail_index,
        sample_index: value.sample_index,
        position_from_origin_m: encode_packed_vec3(value.position_from_origin_m),
    }
}

fn encode_directional_light(value: VulkanDirectionalLight) -> SlVulkanDirectionalLight {
    SlVulkanDirectionalLight {
        direction_ws: encode_packed_vec3(value.direction_ws),
        illuminance_lux: value.illuminance_lux,
        color: encode_packed_color(value.color),
    }
}

fn bytes_view(bytes: &[u8]) -> SlBytesView {
    if bytes.is_empty() {
        SlBytesView {
            data: std::ptr::null(),
            length: 0,
        }
    } else {
        SlBytesView {
            data: bytes.as_ptr(),
            length: usize_to_u32(bytes.len()),
        }
    }
}

fn typed_buffer_view<T>(values: &[T]) -> SlBufferView {
    if values.is_empty() {
        return empty_buffer_view();
    }

    let stride_bytes = usize_to_u32(size_of::<T>());
    let element_count = usize_to_u32(values.len());
    SlBufferView {
        data: values.as_ptr().cast::<c_void>(),
        stride_bytes,
        element_count,
        size_bytes: stride_bytes.saturating_mul(element_count),
    }
}

fn decode_identifier(bytes: &[u8; SL_V2_ID_CAPACITY], length: u32) -> Result<String, SlResult> {
    let length = usize::try_from(length).map_err(|_| status(SlStatusCode::InvalidArgument))?;
    if length == 0 || length > SL_V2_ID_CAPACITY {
        return Err(status(SlStatusCode::InvalidArgument));
    }

    let data = &bytes[..length];
    let value = str::from_utf8(data).map_err(|_| status(SlStatusCode::InvalidArgument))?;
    Ok(value.to_owned())
}

fn encode_identifier(value: &str) -> Result<[u8; SL_V2_ID_CAPACITY], SlResult> {
    if value.len() > SL_V2_ID_CAPACITY {
        return Err(status(SlStatusCode::InternalError));
    }

    let mut bytes = [0u8; SL_V2_ID_CAPACITY];
    bytes[..value.len()].copy_from_slice(value.as_bytes());
    Ok(bytes)
}

fn decode_cpu_backend(value: u32) -> Result<CpuBackend, SlResult> {
    match value {
        0 => Ok(CpuBackend::ReferenceScalar),
        1 => Ok(CpuBackend::SimdArm64),
        2 => Ok(CpuBackend::SimdX64),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn decode_gpu_backend(value: u32) -> Result<GpuBackend, SlResult> {
    match value {
        0 => Ok(GpuBackend::None),
        1 => Ok(GpuBackend::Vulkan),
        2 => Ok(GpuBackend::Metal),
        3 => Ok(GpuBackend::WebGpuClass),
        4 => Ok(GpuBackend::OpenCl),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn decode_timeline_semantics(value: u32) -> Result<TimelineSemantics, SlResult> {
    match value {
        0 => Ok(TimelineSemantics::AbsoluteEpoch),
        1 => Ok(TimelineSemantics::BranchedSandbox),
        _ => Err(status(SlStatusCode::InvalidArgument)),
    }
}

fn decode_observer_mode(value: SlObserverMode) -> ObserverMode {
    match value {
        SlObserverMode::Free => ObserverMode::Free,
        SlObserverMode::FollowSelected => ObserverMode::FollowSelected,
        SlObserverMode::FollowHost => ObserverMode::FollowHost,
        SlObserverMode::SystemFrame => ObserverMode::SystemFrame,
    }
}

fn decode_world_command(command: SlSessionCommand) -> Result<WorldCommand, SlResult> {
    match command.kind {
        SlCommandKind::SeedCanonicalSolarSystem => Ok(WorldCommand::SeedCanonicalSolarSystem),
        SlCommandKind::AdvanceEpoch => Ok(WorldCommand::AdvanceEpoch {
            delta_seconds: command.delta_seconds,
        }),
        SlCommandKind::PausePlayback => Ok(WorldCommand::PausePlayback),
        SlCommandKind::ResumePlayback => Ok(WorldCommand::ResumePlayback),
        SlCommandKind::SetPlaybackRate => Ok(WorldCommand::SetPlaybackRate {
            sim_seconds_per_real_second: command.sim_seconds_per_real_second,
        }),
        SlCommandKind::SetObserverMode => Ok(WorldCommand::SetObserverMode {
            mode: decode_observer_mode(command.observer_mode),
        }),
        SlCommandKind::FocusBody => {
            let body_id = decode_optional_identifier(&command.body_id, command.body_id_len)?;
            Ok(WorldCommand::FocusBody {
                body_id: body_id.map(BodyId),
            })
        }
        SlCommandKind::SpawnBody => {
            let body_id = decode_identifier(&command.body_id, command.body_id_len)?;
            let body_class = decode_body_class(command.body_class)?;
            Ok(WorldCommand::SpawnBody {
                body: BodyState {
                    body_id: BodyId(body_id),
                    body_class,
                    mass_kg: command.body_mass_kg,
                    radius_m: command.body_radius_m,
                    position_m: decode_vector3d(command.body_position),
                    velocity_mps: decode_vector3d(command.body_velocity),
                },
            })
        }
        SlCommandKind::RemoveBody => Ok(WorldCommand::RemoveBody {
            body_id: BodyId(decode_identifier(&command.body_id, command.body_id_len)?),
        }),
        SlCommandKind::SetBodyKinematics => {
            let body_id = decode_identifier(&command.body_id, command.body_id_len)?;
            Ok(WorldCommand::SetBodyKinematics {
                body_id: BodyId(body_id),
                position_m: decode_vector3d(command.body_position),
                velocity_mps: decode_vector3d(command.body_velocity),
            })
        }
        SlCommandKind::CreateCheckpoint => {
            let checkpoint_id =
                decode_optional_identifier(&command.checkpoint_id, command.checkpoint_id_len)?
                    .map(CheckpointId);
            let label = decode_optional_identifier(
                &command.checkpoint_label,
                command.checkpoint_label_len,
            )?;
            Ok(WorldCommand::CreateCheckpoint {
                checkpoint_id,
                label,
            })
        }
        SlCommandKind::CreateBranchFromCheckpoint => {
            let checkpoint_id = CheckpointId(decode_identifier(
                &command.checkpoint_id,
                command.checkpoint_id_len,
            )?);
            let new_branch_id =
                decode_optional_identifier(&command.new_branch_id, command.new_branch_id_len)?
                    .map(BranchId);
            Ok(WorldCommand::CreateBranchFromCheckpoint {
                checkpoint_id,
                new_branch_id,
            })
        }
    }
}

fn decode_optional_identifier(
    bytes: &[u8; SL_V2_ID_CAPACITY],
    length: u32,
) -> Result<Option<String>, SlResult> {
    if length == 0 {
        return Ok(None);
    }

    decode_identifier(bytes, length).map(Some)
}

fn decode_body_class(value: SlBodyClass) -> Result<BodyClass, SlResult> {
    match value {
        SlBodyClass::Star => Ok(BodyClass::Star),
        SlBodyClass::Planet => Ok(BodyClass::Planet),
        SlBodyClass::DwarfPlanet => Ok(BodyClass::DwarfPlanet),
        SlBodyClass::Moon => Ok(BodyClass::Moon),
        SlBodyClass::SmallBody => Ok(BodyClass::SmallBody),
        SlBodyClass::Tracer => Ok(BodyClass::Tracer),
        SlBodyClass::Spacecraft => Ok(BodyClass::Spacecraft),
        SlBodyClass::Custom => Ok(BodyClass::Custom),
    }
}

fn decode_vector3d(value: SlVector3d) -> Vector3d {
    Vector3d {
        x: value.x,
        y: value.y,
        z: value.z,
    }
}

fn runtime_error_to_status(error: RuntimeError) -> SlResult {
    match error {
        RuntimeError::DuplicateBody(_)
        | RuntimeError::UnknownBody(_)
        | RuntimeError::InvalidEpochDelta(_)
        | RuntimeError::InvalidPlaybackRate(_)
        | RuntimeError::UnknownCheckpoint(_)
        | RuntimeError::DuplicateCheckpoint(_)
        | RuntimeError::DuplicateBranch(_)
        | RuntimeError::PackagePlanFailed(_)
        | RuntimeError::NoInstalledManifestAvailable
        | RuntimeError::PackageNotInstalled(_)
        | RuntimeError::MountedPackageMissingFromStore(_) => status(SlStatusCode::InvalidArgument),
        RuntimeError::PackageApplyFailed(_) => status(SlStatusCode::InternalError),
    }
}

fn solver_for_cpu_backend(cpu_backend: &CpuBackend) -> SolverBackend {
    match cpu_backend {
        CpuBackend::ReferenceScalar => SolverBackend::ReferenceScalar,
        CpuBackend::SimdArm64 => SolverBackend::SimdArm64,
        CpuBackend::SimdX64 => SolverBackend::SimdX64,
    }
}

fn cpu_backend_for_solver_backend(solver_backend: &SolverBackend) -> CpuBackend {
    match solver_backend {
        SolverBackend::ReferenceScalar => CpuBackend::ReferenceScalar,
        SolverBackend::SimdArm64 => CpuBackend::SimdArm64,
        SolverBackend::SimdX64 => CpuBackend::SimdX64,
    }
}

fn cpu_solver_path_code(path_id: &str) -> u32 {
    match path_id {
        "scalar.reference" => 0,
        "simd.arm64.neon-f64-pairwise" => 1,
        "simd.x64.scalar-fallback" => 2,
        _ => u32::MAX,
    }
}

fn cpu_fallback_code(fallback_code: &SolverFallbackCode) -> u32 {
    match fallback_code {
        SolverFallbackCode::None => 0,
        SolverFallbackCode::SimdArm64OnNonAarch64Host => 1,
        SolverFallbackCode::SimdArm64MissingNeon => 2,
        SolverFallbackCode::SimdX64Unavailable => 3,
    }
}

fn encode_cpu_backend(cpu_backend: &CpuBackend) -> SlCpuBackend {
    match cpu_backend {
        CpuBackend::ReferenceScalar => SlCpuBackend::ReferenceScalar,
        CpuBackend::SimdArm64 => SlCpuBackend::SimdArm64,
        CpuBackend::SimdX64 => SlCpuBackend::SimdX64,
    }
}

fn encode_gpu_backend(gpu_backend: &GpuBackend) -> SlGpuBackend {
    match gpu_backend {
        GpuBackend::None => SlGpuBackend::None,
        GpuBackend::Vulkan => SlGpuBackend::Vulkan,
        GpuBackend::Metal => SlGpuBackend::Metal,
        GpuBackend::WebGpuClass => SlGpuBackend::WebGpuClass,
        GpuBackend::OpenCl => SlGpuBackend::OpenCl,
    }
}

fn encode_timeline_semantics(value: &TimelineSemantics) -> SlTimelineSemantics {
    match value {
        TimelineSemantics::AbsoluteEpoch => SlTimelineSemantics::AbsoluteEpoch,
        TimelineSemantics::BranchedSandbox => SlTimelineSemantics::BranchedSandbox,
    }
}

fn encode_observer_mode(value: &ObserverMode) -> SlObserverMode {
    match value {
        ObserverMode::Free => SlObserverMode::Free,
        ObserverMode::FollowSelected => SlObserverMode::FollowSelected,
        ObserverMode::FollowHost => SlObserverMode::FollowHost,
        ObserverMode::SystemFrame => SlObserverMode::SystemFrame,
    }
}

fn string_length_to_u32(value: &str) -> u32 {
    usize_to_u32(value.len())
}

fn usize_to_u32(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

fn status(code: SlStatusCode) -> SlResult {
    SlResult {
        code,
        detail_length: 0,
    }
}

fn empty_runtime_info() -> SlRuntimeInfo {
    SlRuntimeInfo {
        abi_version: SOLARLAB_V2_ABI_VERSION,
        requested_cpu_backend: SlCpuBackend::ReferenceScalar,
        cpu_backend: SlCpuBackend::ReferenceScalar,
        gpu_backend: SlGpuBackend::None,
        cpu_feature_flags: 0,
        cpu_solver_path: 0,
        cpu_fallback_code: 0,
    }
}

fn empty_snapshot_summary() -> SlSessionSnapshotSummary {
    SlSessionSnapshotSummary {
        scenario_id: [0; SL_V2_ID_CAPACITY],
        scenario_id_len: 0,
        active_branch_id: [0; SL_V2_ID_CAPACITY],
        active_branch_id_len: 0,
        epoch_seconds: 0.0,
        body_count: 0,
        active_checkpoint_present: 0,
        paused: 0,
        sim_seconds_per_real_second: 0.0,
        timeline_semantics: SlTimelineSemantics::AbsoluteEpoch,
        observer_mode: SlObserverMode::Free,
    }
}

fn empty_buffer_view() -> SlBufferView {
    SlBufferView {
        data: std::ptr::null(),
        stride_bytes: 0,
        element_count: 0,
        size_bytes: 0,
    }
}

fn empty_vulkan_scene_packet_info() -> SlVulkanScenePacketInfo {
    SlVulkanScenePacketInfo {
        scene_revision: bytes_view(&[]),
        epoch_seconds: 0.0,
        observer_mode: SlObserverMode::Free,
        timeline_semantics: SlTimelineSemantics::AbsoluteEpoch,
        camera: SlVulkanCameraPacket::default(),
        body_instance_count: 0,
        tracer_instance_count: 0,
        trail_span_count: 0,
        trail_vertex_count: 0,
        directional_light_count: 0,
        diagnostics: SlRenderDiagnostics::default(),
        provenance_source: bytes_view(&[]),
        provenance_version: bytes_view(&[]),
        provenance_manifest_id: bytes_view(&[]),
        provenance_manifest_digest: bytes_view(&[]),
        provenance_package_digest: bytes_view(&[]),
    }
}

#[cfg(target_os = "android")]
// JNI is a thin forwarding layer over C APIs.
// Handles are still 64-bit integer capabilities; JNI must not copy/stash runtime
// pointers and must treat every handle as invalid after its corresponding destroy/
// release call.
mod android_jni {
    use super::{
        sl_v2_session_apply_command, sl_v2_session_create, sl_v2_session_destroy,
        sl_v2_session_export_vulkan_scene, sl_v2_session_refresh, sl_v2_session_runtime_info,
        sl_v2_session_snapshot_summary, sl_v2_vulkan_scene_packet_buffer,
        sl_v2_vulkan_scene_packet_release, status, SlBodyClass, SlBufferView, SlCommandKind,
        SlRenderPacketHandle, SlResult, SlRuntimeHandle, SlSessionCommand, SlSessionCreateParams,
        SlSessionCreateResult, SlSessionSnapshotSummaryResult, SlStatusCode, SlVector3d,
        SlVulkanSceneBufferKind, SlVulkanScenePacketResult, SL_V2_ID_CAPACITY,
    };
    use jni::objects::{JByteArray, JObject, JValue};
    use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jobject};
    use jni::JNIEnv;

    const CLASS_NATIVE_RESULT: &str = "com/sednalabs/solarlab/runtime/NativeResult";
    const CLASS_NATIVE_CREATE_SESSION_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeCreateSessionResult";
    const CLASS_NATIVE_RUNTIME_INFO_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeRuntimeInfoResult";
    const CLASS_NATIVE_SNAPSHOT_SUMMARY_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeSnapshotSummaryResult";
    const CLASS_NATIVE_VULKAN_SCENE_PACKET_RESULT: &str =
        "com/sednalabs/solarlab/runtime/NativeVulkanScenePacketResult";
    const CLASS_NATIVE_VULKAN_SCENE_PACKET: &str =
        "com/sednalabs/solarlab/runtime/NativeVulkanScenePacket";
    const CLASS_NATIVE_VULKAN_CAMERA_PACKET: &str =
        "com/sednalabs/solarlab/runtime/NativeVulkanCameraPacket";
    const CLASS_NATIVE_RENDER_DIAGNOSTICS: &str =
        "com/sednalabs/solarlab/runtime/NativeRenderDiagnostics";

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // CreateSession is the JNI entrypoint for `sl_v2_session_create`.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeCreateSession(
        mut env: JNIEnv,
        _this: JObject,
        scenario_id_utf8: jbyteArray,
        root_branch_id_utf8: jbyteArray,
        created_at_unix_ms: jlong,
        timeline_semantics: jint,
        live_updates_enabled: jboolean,
        cpu_backend: jint,
        gpu_backend: jint,
    ) -> jobject {
        let create_result = build_session_create_result(
            &env,
            scenario_id_utf8,
            root_branch_id_utf8,
            created_at_unix_ms,
            timeline_semantics,
            live_updates_enabled,
            cpu_backend,
            gpu_backend,
        );

        match create_native_create_session_result(&mut env, &create_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // DestroySession must be treated as the single teardown point for session
    // handles coming from Kotlin.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeDestroySession(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let native_result = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_destroy(SlRuntimeHandle { raw: handle_value })
        } else {
            status(SlStatusCode::InvalidArgument)
        };

        match create_native_result(&mut env, native_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeRuntimeInfo(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let runtime_info = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_runtime_info(SlRuntimeHandle { raw: handle_value })
        } else {
            super::SlRuntimeInfoResult {
                result: status(SlStatusCode::InvalidArgument),
                info: super::empty_runtime_info(),
            }
        };

        match create_native_runtime_info_result(&mut env, runtime_info) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // SnapshotSummary returns read-only session state.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeSnapshotSummary(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let snapshot_summary = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_snapshot_summary(SlRuntimeHandle { raw: handle_value })
        } else {
            SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InvalidArgument),
                summary: super::empty_snapshot_summary(),
            }
        };

        match create_native_snapshot_summary_result(&mut env, snapshot_summary) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // RefreshSession is an explicit JNI read-refresh operation on the current session.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeRefreshSession(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let refresh_summary = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_refresh(SlRuntimeHandle { raw: handle_value })
        } else {
            SlSessionSnapshotSummaryResult {
                result: status(SlStatusCode::InvalidArgument),
                summary: super::empty_snapshot_summary(),
            }
        };

        match create_native_snapshot_summary_result(&mut env, refresh_summary) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // ApplyCommand forwards a single command and returns the updated snapshot summary.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeApplyCommand(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
        kind: jint,
        body_id_utf8: jbyteArray,
        body_class: jint,
        body_position_x: jdouble,
        body_position_y: jdouble,
        body_position_z: jdouble,
        body_velocity_x: jdouble,
        body_velocity_y: jdouble,
        body_velocity_z: jdouble,
        body_mass_kg: jdouble,
        body_radius_m: jdouble,
        checkpoint_id_utf8: jbyteArray,
        checkpoint_label_utf8: jbyteArray,
        new_branch_id_utf8: jbyteArray,
        observer_mode: jint,
        delta_seconds: f64,
        sim_seconds_per_real_second: f64,
        recorded_at_unix_ms: jlong,
    ) -> jobject {
        let command_result = build_apply_command_result(
            &env,
            handle,
            kind,
            body_id_utf8,
            body_class,
            body_position_x,
            body_position_y,
            body_position_z,
            body_velocity_x,
            body_velocity_y,
            body_velocity_z,
            body_mass_kg,
            body_radius_m,
            checkpoint_id_utf8,
            checkpoint_label_utf8,
            new_branch_id_utf8,
            observer_mode,
            delta_seconds,
            sim_seconds_per_real_second,
            recorded_at_unix_ms,
        );

        match create_native_snapshot_summary_result(&mut env, command_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // ExportVulkanScene maps to the packet-export flow and returns a packet handle
    // plus metadata required for buffer reads.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeExportVulkanScene(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let packet_result = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_session_export_vulkan_scene(SlRuntimeHandle { raw: handle_value })
        } else {
            SlVulkanScenePacketResult {
                result: status(SlStatusCode::InvalidArgument),
                handle: SlRenderPacketHandle::default(),
                info: super::empty_vulkan_scene_packet_info(),
            }
        };

        match create_native_vulkan_scene_packet_result(&mut env, packet_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    #[allow(non_snake_case)]
    #[unsafe(no_mangle)]
    // ReleaseVulkanScene must be called when Kotlin/Java owners are done with packet views.
    pub extern "system" fn Java_com_sednalabs_solarlab_runtime_JniNativeRuntimeTransport_nativeReleaseVulkanScene(
        mut env: JNIEnv,
        _this: JObject,
        handle: jlong,
    ) -> jobject {
        let native_result = if let Ok(handle_value) = u64::try_from(handle) {
            sl_v2_vulkan_scene_packet_release(SlRenderPacketHandle { raw: handle_value })
        } else {
            status(SlStatusCode::InvalidArgument)
        };

        match create_native_result(&mut env, native_result) {
            Ok(value) => value.into_raw(),
            Err(_) => JObject::null().into_raw(),
        }
    }

    fn build_session_create_result(
        env: &JNIEnv,
        scenario_id_utf8: jbyteArray,
        root_branch_id_utf8: jbyteArray,
        created_at_unix_ms: jlong,
        timeline_semantics: jint,
        live_updates_enabled: jboolean,
        cpu_backend: jint,
        gpu_backend: jint,
    ) -> SlSessionCreateResult {
        let (scenario_id, scenario_id_len) = match decode_java_id_bytes(env, scenario_id_utf8) {
            Ok(value) => value,
            Err(result) => return create_error_session(result),
        };

        let (root_branch_id, root_branch_id_len) =
            match decode_java_id_bytes(env, root_branch_id_utf8) {
                Ok(value) => value,
                Err(result) => return create_error_session(result),
            };

        let created_at_unix_ms = match created_at_unix_ms.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let timeline_semantics = match timeline_semantics.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let cpu_backend = match cpu_backend.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        let gpu_backend = match gpu_backend.try_into() {
            Ok(value) => value,
            Err(_) => return create_error_session(status(SlStatusCode::InvalidArgument)),
        };

        sl_v2_session_create(SlSessionCreateParams {
            scenario_id,
            scenario_id_len,
            root_branch_id,
            root_branch_id_len,
            created_at_unix_ms,
            timeline_semantics,
            live_updates_enabled: u8::from(live_updates_enabled != 0),
            cpu_backend,
            gpu_backend,
        })
    }

    #[allow(clippy::too_many_arguments)]
    fn build_apply_command_result(
        env: &JNIEnv,
        handle: jlong,
        kind: jint,
        body_id_utf8: jbyteArray,
        body_class: jint,
        body_position_x: jdouble,
        body_position_y: jdouble,
        body_position_z: jdouble,
        body_velocity_x: jdouble,
        body_velocity_y: jdouble,
        body_velocity_z: jdouble,
        body_mass_kg: jdouble,
        body_radius_m: jdouble,
        checkpoint_id_utf8: jbyteArray,
        checkpoint_label_utf8: jbyteArray,
        new_branch_id_utf8: jbyteArray,
        observer_mode: jint,
        delta_seconds: f64,
        sim_seconds_per_real_second: f64,
        recorded_at_unix_ms: jlong,
    ) -> SlSessionSnapshotSummaryResult {
        let runtime_handle = match u64::try_from(handle) {
            Ok(value) if value != 0 => SlRuntimeHandle { raw: value },
            _ => {
                return SlSessionSnapshotSummaryResult {
                    result: status(SlStatusCode::InvalidArgument),
                    summary: super::empty_snapshot_summary(),
                };
            }
        };

        let kind = match decode_command_kind(kind) {
            Ok(value) => value,
            Err(result) => return create_error_snapshot_summary(result),
        };

        let observer_mode = match decode_observer_mode(observer_mode) {
            Ok(value) => value,
            Err(result) => return create_error_snapshot_summary(result),
        };

        let (body_id, body_id_len) = match decode_optional_java_id_bytes(env, body_id_utf8) {
            Ok(value) => value,
            Err(result) => return create_error_snapshot_summary(result),
        };

        let body_class = match decode_body_class_value(body_class) {
            Ok(value) => value,
            Err(result) => return create_error_snapshot_summary(result),
        };

        let body_position = SlVector3d {
            x: body_position_x,
            y: body_position_y,
            z: body_position_z,
        };
        let body_velocity = SlVector3d {
            x: body_velocity_x,
            y: body_velocity_y,
            z: body_velocity_z,
        };

        let (checkpoint_id, checkpoint_id_len) =
            match decode_optional_java_id_bytes(env, checkpoint_id_utf8) {
                Ok(value) => value,
                Err(result) => return create_error_snapshot_summary(result),
            };

        let (checkpoint_label, checkpoint_label_len) =
            match decode_optional_java_id_bytes(env, checkpoint_label_utf8) {
                Ok(value) => value,
                Err(result) => return create_error_snapshot_summary(result),
            };

        let (new_branch_id, new_branch_id_len) =
            match decode_optional_java_id_bytes(env, new_branch_id_utf8) {
                Ok(value) => value,
                Err(result) => return create_error_snapshot_summary(result),
            };

        let recorded_at_unix_ms = match i64::try_from(recorded_at_unix_ms) {
            Ok(value) => value,
            Err(_) => return create_error_snapshot_summary(status(SlStatusCode::InvalidArgument)),
        };

        sl_v2_session_apply_command(
            runtime_handle,
            SlSessionCommand {
                kind,
                body_id,
                body_id_len,
                body_class,
                body_position,
                body_velocity,
                body_mass_kg,
                body_radius_m,
                checkpoint_id,
                checkpoint_id_len,
                checkpoint_label,
                checkpoint_label_len,
                new_branch_id,
                new_branch_id_len,
                observer_mode,
                delta_seconds,
                sim_seconds_per_real_second,
                recorded_at_unix_ms,
            },
        )
    }

    fn decode_java_id_bytes(
        env: &JNIEnv,
        value: jbyteArray,
    ) -> Result<([u8; SL_V2_ID_CAPACITY], u32), SlResult> {
        let array = unsafe { JByteArray::from_raw(value) };
        let bytes = env
            .convert_byte_array(&array)
            .map_err(|_| status(SlStatusCode::InvalidArgument))?;

        if bytes.is_empty() || bytes.len() > SL_V2_ID_CAPACITY {
            return Err(status(SlStatusCode::InvalidArgument));
        }

        let mut output = [0_u8; SL_V2_ID_CAPACITY];
        output[..bytes.len()].copy_from_slice(&bytes);
        let length = u32::try_from(bytes.len()).expect("validated Java ID length must fit u32");
        Ok((output, length))
    }

    fn decode_optional_java_id_bytes(
        env: &JNIEnv,
        value: jbyteArray,
    ) -> Result<([u8; SL_V2_ID_CAPACITY], u32), SlResult> {
        if value.is_null() {
            return Ok(([0_u8; SL_V2_ID_CAPACITY], 0));
        }

        decode_java_id_bytes(env, value)
    }

    fn decode_command_kind(value: jint) -> Result<SlCommandKind, SlResult> {
        match value {
            0 => Ok(SlCommandKind::AdvanceEpoch),
            1 => Ok(SlCommandKind::PausePlayback),
            2 => Ok(SlCommandKind::ResumePlayback),
            3 => Ok(SlCommandKind::SetPlaybackRate),
            4 => Ok(SlCommandKind::SetObserverMode),
            5 => Ok(SlCommandKind::FocusBody),
            6 => Ok(SlCommandKind::SpawnBody),
            7 => Ok(SlCommandKind::RemoveBody),
            8 => Ok(SlCommandKind::SetBodyKinematics),
            9 => Ok(SlCommandKind::CreateCheckpoint),
            10 => Ok(SlCommandKind::CreateBranchFromCheckpoint),
            11 => Ok(SlCommandKind::SeedCanonicalSolarSystem),
            _ => Err(status(SlStatusCode::InvalidArgument)),
        }
    }

    fn decode_body_class_value(value: jint) -> Result<SlBodyClass, SlResult> {
        match value {
            0 => Ok(SlBodyClass::Star),
            1 => Ok(SlBodyClass::Planet),
            2 => Ok(SlBodyClass::DwarfPlanet),
            3 => Ok(SlBodyClass::Moon),
            4 => Ok(SlBodyClass::SmallBody),
            5 => Ok(SlBodyClass::Tracer),
            6 => Ok(SlBodyClass::Spacecraft),
            7 => Ok(SlBodyClass::Custom),
            _ => Err(status(SlStatusCode::InvalidArgument)),
        }
    }

    fn decode_observer_mode(value: jint) -> Result<super::SlObserverMode, SlResult> {
        match value {
            0 => Ok(super::SlObserverMode::Free),
            1 => Ok(super::SlObserverMode::FollowSelected),
            2 => Ok(super::SlObserverMode::FollowHost),
            3 => Ok(super::SlObserverMode::SystemFrame),
            _ => Err(status(SlStatusCode::InvalidArgument)),
        }
    }

    fn create_error_session(result: SlResult) -> SlSessionCreateResult {
        SlSessionCreateResult {
            result,
            handle: SlRuntimeHandle::default(),
            runtime_info: super::empty_runtime_info(),
            snapshot_summary: super::empty_snapshot_summary(),
        }
    }

    fn create_error_snapshot_summary(result: SlResult) -> SlSessionSnapshotSummaryResult {
        SlSessionSnapshotSummaryResult {
            result,
            summary: super::empty_snapshot_summary(),
        }
    }

    fn create_native_result<'local>(
        env: &mut JNIEnv<'local>,
        result: SlResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let context = env.new_string(status_label(result.code))?;
        let context_obj = JObject::from(context);
        env.new_object(
            CLASS_NATIVE_RESULT,
            "(ILjava/lang/String;)V",
            &[
                JValue::Int(status_code_as_i32(result.code)),
                JValue::Object(&context_obj),
            ],
        )
    }

    fn create_native_create_session_result<'local>(
        env: &mut JNIEnv<'local>,
        result: &SlSessionCreateResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let native_result = create_native_result(env, result.result)?;
        env.new_object(
            CLASS_NATIVE_CREATE_SESSION_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;JIII)V",
            &[
                JValue::Object(&native_result),
                JValue::Long(i64::try_from(result.handle.raw).unwrap_or(i64::MAX)),
                JValue::Int(i32::try_from(result.runtime_info.abi_version).unwrap_or(i32::MAX)),
                JValue::Int(result.runtime_info.cpu_backend as i32),
                JValue::Int(result.runtime_info.gpu_backend as i32),
            ],
        )
    }

    fn create_native_runtime_info_result<'local>(
        env: &mut JNIEnv<'local>,
        result: super::SlRuntimeInfoResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let native_result = create_native_result(env, result.result)?;
        env.new_object(
            CLASS_NATIVE_RUNTIME_INFO_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;IIIIJII)V",
            &[
                JValue::Object(&native_result),
                JValue::Int(i32::try_from(result.info.abi_version).unwrap_or(i32::MAX)),
                JValue::Int(result.info.requested_cpu_backend as i32),
                JValue::Int(result.info.cpu_backend as i32),
                JValue::Int(result.info.gpu_backend as i32),
                JValue::Long(i64::try_from(result.info.cpu_feature_flags).unwrap_or(i64::MAX)),
                JValue::Int(i32::try_from(result.info.cpu_solver_path).unwrap_or(i32::MAX)),
                JValue::Int(i32::try_from(result.info.cpu_fallback_code).unwrap_or(i32::MAX)),
            ],
        )
    }

    fn create_native_snapshot_summary_result<'local>(
        env: &mut JNIEnv<'local>,
        result: SlSessionSnapshotSummaryResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let native_result = create_native_result(env, result.result)?;
        let scenario_id = env.new_string(id_from_summary(
            &result.summary.scenario_id,
            result.summary.scenario_id_len,
        ))?;
        let active_branch_id = env.new_string(id_from_summary(
            &result.summary.active_branch_id,
            result.summary.active_branch_id_len,
        ))?;
        let scenario_id_obj = JObject::from(scenario_id);
        let active_branch_id_obj = JObject::from(active_branch_id);

        env.new_object(
            CLASS_NATIVE_SNAPSHOT_SUMMARY_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;Ljava/lang/String;Ljava/lang/String;IDZDII)V",
            &[
                JValue::Object(&native_result),
                JValue::Object(&scenario_id_obj),
                JValue::Object(&active_branch_id_obj),
                JValue::Int(i32::try_from(result.summary.body_count).unwrap_or(i32::MAX)),
                JValue::Double(result.summary.epoch_seconds),
                JValue::Bool(result.summary.paused),
                JValue::Double(result.summary.sim_seconds_per_real_second),
                JValue::Int(result.summary.observer_mode as i32),
                JValue::Int(result.summary.timeline_semantics as i32),
            ],
        )
    }

    fn create_native_vulkan_scene_packet_result<'local>(
        env: &mut JNIEnv<'local>,
        result: SlVulkanScenePacketResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let native_result = create_native_result(env, result.result)?;
        let packet_object = if result.result.code == SlStatusCode::Ok && result.handle.raw != 0 {
            create_native_vulkan_scene_packet(env, result)?
        } else {
            JObject::null()
        };

        env.new_object(
            CLASS_NATIVE_VULKAN_SCENE_PACKET_RESULT,
            "(Lcom/sednalabs/solarlab/runtime/NativeResult;Lcom/sednalabs/solarlab/runtime/NativeVulkanScenePacket;)V",
            &[
                JValue::Object(&native_result),
                JValue::Object(&packet_object),
            ],
        )
    }

    fn create_native_vulkan_scene_packet<'local>(
        env: &mut JNIEnv<'local>,
        result: SlVulkanScenePacketResult,
    ) -> jni::errors::Result<JObject<'local>> {
        let camera = create_native_vulkan_camera_packet(env, result.info.camera)?;
        let diagnostics = create_native_render_diagnostics(env, result.info.diagnostics)?;
        let scene_revision = env.new_string(bytes_view_to_string(result.info.scene_revision))?;
        let scene_revision_obj = JObject::from(scene_revision);
        let provenance_source = new_nullable_java_string(env, result.info.provenance_source)?;
        let provenance_version = new_nullable_java_string(env, result.info.provenance_version)?;
        let provenance_manifest_id =
            new_nullable_java_string(env, result.info.provenance_manifest_id)?;
        let provenance_manifest_digest =
            new_nullable_java_string(env, result.info.provenance_manifest_digest)?;
        let provenance_package_digest =
            new_nullable_java_string(env, result.info.provenance_package_digest)?;
        let body_instances =
            create_buffer_object(env, result.handle, SlVulkanSceneBufferKind::BodyInstances)?;
        let tracer_instances =
            create_buffer_object(env, result.handle, SlVulkanSceneBufferKind::TracerInstances)?;
        let trail_spans =
            create_buffer_object(env, result.handle, SlVulkanSceneBufferKind::TrailSpans)?;
        let trail_vertices =
            create_buffer_object(env, result.handle, SlVulkanSceneBufferKind::TrailVertices)?;
        let directional_lights = create_buffer_object(
            env,
            result.handle,
            SlVulkanSceneBufferKind::DirectionalLights,
        )?;

        env.new_object(
            CLASS_NATIVE_VULKAN_SCENE_PACKET,
            "(JLjava/lang/String;DIILcom/sednalabs/solarlab/runtime/NativeVulkanCameraPacket;IIIIILcom/sednalabs/solarlab/runtime/NativeRenderDiagnostics;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)V",
            &[
                JValue::Long(i64::try_from(result.handle.raw).unwrap_or(i64::MAX)),
                JValue::Object(&scene_revision_obj),
                JValue::Double(result.info.epoch_seconds),
                JValue::Int(result.info.observer_mode as i32),
                JValue::Int(result.info.timeline_semantics as i32),
                JValue::Object(&camera),
                JValue::Int(i32::try_from(result.info.body_instance_count).unwrap_or(i32::MAX)),
                JValue::Int(
                    i32::try_from(result.info.tracer_instance_count).unwrap_or(i32::MAX),
                ),
                JValue::Int(i32::try_from(result.info.trail_span_count).unwrap_or(i32::MAX)),
                JValue::Int(i32::try_from(result.info.trail_vertex_count).unwrap_or(i32::MAX)),
                JValue::Int(
                    i32::try_from(result.info.directional_light_count).unwrap_or(i32::MAX),
                ),
                JValue::Object(&diagnostics),
                JValue::Object(&provenance_source),
                JValue::Object(&provenance_version),
                JValue::Object(&provenance_manifest_id),
                JValue::Object(&provenance_manifest_digest),
                JValue::Object(&provenance_package_digest),
                JValue::Object(&body_instances),
                JValue::Object(&tracer_instances),
                JValue::Object(&trail_spans),
                JValue::Object(&trail_vertices),
                JValue::Object(&directional_lights),
            ],
        )
    }

    fn create_native_vulkan_camera_packet<'local>(
        env: &mut JNIEnv<'local>,
        camera: super::SlVulkanCameraPacket,
    ) -> jni::errors::Result<JObject<'local>> {
        env.new_object(
            CLASS_NATIVE_VULKAN_CAMERA_PACKET,
            "(DDDFFFFFFFFFFF)V",
            &[
                JValue::Double(camera.frame_origin_m.x),
                JValue::Double(camera.frame_origin_m.y),
                JValue::Double(camera.frame_origin_m.z),
                JValue::Float(camera.position_from_origin_m.x),
                JValue::Float(camera.position_from_origin_m.y),
                JValue::Float(camera.position_from_origin_m.z),
                JValue::Float(camera.target_from_origin_m.x),
                JValue::Float(camera.target_from_origin_m.y),
                JValue::Float(camera.target_from_origin_m.z),
                JValue::Float(camera.up.x),
                JValue::Float(camera.up.y),
                JValue::Float(camera.up.z),
                JValue::Float(camera.vertical_fov_degrees),
                JValue::Float(camera.exposure),
            ],
        )
    }

    fn create_native_render_diagnostics<'local>(
        env: &mut JNIEnv<'local>,
        diagnostics: super::SlRenderDiagnostics,
    ) -> jni::errors::Result<JObject<'local>> {
        env.new_object(
            CLASS_NATIVE_RENDER_DIAGNOSTICS,
            "(JFFI)V",
            &[
                JValue::Long(i64::try_from(diagnostics.frame_number).unwrap_or(i64::MAX)),
                JValue::Float(diagnostics.cpu_extract_ms),
                JValue::Float(diagnostics.gpu_upload_ms),
                JValue::Int(i32::try_from(diagnostics.dropped_frames).unwrap_or(i32::MAX)),
            ],
        )
    }

    fn create_buffer_object<'local>(
        env: &mut JNIEnv<'local>,
        handle: SlRenderPacketHandle,
        kind: SlVulkanSceneBufferKind,
    ) -> jni::errors::Result<JObject<'local>> {
        let view_result = sl_v2_vulkan_scene_packet_buffer(handle, kind);
        if view_result.result.code != SlStatusCode::Ok || view_result.view.data.is_null() {
            return Ok(JObject::null());
        }

        new_direct_buffer(env, view_result.view)
    }

    fn new_direct_buffer<'local>(
        env: &mut JNIEnv<'local>,
        view: SlBufferView,
    ) -> jni::errors::Result<JObject<'local>> {
        let capacity = usize::try_from(view.size_bytes).unwrap_or(usize::MAX);
        let buffer = unsafe { env.new_direct_byte_buffer(view.data.cast_mut().cast(), capacity)? };
        Ok(JObject::from(buffer))
    }

    fn new_nullable_java_string<'local>(
        env: &mut JNIEnv<'local>,
        view: super::SlBytesView,
    ) -> jni::errors::Result<JObject<'local>> {
        if view.data.is_null() || view.length == 0 {
            return Ok(JObject::null());
        }

        let value = bytes_view_to_string(view);
        let string = env.new_string(value)?;
        Ok(JObject::from(string))
    }

    fn bytes_view_to_string(view: super::SlBytesView) -> String {
        if view.data.is_null() || view.length == 0 {
            return String::new();
        }

        let length = usize::try_from(view.length).unwrap_or(0);
        if length == 0 {
            return String::new();
        }

        let bytes = unsafe { std::slice::from_raw_parts(view.data, length) };
        String::from_utf8(bytes.to_vec()).unwrap_or_default()
    }

    fn id_from_summary(bytes: &[u8; SL_V2_ID_CAPACITY], length: u32) -> String {
        super::decode_identifier(bytes, length).unwrap_or_default()
    }

    fn status_label(code: SlStatusCode) -> &'static str {
        match code {
            SlStatusCode::Ok => "ok",
            SlStatusCode::InvalidArgument => "invalid argument",
            SlStatusCode::NotReady => "not ready",
            SlStatusCode::InternalError => "internal error",
        }
    }

    fn status_code_as_i32(code: SlStatusCode) -> i32 {
        match code {
            SlStatusCode::Ok => 0,
            SlStatusCode::InvalidArgument => 1,
            SlStatusCode::NotReady => 2,
            SlStatusCode::InternalError => 3,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::id_from_summary;
        use crate::SL_V2_ID_CAPACITY;

        #[test]
        fn id_from_summary_returns_utf8_when_valid() {
            let mut bytes = [0_u8; SL_V2_ID_CAPACITY];
            bytes[..4].copy_from_slice(b"main");
            assert_eq!(id_from_summary(&bytes, 4), "main");
        }

        #[test]
        fn id_from_summary_falls_back_to_empty_when_invalid() {
            let mut bytes = [0_u8; SL_V2_ID_CAPACITY];
            bytes[0] = 0xFF;
            assert_eq!(id_from_summary(&bytes, 1), "");
        }
    }
}

#[cfg(test)]
mod tests {
    use std::mem::{align_of, offset_of, size_of};

    use solarlab_domain::{BodyClass, BodyId, Vector3d};
    use solarlab_hardware::{GpuBackend, GpuBackendStateFamily};
    use solarlab_runtime::{BodyState, WorldCommand};

    use super::{
        decode_identifier, registry, sl_v2_abi_version, sl_v2_session_apply_command,
        sl_v2_session_create, sl_v2_session_destroy, sl_v2_session_export_vulkan_scene,
        sl_v2_session_refresh, sl_v2_session_runtime_info, sl_v2_session_snapshot_summary,
        sl_v2_vulkan_scene_packet_buffer, sl_v2_vulkan_scene_packet_release, SlBodyClass,
        SlCommandKind, SlCpuBackend, SlGpuBackend, SlObserverMode, SlRuntimeInfo,
        SlRuntimeInfoResult, SlSessionCommand, SlSessionCreateParams, SlStatusCode,
        SlTimelineSemantics, SlVector3d, SlVulkanBodyInstance, SlVulkanSceneBufferKind,
        SlVulkanTrailSpan, SL_V2_ID_CAPACITY, SOLARLAB_V2_ABI_VERSION,
    };

    #[test]
    fn abi_version_matches_constant() {
        assert_eq!(sl_v2_abi_version(), SOLARLAB_V2_ABI_VERSION);
    }

    #[test]
    fn runtime_info_layout_matches_c_header_contract() {
        assert_eq!(size_of::<SlRuntimeInfo>(), 32);
        assert_eq!(align_of::<SlRuntimeInfo>(), 8);
        assert_eq!(offset_of!(SlRuntimeInfo, abi_version), 0);
        assert_eq!(offset_of!(SlRuntimeInfo, requested_cpu_backend), 4);
        assert_eq!(offset_of!(SlRuntimeInfo, cpu_backend), 8);
        assert_eq!(offset_of!(SlRuntimeInfo, gpu_backend), 12);
        assert_eq!(offset_of!(SlRuntimeInfo, cpu_feature_flags), 16);
        assert_eq!(offset_of!(SlRuntimeInfo, cpu_solver_path), 24);
        assert_eq!(offset_of!(SlRuntimeInfo, cpu_fallback_code), 28);

        assert_eq!(size_of::<SlRuntimeInfoResult>(), 40);
        assert_eq!(align_of::<SlRuntimeInfoResult>(), 8);
        assert_eq!(offset_of!(SlRuntimeInfoResult, result), 0);
        assert_eq!(offset_of!(SlRuntimeInfoResult, info), 8);
    }

    #[test]
    fn create_query_and_destroy_session() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        assert_ne!(create.handle.raw, 0);

        assert_eq!(
            create.runtime_info.requested_cpu_backend,
            SlCpuBackend::ReferenceScalar
        );
        assert_eq!(
            create.runtime_info.cpu_backend,
            SlCpuBackend::ReferenceScalar
        );
        assert_eq!(create.runtime_info.gpu_backend, SlGpuBackend::None);
        assert_eq!(create.runtime_info.cpu_solver_path, 0);
        assert_eq!(create.runtime_info.cpu_fallback_code, 0);

        let summary = sl_v2_session_snapshot_summary(create.handle);
        assert_eq!(summary.result.code, SlStatusCode::Ok);
        assert_eq!(summary.summary.body_count, 0);
        assert_eq!(summary.summary.paused, 1);
        assert_eq!(summary.summary.active_checkpoint_present, 0);
        assert_eq!(
            summary.summary.timeline_semantics,
            SlTimelineSemantics::BranchedSandbox
        );

        let runtime_info = sl_v2_session_runtime_info(create.handle);
        assert_eq!(runtime_info.result.code, SlStatusCode::Ok);
        assert_eq!(
            runtime_info.info.requested_cpu_backend,
            SlCpuBackend::ReferenceScalar
        );
        assert_eq!(runtime_info.info.cpu_backend, SlCpuBackend::ReferenceScalar);
        assert_eq!(runtime_info.info.gpu_backend, SlGpuBackend::None);
        assert_eq!(runtime_info.info.cpu_solver_path, 0);
        assert_eq!(runtime_info.info.cpu_fallback_code, 0);

        let destroy = sl_v2_session_destroy(create.handle);
        assert_eq!(destroy.code, SlStatusCode::Ok);

        let stale_summary = sl_v2_session_snapshot_summary(create.handle);
        assert_eq!(stale_summary.result.code, SlStatusCode::NotReady);
    }

    #[test]
    fn create_session_supports_opencl_gpu_backend() {
        let mut params = new_params("sol-system", "main");
        params.gpu_backend = 4;

        let create = sl_v2_session_create(params);
        assert_eq!(create.result.code, SlStatusCode::Ok);
        assert_ne!(create.handle.raw, 0);
        assert_eq!(create.runtime_info.gpu_backend, SlGpuBackend::OpenCl);

        let runtime_info = sl_v2_session_runtime_info(create.handle);
        assert_eq!(runtime_info.result.code, SlStatusCode::Ok);
        assert_eq!(runtime_info.info.gpu_backend, SlGpuBackend::OpenCl);

        let destroy = sl_v2_session_destroy(create.handle);
        assert_eq!(destroy.code, SlStatusCode::Ok);
    }

    #[test]
    fn runtime_info_preserves_requested_and_effective_arm64_cpu_truth() {
        let mut params = new_params("sol-system", "main");
        params.cpu_backend = 1;

        let create = sl_v2_session_create(params);
        assert_eq!(create.result.code, SlStatusCode::Ok);
        assert_eq!(
            create.runtime_info.requested_cpu_backend,
            SlCpuBackend::SimdArm64
        );

        let has_neon = create.runtime_info.cpu_feature_flags & 1 != 0;
        if cfg!(target_arch = "aarch64") && has_neon {
            assert_eq!(create.runtime_info.cpu_backend, SlCpuBackend::SimdArm64);
            assert_eq!(create.runtime_info.cpu_solver_path, 1);
            assert_eq!(create.runtime_info.cpu_fallback_code, 0);
        } else {
            assert_eq!(
                create.runtime_info.cpu_backend,
                SlCpuBackend::ReferenceScalar
            );
            assert_eq!(create.runtime_info.cpu_solver_path, 0);
            if cfg!(target_arch = "aarch64") {
                assert_eq!(create.runtime_info.cpu_fallback_code, 2);
            } else {
                assert_eq!(create.runtime_info.cpu_fallback_code, 1);
            }
        }

        let runtime_info = sl_v2_session_runtime_info(create.handle);
        assert_eq!(runtime_info.result.code, SlStatusCode::Ok);
        assert_eq!(runtime_info.info, create.runtime_info);

        let destroy = sl_v2_session_destroy(create.handle);
        assert_eq!(destroy.code, SlStatusCode::Ok);
    }

    #[test]
    fn opencl_session_uses_dual_backend_family_assignments() {
        let mut params = new_params("sol-system", "main");
        params.gpu_backend = 4;

        let create = sl_v2_session_create(params);
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let registry_lock = registry().lock().expect("session registry lock");
        let session = registry_lock
            .sessions
            .get(&create.handle.raw)
            .expect("session should exist");
        let profile = &session.runtime.snapshot().hardware_profile;

        assert!(profile.has_one_owner_per_state_family());
        assert!(profile
            .active_gpu_backends()
            .iter()
            .any(
                |assignment| assignment.state_family == GpuBackendStateFamily::Rendering
                    && assignment.backend == GpuBackend::Vulkan
            ));
        assert!(profile
            .active_gpu_backends()
            .iter()
            .any(
                |assignment| assignment.state_family == GpuBackendStateFamily::Simulation
                    && assignment.backend == GpuBackend::OpenCl
            ));
        assert!(
            profile
                .acceleration_modes
                .iter()
                .any(|mode| mode == "dual-gpu"),
            "OpenCL sessions should advertise dual-gpu acceleration mode"
        );
        assert!(
            profile
                .acceleration_modes
                .iter()
                .any(|mode| mode == "opencl-long-horizon"),
            "OpenCL sessions should expose long-horizon workload mode"
        );
        assert!(
            profile
                .acceleration_modes
                .iter()
                .any(|mode| mode == "interop-error-budget-v1"),
            "OpenCL sessions should expose interop error-budget policy mode"
        );
        assert!(profile.has_explicit_opencl_workload_surface());
        assert!(
            profile.interop_error_budget_policy().is_some(),
            "OpenCL sessions should surface a concrete interop error-budget policy"
        );

        drop(registry_lock);
        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn rejects_invalid_create_parameters() {
        let mut params = new_params("ok", "main");
        params.cpu_backend = 9;

        let create = sl_v2_session_create(params);
        assert_eq!(create.result.code, SlStatusCode::InvalidArgument);
        assert_eq!(create.handle.raw, 0);

        let mut invalid_utf8 = new_params("ok", "main");
        invalid_utf8.scenario_id[0] = 0xFF;

        let create = sl_v2_session_create(invalid_utf8);
        assert_eq!(create.result.code, SlStatusCode::InvalidArgument);
    }

    #[test]
    fn rejects_destroy_for_unknown_handle() {
        let destroy = sl_v2_session_destroy(super::SlRuntimeHandle { raw: 999_999 });
        assert_eq!(destroy.code, SlStatusCode::NotReady);
    }

    #[test]
    fn exports_vulkan_packet_buffers_and_releases_them() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        seed_runtime_with_body(create.handle, "earth", 10.0);

        let packet = sl_v2_session_export_vulkan_scene(create.handle);
        assert_eq!(packet.result.code, SlStatusCode::Ok);
        assert_ne!(packet.handle.raw, 0);
        assert_eq!(packet.info.body_instance_count, 1);
        assert!(packet.info.scene_revision.length > 0);

        let body_view =
            sl_v2_vulkan_scene_packet_buffer(packet.handle, SlVulkanSceneBufferKind::BodyInstances);
        assert_eq!(body_view.result.code, SlStatusCode::Ok);
        assert_eq!(
            body_view.view.stride_bytes,
            u32::try_from(size_of::<SlVulkanBodyInstance>()).expect("stride fits")
        );
        assert_eq!(body_view.view.element_count, 1);
        assert_eq!(body_view.view.size_bytes, body_view.view.stride_bytes);

        let release = sl_v2_vulkan_scene_packet_release(packet.handle);
        assert_eq!(release.code, SlStatusCode::Ok);

        let stale_view =
            sl_v2_vulkan_scene_packet_buffer(packet.handle, SlVulkanSceneBufferKind::BodyInstances);
        assert_eq!(stale_view.result.code, SlStatusCode::NotReady);

        let stale_release = sl_v2_vulkan_scene_packet_release(packet.handle);
        assert_eq!(stale_release.code, SlStatusCode::NotReady);

        let destroy = sl_v2_session_destroy(create.handle);
        assert_eq!(destroy.code, SlStatusCode::Ok);
    }

    #[test]
    fn exported_vulkan_packet_matches_direct_adapter_output() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        seed_runtime_with_body(create.handle, "earth", 10.0);
        seed_runtime_with_body(create.handle, "moon", 12.0);
        focus_body(create.handle, "moon");

        let direct_packet = {
            let mut registry = registry().lock().expect("session registry lock");
            let session = registry
                .sessions
                .get_mut(&create.handle.raw)
                .expect("session exists");
            let scene = session.runtime.render_scene();
            session.vulkan_scene_adapter.adapt(&scene)
        };

        let exported = sl_v2_session_export_vulkan_scene(create.handle);
        assert_eq!(exported.result.code, SlStatusCode::Ok);
        assert_eq!(exported.info.epoch_seconds, direct_packet.epoch_seconds);
        assert_eq!(
            exported.info.body_instance_count as usize,
            direct_packet.body_instances.len()
        );
        assert_eq!(
            exported.info.directional_light_count as usize,
            direct_packet.directional_lights.len()
        );
        assert_eq!(
            exported.info.camera.frame_origin_m.x,
            direct_packet.camera.frame_origin_m.x
        );
        assert_eq!(
            exported.info.camera.target_from_origin_m.z,
            direct_packet.camera.target_from_origin_m.z
        );

        let body_view = sl_v2_vulkan_scene_packet_buffer(
            exported.handle,
            SlVulkanSceneBufferKind::BodyInstances,
        );
        assert_eq!(body_view.result.code, SlStatusCode::Ok);
        let exported_bodies = unsafe {
            std::slice::from_raw_parts(
                body_view.view.data.cast::<SlVulkanBodyInstance>(),
                usize::try_from(body_view.view.element_count).expect("element count fits"),
            )
        };
        assert_eq!(exported_bodies.len(), direct_packet.body_instances.len());
        assert_eq!(
            exported_bodies[1].position_from_origin_m.z,
            direct_packet.body_instances[1].position_from_origin_m.z
        );
        assert_eq!(exported_bodies[1].selected, 1);
        assert_eq!(
            decode_identifier(&exported_bodies[1].body_id, exported_bodies[1].body_id_len)
                .expect("body id should decode"),
            direct_packet.body_instances[1].body_id.0,
        );

        let trail_view =
            sl_v2_vulkan_scene_packet_buffer(exported.handle, SlVulkanSceneBufferKind::TrailSpans);
        assert_eq!(trail_view.result.code, SlStatusCode::Ok);
        let exported_trails = unsafe {
            std::slice::from_raw_parts(
                trail_view.view.data.cast::<SlVulkanTrailSpan>(),
                usize::try_from(trail_view.view.element_count).expect("element count fits"),
            )
        };
        assert_eq!(exported_trails.len(), direct_packet.trail_spans.len());
        assert_eq!(
            decode_identifier(
                &exported_trails[1].source_body_id,
                exported_trails[1].source_body_id_len,
            )
            .expect("trail source body id should decode"),
            direct_packet.trail_spans[1].source_body_id.0,
        );
        assert_eq!(
            exported_trails[1].head_highlighted,
            u32::from(direct_packet.trail_spans[1].head_highlighted),
        );
        assert_eq!(
            exported_trails[1].family as u32,
            direct_packet.trail_spans[1].family as u32,
        );

        assert_eq!(
            sl_v2_vulkan_scene_packet_release(exported.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn export_reuses_session_vulkan_adapter_cache_until_scene_revision_changes() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        seed_runtime_with_body(create.handle, "earth", 10.0);
        seed_runtime_with_body(create.handle, "moon", 12.0);
        focus_body(create.handle, "earth");

        let first_export = sl_v2_session_export_vulkan_scene(create.handle);
        assert_eq!(first_export.result.code, SlStatusCode::Ok);
        let first_scene_revision = {
            let registry = registry().lock().expect("session registry lock");
            let session = registry.get(create.handle).expect("session exists");
            let revision = session.vulkan_scene_adapter.cached_scene_revision();
            revision_prefix(revision.unwrap_or_default()).to_owned()
        };

        let second_export = sl_v2_session_export_vulkan_scene(create.handle);
        assert_eq!(second_export.result.code, SlStatusCode::Ok);
        let second_scene_revision = {
            let registry = registry().lock().expect("session registry lock");
            let session = registry.get(create.handle).expect("session exists");
            let revision = session.vulkan_scene_adapter.cached_scene_revision();
            revision_prefix(revision.unwrap_or_default()).to_owned()
        };

        assert_eq!(first_scene_revision, second_scene_revision);

        assert_eq!(
            sl_v2_session_apply_command(
                create.handle,
                test_session_command(SlCommandKind::FocusBody, |command| {
                    let mut body_id = [0_u8; SL_V2_ID_CAPACITY];
                    body_id[..4].copy_from_slice(b"moon");
                    command.body_id = body_id;
                    command.body_id_len = 4;
                }),
            )
            .result
            .code,
            SlStatusCode::Ok
        );

        let third_export = sl_v2_session_export_vulkan_scene(create.handle);
        assert_eq!(third_export.result.code, SlStatusCode::Ok);
        let third_scene_revision = {
            let registry = registry().lock().expect("session registry lock");
            let session = registry.get(create.handle).expect("session exists");
            let revision = session.vulkan_scene_adapter.cached_scene_revision();
            revision_prefix(revision.unwrap_or_default()).to_owned()
        };

        assert_ne!(first_scene_revision, third_scene_revision);

        assert_eq!(
            sl_v2_vulkan_scene_packet_release(first_export.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(
            sl_v2_vulkan_scene_packet_release(second_export.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(
            sl_v2_vulkan_scene_packet_release(third_export.handle).code,
            SlStatusCode::Ok
        );
        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn rejects_export_for_unknown_session_handle() {
        let packet = sl_v2_session_export_vulkan_scene(super::SlRuntimeHandle { raw: 777_777 });
        assert_eq!(packet.result.code, SlStatusCode::NotReady);
        assert_eq!(packet.handle.raw, 0);
    }

    #[test]
    fn apply_command_advance_epoch_updates_snapshot_and_refresh() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let command_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::AdvanceEpoch, |command| {
                command.delta_seconds = 12.5;
            }),
        );
        assert_eq!(command_result.result.code, SlStatusCode::Ok);
        assert_eq!(command_result.summary.epoch_seconds, 12.5);

        let refresh_result = sl_v2_session_refresh(create.handle);
        assert_eq!(refresh_result.result.code, SlStatusCode::Ok);
        assert_eq!(refresh_result.summary.epoch_seconds, 12.5);

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_focus_rejects_unknown_body() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let mut body_id = [0_u8; SL_V2_ID_CAPACITY];
        body_id[..5].copy_from_slice(b"earth");
        let command_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::FocusBody, |command| {
                command.body_id = body_id;
                command.body_id_len = 5;
            }),
        );
        assert_eq!(command_result.result.code, SlStatusCode::InvalidArgument);

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_spawn_body_adds_body() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let mut body_id = [0_u8; SL_V2_ID_CAPACITY];
        body_id[..4].copy_from_slice(b"ship");
        let command_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::SpawnBody, |command| {
                command.body_id = body_id;
                command.body_id_len = 4;
                command.body_class = SlBodyClass::Spacecraft;
                command.body_position = SlVector3d {
                    x: 4.0,
                    y: 3.0,
                    z: 2.0,
                };
                command.body_velocity = SlVector3d {
                    x: 0.5,
                    y: 0.4,
                    z: 0.0,
                };
                command.body_mass_kg = 4.2;
                command.body_radius_m = 0.6;
            }),
        );
        assert_eq!(command_result.result.code, SlStatusCode::Ok);
        assert_eq!(command_result.summary.body_count, 1);

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_remove_body_removes_existing_body() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let spawn = test_session_command(SlCommandKind::SpawnBody, |command| {
            let mut body_id = [0_u8; SL_V2_ID_CAPACITY];
            body_id[..4].copy_from_slice(b"ship");
            command.body_id = body_id;
            command.body_id_len = 4;
            command.body_class = SlBodyClass::Spacecraft;
        });
        let spawn_result = sl_v2_session_apply_command(create.handle, spawn);
        assert_eq!(spawn_result.result.code, SlStatusCode::Ok);
        assert_eq!(spawn_result.summary.body_count, 1);

        let mut remove_body_id = [0_u8; SL_V2_ID_CAPACITY];
        remove_body_id[..4].copy_from_slice(b"ship");
        let remove_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::RemoveBody, |command| {
                command.body_id = remove_body_id;
                command.body_id_len = 4;
            }),
        );
        assert_eq!(remove_result.result.code, SlStatusCode::Ok);
        assert_eq!(remove_result.summary.body_count, 0);

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_set_body_kinematics_mutates_body_state() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);
        seed_runtime_with_body(create.handle, "ship", 1.0);

        let command_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::SetBodyKinematics, |command| {
                let mut body_id = [0_u8; SL_V2_ID_CAPACITY];
                body_id[..4].copy_from_slice(b"ship");
                command.body_id = body_id;
                command.body_id_len = 4;
                command.body_position = SlVector3d {
                    x: 10.0,
                    y: 20.0,
                    z: 30.0,
                };
                command.body_velocity = SlVector3d {
                    x: 0.5,
                    y: 1.0,
                    z: -1.0,
                };
            }),
        );
        assert_eq!(command_result.result.code, SlStatusCode::Ok);

        let runtime = {
            let registry = registry().lock().expect("session registry lock");
            let session = registry.get(create.handle).expect("session exists");
            session.runtime.snapshot()
        };
        let body = runtime
            .bodies
            .iter()
            .find(|value| value.body_id == BodyId("ship".to_owned()))
            .expect("body should exist");
        assert_eq!(body.position_m.x, 10.0);
        assert_eq!(body.position_m.y, 20.0);
        assert_eq!(body.position_m.z, 30.0);
        assert_eq!(body.velocity_mps.x, 0.5);
        assert_eq!(body.velocity_mps.y, 1.0);
        assert_eq!(body.velocity_mps.z, -1.0);

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_create_checkpoint_and_branch_from_checkpoint() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let checkpoint_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::CreateCheckpoint, |command| {
                let mut checkpoint_id = [0_u8; SL_V2_ID_CAPACITY];
                checkpoint_id[..2].copy_from_slice(b"cp");
                command.checkpoint_id = checkpoint_id;
                command.checkpoint_id_len = 2;
                let mut checkpoint_label = [0_u8; SL_V2_ID_CAPACITY];
                checkpoint_label[..8].copy_from_slice(b"baseline");
                command.checkpoint_label = checkpoint_label;
                command.checkpoint_label_len = 8;
            }),
        );
        assert_eq!(checkpoint_result.result.code, SlStatusCode::Ok);

        let branch_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::CreateBranchFromCheckpoint, |command| {
                let mut checkpoint_id = [0_u8; SL_V2_ID_CAPACITY];
                checkpoint_id[..2].copy_from_slice(b"cp");
                command.checkpoint_id = checkpoint_id;
                command.checkpoint_id_len = 2;
                let mut new_branch_id = [0_u8; SL_V2_ID_CAPACITY];
                new_branch_id[..6].copy_from_slice(b"branch");
                command.new_branch_id = new_branch_id;
                command.new_branch_id_len = 6;
            }),
        );
        assert_eq!(branch_result.result.code, SlStatusCode::Ok);
        assert_eq!(
            super::decode_identifier(
                &branch_result.summary.active_branch_id,
                branch_result.summary.active_branch_id_len,
            )
            .expect("branch id should decode"),
            "branch"
        );

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    #[test]
    fn apply_command_seed_canonical_solar_system_populates_once() {
        let create = sl_v2_session_create(new_params("sol-system", "main"));
        assert_eq!(create.result.code, SlStatusCode::Ok);

        let seed_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::SeedCanonicalSolarSystem, |_command| {}),
        );
        assert_eq!(seed_result.result.code, SlStatusCode::Ok);
        assert!(seed_result.summary.body_count > 0);
        assert_eq!(seed_result.summary.body_count, 365);

        let second_seed_result = sl_v2_session_apply_command(
            create.handle,
            test_session_command(SlCommandKind::SeedCanonicalSolarSystem, |_command| {}),
        );
        assert_eq!(second_seed_result.result.code, SlStatusCode::Ok);
        assert_eq!(
            second_seed_result.summary.body_count,
            seed_result.summary.body_count
        );

        assert_eq!(sl_v2_session_destroy(create.handle).code, SlStatusCode::Ok);
    }

    fn seed_runtime_with_body(handle: super::SlRuntimeHandle, body_id: &str, position_x: f64) {
        let mut registry = registry().lock().expect("session registry lock");
        let body = BodyState {
            body_id: BodyId(body_id.to_owned()),
            body_class: BodyClass::Planet,
            mass_kg: 1.0,
            radius_m: 1.0,
            position_m: Vector3d {
                x: position_x,
                y: 0.0,
                z: 0.0,
            },
            velocity_mps: Vector3d::default(),
        };
        let session = registry
            .sessions
            .get_mut(&handle.raw)
            .expect("session exists");
        let events = session
            .runtime
            .apply_command(WorldCommand::SpawnBody { body }, 123)
            .expect("spawn body");
        assert!(!events.is_empty());
    }

    fn test_session_command(
        kind: SlCommandKind,
        mutate: impl FnOnce(&mut SlSessionCommand),
    ) -> SlSessionCommand {
        let mut command = SlSessionCommand {
            kind,
            body_id: [0_u8; SL_V2_ID_CAPACITY],
            body_id_len: 0,
            body_class: SlBodyClass::Planet,
            body_position: SlVector3d::default(),
            body_velocity: SlVector3d::default(),
            body_mass_kg: 1.0,
            body_radius_m: 1.0,
            checkpoint_id: [0_u8; SL_V2_ID_CAPACITY],
            checkpoint_id_len: 0,
            checkpoint_label: [0_u8; SL_V2_ID_CAPACITY],
            checkpoint_label_len: 0,
            new_branch_id: [0_u8; SL_V2_ID_CAPACITY],
            new_branch_id_len: 0,
            observer_mode: SlObserverMode::Free,
            delta_seconds: 0.0,
            sim_seconds_per_real_second: 0.0,
            recorded_at_unix_ms: 111,
        };

        mutate(&mut command);
        command
    }

    fn focus_body(handle: super::SlRuntimeHandle, body_id: &str) {
        let mut registry = registry().lock().expect("session registry lock");
        let session = registry
            .sessions
            .get_mut(&handle.raw)
            .expect("session exists");
        let events = session
            .runtime
            .apply_command(
                WorldCommand::FocusBody {
                    body_id: Some(BodyId(body_id.to_owned())),
                },
                124,
            )
            .expect("focus body");
        assert!(!events.is_empty());
    }

    fn revision_prefix(scene_revision: &str) -> &str {
        scene_revision.split("|diag:").next().unwrap_or_default()
    }

    fn new_params(scenario: &str, branch: &str) -> SlSessionCreateParams {
        let mut scenario_id = [0u8; SL_V2_ID_CAPACITY];
        scenario_id[..scenario.len()].copy_from_slice(scenario.as_bytes());

        let mut root_branch_id = [0u8; SL_V2_ID_CAPACITY];
        root_branch_id[..branch.len()].copy_from_slice(branch.as_bytes());

        SlSessionCreateParams {
            scenario_id,
            scenario_id_len: u32::try_from(scenario.len()).expect("scenario id length must fit"),
            root_branch_id,
            root_branch_id_len: u32::try_from(branch.len()).expect("branch id length must fit"),
            created_at_unix_ms: 123,
            timeline_semantics: 1,
            live_updates_enabled: 0,
            cpu_backend: 0,
            gpu_backend: 0,
        }
    }
}
