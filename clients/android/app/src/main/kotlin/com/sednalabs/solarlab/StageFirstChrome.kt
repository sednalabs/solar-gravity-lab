package com.sednalabs.solarlab

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
