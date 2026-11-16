package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.sednalabs.solarlab.render.vulkan.LabBodyAnchor
import com.sednalabs.solarlab.render.vulkan.LabProjectedPoint
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.runtime.ProbePreviewResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object LabSpatialTags {
    const val VIEWPORT = "lab.viewport"
    const val DRAFT = "lab.placement.ghost"
    const val VELOCITY = "lab.placement.velocity_handle"
    const val PREVIEW = "lab.placement.path"
    fun label(id: String) = "lab.object.$id"
}

private val SpatialCyan = Color(0xFF8BE5EA)
private val SpatialGold = Color(0xFFFFD18A)

private data class LabLabelPosition(
    val anchor: LabBodyAnchor,
    val title: String,
    val bounds: Rect,
    val selected: Boolean,
    val locator: Boolean,
)

/** Overlay identity is deliberately screen-sized; body geometry remains at physical scale. */
@Composable
internal fun LabSceneOverlay(
    viewport: LabViewportState?,
    names: Map<String, String>,
    selectedBodyId: String?,
    draft: LabProbeDraft?,
    preview: ProbePreviewResult?,
    onSelect: (String) -> Unit,
    onLocate: (String) -> Unit,
    onVelocityDrag: (Vector3d, Double) -> Unit,
    committing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state = viewport ?: return
    val density = LocalDensity.current.density
    val labels = if (draft == null) labLabels(state, names, selectedBodyId, density) else emptyList()
    val projectedDraft = draft?.positionM?.let(state::project)
    val velocity = draft?.relativeVelocityMps()
    val arrowScale = if (draft != null) {
        68.0 * density * state.metresPerPixel / max(draft.circularSpeedMps ?: 1.0, labLength(velocity ?: Vector3d(0.0, 0.0, 0.0)) / 1.8).coerceAtLeast(1.0)
    } else 1.0
    val hasVelocity = velocity != null && labLength(velocity) > 0.01
    val arrowTip = if (draft?.positionM != null && velocity != null) {
        val displayArrow = if (hasVelocity) velocity * arrowScale
            else draft.tangentUnit * (36.0 * density * state.metresPerPixel)
        state.project(draft.positionM!! + displayArrow)
    } else null

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().testTag(LabSpatialTags.PREVIEW)) {
            labels.forEach { label ->
                val point = Offset(label.anchor.xPx, label.anchor.yPx)
                val colour = if (label.selected) SpatialCyan else Color(0xFFBCD0DF)
                if (!label.locator) {
                    val radius = label.anchor.radiusPx.coerceAtLeast(3.5f * density)
                    if (label.selected) drawCircle(colour.copy(alpha = 0.48f), radius + 5f * density, point,
                        style = Stroke(1.2f * density, pathEffect = if (label.anchor.occluded) PathEffect.dashPathEffect(floatArrayOf(4f * density, 4f * density)) else null))
                    if (label.anchor.radiusPx < 8f * density) {
                        drawCircle(Color(0xFF07131F), 5.5f * density, point)
                        drawCircle(colour, 3.1f * density, point, style = Stroke(1.4f * density))
                    }
                    val target = Offset(label.bounds.center.x, label.bounds.center.y)
                    val delta = target - point
                    val length = sqrt(delta.x * delta.x + delta.y * delta.y).coerceAtLeast(1f)
                    val start = point + delta * (min(radius + 6f * density, length) / length)
                    val end = Offset(target.x.coerceIn(label.bounds.left, label.bounds.right),
                        if (target.y > point.y) label.bounds.top else label.bounds.bottom)
                    drawLine(colour.copy(alpha = 0.35f), start, end, 1f * density)
                }
            }

            if (draft != null) {
                val reference = draft.reference.labPosition()
                val radius = draft.distanceM
                if (radius != null && radius > draft.reference.radiusM && radius.isFinite()) {
                    val ring = Path()
                    for (step in 0..128) {
                        val angle = step * 2.0 * PI / 128.0
                        val point = state.project(reference + (draft.plane.axisU * cos(angle) + draft.plane.axisV * sin(angle)) * radius)
                        if (step == 0) ring.moveTo(point.xPx, point.yPx) else ring.lineTo(point.xPx, point.yPx)
                    }
                    drawPath(ring, SpatialGold.copy(alpha = if (draft.altitudeLocked) 0.42f else 0.15f),
                        style = Stroke(1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 6f * density))))
                }
                if (preview != null) {
                    val path = Path()
                    preview.samples.forEachIndexed { index, sample ->
                        val point = state.project(preview.referenceBody.labPosition() + Vector3d(sample.positionX, sample.positionY, sample.positionZ))
                        if (index == 0) path.moveTo(point.xPx, point.yPx) else path.lineTo(point.xPx, point.yPx)
                    }
                    drawPath(path, if (preview.intersectsReferenceBody) Color(0xFFFFA28F) else SpatialGold.copy(alpha = 0.85f),
                        style = Stroke(2f * density, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 5f * density))))
                }
                projectedDraft?.let { point ->
                    val origin = Offset(point.xPx, point.yPx)
                    drawCircle(SpatialGold.copy(alpha = 0.12f), 19f * density, origin)
                    drawCircle(SpatialGold, 7f * density, origin, style = Stroke(2f * density))
                    drawCircle(SpatialGold, 2.5f * density, origin)
                    arrowTip?.let { tip ->
                        val end = Offset(tip.xPx, tip.yPx)
                        val delta = end - origin
                        val length = sqrt(delta.x * delta.x + delta.y * delta.y).coerceAtLeast(1f)
                        val unit = delta / length
                        drawLine(SpatialGold, origin + unit * (11f * density), end, 2f * density, StrokeCap.Round,
                            pathEffect = if (hasVelocity) null else PathEffect.dashPathEffect(floatArrayOf(3f * density, 4f * density)))
                        val perpendicular = Offset(-unit.y, unit.x)
                        if (hasVelocity) {
                            drawLine(SpatialGold, end, end - unit * (10f * density) + perpendicular * (5f * density), 2f * density, StrokeCap.Round)
                            drawLine(SpatialGold, end, end - unit * (10f * density) - perpendicular * (5f * density), 2f * density, StrokeCap.Round)
                        }
                    }
                }
            }
        }

        labels.forEach { label ->
            Surface(
                onClick = { if (label.locator) onLocate(label.anchor.bodyId) else onSelect(label.anchor.bodyId) },
                modifier = Modifier.offset { IntOffset(label.bounds.left.roundToInt(), label.bounds.top.roundToInt()) }
                    .size((label.bounds.width / density).dp, 48.dp)
                    .testTag(LabSpatialTags.label(label.anchor.bodyId))
                    .semantics { contentDescription = if (label.locator) "Locate ${label.title}" else "Select ${label.title}" },
                color = Color(0xE60B1825), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (label.selected) SpatialCyan.copy(alpha = 0.7f) else Color(0xFF314552)),
            ) {
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(if (label.locator) "Locate · ${label.title}" else label.title,
                        color = if (label.selected) SpatialCyan else Color(0xFFE3EDF3), fontSize = 12.sp,
                        fontWeight = if (label.selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (draft != null && projectedDraft != null) {
            val left = (projectedDraft.xPx + 14f * density).coerceIn(4f * density, max(4f * density, state.widthPx - 142f * density))
            val top = (projectedDraft.yPx - 34f * density).coerceIn(4f * density, max(4f * density, state.heightPx - 48f * density))
            Text("${draft.probeId.replace('-', ' ')} · ${draft.altitudeKmText} km", color = SpatialGold, fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .testTag(LabSpatialTags.DRAFT)
                    .semantics { contentDescription = "Draft ${draft.probeId}, altitude ${draft.altitudeKmText} kilometres" })
            if (!committing && arrowTip != null && arrowTip.xPx in 24f * density..state.widthPx - 24f * density &&
                arrowTip.yPx in 24f * density..state.heightPx - 24f * density) {
                LabVelocityHandle(arrowTip, state, draft, arrowScale, hasVelocity, onVelocityDrag)
            }
        }
    }
}

@Composable
private fun LabVelocityHandle(
    tip: LabProjectedPoint,
    viewport: LabViewportState,
    draft: LabProbeDraft,
    arrowScale: Double,
    hasVelocity: Boolean,
    onVelocityDrag: (Vector3d, Double) -> Unit,
) {
    val density = LocalDensity.current.density
    val currentTip by rememberUpdatedState(tip)
    val currentViewport by rememberUpdatedState(viewport)
    val currentDraft by rememberUpdatedState(draft)
    val currentScale by rememberUpdatedState(arrowScale)
    val currentDrag by rememberUpdatedState(onVelocityDrag)
    var position = remember { Offset.Zero }
    var dragScale = remember { 1.0 }
    Surface(
        modifier = Modifier.offset { IntOffset((tip.xPx - 24f * density).roundToInt(), (tip.yPx - 24f * density).roundToInt()) }
            .size(48.dp).testTag(LabSpatialTags.VELOCITY)
            .semantics { contentDescription = if (hasVelocity) "Drag to change probe velocity" else "Drag to set probe velocity" }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { position = Offset(currentTip.xPx, currentTip.yPx); dragScale = currentScale },
                    onDrag = { change, amount ->
                        change.consume()
                        position += amount
                        currentViewport.unprojectToPlane(position.x, position.y, currentDraft.plane.through(currentDraft.reference))?.let {
                            currentDrag(it, 1.0 / dragScale)
                        }
                    })
            },
        color = SpatialGold.copy(alpha = 0.13f), shape = CircleShape,
        border = BorderStroke(1.dp, SpatialGold.copy(alpha = 0.6f)),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(if (hasVelocity) "↗" else "+", color = SpatialGold, fontSize = 19.sp) }
    }
}

private fun labLabels(
    state: LabViewportState,
    names: Map<String, String>,
    selectedId: String?,
    density: Float,
): List<LabLabelPosition> {
    val result = mutableListOf<LabLabelPosition>()
    val margin = 6f * density
    // The permanent zoom rail occupies the right edge of this same viewport.
    val rightReserve = 72f * density
    val height = 48f * density
    state.bodies.sortedWith(compareBy<LabBodyAnchor> {
        when (it.bodyId) { selectedId -> 0; "earth" -> 1; "moon" -> 2; else -> 3 }
    }.thenBy { it.bodyId }).filter { it.bodyId in names && (it.bodyId != "sun" || it.bodyId == selectedId) }.take(18).forEach { anchor ->
        if (!anchor.xPx.isFinite() || !anchor.yPx.isFinite()) return@forEach
        val selected = anchor.bodyId == selectedId
        val offscreen = anchor.xPx < 0 || anchor.yPx < 0 || anchor.xPx > state.widthPx || anchor.yPx > state.heightPx
        if ((offscreen || anchor.occluded) && !selected) return@forEach
        val title = (names[anchor.bodyId] ?: anchor.bodyId) + if (anchor.occluded) " · hidden" else ""
        val width = (((title.length + if (offscreen) 9 else 0) * 7f + 26f).coerceIn(70f, 208f) * density)
            .coerceAtMost((state.widthPx - margin - rightReserve).coerceAtLeast(1f))
        if (state.heightPx < height + 2f * margin) return@forEach
        val radius = anchor.radiusPx.coerceAtLeast(8f * density)
        val candidates = listOf(
            Offset(anchor.xPx + radius * 0.65f + 10f * density, anchor.yPx - radius * 0.65f - height),
            Offset(anchor.xPx - radius * 0.65f - 10f * density - width, anchor.yPx - radius * 0.65f - height),
            Offset(anchor.xPx + radius * 0.65f + 10f * density, anchor.yPx + radius * 0.65f),
            Offset(anchor.xPx - width / 2f, anchor.yPx + radius + 12f * density),
        )
        val bounds = candidates.map { point ->
            val x = point.x.coerceIn(margin, max(margin, state.widthPx - width - rightReserve))
            val y = point.y.coerceIn(margin, max(margin, state.heightPx - height - margin))
            Rect(x, y, x + width, y + height)
        }.firstOrNull { candidate -> result.none { it.bounds.inflate(4f * density).overlaps(candidate) } }
            ?: if (selected) Rect(margin, margin, margin + width, margin + height) else return@forEach
        result += LabLabelPosition(anchor, title, bounds, selected, offscreen)
    }
    return result
}
