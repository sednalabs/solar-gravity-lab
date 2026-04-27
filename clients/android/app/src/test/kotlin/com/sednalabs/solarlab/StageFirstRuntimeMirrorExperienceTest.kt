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
                    "cpu kernels: kernel catalog: 15 paths, " +
                        "active 1 [simd.arm64.neon-f64-parallel-tiled-pairwise], " +
                        "eligible candidates 3 [simd.arm64.sve2-f64-batch-candidate, " +
                        "simd.arm64.sme2-tiled-f64-candidate], " +
                        "blocked candidates 8 [simd.arm64.sve-f64-batch-candidate]",
                    "workloads: rendering=vulkan(realtime + in-frame)",
                ).joinToString(" | "),
                scenarioId = "stress.s25-tile-swarm",
                bodyCount = 749,
            )
        )

        assertEquals("Galaxy S25 Ultra acceleration cockpit", readout.headline)
        assertEquals(
            listOf(
                "S25 swarm",
                "749 bodies",
                "Active NEON f64 parallel tiled",
                "Parallel tiled",
                "24x32-body tiles",
                "8 tile workers",
                "3 eligible lanes",
                "Vulkan",
            ),
            readout.chips,
        )
        assertTrue(readout.detail.contains("neon-f64-parallel-tiled-pairwise"))
        assertTrue(readout.detail.contains("kernel catalog: 15 paths"))
        assertEquals(
            listOf(
                RuntimeAccelerationLane("CPU", "simd.arm64"),
                RuntimeAccelerationLane("GPU", "vulkan", RuntimeAccelerationLaneTone.Active),
                RuntimeAccelerationLane("Solver", "NEON f64 parallel tiled", RuntimeAccelerationLaneTone.Active),
                RuntimeAccelerationLane(
                    "Scheduler",
                    "adaptive tiled active 8 workers (749 bodies, 280126 pairs, 24x32-body tiles, 8 tile workers)",
                    RuntimeAccelerationLaneTone.Active,
                ),
                RuntimeAccelerationLane("Active", "NEON f64 parallel tiled", RuntimeAccelerationLaneTone.Active),
                RuntimeAccelerationLane("Eligible", "SVE2 f64 batch, SME2 tiled f64", RuntimeAccelerationLaneTone.Eligible),
                RuntimeAccelerationLane("Blocked", "SVE f64 batch", RuntimeAccelerationLaneTone.Blocked),
                RuntimeAccelerationLane("Workload", "rendering=vulkan(realtime + in-frame)", RuntimeAccelerationLaneTone.Active),
            ),
            readout.lanes,
        )
        assertTrue(readout.signal > 0.80f)
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
        assertEquals(
            listOf(
                RuntimeAccelerationLane("CPU", "requested simd-arm64 -> effective reference-scalar"),
                RuntimeAccelerationLane("GPU", "vulkan", RuntimeAccelerationLaneTone.Active),
                RuntimeAccelerationLane("Scheduler", "single-worker (365 bodies, 66430 pairs)"),
                RuntimeAccelerationLane("Fallback", "simd-arm64 requested on non-aarch64 host", RuntimeAccelerationLaneTone.Fallback),
            ),
            readout.lanes,
        )
        assertTrue(readout.signal < 0.35f)
    }

    @Test
    fun buildRuntimeAccelerationReadout_usesSolverChipWhenKernelNamesAreUnavailable() {
        val readout = requireNotNull(
            buildRuntimeAccelerationReadout(
                backendSummary = listOf(
                    "cpu=simd.arm64",
                    "gpu=vulkan",
                    "solver: simd.arm64.neon-f64-tiled-pairwise",
                    "cpu scheduler: adaptive tiled active 4 workers (512 bodies, 130816 pairs, 16x32-body tiles, 4 tile workers)",
                    "cpu kernels: kernel catalog: 15 paths, active 1, eligible candidates 2, blocked candidates 10",
                ).joinToString(" | "),
                scenarioId = "sol-system",
                bodyCount = 512,
            )
        )

        assertTrue(readout.chips.contains("Active NEON f64 tiled"))
        assertTrue(readout.chips.none { it.contains("Active active", ignoreCase = true) })
        assertTrue(
            readout.lanes.contains(
                RuntimeAccelerationLane("Eligible", "eligible candidates 2", RuntimeAccelerationLaneTone.Eligible)
            )
        )
    }

    @Test
    fun runtimeMirrorCompactStatusText_normalizesShortStatusText() {
        assertEquals(
            "Runtime connected Vulkan ready · 8 tile workers",
            runtimeMirrorCompactStatusText(
                "Runtime connected\n\tVulkan ready   ·   8 tile workers"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactStatusText_cutsAfterHugePayloadMarker() {
        val compacted = runtimeMirrorCompactStatusText(
            "Render host ready (89686 chars) observer=FollowSelected|sun|" +
                "packet=${"x".repeat(300)}"
        )

        assertEquals("Render host ready (89686 chars)", compacted)
    }

    @Test
    fun runtimeMirrorCompactStatusText_boundsGenericLongStatusText() {
        val compacted = runtimeMirrorCompactStatusText(
            "Runtime connected " + "tile-lane ".repeat(80)
        )

        assertTrue(compacted.endsWith("... [truncated]"))
        assertTrue(compacted.length <= 235)
    }
}
