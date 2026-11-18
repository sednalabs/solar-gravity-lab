package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightState
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/** Read-only geometry from native telemetry. This never propagates or controls a trajectory. */
internal data class FlightDirector(
    val target: FlightAssist,
    val errorDegrees: Double,
    val markerX: Double,
    val markerY: Double,
    val targetBehind: Boolean,
    val spinDegreesPerSecond: Double,
) {
    val aligned: Boolean get() = errorDegrees <= 3.0
}

private data class FlightVector(val x: Double, val y: Double, val z: Double) {
    operator fun unaryMinus() = FlightVector(-x, -y, -z)
    fun cross(other: FlightVector) = FlightVector(y * other.z - z * other.y,
        z * other.x - x * other.z, x * other.y - y * other.x)
    fun unit(): FlightVector? {
        val length = hypot(hypot(x, y), z)
        return if (length.isFinite() && length > 1e-12) FlightVector(x / length, y / length, z / length) else null
    }
}

/** +Z is thrust, +X ship right, +Y ship up; the native quaternion is body to inertial. */
internal fun flightDirector(state: FlightState, selected: FlightAssist): FlightDirector? {
    val target = when (selected) {
        FlightAssist.Manual, FlightAssist.Hold -> FlightAssist.Prograde
        else -> selected
    }
    val position = FlightVector(state.relativeX, state.relativeY, state.relativeZ).unit()
    val velocity = FlightVector(state.relativeVelocityX, state.relativeVelocityY, state.relativeVelocityZ).unit()
    val inertial = when (target) {
        FlightAssist.Prograde -> velocity
        FlightAssist.Retrograde -> velocity?.let { -it }
        FlightAssist.Normal -> position?.let { p -> velocity?.let { p.cross(it).unit() } }
        FlightAssist.AntiNormal -> position?.let { p -> velocity?.let { p.cross(it).unit()?.let { n -> -n } } }
        FlightAssist.RadialOut -> position
        FlightAssist.RadialIn -> position?.let { -it }
        else -> null
    } ?: return null
    val norm = sqrt(state.attitudeX * state.attitudeX + state.attitudeY * state.attitudeY +
        state.attitudeZ * state.attitudeZ + state.attitudeW * state.attitudeW)
    if (!norm.isFinite() || norm < 1e-12) return null
    val q = FlightVector(-state.attitudeX / norm, -state.attitudeY / norm, -state.attitudeZ / norm)
    val w = state.attitudeW / norm
    val t = q.cross(inertial)
    val u = q.cross(t)
    val body = FlightVector(inertial.x + 2.0 * (w * t.x + u.x),
        inertial.y + 2.0 * (w * t.y + u.y), inertial.z + 2.0 * (w * t.z + u.z)).unit() ?: return null
    val angle = acos(body.z.coerceIn(-1.0, 1.0))
    val transverse = hypot(body.x, body.y)
    val radius = (angle / (PI / 2.0)).coerceAtMost(1.0)
    // Exactly aft has no unique turn direction. A hollow rim marker must never
    // resemble a centred, aligned target.
    val markerX = if (transverse > 1e-10) body.x / transverse * radius else 0.0
    val markerY = if (transverse > 1e-10) body.y / transverse * radius else if (body.z < 0.0) 1.0 else 0.0
    return FlightDirector(target, angle * 180.0 / PI, markerX, markerY, body.z < 0.0,
        hypot(hypot(state.angularVelocityX, state.angularVelocityY), state.angularVelocityZ) * 180.0 / PI)
}

internal fun flightManoeuvreDescription(assist: FlightAssist): String = when (assist) {
    FlightAssist.Manual -> "You control the attitude. Release stops the input; existing rotation can continue. Stabilise damps that rotation."
    FlightAssist.Hold -> "Damps the ship’s rotation. It does not keep the nose pointing along your changing orbital direction."
    FlightAssist.Prograde -> "Points along your velocity relative to the host. A short burn near periapsis mainly raises apoapsis, the far side of a bound orbit."
    FlightAssist.Retrograde -> "Points against your velocity relative to the host. A short burn near apoapsis mainly lowers periapsis. Watch the lowest altitude."
    FlightAssist.Normal -> "Points along position × velocity, perpendicular to the orbital plane. Burning tilts the plane; use a node for a planned plane change."
    FlightAssist.AntiNormal -> "Points opposite the orbit normal. Burning tilts the plane in the other direction. Inclination may rise or fall depending on where you burn."
    FlightAssist.RadialOut -> "Points directly away from the host centre. Burning changes the orbit’s shape and orientation; it is not a shortcut to a higher circular orbit."
    FlightAssist.RadialIn -> "Points directly towards the host centre. Burning changes the orbit’s shape and orientation. Check periapsis before committing."
}

internal fun flightOrbitCaution(state: FlightState): String? = when {
    state.altitudeM <= 0.0 -> "Below the host surface. Restore a checkpoint to recover the flight."
    !state.hasValidOrbit -> "An orbital estimate is unavailable at this state."
    (state.periapsisAltitudeM ?: Double.POSITIVE_INFINITY) < 0.0 -> "Surface-crossing orbit: periapsis is below the host surface."
    !state.hasBoundOrbit -> "Unbound trajectory: there is no finite apoapsis."
    else -> null
}
