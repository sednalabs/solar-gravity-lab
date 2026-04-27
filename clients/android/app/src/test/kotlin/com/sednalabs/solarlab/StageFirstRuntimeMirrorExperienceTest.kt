package com.sednalabs.solarlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageFirstRuntimeMirrorExperienceTest {
    @Test
    fun buildRuntimeAccelerationReadout_promotesS25TilePlanIntoChips() {
        val readout = requireNotNull(
            buildRuntimeAccelerationReadout(
                backendSummary = listOf(
                    "cpu=simd.arm64",
                    "gpu=vulkan",
                    "solver: simd.arm64.neon-f64-parallel-tiled-pairwise",
                    "cpu scheduler: adaptive tiled active 8 workers (749 bodies, 280126 pairs, 24x32-body tiles, 8 tile workers)",
                    "cpu kernels: kernel catalog: 15 paths, active 1, eligible candidates 3, blocked candidates 8",
                    "workloads: rendering=vulkan(realtime + in-frame)",
                ).joinToString(" | "),
                scenarioId = "stress.s25-tile-swarm",
                bodyCount = 749,
            )
        )

        assertEquals("S25 tile swarm acceleration probe", readout.headline)
        assertEquals(
            listOf(
                "S25 swarm",
                "749 bodies",
                "Parallel tiled",
                "24x32-body tiles",
                "8 tile workers",
                "Vulkan",
            ),
            readout.chips,
        )
        assertTrue(readout.detail.contains("neon-f64-parallel-tiled-pairwise"))
        assertTrue(readout.detail.contains("kernel catalog: 15 paths"))
    }

    @Test
    fun buildRuntimeAccelerationReadout_handlesScalarFallbackTruth() {
        val readout = requireNotNull(
            buildRuntimeAccelerationReadout(
                backendSummary = listOf(
                    "cpu=requested simd-arm64 -> effective reference-scalar",
                    "gpu=vulkan",
                    "cpu fallback: simd-arm64 requested on non-aarch64 host",
                    "cpu scheduler: single-worker (365 bodies, 66430 pairs)",
                ).joinToString(" | "),
                scenarioId = "sol-system",
                bodyCount = 365,
            )
        )

        assertEquals("Runtime acceleration truth", readout.headline)
        assertEquals(listOf("365 bodies", "Single worker", "Vulkan"), readout.chips)
        assertTrue(readout.detail.contains("effective reference-scalar"))
    }
}
