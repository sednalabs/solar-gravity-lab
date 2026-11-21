package com.sednalabs.solarlab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightState
import java.util.Locale

@Composable
internal fun FlightDirectorReadout(sample: FlightState?, assist: FlightAssist, modifier: Modifier = Modifier) {
    val director = sample?.let { flightDirector(it, assist) }
    val label = director?.let {
        "${it.target.label}, ${"%.1f".format(Locale.US, it.errorDegrees)} degrees from nose" +
            if (it.targetBehind) ", behind the spacecraft" else if (it.aligned) ", aligned" else ""
    } ?: "Flight direction unavailable"
    BoxWithConstraints(modifier.testTag(FlightTags.DIRECTOR).semantics { contentDescription = label }) {
        val compact = maxWidth < 125.dp
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!compact) Canvas(Modifier.size(40.dp)) {
                val radius = size.minDimension * .39f
                val centre = Offset(size.width / 2f, size.height / 2f)
                drawCircle(FlightLine, radius, centre, style = Stroke(1.dp.toPx()))
                drawLine(FlightMuted, centre - Offset(4.dp.toPx(), 0f), centre + Offset(4.dp.toPx(), 0f), 1.dp.toPx())
                drawLine(FlightMuted, centre - Offset(0f, 4.dp.toPx()), centre + Offset(0f, 4.dp.toPx()), 1.dp.toPx())
                director?.let {
                    val position = centre + Offset((it.markerX * radius).toFloat(), (-it.markerY * radius).toFloat())
                    val colour = if (it.aligned) FlightTeal else FlightAmber
                    if (it.targetBehind) drawCircle(colour, 3.dp.toPx(), position, style = Stroke(1.5.dp.toPx()))
                    else drawCircle(colour, 3.dp.toPx(), position)
                }
            }
            Column {
                Text(director?.target?.label ?: "Direction", color = FlightMuted, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(director?.let {
                    if (it.aligned) "Aligned" else "${"%.0f".format(Locale.US, it.errorDegrees)}°${if (it.targetBehind) " aft" else " off"}"
                } ?: "—", color = if (director?.aligned == true) FlightTeal else FlightAmber, fontSize = 12.sp,
                    maxLines = 1, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun FlightGuideDialog(sample: FlightState?, assist: FlightAssist, onDismiss: () -> Unit) {
    val director = sample?.let { flightDirector(it, assist) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = FlightPanel,
        title = { Text("Fly with intent", color = FlightInk) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The reference ship pushes along its nose. Authored ships can have other engine layouts. A camera change never changes the ship’s attitude.",
                    color = FlightInk, fontSize = 14.sp, lineHeight = 20.sp)
                Text("Bring the coloured marker to the crosshair. Its position is the selected orbital direction in the ship’s frame. A hollow marker means the direction is behind you; the number gives the full turn angle.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                if (director != null) Text(
                    "Now: ${director.target.label} is ${"%.1f".format(Locale.US, director.errorDegrees)}° from the nose. " +
                        "Spin ${"%.2f".format(Locale.US, director.spinDegreesPerSecond)}°/s.",
                    color = FlightTeal, fontSize = 13.sp, lineHeight = 19.sp)
                if (assist == FlightAssist.Manual || assist == FlightAssist.Hold) Text(
                    "In Manual and Stabilise, the marker shows prograde for reference. Those modes do not automatically point at it.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                HorizontalDivider(color = FlightLine)
                Text(assist.label, color = FlightInk, fontWeight = FontWeight.SemiBold)
                Text(flightManoeuvreDescription(assist), color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Choose an assist, run time until the nose aligns, then add throttle. Attitude assists need running time to turn the ship. CUT stops the engine and held controls; it does not erase your velocity.",
                    color = FlightInk, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Use Plan while paused to compare a finite burn before committing. Mark saves a checkpoint; Reset returns there on a new timeline.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                HorizontalDivider(color = FlightLine)
                Text("Read the orbit", color = FlightInk, fontWeight = FontWeight.SemiBold)
                Text("Periapsis is the lowest altitude; apoapsis is the highest. These are instantaneous estimates relative to the host, so a burn or a nearby body can change them. Map shows the native trajectory preview.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                if (sample != null) {
                    Text(flightNavigationHeading(sample.navigation), color = FlightTeal, fontSize = 13.sp)
                    sample.navigation?.let {
                        Text("To circularise here: ideal instantaneous Δv ${"%.1f".format(Locale.US, it.circularisationDeltaVMps)} m/s. " +
                            "This includes cancelling radial motion; it is not a finite burn duration or an instruction to burn prograde.",
                            color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    Text("Apsis times are instantaneous two-body estimates. A nearly circular orbit has no distinct periapsis; " +
                        "an open trajectory has no repeating apsides. Burns and other bodies change these estimates.",
                        color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    Text("Speed ${"%.2f".format(Locale.US, sample.speedMps / 1_000.0)} km/s · " +
                        "${if (sample.radialSpeedMps >= 0.0) "outward" else "inward"} ${"%.1f".format(Locale.US, kotlin.math.abs(sample.radialSpeedMps))} m/s",
                        color = FlightTeal, fontSize = 13.sp)
                    flightOrbitCaution(sample)?.let { Text(it, color = FlightAmber, fontSize = 13.sp, lineHeight = 19.sp) }
                }
                Text("RCS translates along the ship’s axes. Fine uses 20% control authority for smaller corrections. Opposite held buttons cancel; releasing one restores the other.",
                    color = FlightMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }, confirmButton = { FlightAction("Back to flight", onDismiss) })
}
