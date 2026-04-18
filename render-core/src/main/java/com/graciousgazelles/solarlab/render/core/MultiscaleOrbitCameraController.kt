package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.pow

data class OrbitCameraControlPolicy(
    val preferredPitchRadians: Double,
    val yawRadiansPerPixel: Double,
    val pitchRadiansPerPixel: Double,
    val zoomExponent: Double,
)

object MultiscaleOrbitCameraController {
    fun policyFor(cameraState: CameraState): OrbitCameraControlPolicy = policyFor(cameraState.scaleBand())

    fun policyFor(scaleBand: CameraScaleBand): OrbitCameraControlPolicy = when (scaleBand) {
        CameraScaleBand.CLOSE -> OrbitCameraControlPolicy(
            preferredPitchRadians = Math.toRadians(44.0),
            yawRadiansPerPixel = 0.0030,
            pitchRadiansPerPixel = 0.0022,
            zoomExponent = 0.58,
        )

        CameraScaleBand.LOCAL -> OrbitCameraControlPolicy(
            preferredPitchRadians = Math.toRadians(50.0),
            yawRadiansPerPixel = 0.0038,
            pitchRadiansPerPixel = 0.0028,
            zoomExponent = 0.68,
        )

        CameraScaleBand.SYSTEM -> OrbitCameraControlPolicy(
            preferredPitchRadians = Math.toRadians(63.0),
            yawRadiansPerPixel = 0.0050,
            pitchRadiansPerPixel = 0.0037,
            zoomExponent = 0.82,
        )

        CameraScaleBand.WIDE -> OrbitCameraControlPolicy(
            preferredPitchRadians = Math.toRadians(72.0),
            yawRadiansPerPixel = 0.0062,
            pitchRadiansPerPixel = 0.0045,
            zoomExponent = 0.92,
        )

        CameraScaleBand.DEEP -> OrbitCameraControlPolicy(
            preferredPitchRadians = Math.toRadians(80.0),
            yawRadiansPerPixel = 0.0074,
            pitchRadiansPerPixel = 0.0052,
            zoomExponent = 1.0,
        )
    }

    fun preferredPitchRadiansFor(viewRadiusM: Double): Double =
        policyFor(CameraScaleBand.fromViewRadiusM(viewRadiusM)).preferredPitchRadians

    fun panByScreenDelta(
        cameraState: CameraState,
        distanceXPx: Float,
        distanceYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): CameraState {
        val frame = OrbitCameraMath.frame(cameraState, viewportWidthPx, viewportHeightPx)
        return cameraState.copy(
            centerM = cameraState.centerM +
                frame.rightM * (distanceXPx * frame.metersPerPixel) -
                frame.upM * (distanceYPx * frame.metersPerPixel),
        ).sanitized()
    }

    fun zoomAroundViewportPoint(
        cameraState: CameraState,
        scaleFactor: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        minViewRadiusM: Double,
        maxViewRadiusM: Double,
    ): CameraState {
        if (scaleFactor <= 0f) return cameraState.sanitized()
        val safeCamera = cameraState.sanitized()
        val policy = policyFor(safeCamera)
        val anchoredPoint = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = safeCamera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        val effectiveScaleFactor = scaleFactor
            .toDouble()
            .coerceIn(MIN_GESTURE_SCALE_FACTOR, MAX_GESTURE_SCALE_FACTOR)
            .pow(policy.zoomExponent)
        val nextRadius = (safeCamera.viewRadiusM / effectiveScaleFactor)
            .coerceIn(minViewRadiusM, maxViewRadiusM)
        val resizedCamera = safeCamera.copy(viewRadiusM = nextRadius).sanitized()
        val anchoredCenter = OrbitCameraMath.centerForAnchorAtViewportPoint(
            anchoredWorldPointM = anchoredPoint,
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = resizedCamera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        return resizedCamera.copy(centerM = anchoredCenter).sanitized()
    }

    fun orbitAroundViewportPoint(
        cameraState: CameraState,
        deltaXPx: Float,
        deltaYPx: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): CameraState {
        val safeCamera = cameraState.sanitized()
        val policy = policyFor(safeCamera)
        val anchoredPoint = OrbitCameraMath.focusPlanePoint(
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = safeCamera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        val rotatedCamera = safeCamera.copy(
            yawRadians = safeCamera.yawRadians - (deltaXPx * policy.yawRadiansPerPixel),
            pitchRadians = safeCamera.pitchRadians - (deltaYPx * policy.pitchRadiansPerPixel),
        ).sanitized()
        val anchoredCenter = OrbitCameraMath.centerForAnchorAtViewportPoint(
            anchoredWorldPointM = anchoredPoint,
            screenXPx = focusXPx,
            screenYPx = focusYPx,
            cameraState = rotatedCamera,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        return rotatedCamera.copy(centerM = anchoredCenter).sanitized()
    }

    fun retarget(
        currentCameraState: CameraState,
        targetCenterM: Vector3d,
        suggestedViewRadiusM: Double,
        minViewRadiusM: Double,
        maxViewRadiusM: Double,
        snapToSuggestedRadius: Boolean,
    ): CameraState {
        val nextRadius = if (snapToSuggestedRadius) {
            suggestedViewRadiusM.coerceIn(minViewRadiusM, maxViewRadiusM)
        } else {
            currentCameraState.viewRadiusM
        }
        val nextPitch = if (snapToSuggestedRadius) {
            preferredPitchRadiansFor(nextRadius)
        } else {
            currentCameraState.pitchRadians
        }
        return currentCameraState.copy(
            centerM = targetCenterM,
            viewRadiusM = nextRadius,
            pitchRadians = nextPitch,
        ).sanitized()
    }

    private const val MIN_GESTURE_SCALE_FACTOR: Double = 0.25
    private const val MAX_GESTURE_SCALE_FACTOR: Double = 4.0
}
