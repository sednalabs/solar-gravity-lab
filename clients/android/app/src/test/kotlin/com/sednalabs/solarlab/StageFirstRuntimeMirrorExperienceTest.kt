package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.RenderStatusPresentation
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
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
            "Parallel ARM64 drive online · Vulkan render path · 3 future ISA lanes scouted",
            readout.statusLine,
        )
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
        assertEquals(
            "CPU simd.arm64 · GPU vulkan · active NEON f64 parallel tiled",
            readout.auditSummary,
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
                    "cpu kernels: kernel catalog: 15 paths, active 0, eligible candidates 0, " +
                        "blocked candidates 12 [simd.arm64.sve-f64-batch-candidate, " +
                        "simd.arm64.sve2-f64-batch-candidate, simd.arm64.sve-i8mm-packed-assist-candidate, " +
                        "simd.arm64.sme-tiled-f64-candidate, simd.arm64.sme2-tiled-f64-candidate, " +
                        "simd.arm64.dotprod-packed-assist-candidate, simd.arm64.i8mm-packed-assist-candidate, " +
                        "simd.arm64.bf16-forecast-assist-candidate, simd.arm64.fp16-visual-assist-candidate, " +
                        "simd.arm64.fhm-visual-assist-candidate, simd.arm64.rdm-vector-assist-candidate, " +
                        "simd.arm64.fcma-vector-assist-candidate]",
                ).joinToString(" | "),
                scenarioId = "sol-system",
                bodyCount = 365,
            )
        )

        assertEquals("Mission acceleration cockpit", readout.headline)
        assertEquals(
            "Emulator scalar truth mode · Vulkan render path · 12 device-only ISA lanes in audit",
            readout.statusLine,
        )
        assertEquals(listOf("365 bodies", "Single worker", "Vulkan"), readout.chips)
        assertTrue(readout.detail.contains("effective reference-scalar"))
        assertEquals(
            listOf(
                RuntimeAccelerationLane("CPU", "requested simd-arm64 -> effective reference-scalar"),
                RuntimeAccelerationLane("GPU", "vulkan", RuntimeAccelerationLaneTone.Active),
                RuntimeAccelerationLane("Scheduler", "single-worker (365 bodies, 66430 pairs)"),
                RuntimeAccelerationLane(
                    "Blocked",
                    "12 blocked lanes · SVE f64 batch, SVE2 f64 batch, SVE I8MM packed assist · 9 more in audit",
                    RuntimeAccelerationLaneTone.Blocked,
                ),
                RuntimeAccelerationLane("Fallback", "simd-arm64 requested on non-aarch64 host", RuntimeAccelerationLaneTone.Fallback),
            ),
            readout.lanes,
        )
        assertEquals(
            "CPU requested simd-arm64 -> effective reference-scalar · GPU vulkan · " +
                "12 blocked lanes retained in debug audit · fallback simd-arm64 requested on non-aarch64 host",
            readout.auditSummary,
        )
        assertTrue(readout.detail.contains("simd.arm64.fcma-vector-assist-candidate"))
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
    fun runtimeMirrorAccelerationStatusLine_keepsHudMissionReadable() {
        assertEquals(
            "ARM64 solver lane online · Vulkan render path · kernel catalog steady",
            runtimeMirrorAccelerationStatusLine(
                activeKernel = "NEON f64 tiled",
                schedulerMode = "Scheduler reported",
                gpuChip = "Vulkan",
                fallback = null,
                eligibleKernelCount = 0,
                blockedKernelCount = 0,
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactAccelerationStatusLine_keepsPhoneHUDGlanceable() {
        assertEquals(
            "ARM64 tiled · Vulkan · 3 future ISA",
            runtimeMirrorCompactAccelerationStatusLine(
                "Parallel ARM64 drive online · Vulkan render path · 3 future ISA lanes scouted"
            ),
        )
        assertEquals(
            "Scalar truth · Vulkan · 12 device ISA",
            runtimeMirrorCompactAccelerationStatusLine(
                "Emulator scalar truth mode · Vulkan render path · 12 device-only ISA lanes in audit"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactAccelerationChipValue_keepsScalarIsaReadable() {
        assertEquals(
            "scalar",
            runtimeMirrorCompactAccelerationChipValue("scalar.reference"),
        )
        assertEquals(
            "NEON tiled",
            runtimeMirrorCompactAccelerationChipValue("NEON f64 parallel tiled"),
        )
    }

    @Test
    fun runtimeMirrorCompactAccelerationAuditSummary_keepsScalarAuditReadable() {
        assertEquals(
            "CPU scalar · GPU Vulkan · active scalar",
            runtimeMirrorCompactAccelerationAuditSummary(
                "CPU simd-arm64 -> scalar · GPU vulkan · active scalar.reference"
            ),
        )
        assertEquals(
            "CPU scalar · GPU Vulkan · active scalar",
            runtimeMirrorCompactAccelerationAuditSummary(
                "CPU simd-arm64 -> scalar · GPU vulkan · active scalar.reference · " +
                    "fallback simd-arm64 requested on non-aarch64 host"
            ),
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
        assertTrue(compacted.length <= 155)
    }

    @Test
    fun runtimeMirrorCompactRevisionText_summarizesScenarioPayloadRevision() {
        assertEquals(
            "sol-system / main / t+6.0h / payload 89693 chars",
            runtimeMirrorCompactRevisionText(
                "scenario=sol-system|branch=main|epoch=21600.000000|" +
                    "observer=FollowSelected|sun|packet=${"x".repeat(80)} (89693 chars)"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactRevisionText_canOmitPayloadForHud() {
        assertEquals(
            "sol-system / main / t+6.0h",
            runtimeMirrorCompactRevisionText(
                value = "scenario=sol-system|branch=main|epoch=21600.000000|" +
                    "observer=FollowSelected|sun|packet=${"x".repeat(80)} (89693 chars)",
                includePayloadSize = false,
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactRevisionMetricText_prefersMissionElapsedTime() {
        assertEquals(
            "t+6.0h",
            runtimeMirrorCompactRevisionMetricText(
                "scenario=sol-system|branch=main|epoch=21600.000000|" +
                    "observer=FollowSelected|sun|packet=${"x".repeat(80)} (89693 chars)"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactRevisionMetric_labelsMissionElapsedTime() {
        assertEquals(
            RuntimeMirrorCompactRevisionMetric(label = "MET", value = "t+6.0h"),
            runtimeMirrorCompactRevisionMetric(
                "scenario=sol-system|branch=main|epoch=21600.000000|" +
                    "observer=FollowSelected|sun|packet=${"x".repeat(80)} (89693 chars)"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactRevisionMetricText_fallsBackToShortSceneLabel() {
        assertEquals(
            "sol-system",
            runtimeMirrorCompactRevisionMetricText("scenario=sol-system|branch=main"),
        )
    }

    @Test
    fun runtimeMirrorCompactRevisionMetric_labelsFallbackAsRevision() {
        assertEquals(
            RuntimeMirrorCompactRevisionMetric(label = "Rev", value = "sol-system"),
            runtimeMirrorCompactRevisionMetric("scenario=sol-system|branch=main"),
        )
    }

    @Test
    fun runtimeMirrorCompactSelectionDetail_keepsRawRevisionOutOfFocusCard() {
        assertEquals(
            "Scene sol-system / main / t+0.0h",
            runtimeMirrorCompactSelectionDetail(
                "Scene revision scenario=sol-system|branch=main|epoch=0.000000|" +
                    "observer=FollowSelected|sun|packet=${"x".repeat(80)} (89676 chars)"
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactScenarioLabel_keepsPortraitHudMissionFirst() {
        assertEquals(
            "Canonical solar system",
            runtimeMirrorCompactScenarioLabel(
                "Canonical solar system | branch=main\n" +
                    "Epoch 0.0h • Speed 6 h/s • Step 6 h",
            ),
        )
    }

    @Test
    fun runtimeMirrorFocusDisplayName_promotesRawBodyIdsForHud() {
        assertEquals("Earth", runtimeMirrorFocusDisplayName(bodyId = "earth", displayName = "earth"))
        assertEquals("Probe Alpha", runtimeMirrorFocusDisplayName(bodyId = "probe-alpha", displayName = "probe-alpha"))
    }

    @Test
    fun runtimeMirrorCompactRevisionText_fallsBackToStatusCompaction() {
        val compacted = runtimeMirrorCompactRevisionText("revision-" + "segment-".repeat(80))

        assertTrue(compacted.endsWith("... [truncated]"))
        assertTrue(compacted.length <= 155)
    }

    @Test
    fun runtimeMirrorCompactRendererStatusText_removesPacketCountersForHUD() {
        assertEquals(
            "Vulkan SPIR-V + compute compaction active",
            runtimeMirrorCompactRendererStatusText(
                "Vulkan SPIR-V graphics pipelines + compute compaction active. " +
                    "rev=-485007626274543117 A=355/AI=355 TN=0 TM=0 TF=0 TL=768/8 bytes=32400 paths..."
            ),
        )
    }

    @Test
    fun runtimeMirrorCompactBackendStatus_keepsPhoneHUDOutOfSentenceMode() {
        assertEquals(
            "Connected · Host ready · rev=sol-system / main / t+6.0h · Vulkan + compute",
            runtimeMirrorCompactBackendStatus(
                "Runtime connected · Render host ready · rev=sol-system / main / t+6.0h · " +
                    "Vulkan SPIR-V + compute compaction active"
            ),
        )
    }

    @Test
    fun buildRuntimeBackendStatus_keepsHudStatusOutOfPacketTelemetry() {
        val status = buildRuntimeBackendStatus(
            uiState = ShellUiState(
                connectionState = SessionConnectionState.Active,
                statusLine = "Render host ready",
                renderStatus = RenderStatusPresentation(
                    sceneRevision = "scenario=sol-system|branch=main|epoch=21600.000000|" +
                        "packet=${"x".repeat(80)} (89693 chars)",
                ),
            ),
            hostRendererStatus = "Vulkan SPIR-V graphics pipelines + compute compaction active. " +
                "rev=-485007626274543117 A=355/AI=355 TN=0 TM=0 TF=0 TL=768/8 bytes=32400 paths...",
        )

        assertEquals(
            "Runtime connected · Render host ready · rev=sol-system / main / t+6.0h · " +
                "Vulkan SPIR-V + compute compaction active",
            status,
        )
    }
}
