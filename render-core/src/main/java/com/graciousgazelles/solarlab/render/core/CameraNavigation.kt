package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import kotlin.math.exp
import kotlin.math.ln

object CameraNavigation {
    fun scenarioFit(
        frame: RenderSceneFrame,
        currentCameraState: CameraState,
        minViewRadiusM: Double,
        maxViewRadiusM: Double,
    ): CameraState {
        val bodies = frame.authoritativeBodies.ifEmpty { frame.tracerBodies }
        if (bodies.isEmpty()) return CameraState()

        val minX = bodies.minOf { it.positionM.x }
        val minY = bodies.minOf { it.positionM.y }
        val minZ = bodies.minOf { it.positionM.z }
        val maxX = bodies.maxOf { it.positionM.x }
        val maxY = bodies.maxOf { it.positionM.y }
        val maxZ = bodies.maxOf { it.positionM.z }
        val center = Vector3d(
            x = (minX + maxX) * 0.5,
            y = (minY + maxY) * 0.5,
            z = (minZ + maxZ) * 0.5,
        )
        val contentRadius = bodies.maxOf { body ->
            center.distanceTo(body.positionM) + body.radiusM.coerceAtLeast(0.0)
        }
        val viewRadius = (contentRadius * SCENARIO_FIT_PADDING)
            .coerceAtLeast(CameraScaleBand.CLOSE.nominalViewRadiusM)
            .coerceIn(minViewRadiusM, maxViewRadiusM)
        return currentCameraState.copy(
            centerM = center,
            viewRadiusM = viewRadius,
            yawRadians = CameraState.DEFAULT_YAW_RADIANS,
            pitchRadians = MultiscaleOrbitCameraController.preferredPitchRadiansFor(viewRadius),
        ).sanitized()
    }

    fun frameBody(
        frame: RenderSceneFrame,
        bodyId: String,
        currentCameraState: CameraState,
        minViewRadiusM: Double,
        maxViewRadiusM: Double,
    ): CameraState? {
        val target = ObserverCameraResolver.resolveCameraTarget(
            frame = frame,
            selectedBodyId = bodyId,
            observerMode = ObserverMode.FOLLOW_SELECTED,
        ) ?: return null
        return MultiscaleOrbitCameraController.retarget(
            currentCameraState = currentCameraState,
            targetCenterM = target.centerM,
            suggestedViewRadiusM = target.suggestedViewRadiusM,
            minViewRadiusM = minViewRadiusM,
            maxViewRadiusM = maxViewRadiusM,
            snapToSuggestedRadius = true,
        )
    }

    fun scalePreset(
        currentCameraState: CameraState,
        scaleBand: CameraScaleBand,
        minViewRadiusM: Double,
        maxViewRadiusM: Double,
    ): CameraState = currentCameraState.copy(
        viewRadiusM = scaleBand.nominalViewRadiusM.coerceIn(minViewRadiusM, maxViewRadiusM),
        pitchRadians = MultiscaleOrbitCameraController.policyFor(scaleBand).preferredPitchRadians,
    ).sanitized()

    fun interpolate(
        start: CameraState,
        target: CameraState,
        fraction: Float,
    ): CameraState {
        val progress = fraction.coerceIn(0f, 1f).toDouble()
        val yawDelta = normalizeAngleRadians(target.yawRadians - start.yawRadians)
        val startRadius = start.viewRadiusM.coerceAtLeast(1.0)
        val targetRadius = target.viewRadiusM.coerceAtLeast(1.0)
        return CameraState(
            centerM = start.centerM + (target.centerM - start.centerM) * progress,
            viewRadiusM = exp(lerp(ln(startRadius), ln(targetRadius), progress)),
            yawRadians = start.yawRadians + (yawDelta * progress),
            pitchRadians = lerp(start.pitchRadians, target.pitchRadians, progress),
        ).sanitized()
    }

    private fun lerp(start: Double, target: Double, fraction: Double): Double =
        start + ((target - start) * fraction)

    private const val SCENARIO_FIT_PADDING = 1.18
}
