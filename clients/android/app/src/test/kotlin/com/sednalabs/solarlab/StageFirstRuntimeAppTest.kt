package com.sednalabs.solarlab

import androidx.compose.ui.unit.dp
import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StageFirstRuntimeAppTest {
    @Test
    fun cameraGuidanceIsConciseAndAccessibilitySized() {
        assertEquals(0.20f, STAGE_CAMERA_COACH_PORTRAIT_MAX_HEIGHT_FRACTION)
        assertEquals(48.dp, StageActionMinimumTouchTarget)
    }

    @Test
    fun stageChromeModeToggleKeepsTheStageRecoverable() {
        assertEquals(StageChromeMode.COLLAPSED, StageChromeMode.MINIMAL.toggle())
        assertEquals(StageChromeMode.EXPANDED, StageChromeMode.COLLAPSED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, StageChromeMode.EXPANDED.toggle())
        assertEquals(StageChromeMode.COLLAPSED, stageChromeModeFromName("missing"))
    }

    @Test
    fun expandedDeckKeepsTheStageDominant() {
        assertEquals(0.30f, expandedStageDeckMaxHeightFraction(compactLayout = true))
        assertEquals(0.34f, expandedStageDeckMaxHeightFraction(compactLayout = false))
    }

    @Test
    fun tracerDensityHasExplicitMonotonicSteps() {
        assertEquals(TraceLayerMode.FOCUS, traceLayerModeFromName("missing"))
        assertEquals(TraceLayerMode.FOCUS, TraceLayerMode.ALL.less())
        assertEquals(TraceLayerMode.OFF, TraceLayerMode.FOCUS.less())
        assertEquals(TraceLayerMode.OFF, TraceLayerMode.OFF.less())
        assertEquals(TraceLayerMode.FOCUS, TraceLayerMode.OFF.more())
        assertEquals(TraceLayerMode.ALL, TraceLayerMode.FOCUS.more())
        assertEquals(TraceLayerMode.ALL, TraceLayerMode.ALL.more())
    }

    @Test
    fun tracerDensityLabelsAreUserFacing() {
        assertEquals("Focused", traceLayerButtonLabel(TraceLayerMode.FOCUS, compact = true))
        assertEquals("Tracers: More", traceLayerButtonLabel(TraceLayerMode.ALL, compact = false))
        assertEquals("Hidden", traceLayerButtonLabel(TraceLayerMode.OFF, compact = true))
    }
}
