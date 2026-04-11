package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

data class OrbitCameraFrame(
    val centerM: Vector3d,
    val cameraPositionM: Vector3d,
    val rightM: Vector3d,
    val upM: Vector3d,
    val forwardM: Vector3d,
    val halfSpanXM: Double,
    val halfSpanYM: Double,
    val halfDepthM: Double,
    val metersPerPixel: Double,
)

data class CameraRay(
    val originM: Vector3d,
    val directionM: Vector3d,
)

data class ProjectedWorldPoint(
    val xPx: Double,
    val yPx: Double,
    val depth01: Double,
    val viewDepthM: Double,
    val clipX: Double,
    val clipY: Double,
)

object OrbitCameraMath {
    fun frame(
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): OrbitCameraFrame {
        val safeCamera = cameraState.sanitized()
        val width = viewportWidthPx.coerceAtLeast(1)
        val height = viewportHeightPx.coerceAtLeast(1)
        val minDimension = minOf(width, height).coerceAtLeast(1).toDouble()
        val halfSpanY = safeCamera.viewRadiusM
        val halfSpanX = safeCamera.viewRadiusM * (width.toDouble() / minDimension)
        val metersPerPixel = (2.0 * safeCamera.viewRadiusM) / minDimension
        val halfDepth = max(safeCamera.viewRadiusM * CameraState.DEFAULT_DEPTH_EXTENT_FACTOR, 1_000_000.0)

        val right = Vector3d(
            x = cos(safeCamera.yawRadians),
            y = sin(safeCamera.yawRadians),
            z = 0.0,
        ).normalized()
        val screenUpHorizontal = Vector3d(
            x = -sin(safeCamera.yawRadians),
            y = cos(safeCamera.yawRadians),
            z = 0.0,
        ).normalized()
        val forward = (
            screenUpHorizontal * cos(safeCamera.pitchRadians) +
                Vector3d(0.0, 0.0, -sin(safeCamera.pitchRadians))
            ).normalized()
        val up = right.cross(forward).normalized()
        val cameraPosition = safeCamera.centerM - forward * (halfDepth * CameraState.DEFAULT_CAMERA_DISTANCE_FACTOR)

        return OrbitCameraFrame(
            centerM = safeCamera.centerM,
            cameraPositionM = cameraPosition,
            rightM = right,
            upM = up,
            forwardM = forward,
            halfSpanXM = halfSpanX,
            halfSpanYM = halfSpanY,
            halfDepthM = halfDepth,
            metersPerPixel = metersPerPixel,
        )
    }

    fun projectToViewport(
        positionM: Vector3d,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): ProjectedWorldPoint? {
        val frame = frame(cameraState, viewportWidthPx, viewportHeightPx)
        return projectToViewport(positionM, frame, viewportWidthPx, viewportHeightPx)
    }

    fun projectToViewport(
        positionM: Vector3d,
        frame: OrbitCameraFrame,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): ProjectedWorldPoint? {
        val width = viewportWidthPx.coerceAtLeast(1)
        val height = viewportHeightPx.coerceAtLeast(1)
        val delta = positionM - frame.centerM
        val clipX = delta.dot(frame.rightM) / max(frame.halfSpanXM, 1.0)
        val clipY = delta.dot(frame.upM) / max(frame.halfSpanYM, 1.0)
        val depth = delta.dot(frame.forwardM)
        val depthClip = depth / max(frame.halfDepthM, 1.0)
        if (abs(clipX) > 1.25 || abs(clipY) > 1.25 || abs(depthClip) > 1.25) {
            return null
        }
        val screenX = ((clipX * 0.5) + 0.5) * width
        val screenY = (1.0 - ((clipY * 0.5) + 0.5)) * height
        val depth01 = ((depthClip * 0.5) + 0.5).coerceIn(0.0, 1.0)
        return ProjectedWorldPoint(
            xPx = screenX,
            yPx = screenY,
            depth01 = depth01,
            viewDepthM = depth,
            clipX = clipX,
            clipY = clipY,
        )
    }

    fun viewportRay(
        screenXPx: Float,
        screenYPx: Float,
        cameraState: CameraState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): CameraRay {
        val frame = frame(cameraState, viewportWidthPx, viewportHeightPx)
        val width = viewportWidthPx.coerceAtLeast(1)
        val height = viewportHeightPx.coerceAtLeast(1)
        val normalizedX = ((screenXPx / width.toFloat()) * 2.0) - 1.0
        val normalizedY = 1.0 - ((screenYPx / height.toFloat()) * 2.0)
        val focusPlanePoint = frame.centerM +
            frame.rightM * (normalizedX * frame.halfSpanXM) +
            frame.upM * (normalizedY * frame.halfSpanYM)
        return CameraRay(
            originM = focusPlanePoint - frame.forwardM * frame.halfDepthM,
            directionM = frame.forwardM,
        )
    }

    fun intersectRayWithPlane(
        ray: CameraRay,
        planePointM: Vector3d,
        planeNormalM: Vector3d,
    ): Vector3d? {
        val normal = planeNormalM.normalized()
        val denominator = ray.directionM.dot(normal)
        if (abs(denominator) <= 1e-9) {
            return null
        }
        val distance = (planePointM - ray.originM).dot(normal) / denominator
        if (distance < 0.0) {
            return null
        }
        return ray.originM + ray.directionM * distance
    }

    fun quantizedSceneOriginM(cameraState: CameraState): Vector3d {
        val safeCamera = cameraState.sanitized()
        val quantizationCellM = max(safeCamera.viewRadiusM * 6.0, 10_000_000.0)
        fun snap(value: Double): Double = round(value / quantizationCellM) * quantizationCellM
        return Vector3d(
            x = snap(safeCamera.centerM.x),
            y = snap(safeCamera.centerM.y),
            z = snap(safeCamera.centerM.z),
        )
    }
}
