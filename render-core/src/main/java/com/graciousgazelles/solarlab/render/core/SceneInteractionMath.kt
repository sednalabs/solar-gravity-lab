package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.max
import kotlin.math.sqrt

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
            val distancePx = sqrt((dx * dx) + (dy * dy).toDouble())
            val allowedRadius = max(DEFAULT_MIN_PICK_RADIUS_PX, projected.visualRadiusPx + DEFAULT_SELECTION_PADDING_PX)
            if (distancePx > allowedRadius) continue

            val score = (distancePx / max(1f, projected.visualRadiusPx)) + projected.depth01 * 0.12
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
    ): Vector3d = screenToWorldOnPlane(
        screenXPx = screenXPx,
        screenYPx = screenYPx,
        cameraState = cameraState,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        planePointM = Vector3d(cameraState.centerM.x, cameraState.centerM.y, worldZ),
        planeNormalM = Vector3d(0.0, 0.0, 1.0),
    ) ?: Vector3d(
        x = cameraState.centerM.x,
        y = cameraState.centerM.y,
        z = worldZ,
    )

    fun screenToWorldOnPlane(
        screenXPx: Float,
        screenYPx: Float,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        planePointM: Vector3d,
        planeNormalM: Vector3d,
    ): Vector3d? {
        val ray = OrbitCameraMath.viewportRay(
            screenXPx = screenXPx,
            screenYPx = screenYPx,
            cameraState = cameraState,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        return OrbitCameraMath.intersectRayWithPlane(ray, planePointM, planeNormalM)
    }

    private fun projectBodyToScreen(
        body: RenderBody,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        bodyRadiusExaggeration: Double,
    ): ProjectedBody? {
        val frame = OrbitCameraMath.frame(cameraState, viewportWidthPx, viewportHeightPx)
        val projected = OrbitCameraMath.projectToViewport(
            positionM = body.positionM,
            frame = frame,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        ) ?: return null

        val baseRadiusPx = body.radiusM / frame.metersPerPixel
        val minimumPx = when {
            body.isMassive -> 3.0
            body.kind == RenderBodyKind.DWARF_PLANET -> 2.5
            else -> 1.5
        }
        val visualRadiusPx = max(minimumPx, baseRadiusPx * bodyRadiusExaggeration).coerceIn(1.5, 96.0).toFloat()

        return ProjectedBody(
            centerXPx = projected.xPx.toFloat(),
            centerYPx = projected.yPx.toFloat(),
            visualRadiusPx = visualRadiusPx,
            depth01 = projected.depth01,
        )
    }

    private data class ProjectedBody(
        val centerXPx: Float,
        val centerYPx: Float,
        val visualRadiusPx: Float,
        val depth01: Double,
    )
}
