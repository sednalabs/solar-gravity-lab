package com.sednalabs.solarlab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.graciousgazelles.solarlab.render.core.TraceLayerMode

internal enum class StageChromeMode {
    COLLAPSED,
    EXPANDED,
}

internal fun StageChromeMode.toggle(): StageChromeMode = when (this) {
    StageChromeMode.COLLAPSED -> StageChromeMode.EXPANDED
    StageChromeMode.EXPANDED -> StageChromeMode.COLLAPSED
}

internal fun TraceLayerMode.next(): TraceLayerMode = when (this) {
    TraceLayerMode.FOCUS -> TraceLayerMode.ALL
    TraceLayerMode.ALL -> TraceLayerMode.OFF
    TraceLayerMode.OFF -> TraceLayerMode.FOCUS
}

internal fun stageChromeModeFromName(value: String): StageChromeMode =
    StageChromeMode.entries.firstOrNull { it.name == value } ?: StageChromeMode.COLLAPSED

internal fun traceLayerModeFromName(value: String): TraceLayerMode =
    TraceLayerMode.entries.firstOrNull { it.name == value } ?: TraceLayerMode.FOCUS

internal fun traceLayerButtonLabel(mode: TraceLayerMode, compact: Boolean): String {
    val label = when (mode) {
        TraceLayerMode.FOCUS -> "Focus"
        TraceLayerMode.ALL -> "All"
        TraceLayerMode.OFF -> "Off"
    }
    return if (compact) "Trace $label" else "Traces: $label"
}

private object StageTrajectoryGlyphMetrics {
    val width = 78.dp
    val height = 36.dp
    val backgroundArcStrokeWidth = 1.dp
    val activeArcStrokeWidth = 1.6.dp
    val probeArcStrokeWidth = 2.dp
    val transferLineStrokeWidth = 1.dp
    val probeHaloRadius = 6.dp
    val probeRadius = 2.4.dp
    val anchorRadius = 2.dp

    const val centerXFraction = 0.45f
    const val centerYFraction = 0.55f
    const val orbitWidthFraction = 0.86f
    const val orbitHeightFraction = 1.16f
    const val orbitCenterOffsetFraction = 0.5f
    const val backgroundArcAlpha = 0.18f
    const val backgroundArcStartAngle = 186f
    const val backgroundArcSweepAngle = 228f
    const val activeArcAlpha = 0.82f
    const val activeArcStartAngle = 210f
    const val activeArcSweepAngle = 92f
    const val probeArcAlpha = 0.74f
    const val probeArcStartAngle = 312f
    const val probeArcSweepAngle = 34f
    const val probeArcInsetFraction = 0.08f
    const val probeArcScale = 1.16f
    const val transferLineAlpha = 0.40f
    const val transferLineStartXFraction = 0.10f
    const val transferLineStartYFraction = 0.14f
    const val transferLineEndXFraction = 0.90f
    const val transferLineEndYFraction = -0.28f
    const val probeHaloAlpha = 0.26f
    const val probeXFraction = 0.70f
    const val probeYFraction = 0.34f
    const val anchorAlpha = 0.88f
    const val anchorXFraction = 0.30f
    const val anchorYFraction = 0.66f
}

@Composable
internal fun StageTrajectoryGlyph(
    orbitColor: Color,
    probeColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .width(StageTrajectoryGlyphMetrics.width)
            .height(StageTrajectoryGlyphMetrics.height),
    ) {
        val center = Offset(
            x = size.width * StageTrajectoryGlyphMetrics.centerXFraction,
            y = size.height * StageTrajectoryGlyphMetrics.centerYFraction,
        )
        val orbitSize = Size(
            width = size.width * StageTrajectoryGlyphMetrics.orbitWidthFraction,
            height = size.height * StageTrajectoryGlyphMetrics.orbitHeightFraction,
        )
        val orbitTopLeft = Offset(
            x = center.x - orbitSize.width * StageTrajectoryGlyphMetrics.orbitCenterOffsetFraction,
            y = center.y - orbitSize.height * StageTrajectoryGlyphMetrics.orbitCenterOffsetFraction,
        )
        drawArc(
            color = orbitColor.copy(alpha = StageTrajectoryGlyphMetrics.backgroundArcAlpha),
            startAngle = StageTrajectoryGlyphMetrics.backgroundArcStartAngle,
            sweepAngle = StageTrajectoryGlyphMetrics.backgroundArcSweepAngle,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = orbitSize,
            style = Stroke(
                width = StageTrajectoryGlyphMetrics.backgroundArcStrokeWidth.toPx(),
                cap = StrokeCap.Round,
            ),
        )
        drawArc(
            color = orbitColor.copy(alpha = StageTrajectoryGlyphMetrics.activeArcAlpha),
            startAngle = StageTrajectoryGlyphMetrics.activeArcStartAngle,
            sweepAngle = StageTrajectoryGlyphMetrics.activeArcSweepAngle,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = orbitSize,
            style = Stroke(
                width = StageTrajectoryGlyphMetrics.activeArcStrokeWidth.toPx(),
                cap = StrokeCap.Round,
            ),
        )
        drawArc(
            color = probeColor.copy(alpha = StageTrajectoryGlyphMetrics.probeArcAlpha),
            startAngle = StageTrajectoryGlyphMetrics.probeArcStartAngle,
            sweepAngle = StageTrajectoryGlyphMetrics.probeArcSweepAngle,
            useCenter = false,
            topLeft = Offset(
                x = orbitTopLeft.x - (orbitSize.width * StageTrajectoryGlyphMetrics.probeArcInsetFraction),
                y = orbitTopLeft.y - (orbitSize.height * StageTrajectoryGlyphMetrics.probeArcInsetFraction),
            ),
            size = Size(
                width = orbitSize.width * StageTrajectoryGlyphMetrics.probeArcScale,
                height = orbitSize.height * StageTrajectoryGlyphMetrics.probeArcScale,
            ),
            style = Stroke(
                width = StageTrajectoryGlyphMetrics.probeArcStrokeWidth.toPx(),
                cap = StrokeCap.Round,
            ),
        )
        drawLine(
            color = orbitColor.copy(alpha = StageTrajectoryGlyphMetrics.transferLineAlpha),
            start = Offset(
                x = size.width * StageTrajectoryGlyphMetrics.transferLineStartXFraction,
                y = center.y + size.height * StageTrajectoryGlyphMetrics.transferLineStartYFraction,
            ),
            end = Offset(
                x = size.width * StageTrajectoryGlyphMetrics.transferLineEndXFraction,
                y = center.y + size.height * StageTrajectoryGlyphMetrics.transferLineEndYFraction,
            ),
            strokeWidth = StageTrajectoryGlyphMetrics.transferLineStrokeWidth.toPx(),
            cap = StrokeCap.Round,
        )
        val probeCenter = Offset(
            x = size.width * StageTrajectoryGlyphMetrics.probeXFraction,
            y = size.height * StageTrajectoryGlyphMetrics.probeYFraction,
        )
        drawCircle(
            color = probeColor.copy(alpha = StageTrajectoryGlyphMetrics.probeHaloAlpha),
            radius = StageTrajectoryGlyphMetrics.probeHaloRadius.toPx(),
            center = probeCenter,
        )
        drawCircle(
            color = probeColor,
            radius = StageTrajectoryGlyphMetrics.probeRadius.toPx(),
            center = probeCenter,
        )
        drawCircle(
            color = orbitColor.copy(alpha = StageTrajectoryGlyphMetrics.anchorAlpha),
            radius = StageTrajectoryGlyphMetrics.anchorRadius.toPx(),
            center = Offset(
                x = size.width * StageTrajectoryGlyphMetrics.anchorXFraction,
                y = size.height * StageTrajectoryGlyphMetrics.anchorYFraction,
            ),
        )
    }
}

@Composable
internal fun StageControlsButton(
    label: String,
    onClick: () -> Unit,
    dense: Boolean = false,
) {
    StageActionButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON),
        secondary = true,
        dense = dense,
    )
}

@Composable
internal fun StageTraceLayerButton(
    mode: TraceLayerMode,
    compact: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dense: Boolean = false,
) {
    StageActionButton(
        label = traceLayerButtonLabel(mode, compact = compact),
        onClick = onClick,
        modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_TRACE_LAYER_BUTTON),
        secondary = true,
        enabled = enabled,
        dense = dense,
    )
}
