package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.abs
import kotlin.math.max

object SceneInteractionMath {
    const val DEFAULT_BODY_RADIUS_EXAGGERATION: Double = 6_000.0
    private const val DEFAULT_MIN_PICK_RADIUS_PX: Float = 18f
    private const val DEFAULT_SELECTION_PADDING_PX: Float = 8f

    fun pickBodyIdAtScreenPoint(
        frame: RenderSceneFrame,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        screenXPx: Float,
        screenYPx: Float,
        bodyRadiusExaggeration: Double = DEFAULT_BODY_RADIUS_EXAGGERATION,
    ): String? {
        val candidates = buildList {
            addAll(frame.authoritativeBodies)
            addAll(frame.tracerBodies)
        }
        if (candidates.isEmpty()) return null

        var bestId: String? = null
        var bestScore = Double.POSITIVE_INFINITY

        for (body in candidates) {
            val projected = projectBodyToScreen(
                body = body,
                cameraState = cameraState,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                bodyRadiusExaggeration = bodyRadiusExaggeration,
            ) ?: continue

            val dx = screenXPx - projected.centerXPx
            val dy = screenYPx - projected.centerYPx
            val distancePx = kotlin.math.sqrt((dx * dx) + (dy * dy).toDouble())
            val allowedRadius = max(DEFAULT_MIN_PICK_RADIUS_PX, projected.visualRadiusPx + DEFAULT_SELECTION_PADDING_PX)
            if (distancePx > allowedRadius) continue

            val score = distancePx / max(1f, projected.visualRadiusPx)
            if (score < bestScore) {
                bestScore = score
                bestId = body.id
            }
        }

        return bestId
    }

    fun screenToWorldPoint(
        screenXPx: Float,
        screenYPx: Float,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        worldZ: Double,
    ): Vector3d {
        val width = viewportWidthPx.coerceAtLeast(1)
        val height = viewportHeightPx.coerceAtLeast(1)
        val minDimension = minOf(width, height).coerceAtLeast(1).toDouble()
        val halfSpanX = cameraState.viewRadiusM * (width.toDouble() / minDimension)
        val halfSpanY = cameraState.viewRadiusM * (height.toDouble() / minDimension)

        val normalizedX = ((screenXPx / width.toFloat()) * 2.0) - 1.0
        val normalizedY = 1.0 - ((screenYPx / height.toFloat()) * 2.0)

        return Vector3d(
            x = cameraState.centerM.x + (normalizedX * halfSpanX),
            y = cameraState.centerM.y + (normalizedY * halfSpanY),
            z = worldZ,
        )
    }

    private fun projectBodyToScreen(
        body: RenderBody,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        bodyRadiusExaggeration: Double,
    ): ProjectedBody? {
        val width = viewportWidthPx.coerceAtLeast(1)
        val height = viewportHeightPx.coerceAtLeast(1)
        val minDimension = minOf(width, height).coerceAtLeast(1).toDouble()
        val halfSpanX = cameraState.viewRadiusM * (width.toDouble() / minDimension)
        val halfSpanY = cameraState.viewRadiusM * (height.toDouble() / minDimension)
        if (halfSpanX == 0.0 || halfSpanY == 0.0) return null

        val relative = body.positionM - cameraState.centerM
        val clipX = relative.x / halfSpanX
        val clipY = relative.y / halfSpanY
        if (abs(clipX) > 1.25 || abs(clipY) > 1.25) return null

        val centerXPx = (((clipX + 1.0) * 0.5) * width).toFloat()
        val centerYPx = (((1.0 - (clipY + 1.0) * 0.5)) * height).toFloat()
        val metersPerPixel = (2.0 * cameraState.viewRadiusM) / minDimension
        val baseRadiusPx = body.radiusM / metersPerPixel
        val minimumPx = when {
            body.isMassive -> 3.0
            body.kind == RenderBodyKind.DWARF_PLANET -> 2.5
            else -> 1.5
        }
        val visualRadiusPx = max(minimumPx, baseRadiusPx * bodyRadiusExaggeration).coerceIn(1.5, 96.0).toFloat()

        return ProjectedBody(
            centerXPx = centerXPx,
            centerYPx = centerYPx,
            visualRadiusPx = visualRadiusPx,
        )
    }

    private data class ProjectedBody(
        val centerXPx: Float,
        val centerYPx: Float,
        val visualRadiusPx: Float,
    )
}
