package com.sednalabs.solarlab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.LunarMissionOutcome
import com.sednalabs.solarlab.runtime.LunarMissionPhase
import com.sednalabs.solarlab.runtime.LunarMissionState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal object LunarFlightTags {
    const val BRIEF = "flight.lunar.brief"
    const val START = "flight.lunar.start"
    const val MISSION = "flight.lunar.mission"
    const val STATUS = "flight.lunar.status"
    const val BURN = "flight.lunar.burn"
    const val RANGE = "flight.lunar.range"
    const val WARP = "flight.lunar.warp"
    const val DETAILS = "flight.lunar.details"
}

@Composable
internal fun LunarFlightBriefDialog(busy: Boolean, error: String?, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF0B1724), titleContentColor = Color(0xFFEDF6FF), textContentColor = Color(0xFFB3C4D4),
        title = { Column {
            Text("EARTH → MOON", color = Color(0xFF8BE6DC), fontSize = 11.sp, letterSpacing = 2.sp)
            Text("Your first lunar flight", fontSize = 24.sp, fontWeight = FontWeight.Light)
        } },
        text = { Column(Modifier.testTag(LunarFlightTags.BRIEF).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(98.dp)) {
                val earth = Offset(size.width * .13f, size.height * .68f)
                val moon = Offset(size.width * .86f, size.height * .33f)
                drawCircle(Color(0xFF438CAE), 23.dp.toPx(), earth)
                drawCircle(Color(0xFF8BE6DC).copy(alpha = .5f), 29.dp.toPx(), earth, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xFFB5BAC4), 11.dp.toPx(), moon)
                val trajectory = Path().apply {
                    moveTo(earth.x + 26.dp.toPx(), earth.y)
                    cubicTo(size.width * .28f, -size.height * .2f, size.width * .72f, size.height * .95f, moon.x, moon.y)
                }
                drawPath(trajectory, Color(0xFFFFC582), style = Stroke(1.6.dp.toPx()))
            }
            Text("Begin 400 km above Earth. Point prograde, fire the main engine, then coast towards a lunar flyby.", fontSize = 15.sp, lineHeight = 21.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("DEPARTURE", fontSize = 10.sp); Text("5 minute burn", color = Color(0xFFEDF6FF), fontSize = 16.sp) }
                Column { Text("REFERENCE FLIGHT", fontSize = 10.sp); Text("About 2.6 days", color = Color(0xFFEDF6FF), fontSize = 16.sp) }
            }
            Text("Fly manually or use the guided departure burn. The engine cuts off automatically when the guided burn is complete.", fontSize = 12.sp, lineHeight = 17.sp)
            Text("A new starting world opens for this mission. Your current experiment and its history are saved automatically first.", color = Color(0xFF8BE6DC), fontSize = 12.sp, lineHeight = 17.sp)
            Text("Authored spacecraft orbit in the laboratory’s Sun–Earth–Moon gravity model. This is a flyby exercise; surface collision and landing are not simulated.", fontSize = 11.sp, lineHeight = 16.sp)
            error?.let { Text(it, color = Color(0xFFFFC582), fontSize = 12.sp) }
            if (busy) { CircularProgressIndicator(Modifier.padding(8.dp)); Text("Saving your experiment and preparing the departure window…", fontSize = 12.sp) }
        } },
        confirmButton = { Button(onClick = onStart, enabled = !busy, modifier = Modifier.testTag(LunarFlightTags.START)) { Text("Start lunar mission") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep exploring") } },
    )
}

@Composable
internal fun LunarMissionPanel(
    mission: LunarMissionState, compact: Boolean, paused: Boolean, ready: Boolean, busy: Boolean,
    coasting: Boolean, onBeginBurn: () -> Unit, onCoast: () -> Unit, onDetails: () -> Unit,
) {
    val ink = Color(0xFFEDF6FF)
    val muted = Color(0xFF9CAEBF)
    val accent = if (mission.hasOutcome && mission.outcome != LunarMissionOutcome.FlybyAchieved)
        Color(0xFFFFC582) else Color(0xFF8BE6DC)
    Surface(Modifier.fillMaxWidth().testTag(LunarFlightTags.MISSION), color = Color(0xE80B1724),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF294451))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (mission.outcome == LunarMissionOutcome.FlybyAchieved) "LUNAR FLYBY ACHIEVED" else "EARTH → MOON",
                        color = accent, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp)
                    Text(mission.phase.label, Modifier.testTag(LunarFlightTags.STATUS), color = ink,
                        fontSize = if (compact) 12.sp else 15.sp, lineHeight = if (compact) 16.sp else 20.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    mission.guidedBurnActive -> Text(
                        (if (paused) "Paused · " else "Burn · ") + lunarBurnTime(mission.guidedBurnRemainingSeconds),
                        Modifier.testTag(LunarFlightTags.BURN), color = Color(0xFFFFC582), fontSize = 13.sp)
                    !mission.hasOutcome && mission.departureRemainingSeconds > 0.001 -> TextButton(
                        onClick = onBeginBurn, enabled = ready && !busy && mission.propellantMassKg > 0.0,
                        modifier = Modifier.testTag(LunarFlightTags.BURN).heightIn(min = 44.dp)) {
                        Text(if (mission.actualMainFullThrottleSeconds > 0.01) "Resume burn" else "Start burn", color = accent, fontSize = 12.sp)
                    }
                    !mission.hasOutcome -> TextButton(onClick = onCoast, enabled = ready && !busy && coasting,
                        modifier = Modifier.testTag(LunarFlightTags.WARP).heightIn(min = 44.dp)) {
                        Text(if (mission.phase == LunarMissionPhase.LunarApproach) "Coast 100×" else "Coast 1000×", color = accent, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onDetails, modifier = Modifier.testTag(LunarFlightTags.DETAILS)) {
                    Text("Info", color = muted, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Moon ${flightDistance(mission.moonAltitudeM)}", Modifier.testTag(LunarFlightTags.RANGE), color = ink, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(lunarRangeRate(mission.moonRangeRateMps), color = muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (!compact) {
                if (mission.departureRemainingSeconds > 0.001 || mission.guidedBurnActive) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF294451), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(mission.departureProgress).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
                    }
                    Text("${lunarBurnTime(mission.departureRemainingSeconds)} engine time remaining · automatic cutoff", color = muted, fontSize = 10.sp, lineHeight = 14.sp)
                } else Text("Closest observed ${flightDistance(mission.closestObservedMoonAltitudeM)} · flight +${lunarElapsedTime(mission.elapsedSeconds)}",
                    color = muted, fontSize = 10.sp, lineHeight = 14.sp)
                Text(lunarMissionGuidance(mission), color = accent, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LunarMissionDetailsDialog(mission: LunarMissionState, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF0B1724), titleContentColor = Color(0xFFEDF6FF),
        textContentColor = Color(0xFFB3C4D4), title = { Text(mission.phase.label) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(lunarMissionGuidance(mission), color = Color(0xFF8BE6DC), lineHeight = 21.sp)
            Text("Flight elapsed: ${lunarElapsedTime(mission.elapsedSeconds)}")
            Text("Main engine: %.1f / %.0f full-throttle seconds".format(Locale.US, mission.actualMainFullThrottleSeconds, mission.plannedBurnSeconds))
            Text("Above Earth: ${flightDistance(mission.earthAltitudeM)}")
            Text("Above Moon: ${flightDistance(mission.moonAltitudeM)}")
            Text("Moon-relative speed: %.1f m/s\n%s".format(Locale.US, mission.moonRelativeSpeedMps, lunarRangeRate(mission.moonRangeRateMps)))
            Text("Closest observed: ${flightDistance(mission.closestObservedMoonAltitudeM)}\nAt flight +${lunarElapsedTime(mission.closestObservedEpochSeconds - mission.launchEpochSeconds)}")
            Text("Reference flyby window: +${lunarElapsedTime(mission.expectedFlybyWindowStartSeconds)} to +${lunarElapsedTime(mission.expectedFlybyWindowEndSeconds)}", fontSize = 12.sp)
            Text("The guided departure uses real thrust and propellant. Manual engine or attitude input cancels it; Resume burn uses the remaining engine time. Coast slows near the Moon and pauses when an outcome is observed.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("A flyby succeeds after passing the Moon between 8 m and 1,000 km above its surface. Surface crossings are mission failures; contact, landing and terrain response are not simulated.", fontSize = 12.sp, lineHeight = 18.sp)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Return to flight") } })
}

private fun lunarMissionGuidance(mission: LunarMissionState): String = when (mission.outcome) {
    LunarMissionOutcome.FlybyAchieved -> "You passed the Moon. Closest observed altitude: ${flightDistance(mission.closestObservedMoonAltitudeM)}."
    LunarMissionOutcome.LunarSurfaceCrossing -> "The trajectory crossed the Moon’s surface. Reset a checkpoint or start a new mission."
    LunarMissionOutcome.EarthSurfaceCrossing -> "The trajectory crossed Earth’s surface. Reset a checkpoint or start a new mission."
    LunarMissionOutcome.Missed -> "The four-day flight window ended without the target flyby. Review the flight or try a fresh departure."
    LunarMissionOutcome.InProgress -> when {
        mission.guidedBurnActive -> "Guided prograde departure. CUT always takes control back."
        mission.departureRemainingSeconds > 0.001 -> "Start the guided prograde burn to depart Earth. Keep the engine off while coasting."
        mission.phase == LunarMissionPhase.LunarApproach -> "Approaching the Moon. Coast at 100× to watch the flyby unfold."
        else -> "Departure complete. Coast towards the Moon; time slows automatically on approach."
    }
}

private fun lunarBurnTime(seconds: Double): String {
    val total = ceil(seconds.coerceAtLeast(0.0)).toLong()
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}

private fun lunarElapsedTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    return if (total >= 86_400) "%dd %02dh %02dm".format(Locale.US, total / 86_400, total / 3_600 % 24, total / 60 % 60)
        else "%02dh %02dm %02ds".format(Locale.US, total / 3_600, total / 60 % 60, total % 60)
}

private fun lunarRangeRate(metresPerSecond: Double): String =
    "${if (metresPerSecond < 0) "Closing" else "Receding"} %.0f m/s".format(Locale.US, abs(metresPerSecond))
