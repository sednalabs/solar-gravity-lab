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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.LunarMissionPhase
import com.sednalabs.solarlab.runtime.LunarMissionState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val LandscapeDock = 76.dp
    val LandscapeHeader = 64.dp
    val LandscapeSide = 184.dp
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
    onLunarMission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val commands = remember(facade, uiState.sessionHandle) { Channel<FlightControl>(Channel.CONFLATED) }
    var control by remember(facade, uiState.sessionHandle) { mutableStateOf(FlightControl()) }
    var pendingControl by remember(facade, uiState.sessionHandle) { mutableStateOf<FlightControl?>(null) }
    var state by remember(facade, uiState.sessionHandle) { mutableStateOf<FlightState?>(null) }
    var lunarMission by remember(facade, uiState.sessionHandle) { mutableStateOf<LunarMissionState?>(null) }
    var missionActionBusy by remember(facade, uiState.sessionHandle) { mutableStateOf(false) }
    var missionDetailsVisible by rememberSaveable { mutableStateOf(false) }
    var guidedCoast by rememberSaveable(uiState.sessionHandle) { mutableStateOf(false) }
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
        guidedCoast = false
        pendingControl = value
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
                        val sampledFlight = facade.flightState()
                        val sampledMission = facade.lunarMissionState()
                        state = sampledFlight
                        lunarMission = sampledMission
                        // Native guidance can cut the engine without a touch event.
                        // Do not overwrite a still-pending manual slider/axis input.
                        if (pendingControl == null && !missionActionBusy && sampledFlight != null) control = sampledFlight.control
                        if (state == null) controlError = "Odyssey is not in this saved world. Return to the lab to launch."
                    } catch (error: CancellationException) { throw error }
                    catch (error: Throwable) { controlError = error.message ?: "Flight telemetry unavailable" }
                    delay(120)
                }
            }
            launch {
                for (next in commands) {
                    try {
                        facade.setFlightControl(next)
                        if (pendingControl == next) pendingControl = null
                        controlError = null
                    }
                    catch (error: CancellationException) { throw error }
                    catch (error: Throwable) {
                        if (pendingControl == next) pendingControl = null
                        controlError = error.message ?: "Flight control unavailable"
                    }
                }
            }
        }
    }
    DisposableEffect(facade, uiState.sessionHandle, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                control = FlightControl.Cut
                pendingControl = null
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

    LaunchedEffect(guidedCoast, lunarMission?.phase, lunarMission?.outcome, uiState.manualAdvance) {
        val mission = lunarMission ?: return@LaunchedEffect
        if (!guidedCoast || uiState.manualAdvance != null) return@LaunchedEffect
        try {
            if (mission.hasOutcome) {
                guidedCoast = false
                facade.suspendFlightControls()
                message = "Mission outcome recorded. Flight paused."
            } else if (mission.phase == LunarMissionPhase.LunarApproach && rate > 100.0) {
                facade.applyCommand(RuntimeCommand.SetPlaybackRate(100.0))
                message = "Lunar approach. Coast slowed to 100×."
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) { controlError = error.message ?: "Could not adjust mission time" }
    }

    val actions = FlightUiActions(
        assist = { send(control.copy(assist = it)) },
        axis = { index, held ->
            val value = if (held) 1.0 else 0.0
            send(if (translationMode) when (index) {
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
            })
        },
        toggleRcs = { cut(); translationMode = !translationMode },
        throttle = { send(control.copy(throttle = it.toDouble())) },
        cut = ::cut,
        playPause = { action { facade.applyCommand(if (paused) RuntimeCommand.ResumePlayback else RuntimeCommand.PausePlayback) } },
        warp = { value -> guidedCoast = false; action { facade.applyCommand(RuntimeCommand.SetPlaybackRate(value.toDouble())) } },
        mark = { action {
            cut(); facade.setFlightControl(FlightControl.Cut)
            facade.applyCommand(RuntimeCommand.PausePlayback)
            val id = "flight.mark.${UUID.randomUUID()}"
            facade.applyCommand(RuntimeCommand.CreateCheckpoint(id, "Odyssey flight checkpoint"))
            if (facade.uiState.value.activeCheckpointId == id) {
                checkpoint = id; message = "Checkpoint ready. Reset returns to this moment."
            } else controlError = "The checkpoint was not confirmed"
        } },
        reset = { checkpoint?.let { id -> action {
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
        } } },
    )
    val notice = controlError ?: message ?: when {
        fuel <= 0.0 && ready -> "Propellant depleted. You can still coast and orient the spacecraft."
        !coasting -> "Time acceleration is available while coasting."
        control.assist == FlightAssist.Prograde -> "Prograde points along your motion. Thrust raises the opposite side of your orbit."
        control.assist == FlightAssist.Retrograde -> "Retrograde points against your motion. Thrust lowers the opposite side of your orbit."
        control.assist == FlightAssist.Hold -> "Stabilise damps rotation. Hold a direction to steer."
        else -> "Hold a direction to steer. Release to stop the control input."
    }
    val status = when {
        !ready -> "Establishing flight link"
        paused -> "Flight paused"
        burning -> "Engine firing"
        rate > 1.0 -> "Coasting at ${rate.toInt()}×"
        else -> "Earth orbital flight"
    }

    fun beginMissionBurn() {
        if (missionActionBusy || !ready) return
        missionActionBusy = true
        guidedCoast = false
        while (commands.tryReceive().isSuccess) { /* Superseded manual inputs must not cancel new guidance. */ }
        pendingControl = null
        action {
            try {
                facade.beginLunarDeparture()
                state = facade.flightState()
                state?.let { control = it.control }
                lunarMission = facade.lunarMissionState()
                message = null
            } finally { missionActionBusy = false }
        }
    }

    fun beginMissionCoast() {
        if (!ready || !coasting || missionActionBusy) return
        guidedCoast = true
        action {
            val coastRate = if (lunarMission?.phase == LunarMissionPhase.LunarApproach) 100.0 else 1_000.0
            facade.applyCommand(RuntimeCommand.SetPlaybackRate(coastRate))
            facade.applyCommand(RuntimeCommand.ResumePlayback)
            message = "Coasting towards the Moon. Approach slows automatically; an observed outcome pauses flight."
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(FlightTags.ROOT)) {
        val landscape = maxWidth > maxHeight && maxHeight < 600.dp
        val header = if (landscape) FlightChromeMetrics.LandscapeHeader else FlightChromeMetrics.Header
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth()
            .background(FlightPanel).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(if (landscape) header else 50.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(23.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * .5f, 0f); lineTo(size.width, size.height)
                        lineTo(size.width * .5f, size.height * .74f); lineTo(0f, size.height); close()
                    }
                    drawPath(p, FlightTeal, style = Stroke(1.5.dp.toPx()))
                }
                Column(if (landscape) Modifier.width(128.dp) else Modifier.weight(1f)) {
                    Text("ODYSSEY", color = FlightInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Text(status, color = if (burning) FlightAmber else FlightTeal, fontSize = 11.sp, maxLines = 1)
                }
                if (landscape) FlightOrbitInstruments(sample, Modifier.weight(1f))
                FlightAction("Saves", { cut(); facade.suspendFlightControls(); onSaved() }, FlightTags.SAVE, enabled = ready)
                FlightAction("Lab", onExit, FlightTags.EXIT)
            }
            if (!landscape) FlightOrbitInstruments(sample, Modifier.fillMaxWidth().height(62.dp))
        }

        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding()
            .padding(top = header + 8.dp, start = if (landscape) FlightChromeMetrics.LandscapeSide else 12.dp,
                end = if (landscape) FlightChromeMetrics.LandscapeSide else 12.dp)
            .widthIn(max = 600.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1 to "Orbit camera", 2 to "Chase", 0 to "Coast prediction").forEach { (mode, label) ->
                    FlightAction(label, { onCamera(mode) }, FlightTags.camera(mode), active = cameraMode == mode)
                }
                FlightAction("Moon mission", { cut(); facade.suspendFlightControls(); onLunarMission() }, LunarFlightTags.BRIEF)
            }
            if (lunarMission != null) {
                LunarMissionPanel(requireNotNull(lunarMission), landscape, paused, ready, missionActionBusy, coasting,
                    onBeginBurn = ::beginMissionBurn, onCoast = ::beginMissionCoast,
                    onDetails = { action { facade.applyCommand(RuntimeCommand.PausePlayback); missionDetailsVisible = true } })
            } else if (missionHelp && !landscape) {
                Surface(color = Color(0xD20C1D2B), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, FlightLine)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Raise your orbit: choose Prograde, run time, then ease up the throttle. Watch apoapsis climb.",
                            Modifier.weight(1f), color = FlightInk, fontSize = 12.sp, lineHeight = 17.sp)
                        FlightAction("×", { missionHelp = false })
                    }
                }
            }
        }

        if (landscape) {
            // Side consoles leave a large, unobstructed central Vulkan viewport.
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(top = header, bottom = FlightChromeMetrics.LandscapeDock)) {
                Surface(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                    .width(FlightChromeMetrics.LandscapeSide - 16.dp),
                    color = FlightPanel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, FlightLine)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Text("ORIENTATION", color = FlightMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                        FlightAssistStrip(control.assist, ready, actions.assist)
                        FlightAttitudePad(translationMode, ready && (!translationMode || fuel > 0.0), actions)
                    }
                }
                Surface(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                    .width(FlightChromeMetrics.LandscapeSide - 16.dp),
                    color = FlightPanel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, FlightLine)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                        FlightEnginePanel(control, sample, ready, paused, actions)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = FlightLine)
                        FlightInstrument("PERIAPSIS", sample?.periapsisAltitudeM?.let(::flightDistance) ?: "—", "lowest altitude")
                    }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
                .height(FlightChromeMetrics.LandscapeDock), color = FlightPanel, border = BorderStroke(1.dp, FlightLine)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    FlightTimeBar(ready, paused, rate, coasting, checkpoint != null, actions)
                    Text(notice, color = if (controlError == null) FlightMuted else FlightAmber, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
                .height(FlightChromeMetrics.PortraitDock), color = FlightPanel, border = BorderStroke(1.dp, FlightLine),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    FlightAssistStrip(control.assist, ready, actions.assist)
                    Row(Modifier.fillMaxWidth().height(158.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(156.dp)) {
                            FlightAttitudePad(translationMode, ready && (!translationMode || fuel > 0.0), actions)
                        }
                        Box(Modifier.weight(1f)) { FlightEnginePanel(control, sample, ready, paused, actions) }
                    }
                    HorizontalDivider(color = FlightLine)
                    FlightTimeBar(ready, paused, rate, coasting, checkpoint != null, actions)
                    Text(notice, Modifier.fillMaxWidth().padding(top = 3.dp), color = if (controlError == null) FlightMuted else FlightAmber,
                        fontSize = 10.sp, maxLines = 2, lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (missionDetailsVisible && lunarMission != null) {
        LunarMissionDetailsDialog(requireNotNull(lunarMission)) { missionDetailsVisible = false }
    }
}

private data class FlightUiActions(
    val assist: (FlightAssist) -> Unit,
    val axis: (Int, Boolean) -> Unit,
    val toggleRcs: () -> Unit,
    val throttle: (Float) -> Unit,
    val cut: () -> Unit,
    val playPause: () -> Unit,
    val warp: (Int) -> Unit,
    val mark: () -> Unit,
    val reset: () -> Unit,
)

@Composable
private fun FlightOrbitInstruments(sample: FlightState?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FlightInstrument("ALTITUDE", sample?.altitudeM?.let(::flightDistance) ?: "—", "above Earth", FlightTags.ALTITUDE,
            Modifier.weight(1f))
        FlightInstrument("ORBITAL SPEED", sample?.speedMps?.let { "%.2f km/s".format(Locale.US, it / 1_000) } ?: "—", "relative to Earth",
            modifier = Modifier.weight(1f))
        FlightInstrument("APOAPSIS", sample?.apoapsisAltitudeM?.let(::flightDistance)
            ?: if (sample?.hasValidOrbit == true && !sample.hasBoundOrbit) "Escape" else "—", "highest altitude", FlightTags.APOAPSIS,
            Modifier.weight(1f))
    }
}

@Composable
private fun FlightAssistStrip(assist: FlightAssist, ready: Boolean, onAssist: (FlightAssist) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlightAssist.entries.forEach { item ->
            FlightAction(item.label, { onAssist(item) }, FlightTags.assist(item), active = assist == item, enabled = ready)
        }
    }
}

@Composable
private fun FlightAttitudePad(translation: Boolean, enabled: Boolean, actions: FlightUiActions) {
    Column {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (translation) "Translate" else "Rotate", color = FlightMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.toggleRcs, contentPadding = PaddingValues(2.dp)) {
                Text(if (translation) "Attitude" else "RCS", color = FlightTeal, fontSize = 11.sp)
            }
        }
        val labels = if (translation) listOf("Left", "Up", "Forward", "Right", "Down", "Back")
            else listOf("Yaw left", "Pitch up", "Roll left", "Yaw right", "Pitch down", "Roll right")
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    FlightHoldButton(labels[index], FlightTags.axis(labels[index]), Modifier.weight(1f), enabled) { actions.axis(index, it) }
                }
            }
        }
    }
}

@Composable
private fun FlightEnginePanel(control: FlightControl, sample: FlightState?, ready: Boolean, paused: Boolean, actions: FlightUiActions) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MAIN ENGINE", color = FlightMuted, fontSize = 9.sp, letterSpacing = .8.sp)
                Text("${(control.throttle * 100).roundToInt()}%", color = if (control.throttle > 0) FlightAmber else FlightInk,
                    fontSize = 28.sp, fontWeight = FontWeight.Light)
            }
            Button(onClick = actions.cut, enabled = ready, modifier = Modifier.heightIn(min = 48.dp).testTag(FlightTags.CUT),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF413427), contentColor = FlightAmber),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("CUT", fontSize = 11.sp) }
        }
        Slider(value = control.throttle.toFloat(), onValueChange = actions.throttle,
            enabled = ready && (sample?.propellantMassKg ?: 0.0) > 0.0, modifier = Modifier.fillMaxWidth().height(40.dp)
                .testTag(FlightTags.THROTTLE).semantics { contentDescription = "Main engine throttle" })
        Text("${flightMass(sample?.propellantMassKg ?: 0.0)} propellant · ${sample?.remainingDeltaVMps?.roundToInt() ?: 0} m/s Δv",
            Modifier.testTag(FlightTags.FUEL), color = FlightTeal, fontSize = 11.sp, maxLines = 2)
        Text(if (paused && control.throttle > 0) "Ready to burn when time runs" else
            "${"%.2f".format(Locale.US, sample?.actualMainAccelerationMps2 ?: 0.0)} m/s² · ${"%.2f".format(Locale.US, sample?.massFlowKgPerSecond ?: 0.0)} kg/s",
            color = FlightMuted, fontSize = 10.sp, maxLines = 2)
    }
}

@Composable
private fun FlightTimeBar(ready: Boolean, paused: Boolean, rate: Double, coasting: Boolean, canReset: Boolean, actions: FlightUiActions) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = actions.playPause, enabled = ready, modifier = Modifier.height(44.dp).testTag(FlightTags.PLAY_PAUSE),
            colors = ButtonDefaults.buttonColors(containerColor = FlightTeal, contentColor = Color(0xFF102D31)),
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(if (paused) "Run" else "Pause", fontSize = 12.sp)
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(1, 10, 100, 1000).forEach { warp ->
                FlightAction("${warp}×", { actions.warp(warp) }, FlightTags.warp(warp), active = rate == warp.toDouble(), enabled = ready && (warp == 1 || coasting))
            }
        }
        FlightAction("Mark", actions.mark, FlightTags.CHECKPOINT, enabled = ready)
        FlightAction("Reset", actions.reset, FlightTags.RESET, enabled = ready && canReset)
    }
}

@Composable
private fun FlightInstrument(label: String, value: String, detail: String, tag: String? = null, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.then(if (tag == null) Modifier else Modifier.testTag(tag))) {
        // Lunar distances must remain readable alongside the other instruments.
        // Fixed column widths also prevent a long apoapsis from pushing altitude
        // outside the screen. Size from constraints, never a layout-state loop.
        val valueSize = when {
            maxWidth < 110.dp -> 14.sp
            maxWidth < 150.dp -> 16.sp
            else -> 19.sp
        }
        Column {
            Text(label, color = FlightMuted, fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = .8.sp, maxLines = 1)
            Text(value, color = FlightInk, fontSize = valueSize, lineHeight = 23.sp, fontWeight = FontWeight.Light,
                fontFamily = FontFamily.SansSerif, maxLines = 1)
            Text(detail, color = FlightMuted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
        }
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
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    DisposableEffect(Unit) { onDispose { if (pressed) latest(false) } }
    Surface(modifier.height(48.dp).testTag(tag)
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
            Text(label, Modifier.padding(horizontal = 2.dp), color = if (enabled) FlightInk else FlightMuted.copy(alpha = .4f),
                fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
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
