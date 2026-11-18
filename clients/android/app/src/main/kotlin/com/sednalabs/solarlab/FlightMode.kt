package com.sednalabs.solarlab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal object FlightTags {
    const val LAUNCH = "flight.launch"
    const val ROOT = "flight.root"
    const val EXIT = "flight.exit"
    const val THROTTLE = "flight.throttle"
    const val CUT = "flight.cut"
    const val PLAY_PAUSE = "flight.play_pause"
    const val FUEL = "flight.fuel"
    const val ALTITUDE = "flight.altitude"
    const val APOAPSIS = "flight.apoapsis"
    const val CHECKPOINT = "flight.checkpoint"
    const val RESET = "flight.reset"
    const val SAVE = "flight.save"
    fun assist(value: FlightAssist) = "flight.assist.${value.name}"
    fun camera(mode: Int) = "flight.camera.$mode"
    fun axis(name: String) = "flight.axis.$name"
    fun warp(rate: Int) = "flight.warp.$rate"
}

internal object FlightChromeMetrics {
    val Header = 112.dp
    val PortraitDock = 302.dp
    val LandscapeDock = 186.dp
}

private val FlightInk = Color(0xFFEDF6FF)
private val FlightMuted = Color(0xFF9CAEBF)
private val FlightTeal = Color(0xFF8BE6DC)
private val FlightAmber = Color(0xFFFFC582)
private val FlightPanel = Color(0xF00B1724)
private val FlightLine = Color(0xFF294451)

/** A touchscreen cockpit over the same native scene and same Rust session as the laboratory. */
@Composable
internal fun FlightModeChrome(
    facade: RuntimeFacade,
    uiState: ShellUiState,
    cameraMode: Int,
    onCamera: (Int) -> Unit,
    onExit: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val commands = remember(facade, uiState.sessionHandle) { Channel<FlightControl>(Channel.CONFLATED) }
    var control by remember(facade, uiState.sessionHandle) { mutableStateOf(FlightControl()) }
    var state by remember(facade, uiState.sessionHandle) { mutableStateOf<FlightState?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var controlError by remember { mutableStateOf<String?>(null) }
    var checkpoint by rememberSaveable { mutableStateOf<String?>(null) }
    var checkpointSession by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(uiState.sessionHandle) {
        if (checkpointSession != (uiState.sessionHandle ?: 0L)) {
            checkpoint = null
            checkpointSession = uiState.sessionHandle ?: 0L
        }
    }
    var translationMode by rememberSaveable { mutableStateOf(false) }
    var missionHelp by rememberSaveable { mutableStateOf(true) }

    fun send(value: FlightControl) {
        control = value
        commands.trySend(value)
    }
    fun cut() { send(control.copy(throttle = 0.0, translationX = 0.0, translationY = 0.0,
        translationZ = 0.0, rotationX = 0.0, rotationY = 0.0, rotationZ = 0.0)) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { controlError = error.message ?: "Flight action unavailable" }
        }
    }

    LaunchedEffect(facade, uiState.sessionHandle, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (isActive) {
                    try {
                        state = facade.flightState()
                        if (state == null) controlError = "Odyssey is not in this saved world. Return to the lab to launch."
                    } catch (error: CancellationException) { throw error }
                    catch (error: Throwable) { controlError = error.message ?: "Flight telemetry unavailable" }
                    delay(120)
                }
            }
            launch {
                for (next in commands) {
                    try { facade.setFlightControl(next); controlError = null }
                    catch (error: CancellationException) { throw error }
                    catch (error: Throwable) { controlError = error.message ?: "Flight control unavailable" }
                }
            }
        }
    }
    DisposableEffect(facade, uiState.sessionHandle, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                control = FlightControl.Cut
                commands.trySend(FlightControl.Cut)
                facade.suspendFlightControls()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            commands.close()
            facade.suspendFlightControls()
        }
    }
    BackHandler(onBack = onExit)

    val sample = state
    val ready = sample != null && uiState.manualAdvance == null
    val paused = uiState.snapshot?.paused != false
    val rate = uiState.snapshot?.simSecondsPerRealSecond ?: 1.0
    val fuel = sample?.propellantMassKg ?: 0.0
    val burning = !paused && (sample?.massFlowKgPerSecond ?: 0.0) > 0.0
    val coasting = !control.requiresRealTime && (sample?.commandedThrottle ?: 0.0) == 0.0 &&
        (sample?.massFlowKgPerSecond ?: 0.0) == 0.0

    BoxWithConstraints(modifier.fillMaxSize().testTag(FlightTags.ROOT)) {
        val landscape = maxWidth > maxHeight && maxHeight < 600.dp
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth()
            .background(FlightPanel).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(23.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * .5f, 0f); lineTo(size.width, size.height)
                        lineTo(size.width * .5f, size.height * .74f); lineTo(0f, size.height); close()
                    }
                    drawPath(p, FlightTeal, style = Stroke(1.5.dp.toPx()))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("ODYSSEY", color = FlightInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Text(when { !ready -> "Establishing flight link"; paused -> "Flight paused";
                        burning -> "Engine firing"; rate > 1.0 -> "Coasting at ${rate.toInt()}×"; else -> "Earth orbital flight" },
                        color = if (burning) FlightAmber else FlightTeal, fontSize = 11.sp)
                }
                FlightAction("Saves", { cut(); facade.suspendFlightControls(); onSaved() }, FlightTags.SAVE, enabled = ready)
                FlightAction("Lab", onExit, FlightTags.EXIT)
            }
            Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                FlightInstrument("ALTITUDE", sample?.altitudeM?.let(::flightDistance) ?: "—", "above Earth", FlightTags.ALTITUDE)
                FlightInstrument("ORBITAL SPEED", sample?.speedMps?.let { "%.2f km/s".format(Locale.US, it / 1_000) } ?: "—", "relative to Earth")
                FlightInstrument("APOAPSIS", sample?.apoapsisAltitudeM?.let(::flightDistance)
                    ?: if (sample?.hasValidOrbit == true && !sample.hasBoundOrbit) "Escape" else "—",
                    "highest altitude", FlightTags.APOAPSIS)
            }
        }

        val dockHeight = if (landscape) FlightChromeMetrics.LandscapeDock else FlightChromeMetrics.PortraitDock
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding()
            .padding(top = FlightChromeMetrics.Header + 8.dp, start = 12.dp, end = 12.dp)
            .widthIn(max = if (landscape) 380.dp else 520.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1 to "Orbit camera", 2 to "Chase", 0 to "Coast prediction").forEach { (mode, label) ->
                    FlightAction(label, { onCamera(mode) }, FlightTags.camera(mode), active = cameraMode == mode)
                }
            }
            if (missionHelp && !landscape) {
                Surface(color = Color(0xD20C1D2B), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, FlightLine)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Raise your orbit: choose Prograde, run time, then ease up the throttle. Watch apoapsis climb.",
                            Modifier.weight(1f), color = FlightInk, fontSize = 12.sp, lineHeight = 17.sp)
                        FlightAction("×", { missionHelp = false })
                    }
                }
            }
        }

        Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
            .height(dockHeight), color = FlightPanel, border = BorderStroke(1.dp, FlightLine),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ATTITUDE", color = FlightMuted, fontSize = 9.sp, letterSpacing = 1.sp,
                        modifier = Modifier.padding(end = 8.dp))
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        FlightAssist.entries.forEach { assist ->
                            FlightAction(assist.label, { send(control.copy(assist = assist)) }, FlightTags.assist(assist),
                                active = control.assist == assist, enabled = ready)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(if (landscape) 92.dp else 158.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(if (landscape) 210.dp else 156.dp)) {
                        if (!landscape) {
                            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (translationMode) "Translate" else "Rotate", color = FlightMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { cut(); translationMode = !translationMode }, contentPadding = PaddingValues(2.dp)) {
                                    Text(if (translationMode) "Attitude" else "RCS", color = FlightTeal, fontSize = 11.sp)
                                }
                            }
                        }
                        val labels = if (translationMode) listOf("Left", "Up", "Forward", "Right", "Down", "Back")
                            else listOf("Yaw left", "Pitch up", "Roll left", "Yaw right", "Pitch down", "Roll right")
                        repeat(2) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(3) { column ->
                                    val index = row * 3 + column
                                    val label = labels[index]
                                    FlightHoldButton(label, FlightTags.axis(label), Modifier.weight(1f), ready && (!translationMode || fuel > 0.0)) { held ->
                                        val value = if (held) 1.0 else 0.0
                                        val next = if (translationMode) when (index) {
                                            0 -> control.copy(translationX = -value)
                                            1 -> control.copy(translationY = value)
                                            2 -> control.copy(translationZ = value)
                                            3 -> control.copy(translationX = value)
                                            4 -> control.copy(translationY = -value)
                                            else -> control.copy(translationZ = -value)
                                        } else when (index) {
                                            0 -> control.copy(rotationY = -value, assist = FlightAssist.Manual)
                                            1 -> control.copy(rotationX = -value, assist = FlightAssist.Manual)
                                            2 -> control.copy(rotationZ = -value, assist = FlightAssist.Manual)
                                            3 -> control.copy(rotationY = value, assist = FlightAssist.Manual)
                                            4 -> control.copy(rotationX = value, assist = FlightAssist.Manual)
                                            else -> control.copy(rotationZ = value, assist = FlightAssist.Manual)
                                        }
                                        send(next)
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("MAIN ENGINE", color = FlightMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                                Text("${(control.throttle * 100).roundToInt()}%", color = if (control.throttle > 0) FlightAmber else FlightInk,
                                    fontSize = if (landscape) 24.sp else 30.sp, fontWeight = FontWeight.Light)
                            }
                            Button(onClick = { cut() }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp).testTag(FlightTags.CUT),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF413427), contentColor = FlightAmber),
                                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("CUT", fontSize = 12.sp) }
                        }
                        Slider(value = control.throttle.toFloat(), onValueChange = { send(control.copy(throttle = it.toDouble())) },
                            enabled = ready && fuel > 0.0, modifier = Modifier.fillMaxWidth().height(40.dp)
                                .testTag(FlightTags.THROTTLE).semantics { contentDescription = "Main engine throttle" })
                        if (!landscape) {
                            Text("${flightMass(fuel)} propellant · ${sample?.remainingDeltaVMps?.roundToInt() ?: 0} m/s Δv",
                                Modifier.testTag(FlightTags.FUEL), color = FlightTeal, fontSize = 11.sp, maxLines = 2)
                            Text(if (paused && control.throttle > 0) "Ready to burn when time runs" else
                                "${"%.2f".format(Locale.US, sample?.actualMainAccelerationMps2 ?: 0.0)} m/s² · ${"%.2f".format(Locale.US, sample?.massFlowKgPerSecond ?: 0.0)} kg/s",
                                color = FlightMuted, fontSize = 10.sp)
                        }
                    }
                    if (landscape) {
                        Column(Modifier.width(170.dp)) {
                            FlightInstrument("PROPELLANT", flightMass(fuel), "${sample?.remainingDeltaVMps?.roundToInt() ?: 0} m/s Δv", FlightTags.FUEL)
                            Text(sample?.periapsisAltitudeM?.let { "Periapsis ${flightDistance(it)}" } ?: "Orbit unavailable",
                                color = FlightMuted, fontSize = 11.sp)
                        }
                    }
                }
                HorizontalDivider(color = FlightLine)
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { action { facade.applyCommand(if (paused) RuntimeCommand.ResumePlayback else RuntimeCommand.PausePlayback) } },
                        enabled = ready, modifier = Modifier.height(44.dp).testTag(FlightTags.PLAY_PAUSE),
                        colors = ButtonDefaults.buttonColors(containerColor = FlightTeal, contentColor = Color(0xFF102D31)),
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(if (paused) "Run" else "Pause", fontSize = 12.sp)
                    }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(1, 10, 100, 1000).forEach { warp ->
                            FlightAction("${warp}×", { action { facade.applyCommand(RuntimeCommand.SetPlaybackRate(warp.toDouble())) } },
                                FlightTags.warp(warp), active = rate == warp.toDouble(), enabled = ready && (warp == 1 || coasting))
                        }
                    }
                    FlightAction("Mark", { action {
                        cut()
                        facade.setFlightControl(FlightControl.Cut)
                        facade.applyCommand(RuntimeCommand.PausePlayback)
                        val id = "flight.mark.${UUID.randomUUID()}"
                        facade.applyCommand(RuntimeCommand.CreateCheckpoint(id, "Odyssey flight checkpoint"))
                        if (facade.uiState.value.activeCheckpointId == id) {
                            checkpoint = id; message = "Checkpoint ready. Reset returns to this moment."
                        } else controlError = "The checkpoint was not confirmed"
                    } }, FlightTags.CHECKPOINT, enabled = ready)
                    FlightAction("Reset", { checkpoint?.let { id -> action {
                        cut(); facade.setFlightControl(FlightControl.Cut)
                        facade.applyCommand(RuntimeCommand.PausePlayback)
                        val branch = "flight.retry.${UUID.randomUUID()}"
                        facade.applyCommand(RuntimeCommand.CreateBranchFromCheckpoint(id, branch))
                        facade.setFlightControl(FlightControl.Cut)
                        facade.applyCommand(RuntimeCommand.PausePlayback)
                        control = FlightControl.Cut
                        if (facade.uiState.value.snapshot?.activeBranchId == branch)
                            message = "Checkpoint restored on a new timeline"
                        else controlError = "The checkpoint could not be restored"
                    } } }, FlightTags.RESET, enabled = ready && checkpoint != null)
                }
                if (!landscape) {
                    Text(controlError ?: message ?: when {
                        fuel <= 0.0 && ready -> "Propellant depleted. You can still coast and explore your orbit."
                        !coasting -> "Time acceleration is available while coasting."
                        control.assist == FlightAssist.Prograde -> "Prograde points along your motion. Thrust raises the opposite side of your orbit."
                        control.assist == FlightAssist.Retrograde -> "Retrograde points against your motion. Thrust lowers the opposite side of your orbit."
                        else -> "Hold a direction to steer. Release to stop the control input."
                    }, Modifier.fillMaxWidth().padding(top = 3.dp), color = if (controlError != null) FlightAmber else FlightMuted,
                        fontSize = 10.sp, maxLines = 2, lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FlightInstrument(label: String, value: String, detail: String, tag: String? = null) {
    Column(Modifier.then(if (tag == null) Modifier else Modifier.testTag(tag))) {
        Text(label, color = FlightMuted, fontSize = 8.sp, letterSpacing = .8.sp)
        Text(value, color = FlightInk, fontSize = 19.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif, maxLines = 1)
        Text(detail, color = FlightMuted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun FlightAction(label: String, action: () -> Unit, tag: String? = null, active: Boolean = false, enabled: Boolean = true) {
    TextButton(onClick = action, enabled = enabled,
        modifier = Modifier.heightIn(min = 40.dp).then(if (tag == null) Modifier else Modifier.testTag(tag)),
        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (active) FlightTeal else FlightInk,
            containerColor = if (active) Color(0xFF1B3A42) else Color(0xA00A1824),
            disabledContentColor = FlightMuted.copy(alpha = .4f))) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun FlightHoldButton(label: String, tag: String, modifier: Modifier, enabled: Boolean, onHeld: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onHeld)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { if (pressed) latest(false) } }
    Surface(modifier.height(46.dp).testTag(tag)
        .semantics {
            contentDescription = label
            onClick("Pulse $label") {
                if (enabled) scope.launch { latest(true); try { delay(180) } finally { latest(false) } }
                enabled
            }
        }
        .pointerInput(enabled) {
            if (enabled) detectTapGestures(onPress = {
                pressed = true; latest(true)
                try { tryAwaitRelease() } finally { pressed = false; latest(false) }
            })
        }, color = if (pressed) Color(0xFF2C6265) else Color(0xFF152B39),
        shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, FlightLine)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (enabled) FlightInk else FlightMuted.copy(alpha = .4f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

internal fun flightDistance(metres: Double): String = when {
    !metres.isFinite() -> "—"
    abs(metres) >= 1_000_000 -> "%,.0f km".format(Locale.US, metres / 1_000)
    abs(metres) >= 1_000 -> "%.1f km".format(Locale.US, metres / 1_000)
    else -> "%.0f m".format(Locale.US, metres)
}

private fun flightMass(kilograms: Double): String = if (kilograms >= 1_000)
    "%.2f t".format(Locale.US, kilograms / 1_000) else "%.0f kg".format(Locale.US, kilograms)
