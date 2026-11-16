package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

internal enum class LabPlacementMotion { CIRCULAR, RELEASE, CUSTOM }
internal enum class LabPlacementDirection { PROGRADE, RETROGRADE }

internal data class LabPlacementHost(val id: String, val name: String)

/** All values and preview messages come from the live placement model. */
internal data class LabPlacementUiState(
    val hostBodyId: String,
    val hosts: List<LabPlacementHost>,
    val planeLabel: String,
    val altitudeKmText: String,
    val altitudeRangeKm: ClosedFloatingPointRange<Float>,
    val motion: LabPlacementMotion,
    val speedKmPerSecondText: String,
    val direction: LabPlacementDirection,
    val previewStatus: String,
    val previewHorizonText: String? = null,
    val error: String? = null,
    val canCreate: Boolean = true,
    val committing: Boolean = false,
    val altitudeLocked: Boolean = false,
    val headingDegreesText: String = "90",
)

internal data class LabPlacementActions(
    val onHostChange: (String) -> Unit,
    val onAltitudeChange: (String) -> Unit,
    val onMotionChange: (LabPlacementMotion) -> Unit,
    val onSpeedChange: (String) -> Unit,
    val onDirectionChange: (LabPlacementDirection) -> Unit,
    val onCreate: () -> Unit,
    val onCancel: () -> Unit,
    val onAltitudeLockChange: (Boolean) -> Unit = {},
    val onHeadingChange: (String) -> Unit = {},
)

internal object LabPlacementTags {
    const val ROOT = "lab.placement"
    const val CONTENT = "lab.placement.content"
    const val ALTITUDE = "lab.placement.altitude"
    const val ALTITUDE_SLIDER = "lab.placement.altitude.slider"
    const val ALTITUDE_LOCK = "lab.placement.altitude.lock"
    const val SPEED = "lab.placement.speed"
    const val HEADING = "lab.placement.heading"
    const val PREVIEW = "lab.placement.preview"
    const val CREATE = LabTags.PROBE_COMMIT
    const val CANCEL = "lab.placement.cancel"
    const val SECTION_POSITION = "lab.placement.section.position"
    const val SECTION_MOTION = "lab.placement.section.motion"
    fun host(id: String) = "lab.placement.host.$id"
    fun motion(value: LabPlacementMotion) = "lab.placement.motion.${value.name.lowercase(Locale.US)}"
    fun direction(value: LabPlacementDirection) = "lab.placement.direction.${value.name.lowercase(Locale.US)}"
}

internal object LabPlacementMetrics {
    val SceneHeaderHeight = 56.dp
    val PortraitHeight = 312.dp
    val LandscapeWidth = 344.dp
}

private enum class PlacementSection { POSITION, MOTION }

/** The scene has one title; the panel below it describes position and motion. */
@Composable
internal fun LabPlacementSceneBar(
    planeLabel: String,
    onFitPath: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = androidx.compose.ui.graphics.Color(0xF207111D)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(6.dp).background(LabGold, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("New probe", color = LabInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(planeLabel, color = LabMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LabTextAction("Fit path", onFitPath, description = "Fit the probe and its predicted path in view")
            LabTextAction("−", onZoomOut, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON,
                description = "Zoom out")
            LabTextAction("+", onZoomIn, tag = SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON,
                description = "Zoom in")
        }
    }
}

/**
 * A nonmodal panel. The host reserves a bounded region below or beside the scene;
 * only the panel receives input, so the ghost and velocity arrow remain touchable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabPlacementSheet(
    state: LabPlacementUiState,
    actions: LabPlacementActions,
    modifier: Modifier = Modifier,
) {
    var sectionName by rememberSaveable {
        mutableStateOf(if (state.motion == LabPlacementMotion.CUSTOM) PlacementSection.MOTION.name else PlacementSection.POSITION.name)
    }
    var previousMotion by rememberSaveable { mutableStateOf(state.motion.name) }
    val section = PlacementSection.valueOf(sectionName)
    val positionScroll = rememberScrollState()
    val motionScroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun showSection(value: PlacementSection) {
        focus.clearFocus()
        keyboard?.hide()
        sectionName = value.name
    }
    // Dragging the scene's velocity handle is a motion edit, so expose its exact values.
    LaunchedEffect(state.motion) {
        if (state.motion == LabPlacementMotion.CUSTOM && previousMotion != state.motion.name) {
            sectionName = PlacementSection.MOTION.name
        }
        previousMotion = state.motion.name
    }
    Surface(modifier.testTag(LabPlacementTags.ROOT), color = LabPanel,
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ColorGoldOutline.copy(alpha = 0.65f))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 256.dp
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compactHeight) 0.dp else 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlacementSectionTab("Position", "${state.hosts.firstOrNull { it.id == state.hostBodyId }?.name ?: state.hostBodyId} · ${state.altitudeKmText} km",
                        section == PlacementSection.POSITION, !state.committing, LabPlacementTags.SECTION_POSITION,
                        Modifier.weight(1f)) { showSection(PlacementSection.POSITION) }
                    PlacementSectionTab("Motion", when (state.motion) {
                        LabPlacementMotion.CIRCULAR -> "Circular orbit"
                        LabPlacementMotion.RELEASE -> "Released at rest"
                        LabPlacementMotion.CUSTOM -> "${state.speedKmPerSecondText} km/s · ${state.headingDegreesText}°"
                    }, section == PlacementSection.MOTION, !state.committing, LabPlacementTags.SECTION_MOTION,
                        Modifier.weight(1f)) { showSection(PlacementSection.MOTION) }
                }
                HorizontalDivider(color = LabLine.copy(alpha = 0.6f))
                Column(Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(if (section == PlacementSection.POSITION) positionScroll else motionScroll)
                    .testTag(LabPlacementTags.CONTENT)
                    .padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (section == PlacementSection.POSITION) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Around", color = LabMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                            FlowRow(Modifier.weight(1f)) {
                                state.hosts.forEach { host ->
                                    PlacementChoice(host.name, state.hostBodyId == host.id, !state.committing,
                                        LabPlacementTags.host(host.id)) { actions.onHostChange(host.id) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlacementNumberField(state.altitudeKmText, "Altitude · km", LabPlacementTags.ALTITUDE,
                                !state.committing, Modifier.weight(1f), actions.onAltitudeChange)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(checked = state.altitudeLocked, onCheckedChange = actions.onAltitudeLockChange,
                                    enabled = !state.committing,
                                    modifier = Modifier.size(48.dp).testTag(LabPlacementTags.ALTITUDE_LOCK)
                                        .semantics { contentDescription = "Lock altitude while placing" },
                                    colors = CheckboxDefaults.colors(checkedColor = LabGold))
                                Text("Lock altitude", color = LabMuted, fontSize = 10.sp)
                            }
                        }
                        PlacementAltitudeSlider(state, actions.onAltitudeChange)
                        Text(if (state.altitudeLocked) "Drag the gold marker along the altitude ring."
                            else "Tap the map or drag the gold marker to place it.",
                            color = LabMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    } else {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabPlacementMotion.entries.forEach { motion ->
                                PlacementChoice(when (motion) {
                                    LabPlacementMotion.CIRCULAR -> "Circular"
                                    LabPlacementMotion.RELEASE -> "Release"
                                    LabPlacementMotion.CUSTOM -> "Custom"
                                }, state.motion == motion, !state.committing, LabPlacementTags.motion(motion)) {
                                    actions.onMotionChange(motion)
                                }
                            }
                        }
                        when (state.motion) {
                            LabPlacementMotion.CIRCULAR -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Starting speed", color = LabMuted, fontSize = 11.sp)
                                        Text("${state.speedKmPerSecondText} km/s", color = LabInk, fontSize = 16.sp,
                                            modifier = Modifier.testTag(LabPlacementTags.SPEED))
                                    }
                                    PlacementDirections(state, actions)
                                }
                                Text("A circular starting speed around this body. Other bodies can change the orbit.",
                                    color = LabMuted, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                            LabPlacementMotion.RELEASE -> Text(
                                "Starts at rest relative to this body, then falls under gravity.",
                                color = LabMuted, fontSize = 12.sp, lineHeight = 18.sp)
                            LabPlacementMotion.CUSTOM -> {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PlacementNumberField(state.speedKmPerSecondText, "Speed · km/s", LabPlacementTags.SPEED,
                                        !state.committing, Modifier.weight(1f), actions.onSpeedChange)
                                    PlacementNumberField(state.headingDegreesText, "Heading · °", LabPlacementTags.HEADING,
                                        !state.committing, Modifier.weight(1f), actions.onHeadingChange)
                                }
                                Text("Drag the arrow to set direction and speed, or enter exact values here.",
                                    color = LabMuted, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(color = LabLine.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compactHeight) 4.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onCancel, enabled = !state.committing,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(LabPlacementTags.CANCEL)) {
                        Text("Cancel", color = LabMuted)
                    }
                    Column(Modifier.weight(1f).testTag(LabPlacementTags.PREVIEW), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(state.error ?: state.previewStatus,
                            color = if (state.error != null) MaterialTheme.colorScheme.error else LabGold,
                            fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        state.previewHorizonText?.let {
                            Text(it, color = LabMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = actions.onCreate, enabled = state.canCreate && !state.committing,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(LabPlacementTags.CREATE),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LabGold, contentColor = ColorGoldInk)) {
                        Text(if (state.committing) "Creating…" else "Create probe", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacementSectionTab(
    title: String,
    summary: String,
    isSelected: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.height(48.dp).testTag(tag).semantics { selected = isSelected },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) ColorGoldWash else androidx.compose.ui.graphics.Color.Transparent)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = if (isSelected) LabGold else LabInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(summary, color = LabMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val ColorGoldOutline = androidx.compose.ui.graphics.Color(0xFF5C4D36)
private val ColorGoldWash = androidx.compose.ui.graphics.Color(0xFF242324)
private val ColorGoldInk = androidx.compose.ui.graphics.Color(0xFF312716)

@Composable
private fun PlacementNumberField(
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) }, modifier = modifier.testTag(tag),
        singleLine = true, enabled = enabled, shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
}

@Composable
private fun PlacementChoice(label: String, isSelected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { selected = isSelected },
        contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (isSelected) LabGold else LabMuted,
            containerColor = if (isSelected) ColorGoldWash else androidx.compose.ui.graphics.Color.Transparent)) {
        Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlacementDirections(state: LabPlacementUiState, actions: LabPlacementActions) {
    Row {
        PlacementChoice("Prograde", state.direction == LabPlacementDirection.PROGRADE,
            !state.committing, LabPlacementTags.direction(LabPlacementDirection.PROGRADE)) {
            actions.onDirectionChange(LabPlacementDirection.PROGRADE)
        }
        PlacementChoice("Retrograde", state.direction == LabPlacementDirection.RETROGRADE,
            !state.committing, LabPlacementTags.direction(LabPlacementDirection.RETROGRADE)) {
            actions.onDirectionChange(LabPlacementDirection.RETROGRADE)
        }
    }
}

@Composable
private fun PlacementAltitudeSlider(state: LabPlacementUiState, onChange: (String) -> Unit) {
    val lower = state.altitudeRangeKm.start.toDouble().coerceAtLeast(0.0)
    val upper = state.altitudeRangeKm.endInclusive.toDouble()
    val validRange = lower.isFinite() && upper.isFinite() && upper > lower
    val logMin = if (validRange) ln(lower + 1.0) else 0.0
    val logSpan = if (validRange) ln(upper + 1.0) - logMin else 1.0
    val altitude = state.altitudeKmText.toDoubleOrNull()?.takeIf { it.isFinite() } ?: lower
    val progress = if (validRange) ((ln(altitude.coerceIn(lower, upper) + 1.0) - logMin) / logSpan).toFloat() else 0f
    Slider(value = progress.coerceIn(0f, 1f), onValueChange = {
        val km = (exp(logMin + it * logSpan) - 1.0).coerceIn(lower, upper)
        onChange(String.format(Locale.US, "%.0f", km))
    }, enabled = validRange && !state.committing,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LabPlacementTags.ALTITUDE_SLIDER)
            .semantics { contentDescription = "Probe altitude" },
        colors = SliderDefaults.colors(thumbColor = LabGold, activeTrackColor = LabGold))
}
