package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.RendezvousGuidance
import com.sednalabs.solarlab.runtime.RendezvousState
import com.sednalabs.solarlab.runtime.RendezvousStatus
import java.util.Locale
import kotlin.math.abs

internal object RendezvousTags {
    const val BRIEF = "flight.rendezvous.brief"
    const val START = "flight.rendezvous.start"
    const val PANEL = "flight.rendezvous.panel"
    const val STATUS = "flight.rendezvous.status"
    const val RANGE = "flight.rendezvous.range"
    const val RELATIVE_SPEED = "flight.rendezvous.relative_speed"
    const val CLOSING = "flight.rendezvous.closing"
    const val DIRECTION = "flight.rendezvous.direction"
    const val APPROACH = "flight.rendezvous.approach"
    const val MATCH = "flight.rendezvous.match"
    const val MANUAL = "flight.rendezvous.manual"
}

private val RendezvousInk = Color(0xFFEDF6FF)
private val RendezvousMuted = Color(0xFF9CAEBF)
private val RendezvousTeal = Color(0xFF8BE6DC)
private val RendezvousAmber = Color(0xFFFFC582)

@Composable
internal fun RendezvousBriefDialog(busy: Boolean, error: String?, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color(0xFF0B1724),
        titleContentColor = RendezvousInk, textContentColor = Color(0xFFB3C4D4),
        title = { Column {
            Text("ORBITAL ENCOUNTER", color = RendezvousTeal, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("Meet Relay Station", fontSize = 24.sp, fontWeight = FontWeight.Light)
        } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(84.dp)) {
                val station = Offset(size.width * .74f, size.height * .5f)
                val ship = Offset(size.width * .16f, size.height * .5f)
                drawCircle(RendezvousTeal.copy(alpha = .25f), 35.dp.toPx(), station, style = Stroke(1.dp.toPx()))
                drawLine(RendezvousAmber, ship, Offset(station.x - 35.dp.toPx(), station.y), 1.5.dp.toPx())
                drawCircle(RendezvousInk, 4.dp.toPx(), ship)
                drawRect(Color(0xFF385F92), Offset(station.x - 38.dp.toPx(), station.y - 12.dp.toPx()), Size(24.dp.toPx(), 24.dp.toPx()))
                drawRect(Color(0xFF385F92), Offset(station.x + 14.dp.toPx(), station.y - 12.dp.toPx()), Size(24.dp.toPx(), 24.dp.toPx()))
                drawLine(RendezvousInk, Offset(station.x - 20.dp.toPx(), station.y), Offset(station.x + 20.dp.toPx(), station.y), 3.dp.toPx())
                drawRoundRect(RendezvousInk, Offset(station.x - 6.dp.toPx(), station.y - 15.dp.toPx()), Size(12.dp.toPx(), 30.dp.toPx()))
            }
            Text("Two vehicles share Earth orbit. Bring Odyssey alongside the station and settle at a 75 m stand-off.", fontSize = 15.sp, lineHeight = 21.sp)
            Text("Approach uses your ship’s RCS to close the distance and brake. Match velocity removes relative motion. Both use real propellant; the station keeps following its own orbit.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Use the RCS pad to fly manually. The target indicator is relative to your ship: right, up and ahead. CUT immediately releases guidance.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Stay outside the 25 m keep-out boundary. This exercise ends at stand-off; physical docking and collision response are not simulated.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Your current experiment and its complete history are saved before this new encounter opens.", color = RendezvousTeal, fontSize = 12.sp, lineHeight = 18.sp)
            error?.let { Text(it, color = RendezvousAmber, fontSize = 12.sp) }
            if (busy) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Saving this world and preparing the encounter…", fontSize = 12.sp) }
        } },
        confirmButton = { Button(onClick = onStart, enabled = !busy, modifier = Modifier.testTag(RendezvousTags.START)) { Text("Start rendezvous") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Return to flight") } },
    )
}

@Composable
internal fun RendezvousPanel(
    state: RendezvousState, compact: Boolean, paused: Boolean, ready: Boolean, busy: Boolean,
    onGuidance: (RendezvousGuidance) -> Unit,
) {
    val warning = state.keepOutEntered || state.status in listOf(RendezvousStatus.KeepOut, RendezvousStatus.OutOfFuel,
        RendezvousStatus.OutOfRange, RendezvousStatus.TargetUnavailable)
    val accent = if (warning) RendezvousAmber else RendezvousTeal
    val bearing = rendezvousBearing(state)
    Surface(Modifier.fillMaxWidth().testTag(RendezvousTags.PANEL), color = Color(0xEB0B1724),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF294451))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = if (compact) 3.dp else 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!compact) TargetBearing(state, Modifier.size(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        state.keepOutEntered -> "KEEP-OUT EVENT RECORDED"
                        state.standOffAchieved -> "STAND-OFF ACHIEVED"
                        else -> "RELAY STATION · ${rendezvousDistance(state.standOffDistanceM)} STAND-OFF"
                    }, color = accent, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .8.sp, maxLines = 1)
                    Text((if (paused) "Paused · " else "") + state.status.label,
                        Modifier.testTag(RendezvousTags.STATUS), color = RendezvousInk, fontSize = if (compact) 12.sp else 14.sp,
                        lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (compact) Text(bearing, Modifier.testTag(RendezvousTags.DIRECTION), color = RendezvousMuted,
                    fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2)
            }
            if (!compact) Text(bearing, Modifier.testTag(RendezvousTags.DIRECTION), color = RendezvousMuted,
                fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                GuidanceButton("Approach ${rendezvousDistance(state.standOffDistanceM)}", RendezvousTags.APPROACH,
                    state.guidance == RendezvousGuidance.Approach,
                    ready && !busy && state.targetAvailable && state.remainingPropellantKg > 0.0, Modifier.weight(1f)) {
                    onGuidance(RendezvousGuidance.Approach)
                }
                GuidanceButton("Match velocity", RendezvousTags.MATCH, state.guidance == RendezvousGuidance.MatchVelocity,
                    ready && !busy && state.targetAvailable && state.remainingPropellantKg > 0.0, Modifier.weight(1f)) {
                    onGuidance(RendezvousGuidance.MatchVelocity)
                }
                GuidanceButton("Manual", RendezvousTags.MANUAL, state.guidance == RendezvousGuidance.Manual,
                    ready && !busy, Modifier) { onGuidance(RendezvousGuidance.Manual) }
            }
            if (!compact) Text(when {
                !state.targetAvailable -> "The saved target is unavailable. Guidance is off; return to the lab or start a fresh encounter."
                state.keepOutEntered -> "The ${rendezvousDistance(state.keepOutDistanceM)} boundary was crossed. No contact response is modelled; recover to a safe separation."
                state.status == RendezvousStatus.Holding -> "Holding with real thrusters. Small corrections counter the different gravitational acceleration at each vehicle."
                state.guidance == RendezvousGuidance.MatchVelocity -> "Matching motion relative to the station. Use Approach to settle at the stand-off point."
                else -> "Keep out: ${rendezvousDistance(state.keepOutDistanceM)} · Propellant used: ${"%.2f".format(Locale.US, state.propellantUsedKg)} kg · Touch a manual control to take over."
            }, color = if (warning) RendezvousAmber else RendezvousMuted, fontSize = 10.sp, lineHeight = 14.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GuidanceButton(label: String, tag: String, active: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 44.dp).testTag(tag),
        colors = ButtonDefaults.textButtonColors(contentColor = RendezvousTeal,
            containerColor = if (active) Color(0xFF1B3A42) else Color.Transparent), shape = RoundedCornerShape(9.dp)) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TargetBearing(state: RendezvousState, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = rendezvousBearing(state) }) {
        val centre = Offset(size.width * .5f, size.height * .5f)
        val radius = size.minDimension * .43f
        drawCircle(Color(0xFF476271), radius, centre, style = Stroke(1.dp.toPx()))
        drawLine(Color(0xFF294451), Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y))
        drawLine(Color(0xFF294451), Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius))
        if (state.targetAvailable) drawCircle(if (state.targetDirectionZ >= 0.0) RendezvousTeal else RendezvousAmber,
            3.dp.toPx(), Offset(centre.x + state.targetDirectionX.toFloat() * radius,
                centre.y - state.targetDirectionY.toFloat() * radius))
    }
}

internal fun rendezvousBearing(state: RendezvousState): String {
    if (!state.targetAvailable) return "No target direction"
    val directions = buildList {
        if (abs(state.targetDirectionX) > .15) add(if (state.targetDirectionX > 0) "right" else "left")
        if (abs(state.targetDirectionY) > .15) add(if (state.targetDirectionY > 0) "up" else "down")
        add(if (state.targetDirectionZ >= 0) "ahead" else "behind")
    }
    return "Target ${directions.joinToString(" · ")}"
}

internal fun rendezvousDistance(metres: Double): String = if (abs(metres) >= 1_000)
    "%.2f km".format(Locale.US, metres / 1_000) else "%.1f m".format(Locale.US, metres)

internal fun rendezvousSpeed(metresPerSecond: Double): String = "%.2f m/s".format(Locale.US, metresPerSecond)
