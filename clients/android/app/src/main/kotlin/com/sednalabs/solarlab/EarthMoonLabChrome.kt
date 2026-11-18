package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal object LabTags {
    const val ROOT = "lab.root"
    const val PLAY_PAUSE = "lab.play_pause"
    const val SPEED_PICKER = "lab.speed_picker"
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
    const val TOOLS_CLOSE = "lab.tools.close"
    const val ZOOM_RAIL = "lab.zoom"
    const val SCALE = "lab.scale"
    const val MODE_MAP = "lab.mode.map"
    const val MODE_3D = "lab.mode.3d"
    const val FOLLOW = "lab.follow"
    const val UNDO = "lab.undo"
    const val GESTURE_HINT = "lab.gesture_hint"
    const val DISMISS_GESTURE_HINT = "lab.gesture_hint.dismiss"
    fun focus(bodyId: String) = "lab.focus.$bodyId"
    fun load(id: String) = "lab.saved.load.$id"
    fun component(kind: String, axis: String) = "lab.edit.$kind.$axis"
}

/** Reserved content heights; the host adds the corresponding system-bar insets. */
internal object LabChromeMetrics {
    val PortraitDockHeight = 116.dp
    val LandscapeDockHeight = 64.dp
    val PortraitHeaderHeight = 96.dp
    val LandscapeHeaderHeight = 56.dp
}

internal enum class LabViewMode { ORBIT_MAP, THREE_DIMENSIONAL }

internal data class LabNavigationUiState(
    val viewMode: LabViewMode = LabViewMode.ORBIT_MAP,
    val followingSelection: Boolean = false,
    val metresPerPixel: Double? = null,
    val canUndo: Boolean = false,
    val selectionSubtitle: String? = null,
)

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
    val setViewMode: (LabViewMode) -> Unit = {},
    val toggleFollow: () -> Unit = {},
    val undo: () -> Unit = {},
    val enterFlight: () -> Unit = {},
)

internal val LabInk = Color(0xFFF0F5FA)
internal val LabMuted = Color(0xFF9EAFBF)
internal val LabBlue = Color(0xFF9CD9F4)
internal val LabPanel = Color(0xF20D1826)
internal val LabLine = Color(0xFF283D51)
internal val LabGold = Color(0xFFF1C985)

/** Camera, object and time controls have distinct homes around the live scene. */
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
    navigation: LabNavigationUiState = LabNavigationUiState(),
) {
    var speedSheetVisible by rememberSaveable { mutableStateOf(false) }
    val ready = uiState.connectionState == SessionConnectionState.Active
    val canEdit = ready && uiState.manualAdvance == null && uiState.pendingActionLabel == null
    val scenarioName = if (uiState.snapshot?.scenarioId == "lab.earth-moon") "Earth–Moon" else scenarioTitle

    BoxWithConstraints(modifier.fillMaxSize().testTag(LabTags.ROOT)) {
        val landscape = maxWidth > maxHeight && maxHeight < 600.dp
        val headerHeight = if (landscape) LabChromeMetrics.LandscapeHeaderHeight else LabChromeMetrics.PortraitHeaderHeight
        val dockHeight = if (landscape) LabChromeMetrics.LandscapeDockHeight else LabChromeMetrics.PortraitDockHeight
        Box(Modifier.fillMaxWidth().height(headerHeight + 24.dp)
            .background(Brush.verticalGradient(listOf(Color(0xF2050C16), Color.Transparent))))

        if (mode != StageChromeMode.MINIMAL) {
            LabSceneHeader(scenarioName, availableBodyIds, navigation, actions, landscape, canEdit,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().height(headerHeight))
            LabBottomDock(uiState, selectedBody, selectedName, selectionDescription,
                navigation, playbackPreset, actions, landscape, canEdit,
                availableBodyIds.any { it == "earth" || it == "moon" },
                onSpeed = { speedSheetVisible = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .height(dockHeight).fillMaxWidth().widthIn(max = 1_200.dp))
        } else {
            LabTextAction("Show controls", actions.toggleTools,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                tag = SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
        }

        // This layer has no full-screen gesture handler: the uncovered scene stays interactive.
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(top = if (mode == StageChromeMode.MINIMAL) 0.dp else headerHeight,
                bottom = if (mode == StageChromeMode.MINIMAL) 60.dp else dockHeight,
                start = 16.dp, end = 12.dp)) {
            LabZoomRail(actions, Modifier.align(Alignment.CenterEnd))
            if (mode == StageChromeMode.COLLAPSED && ready && pendingFrameName == null) {
                LabGestureHint(navigation.viewMode, Modifier.align(Alignment.TopStart).padding(top = 4.dp, end = 64.dp))
            }
            navigation.metresPerPixel?.takeIf { it.isFinite() && it > 0.0 }?.let {
                LabScaleRuler(it, Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp))
            }
            if (navigation.canUndo && !landscape) {
                Surface(Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp),
                    color = LabPanel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LabLine)) {
                    LabTextAction("Undo", actions.undo, tag = LabTags.UNDO, enabled = canEdit)
                }
            }
            pendingFrameName?.let {
                Text(it, Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                    .testTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST),
                    color = LabBlue, fontSize = 12.sp)
            }
        }
    }

    if (mode == StageChromeMode.EXPANDED) {
        LabMenuSheet(traceMode, cameraBand, stepPreset, ready, actions)
    }
    if (speedSheetVisible) {
        LabTimeSheet(uiState, playbackPreset, actions, canEdit) { speedSheetVisible = false }
    }
}

@Composable
private fun LabSceneHeader(
    scenarioName: String,
    availableBodyIds: Set<String>,
    navigation: LabNavigationUiState,
    actions: LabStageActions,
    landscape: Boolean,
    canEdit: Boolean,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(LabTags.COMPACT_HEADER)
        .semantics { isTraversalGroup = true; traversalIndex = 0f }
        .padding(horizontal = 12.dp, vertical = if (landscape) 4.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(22.dp)) {
                    drawCircle(LabBlue.copy(alpha = 0.35f), size.minDimension * 0.39f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    drawCircle(LabBlue, size.minDimension * 0.12f, Offset(size.width * 0.78f, size.height * 0.25f))
                    drawCircle(LabInk, size.minDimension * 0.17f)
                }
                Spacer(Modifier.width(9.dp))
                Text(scenarioName, Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SCENARIO_BADGE),
                    color = LabInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (landscape) {
                LabViewModePill(navigation.viewMode, actions.setViewMode)
                Spacer(Modifier.width(8.dp))
                LabDestinations(availableBodyIds, actions)
                Spacer(Modifier.width(8.dp))
            }
            if (landscape && navigation.canUndo) {
                LabTextAction("Undo", actions.undo, tag = LabTags.UNDO, enabled = canEdit)
            }
            LabTextAction("Fly", actions.enterFlight, tag = FlightTags.LAUNCH,
                description = "Launch orbital flight", enabled = canEdit && "earth" in availableBodyIds)
            // Objects is useful during initial loading too: opening it must not
            // silently ignore a tap while Rust is filling the body catalogue.
            LabTextAction("Objects", actions.search, tag = SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            TextButton(onClick = actions.toggleTools,
                modifier = Modifier.size(48.dp).testTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
                    .semantics { contentDescription = "Laboratory menu" },
                contentPadding = PaddingValues(0.dp)) {
                Canvas(Modifier.size(22.dp)) {
                    for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                        drawCircle(LabInk, 1.7.dp.toPx(), Offset(size.width * 0.5f, size.height * fraction))
                    }
                }
            }
        }
        if (!landscape) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                LabViewModePill(navigation.viewMode, actions.setViewMode)
                LabDestinations(availableBodyIds, actions)
            }
        }
    }
}

@Composable
private fun LabViewModePill(mode: LabViewMode, onChange: (LabViewMode) -> Unit) {
    Surface(color = Color(0xA6112333), shape = RoundedCornerShape(16.dp)) {
        Row {
            LabTextAction("Orbit map", { onChange(LabViewMode.ORBIT_MAP) },
                Modifier.semantics { selected = mode == LabViewMode.ORBIT_MAP }, tag = LabTags.MODE_MAP,
                active = mode == LabViewMode.ORBIT_MAP, description = "Orbit map view")
            LabTextAction("3D", { onChange(LabViewMode.THREE_DIMENSIONAL) },
                Modifier.semantics { selected = mode == LabViewMode.THREE_DIMENSIONAL }, tag = LabTags.MODE_3D,
                active = mode == LabViewMode.THREE_DIMENSIONAL, description = "Three-dimensional view")
        }
    }
}

@Composable
private fun LabDestinations(availableBodyIds: Set<String>, actions: LabStageActions) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        if ("earth" in availableBodyIds) LabTextAction("Earth", { actions.focus("earth") }, tag = LabTags.focus("earth"))
        if ("moon" in availableBodyIds) LabTextAction("Moon", { actions.focus("moon") }, tag = LabTags.focus("moon"))
        LabTextAction("System", actions.home, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON,
            description = "Fit the system in view")
    }
}

@Composable
private fun LabBottomDock(
    uiState: ShellUiState,
    selectedBody: RuntimeStageBody?,
    selectedName: String,
    selectionDescription: String?,
    navigation: LabNavigationUiState,
    playbackPreset: PlaybackSpeedPreset,
    actions: LabStageActions,
    landscape: Boolean,
    canEdit: Boolean,
    canAddProbe: Boolean,
    onSpeed: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier.testTag(LabTags.COMPACT_PLAYBACK)
        .semantics { isTraversalGroup = true; traversalIndex = 2f }
        .padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xF20B1926), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LabLine.copy(alpha = 0.6f))) {
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LabSelectionCard(selectedBody, selectedName, selectionDescription, navigation, actions, canEdit,
                    Modifier.weight(0.46f).height(48.dp))
                Box(Modifier.width(1.dp).height(28.dp).background(LabLine))
                LabPlaybackDock(uiState, playbackPreset, actions, canEdit, canAddProbe, onSpeed,
                    Modifier.weight(0.54f).height(56.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                LabSelectionCard(selectedBody, selectedName, selectionDescription, navigation, actions, canEdit,
                    Modifier.fillMaxWidth().height(48.dp))
                HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = LabLine.copy(alpha = 0.6f))
                LabPlaybackDock(uiState, playbackPreset, actions, canEdit, canAddProbe, onSpeed,
                    Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun LabSelectionCard(
    selectedBody: RuntimeStageBody?,
    selectedName: String,
    selectionDescription: String?,
    navigation: LabNavigationUiState,
    actions: LabStageActions,
    canEdit: Boolean,
    modifier: Modifier,
) {
    Row(modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(
            selectedBody?.let { Color(it.colorArgb) } ?: LabMuted, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(if (selectedBody != null) selectedName else "Choose an object",
                Modifier.testTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                    .semantics { contentDescription = selectionDescription ?: selectedName },
                color = LabInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(navigation.selectionSubtitle ?: if (navigation.followingSelection) "Following"
                else selectedBody?.let { "Radius ${formatLabDistance(it.radiusM)}" }
                ?: "Tap a name in the scene", color = LabMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LabTextAction("Focus", actions.frame, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON,
            enabled = selectedBody != null, description = "Focus on selected object")
        LabTextAction("Follow", actions.toggleFollow,
            tag = LabTags.FOLLOW, active = navigation.followingSelection, enabled = selectedBody != null,
            description = if (navigation.followingSelection) "Stop following selected object" else "Follow selected object")
        LabTextAction("Inspect", actions.inspect, tag = LabTags.INSPECT, enabled = canEdit && selectedBody != null)
    }
}

@Composable
private fun LabPlaybackDock(
    uiState: ShellUiState,
    playbackPreset: PlaybackSpeedPreset,
    actions: LabStageActions,
    canEdit: Boolean,
    canAddProbe: Boolean,
    onSpeed: () -> Unit,
    modifier: Modifier,
) {
    val running = uiState.snapshot?.paused == false
    Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = actions.togglePlayback,
            modifier = Modifier.size(48.dp).testTag(LabTags.PLAY_PAUSE)
                .semantics { contentDescription = if (running) "Pause simulation" else "Run simulation" },
            enabled = canEdit, shape = CircleShape, contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LabBlue, contentColor = Color(0xFF102433))) {
            LabPlaybackGlyph(running, Color(0xFF102433))
        }
        Column(Modifier.weight(1f)) {
            Text(uiState.pendingActionLabel ?: if (running) "Running" else "Paused",
                color = if (running) LabBlue else LabMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatLabEpoch(uiState.snapshot?.epochSeconds ?: 0.0), color = LabInk,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (uiState.manualAdvance != null) {
            LabTextAction("Cancel", actions.cancelAdvance,
                enabled = !uiState.manualAdvance.cancelRequested, description = "Cancel time advance")
        } else {
            TextButton(onClick = onSpeed, enabled = canEdit,
                modifier = Modifier.height(48.dp).widthIn(min = 60.dp).testTag(LabTags.SPEED_PICKER)
                    .semantics { contentDescription = "Time speed ${playbackPreset.label}" },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(playbackPreset.label, color = LabBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(if (uiState.playbackMeasurement?.limited == true) "Limited" else "Time speed",
                        color = LabMuted, fontSize = 10.sp)
                }
            }
        }
        Button(onClick = actions.addProbe,
            modifier = Modifier.height(48.dp).testTag(LabTags.ADD_PROBE),
            enabled = canEdit && canAddProbe, shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF17354A), contentColor = LabBlue)) {
            Text("Add probe", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LabGestureHint(mode: LabViewMode, modifier: Modifier) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var expired by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(mode) { delay(7_000); expired = true }
    if (!dismissed && !expired) {
        Surface(modifier.testTag(LabTags.GESTURE_HINT), color = Color(0xC9081723),
            shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (mode == LabViewMode.ORBIT_MAP) "Drag to pan · pinch to zoom" else "Drag to orbit · pinch to zoom",
                    Modifier.weight(1f, fill = false), color = LabMuted, fontSize = 12.sp,
                    maxLines = 2, lineHeight = 16.sp)
                LabTextAction("×", { dismissed = true }, Modifier.size(48.dp),
                    tag = LabTags.DISMISS_GESTURE_HINT, description = "Dismiss gesture hint")
            }
        }
    }
}

@Composable
private fun LabZoomRail(actions: LabStageActions, modifier: Modifier) {
    Column(modifier.testTag(LabTags.ZOOM_RAIL), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ZOOM", color = LabMuted, fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 5.dp))
        Surface(color = LabPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, LabLine)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LabRepeatZoomButton("+", "Zoom in", SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON, actions.zoomIn)
                HorizontalDivider(Modifier.width(24.dp), color = LabLine)
                LabRepeatZoomButton("−", "Zoom out", SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON, actions.zoomOut)
            }
        }
    }
}

@Composable
private fun LabRepeatZoomButton(label: String, description: String, tag: String, action: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentAction by rememberUpdatedState(action)
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(350)
            while (isActive) {
                currentAction()
                delay(110)
            }
        }
    }
    TextButton(onClick = action, interactionSource = interactionSource,
        modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)) {
        Text(label, color = LabInk, fontSize = 25.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun LabScaleRuler(metresPerPixel: Double, modifier: Modifier) {
    val targetPx = with(LocalDensity.current) { 76.dp.toPx() }
    val desiredDistance = targetPx * metresPerPixel
    val power = 10.0.pow(floor(log10(desiredDistance)))
    val mark = listOf(1.0, 2.0, 5.0).lastOrNull { it * power <= desiredDistance }?.times(power) ?: power
    val lineFraction = (mark / desiredDistance).toFloat().coerceIn(0f, 1f)
    Column(modifier.testTag(LabTags.SCALE)) {
        Text(formatLabDistance(mark), color = LabMuted, fontSize = 10.sp)
        Canvas(Modifier.width(76.dp).height(9.dp)) {
            val end = size.width * lineFraction
            val y = size.height * 0.55f
            drawLine(LabMuted, Offset(0f, y), Offset(end, y), 1.dp.toPx())
            drawLine(LabMuted, Offset(0f, y - 3.dp.toPx()), Offset(0f, y + 3.dp.toPx()), 1.dp.toPx())
            drawLine(LabMuted, Offset(end, y - 3.dp.toPx()), Offset(end, y + 3.dp.toPx()), 1.dp.toPx())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabMenuSheet(
    traceMode: TraceLayerMode,
    cameraBand: CameraScaleBand,
    stepPreset: StepQuantumPreset,
    ready: Boolean,
    actions: LabStageActions,
) {
    fun open(action: () -> Unit) { actions.toggleTools(); action() }
    ModalBottomSheet(onDismissRequest = actions.toggleTools,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D1826), contentColor = LabInk) {
        Column(Modifier.fillMaxWidth().testTag(LabTags.TOOLS_PANEL).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your laboratory", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                LabTextAction("Done", actions.toggleTools, tag = LabTags.TOOLS_CLOSE,
                    description = "Close laboratory tools")
            }
            LabMenuRow("Launch orbital flight", "Pilot Odyssey, change your orbit and explore", "flight.launch.menu", ready) { open(actions.enterFlight) }
            LabMenuRow("Saved experiments", "Save this moment or reopen an experiment", LabTags.SAVED, ready) { open(actions.saved) }
            LabMenuRow("Choose a scene", "Explore another starting system", SolarLabTestTags.STAGE_FIRST_SCENARIO_BUTTON) { open(actions.scenarios) }
            LabMenuRow("About this model", "Sources, epoch and scientific approximations", LabTags.SCIENCE) { open(actions.science) }
            LabMenuRow("Gesture guide", "Move, zoom and choose objects", SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON) { open(actions.help) }
            HorizontalDivider(color = LabLine)
            Text("View & paths", color = LabMuted, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag(SolarLabTestTags.STAGE_FIRST_CAMERA_SCALE_CHIP)) {
                CameraScaleBand.entries.forEach { band ->
                    LabTextAction(band.label, { actions.setScale(band) },
                        tag = SolarLabTestTags.stageFirstCameraScalePresetTag(band.name), active = band == cameraBand)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Orbit paths", color = LabInk, fontSize = 15.sp)
                    Text(when (traceMode) { TraceLayerMode.OFF -> "Hidden"; TraceLayerMode.FOCUS -> "Selected objects"; TraceLayerMode.ALL -> "All objects" },
                        color = LabMuted, fontSize = 12.sp)
                }
                LabTextAction("Less", actions.fewerTraces, tag = SolarLabTestTags.STAGE_FIRST_TRACE_LESS_BUTTON, enabled = traceMode != TraceLayerMode.OFF)
                LabTextAction("More", actions.moreTraces, tag = SolarLabTestTags.STAGE_FIRST_TRACE_MORE_BUTTON, enabled = traceMode != TraceLayerMode.ALL)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Advance time", Modifier.weight(1f), color = LabInk, fontSize = 15.sp)
                LabTextAction(stepPreset.label, actions.cycleStep, tag = SolarLabTestTags.STAGE_FIRST_STEP_DURATION)
                LabTextAction("Advance", actions.step, enabled = ready)
            }
            LabMenuRow("Diagnostics", "Runtime and rendering details", SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON) { open(actions.debug) }
        }
    }
}

@Composable
private fun LabMenuRow(title: String, subtitle: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) LabInk else LabMuted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = LabMuted, fontSize = 12.sp)
        }
        Text("›", color = LabMuted, fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabTimeSheet(uiState: ShellUiState, preset: PlaybackSpeedPreset, actions: LabStageActions,
    canEdit: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D1826), contentColor = LabInk) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Time speed", style = MaterialTheme.typography.titleLarge)
            Text("How quickly the experiment moves through time.", color = LabMuted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                LabTextAction("Slower", actions.speedDown, tag = LabTags.SPEED_DOWN, enabled = canEdit)
                Text(preset.label, color = LabBlue, fontSize = 30.sp, fontWeight = FontWeight.Light)
                LabTextAction("Faster", actions.speedUp, tag = LabTags.SPEED_UP, enabled = canEdit)
            }
            uiState.playbackMeasurement?.achievedRate?.let {
                Text("${if (uiState.playbackMeasurement?.limited == true) "Limited to" else "Currently achieving"} ${formatLabRate(it)}",
                    color = LabMuted, fontSize = 13.sp)
            }
            if (uiState.playbackMeasurement?.limited == true) {
                Text("The simulation is taking smaller steps to preserve accuracy.", color = LabMuted, fontSize = 12.sp)
            }
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Done") }
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
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = if (active) LabBlue else LabInk,
            containerColor = if (active) Color(0xFF193B51) else Color.Transparent,
            disabledContentColor = LabMuted.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(label, fontFamily = FontFamily.SansSerif, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabPlaybackGlyph(running: Boolean, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
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
