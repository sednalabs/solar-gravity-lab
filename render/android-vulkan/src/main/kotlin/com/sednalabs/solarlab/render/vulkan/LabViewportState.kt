package com.sednalabs.solarlab.render.vulkan

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A declared, non-rotating authoring plane. Coordinates are metres in the simulation frame. */
data class LabPlane(val originM: Vector3d, val normal: Vector3d) {
    val isValid: Boolean
        get() = originM.isFinite() && normal.isFinite() &&
            normal.magnitudeSquared().let { it.isFinite() && it > 1e-20 }

    companion object {
        val XY = LabPlane(Vector3d.ZERO, Vector3d(0.0, 0.0, 1.0))
    }
}

/** Coordinates are physical pixels local to the render host, not the whole window. */
data class LabProjectedPoint(val xPx: Float, val yPx: Float, val depthM: Double)

data class LabBodyAnchor(
    val bodyId: String,
    val xPx: Float,
    val yPx: Float,
    val radiusPx: Float,
    val depthM: Double,
    /** True when another body's visible surface hides this body's centre. */
    val occluded: Boolean,
    val worldPositionM: Vector3d,
)

/** One successfully submitted native frame, including its displayed interpolation fraction. */
data class LabViewportState(
    val widthPx: Int,
    val heightPx: Int,
    val camera: CameraState,
    val metresPerPixel: Double,
    val bodies: List<LabBodyAnchor>,
) {
    // This basis matches SolarLabPresentation::MakeCameraBasis and the native Vulkan camera.
    // Do not sanitize the pitch through the legacy 3D orbit limit: map views may be overhead.
    private val right: Vector3d
        get() = Vector3d(cos(camera.yawRadians), sin(camera.yawRadians), 0.0)
    private val forward: Vector3d
        get() = Vector3d(
            -sin(camera.yawRadians) * cos(camera.pitchRadians),
            cos(camera.yawRadians) * cos(camera.pitchRadians),
            -sin(camera.pitchRadians),
        )
    private val up: Vector3d get() = right.cross(forward)

    fun project(world: Vector3d): LabProjectedPoint {
        val relative = world - camera.centerM
        return LabProjectedPoint(
            xPx = (widthPx * 0.5 + relative.dot(right) / metresPerPixel).toFloat(),
            yPx = (heightPx * 0.5 - relative.dot(up) / metresPerPixel).toFloat(),
            depthM = relative.dot(forward),
        )
    }

    /** Returns no placement for a parallel ray or invalid geometry; never invents a centre hit. */
    fun unprojectToPlane(xPx: Float, yPx: Float, plane: LabPlane): Vector3d? {
        if (!xPx.isFinite() || !yPx.isFinite() || !plane.isValid ||
            !metresPerPixel.isFinite() || metresPerPixel <= 0.0) return null
        val normal = plane.normal.normalized()
        val direction = forward
        val denominator = direction.dot(normal)
        if (!denominator.isFinite() || abs(denominator) < 1e-6) return null
        val onCameraPlane = camera.centerM +
            right * ((xPx - widthPx * 0.5) * metresPerPixel) +
            up * ((heightPx * 0.5 - yPx) * metresPerPixel)
        val distance = (plane.originM - onCameraPlane).dot(normal) / denominator
        return (onCameraPlane + direction * distance).takeIf { it.isFinite() }
    }
}

private fun Vector3d.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
