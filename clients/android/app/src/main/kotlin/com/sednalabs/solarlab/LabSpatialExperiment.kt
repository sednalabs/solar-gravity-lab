package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.sednalabs.solarlab.render.vulkan.LabPlane
import com.sednalabs.solarlab.runtime.RuntimeBodyClass
import com.sednalabs.solarlab.runtime.RuntimeBodyState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A named, frozen authoring plane. None of these display vectors changes the live world. */
internal data class LabAuthoringPlane(
    val normal: Vector3d,
    val axisU: Vector3d,
    val axisV: Vector3d,
    val label: String,
) {
    fun through(body: RuntimeBodyState) = LabPlane(body.labPosition(), normal)
}

internal fun earthMoonAuthoringPlane(earth: RuntimeBodyState, moon: RuntimeBodyState?): LabAuthoringPlane {
    val radial = moon?.labPosition()?.minus(earth.labPosition()) ?: Vector3d(1.0, 0.0, 0.0)
    val velocity = moon?.labVelocity()?.minus(earth.labVelocity()) ?: Vector3d(0.0, 1.0, 0.0)
    val normal = labUnit(labCross(radial, velocity)) ?: Vector3d(0.0, 0.0, 1.0)
    val projected = radial - normal * labDot(radial, normal)
    val u = labUnit(projected) ?: Vector3d(1.0, 0.0, 0.0)
    val v = labUnit(labCross(normal, u)) ?: Vector3d(0.0, 1.0, 0.0)
    return LabAuthoringPlane(normal, u, v, if (moon != null) "Earth–Moon plane" else "Reference plane")
}

/** Immutable, uncommitted input. Position, direction and speed are all body-relative. */
internal data class LabProbeDraft(
    val reference: RuntimeBodyState,
    val plane: LabAuthoringPlane,
    val probeId: String,
    val epochSeconds: Double,
    val altitudeKmText: String = "800",
    val azimuthRadians: Double = -PI / 4.0,
    val motion: LabPlacementMotion = LabPlacementMotion.CIRCULAR,
    val direction: LabPlacementDirection = LabPlacementDirection.PROGRADE,
    val speedKmPerSecondText: String = "0",
    val headingDegreesText: String = "90",
    val altitudeLocked: Boolean = false,
) {
    val altitudeM: Double? get() = altitudeKmText.toDoubleOrNull()?.times(1_000.0)?.takeIf(Double::isFinite)
    val distanceM: Double? get() = altitudeM?.plus(reference.radiusM)
    val radialUnit: Vector3d get() = plane.axisU * cos(azimuthRadians) + plane.axisV * sin(azimuthRadians)
    val tangentUnit: Vector3d get() = plane.axisU * -sin(azimuthRadians) + plane.axisV * cos(azimuthRadians)
    val positionM: Vector3d? get() = distanceM?.let { reference.labPosition() + radialUnit * it }
    val circularSpeedMps: Double? get() = distanceM?.takeIf { it > 0.0 }?.let {
        sqrt(6.67430e-11 * reference.sourceMassKg / it)
    }?.takeIf(Double::isFinite)

    val validationError: String? get() = when {
        altitudeM == null -> "Enter an altitude in kilometres."
        altitudeM!! < 1_000.0 -> "Place the probe at least 1 km above the surface."
        altitudeM!! > 5.0e12 -> "Choose an altitude below 5 billion kilometres."
        motion == LabPlacementMotion.CUSTOM && speedKmPerSecondText.toDoubleOrNull()?.let { it.isFinite() && it >= 0.0 && it <= 100_000.0 } != true ->
            "Enter a speed between 0 and 100,000 km/s."
        motion == LabPlacementMotion.CUSTOM && headingDegreesText.toDoubleOrNull()?.isFinite() != true ->
            "Enter a direction in degrees."
        reference.sourceMassKg <= 0.0 && motion == LabPlacementMotion.CIRCULAR -> "This body cannot provide a circular starting orbit."
        else -> null
    }

    fun relativeVelocityMps(): Vector3d? = when (motion) {
        LabPlacementMotion.CIRCULAR -> circularSpeedMps?.let {
            tangentUnit * (it * if (direction == LabPlacementDirection.PROGRADE) 1.0 else -1.0)
        }
        LabPlacementMotion.RELEASE -> Vector3d(0.0, 0.0, 0.0)
        LabPlacementMotion.CUSTOM -> {
            val speed = speedKmPerSecondText.toDoubleOrNull()?.times(1_000.0)
            val heading = headingDegreesText.toDoubleOrNull()?.let(Math::toRadians)
            if (speed == null || heading == null || !speed.isFinite() || !heading.isFinite()) null
            else (plane.axisU * cos(heading) + plane.axisV * sin(heading)) * speed
        }
    }

    fun candidate(): RuntimeBodyState? {
        if (validationError != null) return null
        val position = positionM ?: return null
        val velocity = reference.labVelocity() + (relativeVelocityMps() ?: return null)
        return RuntimeBodyState(
            bodyId = probeId, bodyClass = RuntimeBodyClass.Spacecraft,
            massKg = 1_000.0, sourceMassKg = 0.0, radiusM = 2.0,
            positionX = position.x, positionY = position.y, positionZ = position.z,
            velocityX = velocity.x, velocityY = velocity.y, velocityZ = velocity.z,
        )
    }

    fun atWorldPoint(point: Vector3d): LabProbeDraft {
        val offset = point - reference.labPosition()
        val x = labDot(offset, plane.axisU)
        val y = labDot(offset, plane.axisV)
        val distance = sqrt(x * x + y * y)
        if (!distance.isFinite() || distance < 1.0) return this
        return copy(
            azimuthRadians = atan2(y, x),
            altitudeKmText = if (altitudeLocked) altitudeKmText
                else labDecimal((distance - reference.radiusM) / 1_000.0),
        )
    }

    fun withMotion(next: LabPlacementMotion): LabProbeDraft {
        if (next != LabPlacementMotion.CUSTOM) return copy(motion = next)
        val velocity = relativeVelocityMps() ?: Vector3d(0.0, 0.0, 0.0)
        val speed = labLength(velocity)
        val angle = if (speed > 0.0) atan2(labDot(velocity, plane.axisV), labDot(velocity, plane.axisU))
            else azimuthRadians + PI / 2.0
        return copy(motion = next, speedKmPerSecondText = labDecimal(speed / 1_000.0),
            headingDegreesText = labDecimal((Math.toDegrees(angle) + 360.0) % 360.0))
    }

    /** Dragging the velocity handle changes only motion, never the position of the draft. */
    fun withVelocityPoint(point: Vector3d, metresPerSecondPerWorldMetre: Double): LabProbeDraft {
        val position = positionM ?: return this
        val arrow = point - position
        val x = labDot(arrow, plane.axisU)
        val y = labDot(arrow, plane.axisV)
        val speed = sqrt(x * x + y * y) * metresPerSecondPerWorldMetre
        if (!speed.isFinite()) return this
        return copy(motion = LabPlacementMotion.CUSTOM,
            speedKmPerSecondText = labDecimal((speed / 1_000.0).coerceIn(0.0, 100_000.0)),
            headingDegreesText = labDecimal((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0))
    }

    fun previewHorizonSeconds(): Double {
        val distance = distanceM ?: return 3_600.0
        val speed = circularSpeedMps ?: return 3_600.0
        return if (speed > 0.0) (2.0 * PI * distance / speed * 1.25).coerceIn(300.0, 43_200.0) else 3_600.0
    }
}

internal fun RuntimeBodyState.labPosition() = Vector3d(positionX, positionY, positionZ)
internal fun RuntimeBodyState.labVelocity() = Vector3d(velocityX, velocityY, velocityZ)
internal fun labDot(a: Vector3d, b: Vector3d): Double = a.x * b.x + a.y * b.y + a.z * b.z
internal fun labCross(a: Vector3d, b: Vector3d) = Vector3d(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
internal fun labLength(value: Vector3d): Double = sqrt(labDot(value, value))
internal fun labUnit(value: Vector3d): Vector3d? = labLength(value).takeIf { it.isFinite() && it > 1.0e-12 }?.let { value * (1.0 / it) }
internal fun labDecimal(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
