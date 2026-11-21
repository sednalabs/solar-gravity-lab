package com.sednalabs.solarlab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednalabs.solarlab.runtime.FlightBurnPreview
import kotlin.math.min

/** Common scale and focus; orientation is deliberately schematic and stated in the caption. */
@Composable
internal fun FlightOrbitShapeReadout(hostRadiusM: Double, result: FlightBurnPreview, stale: Boolean) {
    val shape = flightOrbitShape(hostRadiusM, result.beforePeriapsisM, result.beforeApoapsisM,
        result.afterPeriapsisM, result.afterApoapsisM) ?: return
    val description = "Orbit size comparison. Before is dashed, after is solid. " +
        "Same scale, with both periapses drawn to the right. Orbital plane and rotation are not shown."
    Column(Modifier.fillMaxWidth().testTag(FlightPlanningTags.SHAPE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ORBIT SIZE  ·  before dashed / after solid", color = FlightMuted, fontSize = 9.sp)
        Canvas(Modifier.fillMaxWidth().height(106.dp).semantics { contentDescription = description }) {
            val margin = 6.dp.toPx()
            val scale = min((size.width - margin * 2) / (shape.left + shape.right),
                (size.height - margin * 2) / (shape.halfHeight * 2)).toFloat()
            val focus = Offset((size.width + (shape.left - shape.right).toFloat() * scale) * .5f, size.height * .5f)
            drawCircle(FlightLine.copy(alpha = .7f), (shape.hostRadius * scale).toFloat(), focus)
            drawCircle(FlightMuted.copy(alpha = .5f), (shape.hostRadius * scale).toFloat(), focus, style = Stroke(1.dp.toPx()))
            val alpha = if (stale) .4f else 1f
            fun orbit(ellipse: FlightOrbitEllipse?, after: Boolean) {
                if (ellipse == null) return
                val centre = focus + Offset((ellipse.centreX * scale).toFloat(), 0f)
                val rx = (ellipse.major * scale).toFloat()
                val ry = (ellipse.minor * scale).toFloat()
                val colour = (if (after) FlightTeal else FlightMuted).copy(alpha = alpha)
                drawOval(colour, centre - Offset(rx, ry), Size(rx * 2, ry * 2),
                    style = Stroke(if (after) 1.8.dp.toPx() else 1.2.dp.toPx(),
                        pathEffect = if (after) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                if (after) {
                    drawCircle(colour, 2.dp.toPx(), centre + Offset(rx, 0f))
                    drawCircle(colour, 2.dp.toPx(), centre - Offset(rx, 0f))
                }
            }
            orbit(shape.before, false)
            orbit(shape.after, true)
        }
        Text("Same scale; periapses drawn to the right. Plane and rotation are not shown.",
            color = FlightMuted, fontSize = 10.sp, lineHeight = 14.sp)
        if (shape.before == null || shape.after == null) Text(
            "${if (shape.after == null) "After" else "Before"}: no closed ellipse to display.",
            color = FlightAmber, fontSize = 11.sp)
    }
}
