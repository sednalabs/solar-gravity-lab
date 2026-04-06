#ifndef SOLARLAB_V2_H
#define SOLARLAB_V2_H
// ABI contract for the canonical Rust runtime/session seam.
// Bump when ABI layout changes.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ABI version of the runtime/session contract.
#define SOLARLAB_V2_ABI_VERSION 2u
// Fixed-size inline capacity for identifier payloads carried by value in structs.
#define SL_V2_ID_CAPACITY 96u

// Opaque per-session handle. Handle value 0 is reserved and treated as invalid.
typedef struct SlRuntimeHandle {
  uint64_t raw;
} SlRuntimeHandle;

// Opaque exported scene packet handle. Release this handle to invalidate packet
// buffers and prevent dangling views.
typedef struct SlRenderPacketHandle {
  uint64_t raw;
} SlRenderPacketHandle;

typedef enum SlStatusCode {
  SL_STATUS_OK = 0,
  SL_STATUS_INVALID_ARGUMENT = 1,
  SL_STATUS_NOT_READY = 2,
  SL_STATUS_INTERNAL_ERROR = 3
} SlStatusCode;

typedef struct SlResult {
  SlStatusCode code;
  uint32_t detail_length;
} SlResult;

typedef struct SlBytesView {
  const uint8_t* data;
  uint32_t length;
} SlBytesView;

typedef struct SlBufferView {
  const void* data;
  uint32_t stride_bytes;
  uint32_t element_count;
  uint32_t size_bytes;
} SlBufferView;

typedef enum SlCpuBackend {
  SL_CPU_BACKEND_REFERENCE_SCALAR = 0,
  SL_CPU_BACKEND_SIMD_ARM64 = 1,
  SL_CPU_BACKEND_SIMD_X64 = 2
} SlCpuBackend;

typedef enum SlGpuBackend {
  SL_GPU_BACKEND_NONE = 0,
  SL_GPU_BACKEND_VULKAN = 1,
  SL_GPU_BACKEND_METAL = 2,
  SL_GPU_BACKEND_WEBGPU_CLASS = 3,
  SL_GPU_BACKEND_OPENCL = 4
} SlGpuBackend;

typedef enum SlTimelineSemantics {
  SL_TIMELINE_ABSOLUTE_EPOCH = 0,
  SL_TIMELINE_BRANCHED_SANDBOX = 1
} SlTimelineSemantics;

typedef enum SlObserverMode {
  SL_OBSERVER_FREE = 0,
  SL_OBSERVER_FOLLOW_SELECTED = 1,
  SL_OBSERVER_FOLLOW_HOST = 2,
  SL_OBSERVER_SYSTEM_FRAME = 3
} SlObserverMode;

typedef enum SlVulkanSceneBufferKind {
  SL_VULKAN_SCENE_BODY_INSTANCES = 0,
  SL_VULKAN_SCENE_TRACER_INSTANCES = 1,
  SL_VULKAN_SCENE_TRAIL_SPANS = 2,
  SL_VULKAN_SCENE_TRAIL_VERTICES = 3,
  SL_VULKAN_SCENE_DIRECTIONAL_LIGHTS = 4
} SlVulkanSceneBufferKind;

typedef enum SlCommandKind {
  SL_COMMAND_ADVANCE_EPOCH = 0,
  SL_COMMAND_PAUSE_PLAYBACK = 1,
  SL_COMMAND_RESUME_PLAYBACK = 2,
  SL_COMMAND_SET_PLAYBACK_RATE = 3,
  SL_COMMAND_SET_OBSERVER_MODE = 4,
  SL_COMMAND_FOCUS_BODY = 5,
  SL_COMMAND_SPAWN_BODY = 6,
  SL_COMMAND_REMOVE_BODY = 7,
  SL_COMMAND_SET_BODY_KINEMATICS = 8,
  SL_COMMAND_CREATE_CHECKPOINT = 9,
  SL_COMMAND_CREATE_BRANCH_FROM_CHECKPOINT = 10,
  SL_COMMAND_SEED_CANONICAL_SOLAR_SYSTEM = 11
} SlCommandKind;

typedef enum SlBodyClass {
  SL_BODY_CLASS_STAR = 0,
  SL_BODY_CLASS_PLANET = 1,
  SL_BODY_CLASS_DWARF_PLANET = 2,
  SL_BODY_CLASS_MOON = 3,
  SL_BODY_CLASS_SMALL_BODY = 4,
  SL_BODY_CLASS_TRACER = 5,
  SL_BODY_CLASS_SPACECRAFT = 6,
  SL_BODY_CLASS_CUSTOM = 7
} SlBodyClass;

typedef struct SlRuntimeInfo {
  uint32_t abi_version;
  SlCpuBackend cpu_backend;
  SlGpuBackend gpu_backend;
} SlRuntimeInfo;

typedef struct SlVector3d {
  double x;
  double y;
  double z;
} SlVector3d;

typedef struct SlPackedVec3 {
  float x;
  float y;
  float z;
} SlPackedVec3;

typedef struct SlPackedColor {
  float r;
  float g;
  float b;
  float a;
} SlPackedColor;

typedef struct SlRenderDiagnostics {
  uint64_t frame_number;
  float cpu_extract_ms;
  float gpu_upload_ms;
  uint32_t dropped_frames;
} SlRenderDiagnostics;

typedef struct SlVulkanCameraPacket {
  SlVector3d frame_origin_m;
  SlPackedVec3 position_from_origin_m;
  SlPackedVec3 target_from_origin_m;
  SlPackedVec3 up;
  float vertical_fov_degrees;
  float exposure;
} SlVulkanCameraPacket;

typedef struct SlVulkanBodyInstance {
  SlPackedVec3 position_from_origin_m;
  float radius_m;
  SlPackedColor albedo;
  float emissive_luminance;
  uint32_t selected;
} SlVulkanBodyInstance;

typedef struct SlVulkanTracerInstance {
  SlPackedVec3 position_from_origin_m;
  SlPackedColor color;
  float size_px;
} SlVulkanTracerInstance;

typedef struct SlVulkanTrailVertex {
  uint32_t trail_index;
  uint32_t sample_index;
  SlPackedVec3 position_from_origin_m;
} SlVulkanTrailVertex;

typedef struct SlVulkanTrailSpan {
  uint32_t vertex_offset;
  uint32_t vertex_count;
  SlPackedColor color;
  uint32_t max_samples;
  uint32_t head_highlighted;
  uint8_t source_body_id[SL_V2_ID_CAPACITY];
  uint32_t source_body_id_len;
} SlVulkanTrailSpan;

typedef struct SlVulkanDirectionalLight {
  SlPackedVec3 direction_ws;
  float illuminance_lux;
  SlPackedColor color;
} SlVulkanDirectionalLight;

typedef struct SlVulkanScenePacketInfo {
  SlBytesView scene_revision;
  double epoch_seconds;
  SlObserverMode observer_mode;
  SlTimelineSemantics timeline_semantics;
  SlVulkanCameraPacket camera;
  uint32_t body_instance_count;
  uint32_t tracer_instance_count;
  uint32_t trail_span_count;
  uint32_t trail_vertex_count;
  uint32_t directional_light_count;
  SlRenderDiagnostics diagnostics;
  SlBytesView provenance_source;
  SlBytesView provenance_version;
  SlBytesView provenance_manifest_id;
  SlBytesView provenance_manifest_digest;
  SlBytesView provenance_package_digest;
} SlVulkanScenePacketInfo;

typedef struct SlSessionCreateParams {
  uint8_t scenario_id[SL_V2_ID_CAPACITY];
  uint32_t scenario_id_len;
  uint8_t root_branch_id[SL_V2_ID_CAPACITY];
  uint32_t root_branch_id_len;
  int64_t created_at_unix_ms;
  uint32_t timeline_semantics;
  uint8_t live_updates_enabled;
  uint32_t cpu_backend;
  uint32_t gpu_backend;
} SlSessionCreateParams;

typedef struct SlSessionSnapshotSummary {
  uint8_t scenario_id[SL_V2_ID_CAPACITY];
  uint32_t scenario_id_len;
  uint8_t active_branch_id[SL_V2_ID_CAPACITY];
  uint32_t active_branch_id_len;
  double epoch_seconds;
  uint32_t body_count;
  uint8_t active_checkpoint_present;
  uint8_t paused;
  double sim_seconds_per_real_second;
  SlTimelineSemantics timeline_semantics;
  SlObserverMode observer_mode;
} SlSessionSnapshotSummary;

typedef struct SlSessionCommand {
  SlCommandKind kind;
  uint8_t body_id[SL_V2_ID_CAPACITY];
  uint32_t body_id_len;
  SlBodyClass body_class;
  SlVector3d body_position;
  SlVector3d body_velocity;
  double body_mass_kg;
  double body_radius_m;
  uint8_t checkpoint_id[SL_V2_ID_CAPACITY];
  uint32_t checkpoint_id_len;
  uint8_t checkpoint_label[SL_V2_ID_CAPACITY];
  uint32_t checkpoint_label_len;
  uint8_t new_branch_id[SL_V2_ID_CAPACITY];
  uint32_t new_branch_id_len;
  SlObserverMode observer_mode;
  double delta_seconds;
  double sim_seconds_per_real_second;
  int64_t recorded_at_unix_ms;
} SlSessionCommand;

// Lifecycle + diagnostics result payload types:
typedef struct SlSessionCreateResult {
  SlResult result;
  SlRuntimeHandle handle;
  SlRuntimeInfo runtime_info;
  SlSessionSnapshotSummary snapshot_summary;
} SlSessionCreateResult;

typedef struct SlRuntimeInfoResult {
  SlResult result;
  SlRuntimeInfo info;
} SlRuntimeInfoResult;

typedef struct SlSessionSnapshotSummaryResult {
  SlResult result;
  SlSessionSnapshotSummary summary;
} SlSessionSnapshotSummaryResult;

typedef struct SlVulkanScenePacketResult {
  SlResult result;
  SlRenderPacketHandle handle;
  SlVulkanScenePacketInfo info;
} SlVulkanScenePacketResult;

typedef struct SlBufferViewResult {
  SlResult result;
  SlBufferView view;
} SlBufferViewResult;

// ABI contract entrypoints.
// - session_*: create/read/command/refresh lifecycle
// - *_session_export_*: render extraction + packet handle ownership flow
// - *_buffer/release: borrowed packet view + explicit release
uint32_t sl_v2_abi_version(void);
SlSessionCreateResult sl_v2_session_create(SlSessionCreateParams params);
SlResult sl_v2_session_destroy(SlRuntimeHandle handle);
SlRuntimeInfoResult sl_v2_session_runtime_info(SlRuntimeHandle handle);
SlSessionSnapshotSummaryResult sl_v2_session_snapshot_summary(SlRuntimeHandle handle);
SlSessionSnapshotSummaryResult sl_v2_session_refresh(SlRuntimeHandle handle);
SlSessionSnapshotSummaryResult sl_v2_session_apply_command(
    SlRuntimeHandle handle,
    SlSessionCommand command);
SlVulkanScenePacketResult sl_v2_session_export_vulkan_scene(SlRuntimeHandle handle);
SlBufferViewResult sl_v2_vulkan_scene_packet_buffer(
    SlRenderPacketHandle handle,
    SlVulkanSceneBufferKind buffer_kind);
SlResult sl_v2_vulkan_scene_packet_release(SlRenderPacketHandle handle);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // SOLARLAB_V2_H
