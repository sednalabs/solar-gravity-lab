package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
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
}
