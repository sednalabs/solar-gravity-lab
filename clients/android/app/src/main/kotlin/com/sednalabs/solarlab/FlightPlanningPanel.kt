package com.sednalabs.solarlab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.CoastIntegrator
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightBurnPreview
import com.sednalabs.solarlab.runtime.FlightControl
import com.sednalabs.solarlab.runtime.FlightDynamicsSettings
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.RuntimeFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal object FlightPlanningTags {
    const val ROOT = "flight.planning"
    const val PAUSE = "flight.planning.pause"
    const val PREVIEW = "flight.planning.preview"
    const val RESULT = "flight.planning.result"
    const val SUMMARY = "flight.planning.summary"
    const val SHAPE = "flight.planning.shape"
    const val ATTITUDE = "flight.planning.attitude"
    const val NAVIGATION = "flight.planning.navigation"
    const val DURATION = "flight.planning.duration"
    const val THROTTLE = "flight.planning.throttle"
    fun method(value: CoastIntegrator) = "flight.planning.method.${value.name}"
    fun assist(value: FlightAssist) = "flight.planning.assist.${value.name}"
}

/** Planning submits a read-only request to Rust; it never stages a live control command. */
@Composable
internal fun FlightPlanningDialog(
    facade: RuntimeFacade,
    sessionHandle: Long?,
    sample: FlightState?,
    control: FlightControl,
    paused: Boolean,
    ready: Boolean,
    onPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var settings by remember(facade, sessionHandle) { mutableStateOf<FlightDynamicsSettings?>(null) }
    var duration by rememberSaveable(sessionHandle) { mutableStateOf(30f) }
    var throttle by rememberSaveable(sessionHandle) { mutableStateOf(control.throttle.toFloat().takeIf { it > 0f } ?: .25f) }
    var assist by rememberSaveable(sessionHandle) { mutableStateOf(control.assist) }
    var preview by remember(facade, sessionHandle) { mutableStateOf<FlightBurnPreview?>(null) }
    var busy by remember(facade, sessionHandle) { mutableStateOf(false) }
    var error by remember(facade, sessionHandle) { mutableStateOf<String?>(null) }
    fun edit() { preview = null; error = null }
    fun runRequest(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { error = failure.message ?: "The calculation could not be completed." }
            finally { busy = false }
        }
    }
    LaunchedEffect(facade, sessionHandle) {
        try { settings = facade.flightDynamicsSettings() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { error = failure.message ?: "Dynamics settings are unavailable." }
    }
    val result = preview
    val stale = result != null && (sample == null || sample.revision != result.revision || sample.epochSeconds != result.epochSeconds)
    AlertDialog(onDismissRequest = onDismiss, containerColor = FlightPanel,
        modifier = Modifier.testTag(FlightPlanningTags.ROOT),
        title = { Text("Plan a burn", color = FlightInk) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Compare an engine burn from this moment. Thrust begins immediately while the ship turns towards the selected direction; your current attitude matters.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 18.sp)
                if (!paused) {
                    Text("Pause flight to calculate from a stable starting point.", color = FlightAmber, fontSize = 13.sp)
                    FlightAction("Pause to plan", onPause, FlightPlanningTags.PAUSE, enabled = ready && !busy)
                }
                Text(flightNavigationHeading(sample?.navigation), Modifier.testTag(FlightPlanningTags.NAVIGATION),
                    color = FlightTeal, fontSize = 12.sp)
                Text("Apsis times are instantaneous two-body estimates. Thrust and other bodies can change them.",
                    color = FlightMuted, fontSize = 11.sp, lineHeight = 15.sp)
                Text("${duration.roundToInt()} seconds · ${(throttle * 100f).roundToInt()}% throttle", color = FlightInk,
                    fontWeight = FontWeight.Medium)
                Slider(value = duration, onValueChange = { duration = it.roundToInt().toFloat(); edit() },
                    valueRange = 1f..300f, enabled = !busy,
                    modifier = Modifier.testTag(FlightPlanningTags.DURATION).semantics { contentDescription = "Planned burn duration, seconds" })
                Slider(value = throttle, onValueChange = { throttle = it; edit() }, valueRange = 0f..1f, enabled = !busy,
                    modifier = Modifier.testTag(FlightPlanningTags.THROTTLE).semantics { contentDescription = "Planned engine throttle" })
                Text("Direction", color = FlightMuted, fontSize = 11.sp)
                FlightAssist.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { option ->
                            FlightAction(option.label, { assist = option; edit() }, FlightPlanningTags.assist(option),
                                active = assist == option, enabled = !busy, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(flightManoeuvreDescription(assist), color = FlightMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Text(flightBurnAttitude(sample, assist, throttle.toDouble()), Modifier.testTag(FlightPlanningTags.ATTITUDE),
                    color = FlightAmber, fontSize = 12.sp, lineHeight = 17.sp)
                FlightAction(if (busy) "Calculating…" else "Preview this burn", {
                    runRequest {
                        val calculated = facade.previewFlightBurn(duration.roundToInt().toDouble(), throttle.toDouble(), assist,
                            expectedSessionHandle = sessionHandle)
                        preview = calculated
                    }
                }, FlightPlanningTags.PREVIEW, enabled = paused && ready && !busy, modifier = Modifier.fillMaxWidth())
                if (result != null) Column(Modifier.testTag(FlightPlanningTags.RESULT), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HorizontalDivider(color = FlightLine)
                    Column(Modifier.testTag(FlightPlanningTags.SUMMARY), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (stale) "Flight changed. Preview again." else "Current orbit → after burn", color = if (stale) FlightAmber else FlightTeal,
                            fontWeight = FontWeight.Medium)
                        Text("Periapsis  ${result.beforePeriapsisM?.let(::flightDistance) ?: "—"} → ${result.afterPeriapsisM?.let(::flightDistance) ?: "—"}",
                            color = FlightInk, fontSize = 13.sp)
                        Text("Apoapsis  ${result.beforeApoapsisM?.let(::flightDistance) ?: "Unbound / unavailable"} → ${result.afterApoapsisM?.let(::flightDistance) ?: "Unbound / unavailable"}",
                            color = FlightInk, fontSize = 13.sp)
                        Text("Fuel ${"%.2f".format(Locale.US, result.fuelUsedKg)} kg · elapsed ${"%.1f".format(Locale.US, result.elapsedSeconds)} s",
                            color = FlightTeal, fontSize = 13.sp)
                    }
                    Text(flightApsisChange("Periapsis", result.beforePeriapsisM, result.afterPeriapsisM) + ". " +
                        flightApsisChange("Apoapsis", result.beforeApoapsisM, result.afterApoapsisM) + ".",
                        color = if (stale) FlightMuted else FlightTeal, fontSize = 12.sp, lineHeight = 17.sp)
                    result.referenceRadiusM?.let { FlightOrbitShapeReadout(it, result, stale) }
                    result.afterGuidance?.let { Text("After burn: ${flightNavigationHeading(it)}",
                        color = FlightMuted, fontSize = 12.sp, lineHeight = 17.sp) }
                    result.warnings.forEach { Text(it, color = FlightAmber, fontSize = 12.sp, lineHeight = 17.sp) }
                    Text("Calculated at t = ${"%.1f".format(Locale.US, result.epochSeconds)} s · revision ${result.revision}",
                        color = FlightMuted, fontSize = 10.sp)
                    Text("The live throttle is unchanged. Return to flight, choose the direction and fly the burn when ready.",
                        color = FlightMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                error?.let { Text(it, color = FlightAmber, fontSize = 12.sp, lineHeight = 17.sp) }
                HorizontalDivider(color = FlightLine)
                Text("Coasting method", color = FlightInk, fontWeight = FontWeight.Medium)
                settings?.let { current ->
                    CoastIntegrator.entries.forEach { method ->
                        val label = when (method) {
                            CoastIntegrator.Leapfrog -> "Leapfrog · everyday coast"
                            CoastIntegrator.FourthOrder -> "Fourth order · smooth coast"
                            CoastIntegrator.AdaptiveEncounter -> "Adaptive · close encounters"
                        }
                        FlightAction(label, {
                            runRequest {
                                facade.setFlightDynamicsSettings(current.copy(integrator = method), expectedSessionHandle = sessionHandle)
                                settings = facade.flightDynamicsSettings()
                                preview = null
                            }
                        }, FlightPlanningTags.method(method), active = current.integrator == method,
                            enabled = paused && ready && !busy, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Powered flight uses the same forward-time method in every mode. Choose a different coast method for a specific accuracy need.",
                        color = FlightMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(if (current.harmonicCount > 0) "Gravity harmonics: ${current.harmonicCount} authored ${if (current.harmonicCount == 1) "body" else "bodies"}"
                        else "Gravity harmonics: none in this scenario", color = FlightMuted, fontSize = 11.sp)
                } ?: Text("Reading dynamics settings…", color = FlightMuted, fontSize = 12.sp)
            }
        }, confirmButton = { FlightAction("Back to flight", onDismiss) })
}
