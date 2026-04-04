package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * GPU-ready scene packet shared by the Android UI layer and native render backends.
 *
 * Authoritative bodies stay unthinned. Tracers are camera-aware and partitioned into deterministic
 * LOD tiers so the native renderer can scale up without having to own first-pass visibility logic.
 */
data class NativeScenePacket(
    val sourceRevision: Long,
    val authoritativePositionsM: DoubleArray,
    val authoritativeRadiiM: FloatArray,
    val authoritativeColorsArgb: IntArray,
    val authoritativeKinds: IntArray,
    val tracerNearPositionsM: DoubleArray,
    val tracerNearRadiiM: FloatArray,
    val tracerNearColorsArgb: IntArray,
    val tracerNearKinds: IntArray,
    val tracerMediumPositionsM: DoubleArray,
    val tracerMediumRadiiM: FloatArray,
    val tracerMediumColorsArgb: IntArray,
    val tracerMediumKinds: IntArray,
    val tracerFarPositionsM: DoubleArray,
    val tracerFarRadiiM: FloatArray,
    val tracerFarColorsArgb: IntArray,
    val tracerFarKinds: IntArray,
    val trailPositionsM: DoubleArray,
    val trailColorsArgb: IntArray,
    val trailVertexCounts: IntArray,
) {
    val authoritativeCount: Int get() = authoritativeRadiiM.size
    val tracerNearCount: Int get() = tracerNearRadiiM.size
    val tracerMediumCount: Int get() = tracerMediumRadiiM.size
    val tracerFarCount: Int get() = tracerFarRadiiM.size
    val trailCount: Int get() = trailVertexCounts.size

    companion object {
        @JvmStatic
        fun fromScene(
            frame: RenderSceneFrame,
            cameraState: CameraState? = null,
            viewportWidthPx: Int = 1920,
            viewportHeightPx: Int = 1080,
            policy: ScenePacketBuildPolicy = ScenePacketBuildPolicy(),
            selectedBodyId: String? = null,
        ): NativeScenePacket {
            val view = cameraState?.let {
                SceneView(
                    centerM = it.centerM,
                    viewRadiusM = it.viewRadiusM,
                    viewportWidthPx = viewportWidthPx.coerceAtLeast(1),
                    viewportHeightPx = viewportHeightPx.coerceAtLeast(1),
                    policy = policy,
                )
            }

            val authoritativeBodies = if (view == null) {
                frame.authoritativeBodies
            } else {
                frame.authoritativeBodies.filter { body ->
                    view.classify(body.positionM) != null
                }
            }
            val authoritativePack = packBodies(authoritativeBodies, selectedBodyId)

            val tracerSelection = if (view == null) {
                TracerSelection(
                    near = frame.tracerBodies,
                    medium = emptyList(),
                    far = emptyList(),
                )
            } else {
                selectTracerTiers(frame.tracerBodies, view, policy)
            }

            val nearPack = packBodies(tracerSelection.near, selectedBodyId)
            val mediumPack = packBodies(tracerSelection.medium, selectedBodyId)
            val farPack = packBodies(tracerSelection.far, selectedBodyId)

            val simplifiedTrails = if (view == null) {
                frame.trails.map { trail ->
                    trail.copy(pointsM = capTrailVertices(trail.pointsM, policy.maxTrailVerticesPerTrail))
                }
            } else {
                frame.trails.mapNotNull { trail ->
                    simplifyTrail(trail, view, policy)
                }
            }
            val trailPack = packTrails(simplifiedTrails, selectedBodyId)

            return NativeScenePacket(
                sourceRevision = frame.sourceRevision,
                authoritativePositionsM = authoritativePack.positionsM,
                authoritativeRadiiM = authoritativePack.radiiM,
                authoritativeColorsArgb = authoritativePack.colorsArgb,
                authoritativeKinds = authoritativePack.kinds,
                tracerNearPositionsM = nearPack.positionsM,
                tracerNearRadiiM = nearPack.radiiM,
                tracerNearColorsArgb = nearPack.colorsArgb,
                tracerNearKinds = nearPack.kinds,
                tracerMediumPositionsM = mediumPack.positionsM,
                tracerMediumRadiiM = mediumPack.radiiM,
                tracerMediumColorsArgb = mediumPack.colorsArgb,
                tracerMediumKinds = mediumPack.kinds,
                tracerFarPositionsM = farPack.positionsM,
                tracerFarRadiiM = farPack.radiiM,
                tracerFarColorsArgb = farPack.colorsArgb,
                tracerFarKinds = farPack.kinds,
                trailPositionsM = trailPack.positionsM,
                trailColorsArgb = trailPack.colorsArgb,
                trailVertexCounts = trailPack.vertexCounts,
            )
        }

        private fun selectTracerTiers(
            tracers: List<RenderBody>,
            view: SceneView,
            policy: ScenePacketBuildPolicy,
        ): TracerSelection {
            if (tracers.isEmpty()) {
                return TracerSelection(emptyList(), emptyList(), emptyList())
            }

            val near = ArrayList<ScoredBody>()
            val medium = ArrayList<ScoredBody>()
            val far = ArrayList<ScoredBody>()
            for (body in tracers) {
                val tier = view.classify(body.positionM) ?: continue
                val score = tracerScore(body, view)
                when (tier) {
                    TracerLodTier.NEAR -> near += ScoredBody(body, score)
                    TracerLodTier.MEDIUM -> medium += ScoredBody(body, score)
                    TracerLodTier.FAR -> far += ScoredBody(body, score)
                }
            }

            return TracerSelection(
                near = downsampleTier(near, policy.nearTracerBudget),
                medium = downsampleTier(medium, policy.mediumTracerBudget),
                far = downsampleTier(far, policy.farTracerBudget),
            )
        }

        private fun tracerScore(body: RenderBody, view: SceneView): Double {
            val relative = body.positionM - view.centerM
            val distance = max(relative.magnitude(), 1.0)
            val projected = body.radiusM / view.metersPerPixel
            val kindBias = when (body.kind) {
                RenderBodyKind.COMET -> 3.0
                RenderBodyKind.PROBE -> 2.0
                RenderBodyKind.TEST_OBJECT -> 2.5
                else -> 1.0
            }
            return projected * kindBias + (view.viewRadiusM / distance)
        }

        private fun downsampleTier(bodies: List<ScoredBody>, budget: Int): List<RenderBody> {
            if (budget <= 0 || bodies.isEmpty()) return emptyList()
            if (bodies.size <= budget) {
                return bodies.sortedByDescending { it.score }.map { it.body }
            }

            val sorted = bodies.sortedByDescending { it.score }
            val guaranteed = min(sorted.size, max(1, budget / 4))
            val selected = ArrayList<RenderBody>(budget)
            repeat(guaranteed) { index ->
                selected += sorted[index].body
            }
            if (selected.size == budget) {
                return selected
            }

            val remaining = sorted.subList(guaranteed, sorted.size)
            val step = remaining.size.toDouble() / (budget - selected.size).toDouble()
            var cursor = 0.0
            while (selected.size < budget && remaining.isNotEmpty()) {
                val index = min(remaining.lastIndex, cursor.toInt())
                selected += remaining[index].body
                cursor += step
            }
            return selected
        }

        private fun simplifyTrail(
            trail: RenderTrail,
            view: SceneView,
            policy: ScenePacketBuildPolicy,
        ): RenderTrail? {
            if (trail.pointsM.size < 2) return null
            val clipped = trail.pointsM.mapNotNull { point ->
                val screen = view.toScreen(point)
                if (screen.x < -0.35 * view.viewportWidthPx || screen.x > 1.35 * view.viewportWidthPx ||
                    screen.y < -0.35 * view.viewportHeightPx || screen.y > 1.35 * view.viewportHeightPx
                ) {
                    null
                } else {
                    ScreenPoint3(point, screen.x, screen.y)
                }
            }
            if (clipped.size < 2) return null

            val simplified = ArrayList<Vector3d>(min(clipped.size, policy.maxTrailVerticesPerTrail))
            simplified += clipped.first().world
            var lastKept = clipped.first()
            for (index in 1 until clipped.lastIndex) {
                val candidate = clipped[index]
                val dx = candidate.xPx - lastKept.xPx
                val dy = candidate.yPx - lastKept.yPx
                if ((dx * dx + dy * dy) >= policy.trailSimplificationTolerancePx * policy.trailSimplificationTolerancePx) {
                    simplified += candidate.world
                    lastKept = candidate
                    if (simplified.size >= policy.maxTrailVerticesPerTrail - 1) {
                        break
                    }
                }
            }
            simplified += clipped.last().world
            if (simplified.size < 2) return null
            return trail.copy(pointsM = simplified)
        }

        private fun capTrailVertices(points: List<Vector3d>, maxVertices: Int): List<Vector3d> {
            if (points.size <= maxVertices) return points
            val step = (points.size - 1).toDouble() / (maxVertices - 1).toDouble()
            return buildList(maxVertices) {
                var cursor = 0.0
                repeat(maxVertices - 1) {
                    add(points[cursor.toInt()])
                    cursor += step
                }
                add(points.last())
            }
        }

        private fun packBodies(
            bodies: List<RenderBody>,
            selectedBodyId: String?,
        ): PackedBodies {
            val positions = DoubleArray(bodies.size * 3)
            val radii = FloatArray(bodies.size)
            val colors = IntArray(bodies.size)
            val kinds = IntArray(bodies.size)
            bodies.forEachIndexed { index, body ->
                val offset = index * 3
                positions[offset] = body.positionM.x
                positions[offset + 1] = body.positionM.y
                positions[offset + 2] = body.positionM.z
                val isSelected = body.id == selectedBodyId
                radii[index] = (body.radiusM * if (isSelected) selectedRadiusBoost(body.kind) else 1.0).toFloat()
                colors[index] = if (isSelected) brightenArgb(body.colorArgb) else body.colorArgb
                kinds[index] = body.kind.ordinal
            }
            return PackedBodies(positions, radii, colors, kinds)
        }

        private fun packTrails(
            trails: List<RenderTrail>,
            selectedBodyId: String?,
        ): PackedTrails {
            val trailVertexCount = trails.sumOf { it.pointsM.size }
            val trailPositions = DoubleArray(trailVertexCount * 3)
            val trailColors = IntArray(trailVertexCount)
            val trailVertexCounts = IntArray(trails.size)
            var trailOffset = 0
            trails.forEachIndexed { trailIndex, trail ->
                val emphasizedColor = if (trail.bodyId == selectedBodyId) {
                    brightenArgb(trail.colorArgb)
                } else {
                    trail.colorArgb
                }
                val alphaBoost = if (trail.bodyId == selectedBodyId) {
                    policy.selectedTrailAlphaBoost
                } else {
                    1.0
                }
                val trailColor = withAlphaMultiplier(
                    emphasizedColor,
                    trail.alpha.toDouble() * alphaBoost,
                )
                trailVertexCounts[trailIndex] = trail.pointsM.size
                trail.pointsM.forEach { point ->
                    trailPositions[trailOffset * 3] = point.x
                    trailPositions[trailOffset * 3 + 1] = point.y
                    trailPositions[trailOffset * 3 + 2] = point.z
                    trailColors[trailOffset] = trailColor
                    trailOffset += 1
                }
            }
            return PackedTrails(trailPositions, trailColors, trailVertexCounts)
        }

        private fun selectedRadiusBoost(kind: RenderBodyKind): Double = when (kind) {
            RenderBodyKind.STAR -> 1.12
            RenderBodyKind.PLANET,
            RenderBodyKind.DWARF_PLANET,
            -> 1.24
            RenderBodyKind.ASTEROID -> 1.38
            RenderBodyKind.COMET,
            RenderBodyKind.PROBE,
            RenderBodyKind.TEST_OBJECT,
            -> 1.48
        }

        private fun brightenArgb(argb: Int): Int {
            val alpha = (argb ushr 24) and 0xFF
            val red = (argb ushr 16) and 0xFF
            val green = (argb ushr 8) and 0xFF
            val blue = argb and 0xFF
            val boostedRed = red + ((255 - red) * 0.28).toInt()
            val boostedGreen = green + ((255 - green) * 0.28).toInt()
            val boostedBlue = blue + ((255 - blue) * 0.28).toInt()
            return (alpha shl 24) or
                (boostedRed.coerceIn(0, 255) shl 16) or
                (boostedGreen.coerceIn(0, 255) shl 8) or
                boostedBlue.coerceIn(0, 255)
        }

        private fun withAlphaMultiplier(argb: Int, alphaMultiplier: Double): Int {
            val baseAlpha = ((argb ushr 24) and 0xFF) / 255.0
            val effectiveAlpha = (baseAlpha * alphaMultiplier).coerceIn(0.0, 1.0)
            val alpha = (effectiveAlpha * 255.0).toInt().coerceIn(0, 255)
            return (argb and 0x00FF_FFFF) or (alpha shl 24)
        }
    }
}

private data class PackedBodies(
    val positionsM: DoubleArray,
    val radiiM: FloatArray,
    val colorsArgb: IntArray,
    val kinds: IntArray,
)

private data class PackedTrails(
    val positionsM: DoubleArray,
    val colorsArgb: IntArray,
    val vertexCounts: IntArray,
)

private data class TracerSelection(
    val near: List<RenderBody>,
    val medium: List<RenderBody>,
    val far: List<RenderBody>,
)

private data class ScoredBody(
    val body: RenderBody,
    val score: Double,
)

private data class ScreenPoint3(
    val world: Vector3d,
    val xPx: Double,
    val yPx: Double,
)

private data class SceneView(
    val centerM: Vector3d,
    val viewRadiusM: Double,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val policy: ScenePacketBuildPolicy = ScenePacketBuildPolicy(),
) {
    private val minDimensionPx: Double = min(viewportWidthPx, viewportHeightPx).coerceAtLeast(1).toDouble()
    private val halfSpanX: Double = viewRadiusM * (viewportWidthPx.toDouble() / minDimensionPx)
    private val halfSpanY: Double = viewRadiusM * (viewportHeightPx.toDouble() / minDimensionPx)
    val metersPerPixel: Double = (2.0 * viewRadiusM) / minDimensionPx

    fun classify(positionM: Vector3d): TracerLodTier? {
        val relative = positionM - centerM
        val normalized = max(
            abs(relative.x) / max(halfSpanX, 1.0),
            abs(relative.y) / max(halfSpanY, 1.0),
        )
        return when {
            normalized <= policy.nearTracerExtentFactor -> TracerLodTier.NEAR
            normalized <= policy.mediumTracerExtentFactor -> TracerLodTier.MEDIUM
            normalized <= policy.farTracerExtentFactor -> TracerLodTier.FAR
            else -> null
        }
    }

    fun toScreen(positionM: Vector3d): ScreenPoint {
        val relative = positionM - centerM
        val clipX = relative.x / max(halfSpanX, 1.0)
        val clipY = relative.y / max(halfSpanY, 1.0)
        return ScreenPoint(
            x = (clipX * 0.5 + 0.5) * viewportWidthPx,
            y = (1.0 - (clipY * 0.5 + 0.5)) * viewportHeightPx,
        )
    }
}

private data class ScreenPoint(
    val x: Double,
    val y: Double,
)
