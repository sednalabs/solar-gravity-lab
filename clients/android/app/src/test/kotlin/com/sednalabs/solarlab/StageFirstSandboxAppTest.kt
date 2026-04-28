package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageFirstSandboxAppTest {
    @Test
    fun stageChromeModeToggleCyclesBetweenCollapsedAndExpanded() {
        assertEquals(StageChromeMode.EXPANDED, StageChromeMode.COLLAPSED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, StageChromeMode.EXPANDED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeFromName("missing"))
    }

    @Test
    fun traceLayerModeNextCyclesFocusAllOff() {
        assertEquals(TraceLayerMode.ALL, TraceLayerMode.FOCUS.next())
        assertEquals(TraceLayerMode.OFF, TraceLayerMode.ALL.next())
        assertEquals(TraceLayerMode.FOCUS, TraceLayerMode.OFF.next())
        assertEquals(TraceLayerMode.FOCUS, traceLayerModeFromName("missing"))
    }

    @Test
    fun traceLayerButtonLabelKeepsCompactHudShort() {
        assertEquals("Focus", traceLayerButtonLabel(TraceLayerMode.FOCUS, compact = true))
        assertEquals("Traces: All", traceLayerButtonLabel(TraceLayerMode.ALL, compact = false))
        assertEquals("Off", traceLayerButtonLabel(TraceLayerMode.OFF, compact = true))
    }

    @Test
    fun buildIdleMissionTrajectoryDetail_namesSceneBodyCount() {
        assertEquals(
            "Tracking 39 bodies with live fly paths. " +
                "Tap a luminous body to focus, or open Immersive for the accelerated runtime view.",
            buildIdleMissionTrajectoryDetail(39),
        )
    }

    @Test
    fun buildIdleMissionTrajectoryDetail_handlesSceneWarmup() {
        assertEquals(
            "Acquiring ephemeris scene and live fly paths. " +
                "Tap a luminous body to focus, or open Immersive for the accelerated runtime view.",
            buildIdleMissionTrajectoryDetail(null),
        )
    }

    @Test
    fun compactStageBackendHudStatusText_removesRendererPacketTelemetry() {
        assertEquals(
            "Vulkan SPIR-V + compute compaction active. Wide orbit 63° / yaw -34°",
            compactStageBackendHudStatusText(
                "Vulkan SPIR-V graphics pipelines + compute compaction active. " +
                    "Wide orbit 63° / yaw -34° · rev=80 A=39/AI=39 TN=268 TM=12 TF=20 " +
                    "TL=118/57 bytes=1728384 paths=[sprite,sprite,cheap-point,density-point,thin-line] " +
                    "compute=[TM:1/src=state/vis=-,TF:1/src=state/cap=81600/tiles=10200]"
            ),
        )
    }

    @Test
    fun compactStageBackendHudStatusText_boundsUnknownLongStatus() {
        val compacted = compactStageBackendHudStatusText("Renderer " + "packet ".repeat(80))

        assertTrue(compacted.endsWith("... [truncated]"))
        assertTrue(compacted.length <= 135)
    }

    @Test
    fun compactStageBackendHudStatusText_usesStableFallbackForBlankStatus() {
        assertEquals(
            "Preparing immersive Vulkan stage.",
            compactStageBackendHudStatusText(" \n\t "),
        )
    }
}
