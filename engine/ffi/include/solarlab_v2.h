#ifndef SOLARLAB_V2_H
#define SOLARLAB_V2_H

#include <stdint.h>

#define SOLARLAB_V2_ABI_VERSION 1u

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

typedef struct SlRuntimeInfo {
  uint32_t abi_version;
  SlCpuBackend cpu_backend;
  SlGpuBackend gpu_backend;
} SlRuntimeInfo;

#endif  // SOLARLAB_V2_H
