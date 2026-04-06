package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBridgeGpuBackendSelectionTest {
    @Test
    fun preferredGpuBackendCode_mapsKnownBackendNames() {
        assertEquals(NATIVE_GPU_BACKEND_NONE, preferredGpuBackendCode("none"))
        assertEquals(NATIVE_GPU_BACKEND_VULKAN, preferredGpuBackendCode("vulkan"))
        assertEquals(NATIVE_GPU_BACKEND_METAL, preferredGpuBackendCode("metal"))
        assertEquals(NATIVE_GPU_BACKEND_WEBGPU_CLASS, preferredGpuBackendCode("webgpu"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("opencl"))
    }

    @Test
    fun preferredGpuBackendCode_acceptsAliasesAndCaseVariants() {
        assertEquals(NATIVE_GPU_BACKEND_WEBGPU_CLASS, preferredGpuBackendCode("WebGPU-Class"))
        assertEquals(NATIVE_GPU_BACKEND_WEBGPU_CLASS, preferredGpuBackendCode("webgpu_class"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("OPEN-CL"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("open_cl"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("vulkan+opencl"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("opencl+vulkan"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("vulkan + opencl"))
        assertEquals(NATIVE_GPU_BACKEND_OPENCL, preferredGpuBackendCode("opencl, vulkan"))
    }

    @Test
    fun preferredGpuBackendCode_fallsBackToNone_forUnknownOrBlankValues() {
        assertEquals(NATIVE_GPU_BACKEND_NONE, preferredGpuBackendCode(""))
        assertEquals(NATIVE_GPU_BACKEND_NONE, preferredGpuBackendCode("  "))
        assertEquals(NATIVE_GPU_BACKEND_NONE, preferredGpuBackendCode("cuda"))
    }

    @Test
    fun nativeRuntimeInfoResult_surfacesOpenClWorkloadsAndInteropPolicy() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 2,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_OPENCL,
        )

        assertTrue(info.gpuWorkloadSummary()?.contains("long-horizon") == true)
        assertTrue(info.gpuInteropErrorBudgetSummary()?.contains("position<=5m") == true)
    }

    @Test
    fun nativeRuntimeInfoResult_returnsNoInteropPolicy_forNonOpenClBackends() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 2,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
        )

        assertTrue(info.gpuWorkloadSummary()?.contains("realtime") == true)
        assertNull(info.gpuInteropErrorBudgetSummary())
    }
}
