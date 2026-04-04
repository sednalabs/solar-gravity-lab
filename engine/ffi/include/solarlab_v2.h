#ifndef SOLARLAB_V2_H
#define SOLARLAB_V2_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SOLARLAB_V2_ABI_VERSION 1u
#define SL_V2_ID_CAPACITY 96u

typedef struct SlRuntimeHandle {
  uint64_t raw;
} SlRuntimeHandle;

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

typedef enum SlCpuBackend {
  SL_CPU_BACKEND_REFERENCE_SCALAR = 0,
  SL_CPU_BACKEND_SIMD_ARM64 = 1,
  SL_CPU_BACKEND_SIMD_X64 = 2
} SlCpuBackend;

typedef enum SlGpuBackend {
  SL_GPU_BACKEND_NONE = 0,
  SL_GPU_BACKEND_VULKAN = 1,
  SL_GPU_BACKEND_METAL = 2,
  SL_GPU_BACKEND_WEBGPU_CLASS = 3
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

typedef struct SlRuntimeInfo {
  uint32_t abi_version;
  SlCpuBackend cpu_backend;
  SlGpuBackend gpu_backend;
} SlRuntimeInfo;

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

uint32_t sl_v2_abi_version(void);
SlSessionCreateResult sl_v2_session_create(SlSessionCreateParams params);
SlResult sl_v2_session_destroy(SlRuntimeHandle handle);
SlRuntimeInfoResult sl_v2_session_runtime_info(SlRuntimeHandle handle);
SlSessionSnapshotSummaryResult sl_v2_session_snapshot_summary(SlRuntimeHandle handle);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // SOLARLAB_V2_H
