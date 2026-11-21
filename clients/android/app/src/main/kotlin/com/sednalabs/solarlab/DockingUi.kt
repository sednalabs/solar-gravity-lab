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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.DockingGuidance
import com.sednalabs.solarlab.runtime.DockingState
import com.sednalabs.solarlab.runtime.DockingStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal object DockingTags {
    const val BRIEF = "flight.docking.brief"
    const val START = "flight.docking.start"
    const val PANEL = "flight.docking.panel"
    const val STATUS = "flight.docking.status"
    const val GAP = "flight.docking.gap"
    const val CLOSING = "flight.docking.closing"
    const val ALIGNMENT = "flight.docking.alignment"
    const val OFFSET = "flight.docking.offset"
    const val DOCK = "flight.docking.dock"
    const val ABORT = "flight.docking.abort"
    const val UNDOCK = "flight.docking.undock"
}

private val DockInk = Color(0xFFEDF6FF)
private val DockMuted = Color(0xFF9CAEBF)
private val DockTeal = Color(0xFF8BE6DC)
private val DockAmber = Color(0xFFFFC582)

@Composable
internal fun DockingBriefDialog(busy: Boolean, error: String?, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Color(0xFF0B1724),
        titleContentColor = DockInk, textContentColor = Color(0xFFB3C4D4),
        title = { Column {
            Text("PRECISION FLIGHT", color = DockTeal, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("A place to dock", fontSize = 24.sp, fontWeight = FontWeight.Light)
        } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                val centre = Offset(size.width * .5f, size.height * .5f)
                drawCircle(DockTeal.copy(alpha = .18f), 34.dp.toPx(), centre)
                drawCircle(DockTeal, 25.dp.toPx(), centre, style = Stroke(3.dp.toPx()))
                drawCircle(DockInk, 15.dp.toPx(), centre, style = Stroke(1.5.dp.toPx()))
                drawLine(DockAmber, Offset(centre.x, centre.y - 32.dp.toPx()),
                    Offset(centre.x, centre.y - 19.dp.toPx()), 3.dp.toPx())
                drawLine(DockMuted, Offset(centre.x - 9.dp.toPx(), centre.y), Offset(centre.x + 9.dp.toPx(), centre.y))
                drawLine(DockMuted, Offset(centre.x, centre.y - 9.dp.toPx()), Offset(centre.x, centre.y + 9.dp.toPx()))
            }
            Text("Begin aligned 30 m from Relay, about 22 m from its service port. Bring the two ports together gently, then release and fly away.", fontSize = 15.sp, lineHeight = 21.sp)
            Text("Choose Dock to align and approach with real thrusters. Press Run to advance time. Guidance uses propellant. Attachment requires actual port contact with safe alignment and relative motion.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Watch the gap, closing speed, angle and lateral offset. Abort or a manual control releases guidance. Use the RCS pad for fine translation; the main engine is powerful at this distance.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("The docking ports and hulls have physical contact response. A fast or misaligned collision cancels capture and records an unsafe contact. Docking locks the vehicles at their actual arrival pose.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Once docked, propulsion is inhibited. Undock releases the attachment without pushing either vehicle; hold Back on the RCS pad to separate.", fontSize = 12.sp, lineHeight = 18.sp)
            Text("Your current experiment and its complete history are saved before this new mission opens.", color = DockTeal, fontSize = 12.sp, lineHeight = 18.sp)
            error?.let { Text(it, color = DockAmber, fontSize = 12.sp) }
            if (busy) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Saving this world and preparing the port…", fontSize = 12.sp) }
        } },
        confirmButton = { Button(onClick = onStart, enabled = !busy, modifier = Modifier.testTag(DockingTags.START)) { Text("Start docking") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Return to flight") } },
    )
}

@Composable
internal fun DockingPanel(
    state: DockingState, compact: Boolean, paused: Boolean, ready: Boolean, busy: Boolean,
    onGuidance: (DockingGuidance) -> Unit, onUndock: () -> Unit, modifier: Modifier = Modifier,
) {
    val warning = state.unsafeContact || state.status in listOf(DockingStatus.OutOfFuel,
        DockingStatus.OutOfRange, DockingStatus.TargetUnavailable)
    val accent = if (warning) DockAmber else DockTeal
    Surface(modifier.fillMaxWidth().testTag(DockingTags.PANEL), color = Color(0xEB0B1724),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF294451))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = if (compact) 3.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) DockingAlignment(state, Modifier.size(42.dp))
            Column(Modifier.weight(if (compact) 1.6f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when {
                    state.attached -> "RELAY · PHYSICAL ATTACHMENT"
                    state.unsafeContact -> "RELAY · CONTACT EVENT RECORDED"
                    state.armed -> "RELAY · CAPTURE ARMED"
                    else -> "RELAY · SERVICE PORT"
                }, color = accent, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = .8.sp, maxLines = 1)
                Text((if (paused) "Paused · " else "") + state.status.label,
                    Modifier.testTag(DockingTags.STATUS), color = DockInk, fontSize = if (compact) 12.sp else 14.sp,
                    lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dockingOffsetLabel(state), Modifier.testTag(DockingTags.OFFSET), color = DockMuted,
                    fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) {
                    DockingActions(state, ready, busy, onGuidance, onUndock)
                    Text(dockingInstruction(state), color = if (warning) DockAmber else DockMuted,
                        fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) DockingActions(state, ready, busy, onGuidance, onUndock, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DockingActions(state: DockingState, ready: Boolean, busy: Boolean,
    onGuidance: (DockingGuidance) -> Unit, onUndock: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (state.attached) {
            DockingButton("Undock", DockingTags.UNDOCK, false, ready && !busy, Modifier.weight(1f), onUndock)
        } else {
            DockingButton("Dock", DockingTags.DOCK, state.guidance == DockingGuidance.Approach,
                ready && !busy && state.targetAvailable && state.remainingPropellantKg > 0.0, Modifier.weight(1f)) {
                onGuidance(DockingGuidance.Approach)
            }
            DockingButton("Abort", DockingTags.ABORT, false, ready && !busy, Modifier.weight(1f)) {
                onGuidance(DockingGuidance.Manual)
            }
        }
    }
}

@Composable
private fun DockingButton(label: String, tag: String, active: Boolean, enabled: Boolean,
    modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 44.dp).testTag(tag),
        colors = ButtonDefaults.textButtonColors(contentColor = DockTeal,
            containerColor = if (active) Color(0xFF1B3A42) else Color.Transparent), shape = RoundedCornerShape(9.dp)) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DockingAlignment(state: DockingState, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = dockingOffsetLabel(state) }) {
        val centre = Offset(size.width * .5f, size.height * .5f)
        val radius = size.minDimension * .43f
        val scaleM = max(state.captureLateralLimitM * 4, state.lateralErrorM)
        drawCircle(Color(0xFF476271), radius, centre, style = Stroke(1.dp.toPx()))
        drawCircle(DockTeal.copy(alpha = .35f), (radius * state.captureLateralLimitM / scaleM).toFloat(), centre)
        drawLine(Color(0xFF294451), Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y))
        drawLine(Color(0xFF294451), Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius))
        if (state.targetAvailable) drawCircle(if (state.captureReady || state.attached) DockTeal else DockAmber,
            3.dp.toPx(), Offset(centre.x + (state.positionPortXM / scaleM * radius).toFloat(),
                centre.y - (state.positionPortYM / scaleM * radius).toFloat()))
    }
}

internal fun dockingInstruction(state: DockingState): String = when {
    state.attached -> "Latches engaged. Engines locked. Undock releases the joint; hold Back to fly away."
    !state.targetAvailable -> "The saved station is unavailable. Start a new docking mission."
    state.unsafeContact -> "Unsafe contact was recorded. Capture is cancelled; use RCS to separate and realign before trying Dock again."
    state.guidance == DockingGuidance.Approach -> "Dock is controlling the thrusters. Run time to approach; Abort or a manual control takes over."
    else -> "Dock aligns and approaches the port. After undocking, hold Back to separate. Nearby operations remain at 1×."
}

internal fun dockingOffsetLabel(state: DockingState): String = if (!state.targetAvailable) "No port position" else
    "Port offset: right ${dockingSignedDistance(state.positionPortXM)} · up ${dockingSignedDistance(state.positionPortYM)}"

internal fun dockingDistance(metres: Double): String = if (abs(metres) < 1)
    "%.1f cm".format(Locale.US, metres * 100) else "%.2f m".format(Locale.US, metres)

private fun dockingSignedDistance(metres: Double): String = if (abs(metres) < 1)
    "%+.1f cm".format(Locale.US, metres * 100) else "%+.2f m".format(Locale.US, metres)

internal fun dockingAngle(radians: Double): String = "%.1f°".format(Locale.US, Math.toDegrees(radians))
