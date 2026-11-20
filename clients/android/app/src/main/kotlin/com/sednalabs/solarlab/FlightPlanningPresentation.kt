package com.sednalabs.solarlab

import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.OrbitGuidance
import com.sednalabs.solarlab.runtime.OrbitRegime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Display geometry only: native apsis radii define a schematic ellipse, never a propagated path. */
internal data class FlightOrbitEllipse(val centreX: Double, val major: Double, val minor: Double)
internal data class FlightOrbitShape(
    val hostRadius: Double,
    val before: FlightOrbitEllipse?,
    val after: FlightOrbitEllipse?,
    val left: Double,
    val right: Double,
    val halfHeight: Double,
)

internal fun flightOrbitShape(hostRadiusM: Double, beforePeriapsisM: Double?, beforeApoapsisM: Double?,
    afterPeriapsisM: Double?, afterApoapsisM: Double?): FlightOrbitShape? {
    if (!hostRadiusM.isFinite() || hostRadiusM < 0.0) return null
    fun radii(periapsis: Double?, apoapsis: Double?): Pair<Double, Double>? {
        if (periapsis == null || apoapsis == null) return null
        val peri = hostRadiusM + periapsis
        val apo = hostRadiusM + apoapsis
        return if (peri.isFinite() && apo.isFinite() && peri > 0.0 && apo >= peri) peri to apo else null
    }
    val before = radii(beforePeriapsisM, beforeApoapsisM)
    val after = radii(afterPeriapsisM, afterApoapsisM)
    if (before == null && after == null) return null
    val unit = max(hostRadiusM, max(before?.second ?: 0.0, after?.second ?: 0.0))
    fun ellipse(pair: Pair<Double, Double>?): FlightOrbitEllipse? = pair?.let {
        // Normalise before arithmetic: large radii cannot overflow a*b or (a+b).
        val peri = it.first / unit
        val apo = it.second / unit
        FlightOrbitEllipse((peri - apo) * .5, (peri + apo) * .5, sqrt(peri * apo))
    }
    val a = ellipse(before)
    val b = ellipse(after)
    val host = hostRadiusM / unit
    return FlightOrbitShape(host, a, b,
        max(host, max((before?.second ?: 0.0) / unit, (after?.second ?: 0.0) / unit)),
        max(host, max((before?.first ?: 0.0) / unit, (after?.first ?: 0.0) / unit)),
        max(host, max(a?.minor ?: 0.0, b?.minor ?: 0.0)))
}

internal fun flightApsisChange(label: String, before: Double?, after: Double?): String {
    if (after == null || !after.isFinite()) return "$label has no finite estimate after the burn"
    if (before == null || !before.isFinite()) return "$label becomes ${flightDistance(after)}"
    val change = after - before
    if (!change.isFinite()) return "$label changes beyond the display range"
    return when {
        abs(change) < 1.0 -> "$label changes by less than 1 m"
        change > 0.0 -> "$label rises ${flightDistance(change)}"
        else -> "$label falls ${flightDistance(-change)}"
    }
}

internal fun flightCountdown(seconds: Double?): String? {
    if (seconds == null || !seconds.isFinite() || seconds < 0.0) return null
    if (seconds == 0.0) return "now"
    if (seconds < 1.0) return "in <1 s"
    if (seconds >= 86_400.0) return "in ${"%.1f".format(Locale.US, seconds / 86_400.0)} d"
    val rounded = ceil(seconds).toLong()
    return if (rounded >= 3_600) "in ${rounded / 3_600} h ${(rounded % 3_600) / 60} m"
    else if (rounded >= 60) "in ${rounded / 60} m ${rounded % 60} s" else "in $rounded s"
}

internal fun flightApsisTiming(guidance: OrbitGuidance?, periapsis: Boolean): String? {
    if (guidance == null) return null
    val time = if (periapsis) guidance.timeToPeriapsisSeconds else guidance.timeToApoapsisSeconds
    flightCountdown(time)?.let { return "≈ $it" }
    return when (guidance.regime) {
        OrbitRegime.NearCircular -> "no distinct apsis"
        OrbitRegime.NearParabolic -> "timing unavailable"
        OrbitRegime.Hyperbolic -> if (periapsis) "no future estimate" else "no apoapsis"
        OrbitRegime.Elliptic -> null
    }
}

internal fun flightNavigationHeading(guidance: OrbitGuidance?): String {
    if (guidance == null) return "Apsis timing unavailable"
    val peri = guidance.timeToPeriapsisSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    val apo = guidance.timeToApoapsisSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    return when {
        peri != null && (apo == null || peri <= apo) -> "Periapsis ${flightCountdown(peri)}"
        apo != null -> "Apoapsis ${flightCountdown(apo)}"
        guidance.regime == OrbitRegime.NearCircular -> "Nearly circular · no distinct apsis"
        guidance.regime == OrbitRegime.Hyperbolic -> "Open trajectory · no future periapsis estimate"
        guidance.regime == OrbitRegime.NearParabolic -> "Apsis timing unavailable near escape speed"
        else -> "Apsis timing unavailable"
    }
}

internal fun flightBurnAttitude(state: FlightState?, assist: FlightAssist, throttle: Double): String {
    if (state == null) return "Waiting for the spacecraft state"
    if (throttle <= 0.0) return "Coast preview · main engine off"
    if (state.maxThrustN <= 0.0) return "This spacecraft has no main engine"
    if (state.propellantMassKg <= 0.0) return "No propellant remains for this burn"
    if (assist == FlightAssist.Manual) return "Current nose direction · existing spin continues"
    if (assist == FlightAssist.Hold) return "Current nose direction · Stabilise damps spin"
    val director = flightDirector(state, assist) ?: return "The selected direction is unavailable at this state"
    val angle = "%.0f".format(Locale.US, director.errorDegrees)
    return if (!director.aligned) "$angle° to turn · thrust starts before alignment"
    else if (director.spinDegreesPerSecond > .5) "Nose aligned · still rotating ${"%.1f".format(Locale.US, director.spinDegreesPerSecond)}°/s"
    else "Nose aligned with ${assist.label.lowercase(Locale.US)}"
}
