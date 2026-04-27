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
    fun preferredGpuBackendLabel_tracksRequestedBackendForTelemetry() {
        assertEquals("none", preferredGpuBackendLabel(""))
        assertEquals("vulkan", preferredGpuBackendLabel("vulkan"))
        assertEquals("vulkan+opencl", preferredGpuBackendLabel("vulkan + opencl"))
        assertEquals("vulkan+opencl", preferredGpuBackendLabel("opencl, vulkan"))
        assertEquals("unsupported:cuda", preferredGpuBackendLabel("cuda"))
    }

    @Test
    fun nativeRuntimeInfoResult_surfacesOpenClWorkloadsAndInteropPolicy() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_OPENCL,
            cpuFeatureFlags = 0,
            cpuSolverPath = 1,
            cpuFallbackCode = 0,
        )

        assertTrue(info.gpuWorkloadSummary()?.contains("long-horizon") == true)
        assertTrue(info.gpuInteropErrorBudgetSummary()?.contains("position<=5m") == true)
    }

    @Test
    fun nativeRuntimeInfoResult_returnsNoInteropPolicy_forNonOpenClBackends() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
            cpuFeatureFlags = 0,
            cpuSolverPath = 1,
            cpuFallbackCode = 0,
        )

        assertTrue(info.gpuWorkloadSummary()?.contains("realtime") == true)
        assertNull(info.gpuInteropErrorBudgetSummary())
    }

    @Test
    fun nativeRuntimeInfoResult_surfacesCpuIsaTruth() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = 0,
            gpuBackend = NATIVE_GPU_BACKEND_NONE,
            cpuFeatureFlags = (1L shl 0) or (1L shl 7) or (1L shl 9),
            cpuSolverPath = 0,
            cpuFallbackCode = 1,
        )

        assertEquals("simd-arm64", info.requestedCpuBackendLabel())
        assertEquals("reference-scalar", info.cpuBackendLabel())
        assertEquals("scalar.reference", info.cpuSolverPathLabel())
        assertEquals("neon+sve2+sme2", info.cpuFeatureSummary())
        assertTrue(info.cpuFallbackSummary()?.contains("non-aarch64") == true)
    }

    @Test
    fun nativeRuntimeInfoResult_surfacesCpuSchedulerTruth() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
            cpuFeatureFlags = 1L shl 0,
            cpuSolverPath = 1,
            cpuFallbackCode = 0,
            cpuScheduleMode = 1,
            cpuScheduleActiveWorkers = 1,
            cpuScheduleCandidateWorkers = 8,
            cpuScheduleBodyCount = 192,
            cpuScheduleEstimatedPairCount = 18_336,
            cpuScheduleTileSizeBodies = 32,
            cpuScheduleTileCount = 6,
            cpuScheduleParallelTileWorkers = 6,
        )

        assertEquals(
            "single-worker active, adaptive tiled candidate 8 workers (192 bodies, 18336 pairs, 6x32-body tiles, 6 tile workers)",
            info.cpuScheduleSummary(),
        )
    }

    @Test
    fun nativeRuntimeInfoResult_labelsLargeSceneTiledNeonSolverPath() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
            cpuFeatureFlags = 1L shl 0,
            cpuSolverPath = 3,
            cpuFallbackCode = 0,
            cpuScheduleMode = 1,
            cpuScheduleActiveWorkers = 1,
            cpuScheduleCandidateWorkers = 8,
            cpuScheduleBodyCount = 192,
            cpuScheduleEstimatedPairCount = 18_336,
            cpuScheduleTileSizeBodies = 32,
            cpuScheduleTileCount = 6,
            cpuScheduleParallelTileWorkers = 6,
        )

        assertEquals("simd.arm64.neon-f64-tiled-pairwise", info.cpuSolverPathLabel())
        assertEquals(
            "single-worker active, adaptive tiled candidate 8 workers (192 bodies, 18336 pairs, 6x32-body tiles, 6 tile workers)",
            info.cpuScheduleSummary(),
        )
    }

    @Test
    fun nativeRuntimeInfoResult_labelsParallelTiledNeonSolverPath() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
            cpuFeatureFlags = 1L shl 0,
            cpuSolverPath = 4,
            cpuFallbackCode = 0,
            cpuScheduleMode = 2,
            cpuScheduleActiveWorkers = 8,
            cpuScheduleCandidateWorkers = 8,
            cpuScheduleBodyCount = 192,
            cpuScheduleEstimatedPairCount = 18_336,
            cpuScheduleTileSizeBodies = 32,
            cpuScheduleTileCount = 6,
            cpuScheduleParallelTileWorkers = 6,
        )

        assertEquals("simd.arm64.neon-f64-parallel-tiled-pairwise", info.cpuSolverPathLabel())
        assertEquals(
            "adaptive tiled active 8 workers (192 bodies, 18336 pairs, 6x32-body tiles, 6 tile workers)",
            info.cpuScheduleSummary(),
        )
    }

    @Test
    fun nativeRuntimeInfoResult_surfacesCpuKernelCatalogTruth() {
        val info = NativeRuntimeInfoResult(
            result = NativeResult(code = 0),
            abiVersion = 8,
            requestedCpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_VULKAN,
            cpuFeatureFlags = (1L shl 0) or (1L shl 7) or (1L shl 9),
            cpuSolverPath = 1,
            cpuFallbackCode = 0,
            cpuKernelCatalogCount = 15,
            cpuKernelActiveCount = 1,
            cpuKernelEligibleCandidateCount = 2,
            cpuKernelBlockedCandidateCount = 10,
            cpuKernelActiveMask = 1L shl 0,
            cpuKernelEligibleCandidateMask = (1L shl 4) or (1L shl 7),
            cpuKernelBlockedCandidateMask = (1L shl 3) or (1L shl 5),
        )

        assertEquals(
            "kernel catalog: 15 paths, " +
                "active 1 [simd.arm64.neon-f64-pairwise], " +
                "eligible candidates 2 [simd.arm64.sve2-f64-batch-candidate, " +
                "simd.arm64.sme2-tiled-f64-candidate], " +
                "blocked candidates 10 [simd.arm64.sve-f64-batch-candidate, " +
                "simd.arm64.sve-i8mm-packed-assist-candidate]",
            info.cpuKernelCatalogSummary(),
        )
    }
}
