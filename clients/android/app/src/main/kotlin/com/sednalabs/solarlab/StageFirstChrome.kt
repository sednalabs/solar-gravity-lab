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

@Composable
internal fun StageTrajectoryGlyph(
    orbitColor: Color,
    probeColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .width(78.dp)
            .height(36.dp),
    ) {
        val center = Offset(size.width * 0.45f, size.height * 0.55f)
        val orbitSize = Size(size.width * 0.86f, size.height * 1.16f)
        val orbitTopLeft = Offset(
            x = center.x - orbitSize.width * 0.5f,
            y = center.y - orbitSize.height * 0.5f,
        )
        drawArc(
            color = orbitColor.copy(alpha = 0.18f),
            startAngle = 186f,
            sweepAngle = 228f,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = orbitSize,
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = orbitColor.copy(alpha = 0.82f),
            startAngle = 210f,
            sweepAngle = 92f,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = orbitSize,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = probeColor.copy(alpha = 0.74f),
            startAngle = 312f,
            sweepAngle = 34f,
            useCenter = false,
            topLeft = Offset(
                x = orbitTopLeft.x - (orbitSize.width * 0.08f),
                y = orbitTopLeft.y - (orbitSize.height * 0.08f),
            ),
            size = Size(orbitSize.width * 1.16f, orbitSize.height * 1.16f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            color = orbitColor.copy(alpha = 0.40f),
            start = Offset(size.width * 0.10f, center.y + size.height * 0.14f),
            end = Offset(size.width * 0.90f, center.y - size.height * 0.28f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = probeColor.copy(alpha = 0.26f),
            radius = 6.dp.toPx(),
            center = Offset(size.width * 0.70f, size.height * 0.34f),
        )
        drawCircle(
            color = probeColor,
            radius = 2.4.dp.toPx(),
            center = Offset(size.width * 0.70f, size.height * 0.34f),
        )
        drawCircle(
            color = orbitColor.copy(alpha = 0.88f),
            radius = 2.dp.toPx(),
            center = Offset(size.width * 0.30f, size.height * 0.66f),
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
