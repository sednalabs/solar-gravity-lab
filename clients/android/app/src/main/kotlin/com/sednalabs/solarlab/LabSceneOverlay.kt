package com.sednalabs.solarlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

internal data class LabLabelPosition(
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
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val labels = if (draft == null) labLabels(state, names, selectedBodyId, density) { title ->
        textMeasurer.measure(AnnotatedString(title), labelStyle, maxLines = 1).size.width.toFloat()
    } else emptyList()
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
    val previewPoints = preview?.samples?.map { sample ->
        state.project(preview.referenceBody.labPosition() + Vector3d(sample.positionX, sample.positionY, sample.positionZ))
    }.orEmpty()
    val draftCaption = draft?.let { "${it.probeId.replace('-', ' ').replaceFirstChar { c -> c.uppercase() }} · ${it.altitudeKmText} km" }
    val draftCaptionBounds = if (draftCaption != null && projectedDraft != null) {
        val textSize = textMeasurer.measure(AnnotatedString(draftCaption), labelStyle, maxLines = 1).size
        val width = (textSize.width + 18f * density)
            .coerceIn(96f * density, 196f * density)
        val height = max(28f * density, textSize.height + 10f * density)
        labDraftCaptionBounds(state, projectedDraft, arrowTip, previewPoints, width, height, density)
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
                    // The compact visual pill sits inside a larger transparent touch target.
                    drawLine(colour.copy(alpha = if (label.selected) 0.32f else 0.2f), start, target, 1f * density)
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
                    previewPoints.forEachIndexed { index, point ->
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
                color = Color.Transparent, shape = RoundedCornerShape(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(Modifier.fillMaxWidth().heightIn(min = 28.dp),
                        color = if (label.selected) Color(0xD00C2430) else Color(0xB5091520),
                        shape = RoundedCornerShape(8.dp),
                        border = if (label.selected) BorderStroke(1.dp, SpatialCyan.copy(alpha = 0.22f)) else null) {
                        Box(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(if (label.locator) "Locate · ${label.title}" else label.title,
                                color = if (label.selected) SpatialCyan else Color(0xFFE3EDF3), fontSize = 12.sp,
                                fontWeight = if (label.selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (draft != null && projectedDraft != null && draftCaptionBounds != null) {
            Surface(modifier = Modifier.offset { IntOffset(draftCaptionBounds.left.roundToInt(), draftCaptionBounds.top.roundToInt()) }
                    .size((draftCaptionBounds.width / density).dp, (draftCaptionBounds.height / density).dp)
                    .testTag(LabSpatialTags.DRAFT)
                    .semantics { contentDescription = "Draft ${draft.probeId}, altitude ${draft.altitudeKmText} kilometres" },
                color = Color(0xED101A22), shape = RoundedCornerShape(8.dp)) {
                Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                    Text(draftCaption.orEmpty(), color = SpatialGold, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!committing && arrowTip != null && arrowTip.xPx in 24f * density..state.widthPx - 24f * density &&
                arrowTip.yPx in 24f * density..state.heightPx - 24f * density) {
                LabVelocityHandle(arrowTip, projectedDraft, state, draft, arrowScale, hasVelocity, onVelocityDrag)
            }
        }
    }
}

@Composable
private fun LabVelocityHandle(
    tip: LabProjectedPoint,
    origin: LabProjectedPoint,
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
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val centre = Offset(size.width * 0.5f, size.height * 0.5f)
                val stroke = 1.7.dp.toPx()
                if (hasVelocity) {
                    val direction = Offset(tip.xPx - origin.xPx, tip.yPx - origin.yPx)
                    val length = sqrt(direction.x * direction.x + direction.y * direction.y).coerceAtLeast(0.001f)
                    val unit = direction / length
                    val perpendicular = Offset(-unit.y, unit.x)
                    val end = centre + unit * (7.dp.toPx())
                    drawLine(SpatialGold, centre - unit * (7.dp.toPx()), end, stroke, StrokeCap.Round)
                    drawLine(SpatialGold, end, end - unit * (5.dp.toPx()) + perpendicular * (4.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(SpatialGold, end, end - unit * (5.dp.toPx()) - perpendicular * (4.dp.toPx()), stroke, StrokeCap.Round)
                } else {
                    drawLine(SpatialGold, centre - Offset(6.dp.toPx(), 0f), centre + Offset(6.dp.toPx(), 0f), stroke, StrokeCap.Round)
                    drawLine(SpatialGold, centre - Offset(0f, 6.dp.toPx()), centre + Offset(0f, 6.dp.toPx()), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

internal fun labLabels(
    state: LabViewportState,
    names: Map<String, String>,
    selectedId: String?,
    density: Float,
    titleWidthPx: (String) -> Float = { it.length * 7f * density },
): List<LabLabelPosition> {
    val result = mutableListOf<LabLabelPosition>()
    val margin = 6f * density
    // The permanent zoom rail occupies the right edge of this same viewport.
    val rightReserve = 72f * density
    val height = 48f * density
    val visibleBodies = state.bodies.filter { !it.occluded && it.radiusPx.isFinite() && it.radiusPx > 0f }
    state.bodies.sortedWith(compareBy<LabBodyAnchor> {
        when (it.bodyId) { selectedId -> 0; "earth" -> 1; "moon" -> 2; else -> 3 }
    }.thenBy { it.bodyId }).asSequence().filter {
        it.bodyId in names && (it.bodyId != "sun" || it.bodyId == selectedId) &&
            it.xPx.isFinite() && it.yPx.isFinite() && (it.bodyId == selectedId ||
            (!it.occluded && it.xPx in 0f..state.widthPx.toFloat() && it.yPx in 0f..state.heightPx.toFloat()))
    }.take(18).forEach { anchor ->
        val selected = anchor.bodyId == selectedId
        val offscreen = anchor.xPx < 0 || anchor.yPx < 0 || anchor.xPx > state.widthPx || anchor.yPx > state.heightPx
        val title = (names[anchor.bodyId] ?: anchor.bodyId) + if (anchor.occluded) " · hidden" else ""
        val width = (titleWidthPx(if (offscreen) "Locate · $title" else title) + 20f * density)
            .coerceIn(56f * density, 208f * density)
            .coerceAtMost((state.widthPx - margin - rightReserve).coerceAtLeast(1f))
        if (state.heightPx < height + 2f * margin) return@forEach
        val radius = anchor.radiusPx.takeIf { it.isFinite() }?.coerceAtLeast(8f * density) ?: 8f * density
        val gap = 10f * density
        val candidates = listOf(
            Offset(anchor.xPx + radius * 0.78f + gap, anchor.yPx - radius * 0.78f - height - gap),
            Offset(anchor.xPx - radius * 0.78f - gap - width, anchor.yPx - radius * 0.78f - height - gap),
            Offset(anchor.xPx + radius + gap, anchor.yPx - height / 2f),
            Offset(anchor.xPx - radius - gap - width, anchor.yPx - height / 2f),
            Offset(anchor.xPx - width / 2f, anchor.yPx + radius + gap),
            Offset(anchor.xPx - width / 2f, anchor.yPx - radius - gap - height),
            Offset(margin, margin),
            Offset(margin, state.heightPx - height - margin),
        )
        val rectangles = candidates.map { point ->
            val x = point.x.coerceIn(margin, max(margin, state.widthPx - width - rightReserve))
            val y = point.y.coerceIn(margin, max(margin, state.heightPx - height - margin))
            Rect(x, y, x + width, y + height)
        }.distinct()
        val bounds = rectangles.firstOrNull { candidate ->
            result.none { it.bounds.inflate(4f * density).overlaps(candidate) } &&
                visibleBodies.none { candidate.touchesBody(it, 4f * density) }
        } ?: if (selected) rectangles.minBy { candidate ->
            visibleBodies.count { candidate.touchesBody(it, 4f * density) }
        } else return@forEach
        result += LabLabelPosition(anchor, title, bounds, selected, offscreen)
    }
    return result
}

/** Keep the draft caption beside its marker, clear of the path and velocity control. */
internal fun labDraftCaptionBounds(
    state: LabViewportState,
    origin: LabProjectedPoint,
    arrowTip: LabProjectedPoint?,
    preview: List<LabProjectedPoint>,
    requestedWidth: Float,
    requestedHeight: Float,
    density: Float,
): Rect {
    val margin = 6f * density
    val width = requestedWidth.coerceAtMost(max(1f, state.widthPx - margin * 2f))
    val height = requestedHeight.coerceAtMost(max(1f, state.heightPx - margin * 2f))
    val left = origin.xPx - width - 24f * density
    val right = origin.xPx + 24f * density
    val above = origin.yPx - height - 24f * density
    val below = origin.yPx + 24f * density
    val xs = if ((arrowTip?.xPx ?: origin.xPx) >= origin.xPx) listOf(left, right) else listOf(right, left)
    val ys = if ((arrowTip?.yPx ?: origin.yPx) <= origin.yPx) listOf(below, above) else listOf(above, below)
    val candidates = xs.flatMap { x -> ys.map { y -> Offset(x, y) } } + listOf(
        Offset(origin.xPx - width / 2f, origin.yPx - height - 42f * density),
        Offset(origin.xPx - width / 2f, origin.yPx + 42f * density),
    )
    val segments = preview.zipWithNext { a, b ->
        Rect(min(a.xPx, b.xPx), min(a.yPx, b.yPx), max(a.xPx, b.xPx), max(a.yPx, b.yPx)).inflate(2f * density)
    }
    val handle = arrowTip?.let { Rect(it.xPx - 24f * density, it.yPx - 24f * density,
        it.xPx + 24f * density, it.yPx + 24f * density) }
    val marker = Rect(origin.xPx - 19f * density, origin.yPx - 19f * density,
        origin.xPx + 19f * density, origin.yPx + 19f * density)
    return candidates.map { point ->
        val x = point.x.coerceIn(margin, max(margin, state.widthPx - width - margin))
        val y = point.y.coerceIn(margin, max(margin, state.heightPx - height - margin))
        Rect(x, y, x + width, y + height)
    }.distinct().minBy { candidate ->
        val pathCrossings = segments.count { it.overlaps(candidate.inflate(3f * density)) }
        val bodyCrossings = state.bodies.count { !it.occluded && candidate.touchesBody(it, 4f * density) }
        pathCrossings * 5 + bodyCrossings * 100 +
            (if (handle?.overlaps(candidate) == true) 500 else 0) +
            (if (marker.overlaps(candidate)) 1_000 else 0)
    }
}

private fun Rect.touchesBody(anchor: LabBodyAnchor, clearance: Float): Boolean {
    if (!anchor.radiusPx.isFinite() || anchor.radiusPx <= 0f) return false
    val dx = anchor.xPx - anchor.xPx.coerceIn(left, right)
    val dy = anchor.yPx - anchor.yPx.coerceIn(top, bottom)
    val radius = anchor.radiusPx + clearance
    return dx * dx + dy * dy < radius * radius
}
