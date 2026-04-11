package com.graciousgazelles.solarlab.render.core

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import kotlin.math.PI

/**
 * Orthographic orbit-camera state shared by the immersive client and the packet builder.
 *
 * `yawRadians` rotates the camera around the global Z axis.
 * `pitchRadians` is measured above the XY plane: 90° is overhead, lower values tilt the camera
 * toward the horizon while still keeping the solar-system staging feel.
 */
data class CameraState(
    val centerM: Vector3d = Vector3d.ZERO,
    val viewRadiusM: Double = 24.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M,
    val yawRadians: Double = DEFAULT_YAW_RADIANS,
    val pitchRadians: Double = DEFAULT_PITCH_RADIANS,
) {
    fun sanitized(): CameraState = copy(
        viewRadiusM = viewRadiusM.coerceAtLeast(MIN_VIEW_RADIUS_M),
        yawRadians = normalizeAngleRadians(yawRadians),
        pitchRadians = pitchRadians.coerceIn(MIN_PITCH_RADIANS, MAX_PITCH_RADIANS),
    )

    fun scaleBand(): CameraScaleBand = CameraScaleBand.fromViewRadiusM(viewRadiusM)

    companion object {
        private const val DEFAULT_YAW_DEGREES: Double = -34.0
        private const val DEFAULT_PITCH_DEGREES: Double = 63.0
        private const val MIN_PITCH_DEGREES: Double = 12.0
        private const val MAX_PITCH_DEGREES: Double = 88.0

        const val DEFAULT_DEPTH_EXTENT_FACTOR: Double = 48.0
        const val DEFAULT_CAMERA_DISTANCE_FACTOR: Double = 0.92
        private const val MIN_VIEW_RADIUS_M: Double = 1_000.0

        val DEFAULT_YAW_RADIANS: Double = Math.toRadians(DEFAULT_YAW_DEGREES)
        val DEFAULT_PITCH_RADIANS: Double = Math.toRadians(DEFAULT_PITCH_DEGREES)
        val MIN_PITCH_RADIANS: Double = Math.toRadians(MIN_PITCH_DEGREES)
        val MAX_PITCH_RADIANS: Double = Math.toRadians(MAX_PITCH_DEGREES)
    }
}

enum class CameraScaleBand(
    val label: String,
    val nominalViewRadiusM: Double,
) {
    CLOSE(label = "Close", nominalViewRadiusM = 0.010 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
    LOCAL(label = "Local", nominalViewRadiusM = 0.18 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
    SYSTEM(label = "System", nominalViewRadiusM = 3.2 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
    WIDE(label = "Wide", nominalViewRadiusM = 32.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
    DEEP(label = "Deep", nominalViewRadiusM = 320.0 * PhysicalConstants.ASTRONOMICAL_UNIT_M),
    ;

    fun next(): CameraScaleBand = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromViewRadiusM(viewRadiusM: Double): CameraScaleBand {
            val safeRadius = viewRadiusM.coerceAtLeast(1.0)
            val bands = entries
            for (index in 0 until bands.lastIndex) {
                val lower = bands[index]
                val upper = bands[index + 1]
                val threshold = kotlin.math.sqrt(lower.nominalViewRadiusM * upper.nominalViewRadiusM)
                if (safeRadius < threshold) {
                    return lower
                }
            }
            return bands.last()
        }
    }
}

internal fun normalizeAngleRadians(value: Double): Double {
    var normalized = value % (2.0 * PI)
    if (normalized > PI) {
        normalized -= 2.0 * PI
    } else if (normalized < -PI) {
        normalized += 2.0 * PI
    }
    return normalized
}
