package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import com.sednalabs.solarlab.runtime.PlaybackSpeedPreset
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.StepQuantumPreset
import java.util.Locale
import kotlin.math.abs

internal object LabTags {
    const val ROOT = "lab.root"
    const val PLAY_PAUSE = "lab.play_pause"
    const val SPEED_DOWN = "lab.speed_down"
    const val SPEED_UP = "lab.speed_up"
    const val INSPECT = "lab.inspect"
    const val ADD_PROBE = "lab.add_probe"
    const val SAVED = "lab.saved"
    const val SCIENCE = "lab.science"
    const val EDIT = "lab.edit"
    const val EDIT_COMMIT = "lab.edit.commit"
    const val PROBE_COMMIT = "lab.probe.commit"
    const val SAVE_NAME = "lab.save.name"
    const val SAVE_COMMIT = "lab.save.commit"
    const val SAVED_DIALOG = "lab.saved.dialog"
    const val EDITOR = "lab.editor"
    const val INSPECTOR = "lab.inspector"
    const val COMPACT_HEADER = "lab.compact.header"
    const val COMPACT_PLAYBACK = "lab.compact.playback"
    const val TOOLS_PANEL = "lab.tools"
    fun focus(bodyId: String) = "lab.focus.$bodyId"
    fun load(id: String) = "lab.saved.load.$id"
    fun component(kind: String, axis: String) = "lab.edit.$kind.$axis"
}

internal data class LabStageActions(
    val focus: (String) -> Unit,
    val home: () -> Unit,
    val frame: () -> Unit,
    val togglePlayback: () -> Unit,
    val speedDown: () -> Unit,
    val speedUp: () -> Unit,
    val step: () -> Unit,
    val cycleStep: () -> Unit,
    val inspect: () -> Unit,
    val addProbe: () -> Unit,
    val saved: () -> Unit,
    val science: () -> Unit,
    val search: () -> Unit,
    val scenarios: () -> Unit,
    val toggleTools: () -> Unit,
    val hideChrome: () -> Unit,
    val help: () -> Unit,
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val setScale: (CameraScaleBand) -> Unit,
    val fewerTraces: () -> Unit,
    val moreTraces: () -> Unit,
    val debug: () -> Unit,
    val cancelAdvance: () -> Unit,
)

private val LabInk = Color(0xFFF1F3F5)
private val LabMuted = Color(0xFF9BA9B6)
private val LabBlue = Color(0xFF91C6EA)
private val LabPanel = Color(0xED101720)
private val LabLine = Color(0xFF2A3643)

/** Controls remain usable over the live native world; every action reaches the same runtime. */
@Composable
internal fun EarthMoonLabChrome(
    uiState: ShellUiState,
    selectedBody: RuntimeStageBody?,
    selectedName: String,
    selectionDescription: String?,
    scenarioTitle: String,
    availableBodyIds: Set<String>,
    mode: StageChromeMode,
    traceMode: TraceLayerMode,
    cameraBand: CameraScaleBand,
    stepPreset: StepQuantumPreset,
    playbackPreset: PlaybackSpeedPreset,
    pendingFrameName: String?,
    actions: LabStageActions,
    modifier: Modifier = Modifier,
) {
    val snapshot = uiState.snapshot
    val running = snapshot?.paused == false
    val ready = uiState.connectionState == SessionConnectionState.Active
    val canEdit = ready && uiState.manualAdvance == null && uiState.pendingActionLabel == null
    val compactScenario = if (snapshot?.scenarioId == "lab.earth-moon") "EARTH–MOON LABORATORY" else scenarioTitle.uppercase(Locale.US)

    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(LabTags.ROOT)) {
        val compactHeight = maxHeight < 520.dp && maxWidth > maxHeight
        // Quiet edge shading gives controls contrast without washing out the astronomical scene.
        Box(
            Modifier.fillMaxWidth().height(if (compactHeight) 110.dp else 190.dp)
                .background(Brush.verticalGradient(listOf(Color(0xCC04080E), Color.Transparent))),
        )
        if (compactHeight && mode != StageChromeMode.MINIMAL) {
            CompactHeightLabChrome(uiState, selectedBody, selectedName, selectionDescription,
                compactScenario, availableBodyIds, mode, traceMode, cameraBand, stepPreset,
                playbackPreset, pendingFrameName, actions)
        } else if (mode != StageChromeMode.MINIMAL) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SOLAR GRAVITY LAB", color = LabMuted, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                        Text(compactScenario, modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SCENARIO_BADGE),
                            color = LabInk, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LabTextAction("Model", actions.science, tag = LabTags.SCIENCE)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier.weight(1f).testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            selectedName,
                            modifier = Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                                .semantics { contentDescription = selectionDescription ?: selectedName },
                            color = LabInk, fontSize = 31.sp, lineHeight = 35.sp,
                            fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            selectedBody?.let { "Radius ${formatLabDistance(it.radiusM)}" }
                                ?: "A small world of possibilities",
                            color = LabMuted, fontSize = 12.sp,
                        )
                        pendingFrameName?.let {
                            Text(it, Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST),
                                color = LabBlue, fontSize = 11.sp)
                        }
                    }
                    LabTextAction("Find", actions.search, tag = SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON,
                        enabled = availableBodyIds.isNotEmpty())
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 760.dp)
                    .fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if ("earth" in availableBodyIds) {
                        LabTextAction("Earth", { actions.focus("earth") }, tag = LabTags.focus("earth"),
                            active = selectedBody?.id == "earth")
                    }
                    if ("moon" in availableBodyIds) {
                        LabTextAction("Moon", { actions.focus("moon") }, tag = LabTags.focus("moon"),
                            active = selectedBody?.id == "moon")
                    }
                    LabTextAction("Overview", actions.home, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON)
                    LabTextAction("Frame", actions.frame, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON,
                        enabled = selectedBody != null)
                    LabTextAction("Help", actions.help, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
                }

                if (mode == StageChromeMode.EXPANDED) {
                    LabToolsPanel(traceMode, cameraBand, stepPreset, ready, actions)
                }

                Surface(color = LabPanel, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, LabLine)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = actions.togglePlayback,
                                modifier = Modifier.size(58.dp).testTag(LabTags.PLAY_PAUSE)
                                    .semantics { contentDescription = if (running) "Pause simulation" else "Run simulation" },
                                shape = CircleShape,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LabBlue, contentColor = Color(0xFF102233)),
                                enabled = canEdit,
                            ) { LabPlaybackGlyph(running, Color(0xFF102233)) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (running) "SIMULATION RUNNING" else "SIMULATION PAUSED",
                                    color = LabMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                                Text(formatLabEpoch(snapshot?.epochSeconds ?: 0.0), color = LabInk,
                                    fontSize = 19.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                if (uiState.playbackMeasurement?.limited == true) {
                                    Text("Speed limited to preserve accuracy", color = LabBlue, fontSize = 10.sp)
                                }
                                if (running) uiState.playbackMeasurement?.achievedRate?.let { rate ->
                                    Text("Achieved ${formatLabRate(rate)}", color = LabMuted, fontSize = 10.sp)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(playbackPreset.label, color = LabBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Row {
                                    LabTextAction("−", actions.speedDown, tag = LabTags.SPEED_DOWN,
                                        description = "Slower simulation", enabled = canEdit)
                                    LabTextAction("+", actions.speedUp, tag = LabTags.SPEED_UP,
                                        description = "Faster simulation", enabled = canEdit)
                                }
                            }
                        }
                        uiState.manualAdvance?.let { advance ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Advancing · ${advance.completedSteps} / ${advance.totalSteps}",
                                    Modifier.weight(1f), color = LabMuted, fontSize = 12.sp)
                                LabTextAction("Cancel", actions.cancelAdvance, enabled = !advance.cancelRequested)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LabTextAction("Inspect", actions.inspect, tag = LabTags.INSPECT,
                                enabled = canEdit && selectedBody != null)
                            LabTextAction("Add probe", actions.addProbe, tag = LabTags.ADD_PROBE,
                                enabled = canEdit && "earth" in availableBodyIds)
                            LabTextAction("Saved", actions.saved, tag = LabTags.SAVED, enabled = ready)
                            LabTextAction(if (mode == StageChromeMode.EXPANDED) "Less" else "Tools",
                                actions.toggleTools, tag = SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON,
                                active = mode == StageChromeMode.EXPANDED)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                LabTextAction("Controls", actions.toggleTools, tag = SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            }
        }
    }
}

/** Short windows reserve separate header, stage/tools, and playback regions. */
@Composable
private fun CompactHeightLabChrome(
    uiState: ShellUiState,
    selectedBody: RuntimeStageBody?,
    selectedName: String,
    selectionDescription: String?,
    scenarioTitle: String,
    availableBodyIds: Set<String>,
    mode: StageChromeMode,
    traceMode: TraceLayerMode,
    cameraBand: CameraScaleBand,
    stepPreset: StepQuantumPreset,
    playbackPreset: PlaybackSpeedPreset,
    pendingFrameName: String?,
    actions: LabStageActions,
) {
    val running = uiState.snapshot?.paused == false
    val ready = uiState.connectionState == SessionConnectionState.Active
    val canEdit = ready && uiState.manualAdvance == null && uiState.pendingActionLabel == null
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().testTag(LabTags.COMPACT_HEADER),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(172.dp).testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL)) {
                Text(selectedName,
                    Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                        .semantics { contentDescription = selectionDescription ?: selectedName },
                    color = LabInk, fontSize = 24.sp, fontWeight = FontWeight.Light,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scenarioTitle, Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SCENARIO_BADGE),
                    color = LabMuted, fontSize = 9.sp, letterSpacing = 0.7.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                pendingFrameName?.let {
                    Text(it, Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST),
                        color = LabBlue, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                if ("earth" in availableBodyIds) {
                    LabTextAction("Earth", { actions.focus("earth") }, tag = LabTags.focus("earth"),
                        active = selectedBody?.id == "earth")
                }
                if ("moon" in availableBodyIds) {
                    LabTextAction("Moon", { actions.focus("moon") }, tag = LabTags.focus("moon"),
                        active = selectedBody?.id == "moon")
                }
                LabTextAction("Overview", actions.home, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON)
                LabTextAction("Frame", actions.frame, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON,
                    enabled = selectedBody != null)
                LabTextAction("Help", actions.help, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
            }
            LabTextAction("Find", actions.search, tag = SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON,
                enabled = availableBodyIds.isNotEmpty())
            LabTextAction("Model", actions.science, tag = LabTags.SCIENCE)
        }

        // Tools scroll only within the available middle region. Neither playback
        // nor the selected body can be covered by an expanded panel.
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            if (mode == StageChromeMode.EXPANDED) {
                LabToolsPanel(traceMode, cameraBand, stepPreset, ready, actions,
                    Modifier.align(Alignment.CenterEnd).widthIn(max = 520.dp).fillMaxWidth(0.72f))
            }
        }

        Surface(modifier = Modifier.fillMaxWidth().testTag(LabTags.COMPACT_PLAYBACK),
            color = LabPanel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, LabLine)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions.togglePlayback,
                    modifier = Modifier.size(52.dp).testTag(LabTags.PLAY_PAUSE)
                        .semantics { contentDescription = if (running) "Pause simulation" else "Run simulation" },
                    shape = CircleShape, enabled = canEdit,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlue, contentColor = Color(0xFF102233)),
                ) { LabPlaybackGlyph(running, Color(0xFF102233)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.width(152.dp)) {
                    Text(if (running) "RUNNING" else "PAUSED", color = LabMuted,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
                    Text(formatLabEpoch(uiState.snapshot?.epochSeconds ?: 0.0), color = LabInk,
                        fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (running) uiState.playbackMeasurement?.achievedRate?.let { rate ->
                        Text("${if (uiState.playbackMeasurement?.limited == true) "Limited" else "Achieved"} ${formatLabRate(rate)}",
                            color = LabBlue, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    uiState.manualAdvance?.let { advance ->
                        LabTextAction("Cancel advance", actions.cancelAdvance, enabled = !advance.cancelRequested)
                    }
                    LabTextAction("−", actions.speedDown, tag = LabTags.SPEED_DOWN,
                        description = "Slower simulation", enabled = canEdit)
                    Text(playbackPreset.label, color = LabBlue, fontSize = 12.sp, maxLines = 1)
                    LabTextAction("+", actions.speedUp, tag = LabTags.SPEED_UP,
                        description = "Faster simulation", enabled = canEdit)
                    LabTextAction("Inspect", actions.inspect, tag = LabTags.INSPECT,
                        enabled = canEdit && selectedBody != null)
                    LabTextAction("Add probe", actions.addProbe, tag = LabTags.ADD_PROBE,
                        enabled = canEdit && "earth" in availableBodyIds)
                    LabTextAction("Saved", actions.saved, tag = LabTags.SAVED, enabled = ready)
                }
                LabTextAction(if (mode == StageChromeMode.EXPANDED) "Less" else "Tools", actions.toggleTools,
                    tag = SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON, active = mode == StageChromeMode.EXPANDED)
            }
        }
    }
}

@Composable
private fun LabToolsPanel(
    traceMode: TraceLayerMode,
    cameraBand: CameraScaleBand,
    stepPreset: StepQuantumPreset,
    ready: Boolean,
    actions: LabStageActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.testTag(LabTags.TOOLS_PANEL), color = LabPanel,
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, LabLine)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 216.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                LabTextAction("Scenes", actions.scenarios, tag = SolarLabTestTags.STAGE_FIRST_SCENARIO_BUTTON)
                LabTextAction("Clean view", actions.hideChrome)
                LabTextAction("Zoom +", actions.zoomIn, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
                LabTextAction("Zoom −", actions.zoomOut, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_SCALE_CHIP)) {
                CameraScaleBand.entries.forEach { band ->
                    LabTextAction(band.label, { actions.setScale(band) },
                        tag = SolarLabTestTags.stageFirstCameraScalePresetTag(band.name), active = band == cameraBand)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("TRAILS", color = LabMuted, fontSize = 9.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 8.dp))
                LabTextAction("Less", actions.fewerTraces, tag = SolarLabTestTags.STAGE_FIRST_TRACE_LESS_BUTTON,
                    enabled = traceMode != TraceLayerMode.OFF)
                Text(when (traceMode) { TraceLayerMode.OFF -> "Hidden"; TraceLayerMode.FOCUS -> "Focused"; TraceLayerMode.ALL -> "All" },
                    color = LabBlue, fontSize = 11.sp)
                LabTextAction("More", actions.moreTraces, tag = SolarLabTestTags.STAGE_FIRST_TRACE_MORE_BUTTON,
                    enabled = traceMode != TraceLayerMode.ALL)
                LabTextAction("Step ${stepPreset.label}", actions.step, enabled = ready)
                LabTextAction(stepPreset.label, actions.cycleStep, tag = SolarLabTestTags.STAGE_FIRST_STEP_DURATION)
            }
            Row(Modifier.fillMaxWidth().testTag(SolarLabTestTags.STAGE_FIRST_STATUS_PANEL),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Changes affect the shared simulation.", color = LabMuted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                LabTextAction("Diagnostics", actions.debug, tag = SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON)
            }
        }
    }
}

@Composable
internal fun LabTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    description: String = label,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active) LabBlue else LabInk,
            containerColor = if (active) Color(0x332F648D) else Color.Transparent,
            disabledContentColor = LabMuted.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabPlaybackGlyph(running: Boolean, tint: Color) {
    Canvas(Modifier.size(21.dp)) {
        if (running) {
            val barWidth = size.width * 0.25f
            drawRoundRect(tint, Offset(size.width * 0.13f, 0f), Size(barWidth, size.height), CornerRadius(2.dp.toPx()))
            drawRoundRect(tint, Offset(size.width * 0.62f, 0f), Size(barWidth, size.height), CornerRadius(2.dp.toPx()))
        } else {
            val triangle = Path().apply {
                moveTo(size.width * 0.17f, 0f)
                lineTo(size.width, size.height * 0.5f)
                lineTo(size.width * 0.17f, size.height)
                close()
            }
            drawPath(triangle, tint)
        }
    }
}

internal fun formatLabEpoch(seconds: Double): String {
    if (!seconds.isFinite()) return "Time unavailable"
    val negative = seconds < 0.0
    val totalMinutes = (abs(seconds) / 60.0).toLong()
    val days = totalMinutes / 1_440
    val hours = (totalMinutes % 1_440) / 60
    val minutes = totalMinutes % 60
    return String.format(Locale.US, "%sDay %d · %02d:%02d", if (negative) "−" else "", days, hours, minutes)
}

internal fun formatLabDistance(metres: Double): String = when {
    !metres.isFinite() -> "unavailable"
    abs(metres) >= 1.495978707e11 -> String.format(Locale.US, "%.3f AU", metres / 1.495978707e11)
    abs(metres) >= 1_000.0 -> String.format(Locale.US, "%,.0f km", metres / 1_000.0)
    else -> String.format(Locale.US, "%.1f m", metres)
}

internal fun formatLabRate(rate: Double): String = when {
    !rate.isFinite() || rate < 0.0 -> "unavailable"
    rate >= 86_400.0 -> String.format(Locale.US, "%.2f days/s", rate / 86_400.0)
    rate >= 3_600.0 -> String.format(Locale.US, "%.2f h/s", rate / 3_600.0)
    rate >= 60.0 -> String.format(Locale.US, "%.2f min/s", rate / 60.0)
    else -> String.format(Locale.US, "%.2f sim s/s", rate)
}
