package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.math.degToRad
import com.graciousgazelles.solarlab.core.model.OrbitalElements
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Epoch-tagged planetary starter states generated from JPL's published "Approximate Positions
 * of the Planets" coefficients for the 1800-2050 interval.
 *
 * This deliberately replaces arbitrary phase-angle seeding with deterministic cartesian state
 * vectors tied to a known epoch (J2000 TDB). It is not yet a full DE440 / Horizons vector
 * extract; the interface is structured so a future authoritative cartesian seed catalogue can
 * replace this object without changing the rest of the simulation pipeline.
 *
 * Source model:
 * - JPL SSD "Approximate Positions of the Planets", Table 1 (1800 AD -- 2050 AD)
 * - coordinates are converted from J2000 ecliptic to J2000 equatorial / ICRF-style axes using
 *   the fixed J2000 obliquity published on that page
 */
object JplApproximateSeedCatalog {

    const val J2000_TDB_JULIAN_DATE: Double = 2451545.0
    const val DEFAULT_SEED_JULIAN_DATE_TDB: Double = J2000_TDB_JULIAN_DATE

    private const val J2000_OBLIQUITY_DEG: Double = 23.43928

    private val seriesByPlanetId: Map<String, PlanetarySeries> = mapOf(
        "mercury" to PlanetarySeries(
            semiMajorAxisAu0 = 0.38709927,
            semiMajorAxisAuPerCentury = 0.00000037,
            eccentricity0 = 0.20563593,
            eccentricityPerCentury = 0.00001906,
            inclinationDeg0 = 7.00497902,
            inclinationDegPerCentury = -0.00594749,
            meanLongitudeDeg0 = 252.25032350,
            meanLongitudeDegPerCentury = 149472.67411175,
            longitudeOfPerihelionDeg0 = 77.45779628,
            longitudeOfPerihelionDegPerCentury = 0.16047689,
            longitudeOfAscendingNodeDeg0 = 48.33076593,
            longitudeOfAscendingNodeDegPerCentury = -0.12534081,
        ),
        "venus" to PlanetarySeries(
            semiMajorAxisAu0 = 0.72333566,
            semiMajorAxisAuPerCentury = 0.00000390,
            eccentricity0 = 0.00677672,
            eccentricityPerCentury = -0.00004107,
            inclinationDeg0 = 3.39467605,
            inclinationDegPerCentury = -0.00078890,
            meanLongitudeDeg0 = 181.97909950,
            meanLongitudeDegPerCentury = 58517.81538729,
            longitudeOfPerihelionDeg0 = 131.60246718,
            longitudeOfPerihelionDegPerCentury = 0.00268329,
            longitudeOfAscendingNodeDeg0 = 76.67984255,
            longitudeOfAscendingNodeDegPerCentury = -0.27769418,
        ),
        // JPL publishes the Earth/Moon barycenter in this table rather than the Earth geocenter.
        // For this first serious seed pass, the simulation uses that as the Earth's starter state
        // until a full DE/Horizons vector bundle is dropped in.
        "earth" to PlanetarySeries(
            semiMajorAxisAu0 = 1.00000261,
            semiMajorAxisAuPerCentury = 0.00000562,
            eccentricity0 = 0.01671123,
            eccentricityPerCentury = -0.00004392,
            inclinationDeg0 = -0.00001531,
            inclinationDegPerCentury = -0.01294668,
            meanLongitudeDeg0 = 100.46457166,
            meanLongitudeDegPerCentury = 35999.37244981,
            longitudeOfPerihelionDeg0 = 102.93768193,
            longitudeOfPerihelionDegPerCentury = 0.32327364,
            longitudeOfAscendingNodeDeg0 = 0.0,
            longitudeOfAscendingNodeDegPerCentury = 0.0,
        ),
        "mars" to PlanetarySeries(
            semiMajorAxisAu0 = 1.52371034,
            semiMajorAxisAuPerCentury = 0.00001847,
            eccentricity0 = 0.09339410,
            eccentricityPerCentury = 0.00007882,
            inclinationDeg0 = 1.84969142,
            inclinationDegPerCentury = -0.00813131,
            meanLongitudeDeg0 = -4.55343205,
            meanLongitudeDegPerCentury = 19140.30268499,
            longitudeOfPerihelionDeg0 = -23.94362959,
            longitudeOfPerihelionDegPerCentury = 0.44441088,
            longitudeOfAscendingNodeDeg0 = 49.55953891,
            longitudeOfAscendingNodeDegPerCentury = -0.29257343,
        ),
        "jupiter" to PlanetarySeries(
            semiMajorAxisAu0 = 5.20288700,
            semiMajorAxisAuPerCentury = -0.00011607,
            eccentricity0 = 0.04838624,
            eccentricityPerCentury = -0.00013253,
            inclinationDeg0 = 1.30439695,
            inclinationDegPerCentury = -0.00183714,
            meanLongitudeDeg0 = 34.39644051,
            meanLongitudeDegPerCentury = 3034.74612775,
            longitudeOfPerihelionDeg0 = 14.72847983,
            longitudeOfPerihelionDegPerCentury = 0.21252668,
            longitudeOfAscendingNodeDeg0 = 100.47390909,
            longitudeOfAscendingNodeDegPerCentury = 0.20469106,
        ),
        "saturn" to PlanetarySeries(
            semiMajorAxisAu0 = 9.53667594,
            semiMajorAxisAuPerCentury = -0.00125060,
            eccentricity0 = 0.05386179,
            eccentricityPerCentury = -0.00050991,
            inclinationDeg0 = 2.48599187,
            inclinationDegPerCentury = 0.00193609,
            meanLongitudeDeg0 = 49.95424423,
            meanLongitudeDegPerCentury = 1222.49362201,
            longitudeOfPerihelionDeg0 = 92.59887831,
            longitudeOfPerihelionDegPerCentury = -0.41897216,
            longitudeOfAscendingNodeDeg0 = 113.66242448,
            longitudeOfAscendingNodeDegPerCentury = -0.28867794,
        ),
        "uranus" to PlanetarySeries(
            semiMajorAxisAu0 = 19.18916464,
            semiMajorAxisAuPerCentury = -0.00196176,
            eccentricity0 = 0.04725744,
            eccentricityPerCentury = -0.00004397,
            inclinationDeg0 = 0.77263783,
            inclinationDegPerCentury = -0.00242939,
            meanLongitudeDeg0 = 313.23810451,
            meanLongitudeDegPerCentury = 428.48202785,
            longitudeOfPerihelionDeg0 = 170.95427630,
            longitudeOfPerihelionDegPerCentury = 0.40805281,
            longitudeOfAscendingNodeDeg0 = 74.01692503,
            longitudeOfAscendingNodeDegPerCentury = 0.04240589,
        ),
        "neptune" to PlanetarySeries(
            semiMajorAxisAu0 = 30.06992276,
            semiMajorAxisAuPerCentury = 0.00026291,
            eccentricity0 = 0.00859048,
            eccentricityPerCentury = 0.00005105,
            inclinationDeg0 = 1.77004347,
            inclinationDegPerCentury = 0.00035372,
            meanLongitudeDeg0 = -55.12002969,
            meanLongitudeDegPerCentury = 218.45945325,
            longitudeOfPerihelionDeg0 = 44.96476227,
            longitudeOfPerihelionDegPerCentury = -0.32241464,
            longitudeOfAscendingNodeDeg0 = 131.78422574,
            longitudeOfAscendingNodeDegPerCentury = -0.00508664,
        ),
    )

    fun stateVectorForPlanet(
        planetId: String,
        primaryMassKg: Double,
        bodyMassKg: Double,
        gravitationalConstant: Double,
        julianDateTdb: Double = DEFAULT_SEED_JULIAN_DATE_TDB,
    ): OrbitalMechanics.StateVector {
        val series = seriesByPlanetId[planetId]
            ?: error("No JPL approximate seed series configured for '$planetId'")

        val elements = series.toOrbitalElements(julianDateTdb)
        val eclipticState = OrbitalMechanics.stateVectorAroundPrimary(
            primaryMassKg = primaryMassKg,
            bodyMassKg = bodyMassKg,
            elements = elements,
            gravitationalConstant = gravitationalConstant,
        )

        return OrbitalMechanics.StateVector(
            positionM = rotateEclipticToEquatorial(eclipticState.positionM),
            velocityMps = rotateEclipticToEquatorial(eclipticState.velocityMps),
        )
    }

    private fun PlanetarySeries.toOrbitalElements(
        julianDateTdb: Double,
    ): OrbitalElements {
        val centuriesFromJ2000 = (julianDateTdb - J2000_TDB_JULIAN_DATE) / 36_525.0

        val semiMajorAxisM = (
            semiMajorAxisAu0 + semiMajorAxisAuPerCentury * centuriesFromJ2000
        ) * PhysicalConstants.ASTRONOMICAL_UNIT_M
        val eccentricity = eccentricity0 + eccentricityPerCentury * centuriesFromJ2000
        val inclinationRad = (
            inclinationDeg0 + inclinationDegPerCentury * centuriesFromJ2000
        ).degToRad()
        val longitudeOfAscendingNodeRad = (
            longitudeOfAscendingNodeDeg0 + longitudeOfAscendingNodeDegPerCentury * centuriesFromJ2000
        ).degToRad()

        val longitudeOfPerihelionRad = (
            longitudeOfPerihelionDeg0 + longitudeOfPerihelionDegPerCentury * centuriesFromJ2000
        ).degToRad()
        val meanLongitudeRad = (
            meanLongitudeDeg0 + meanLongitudeDegPerCentury * centuriesFromJ2000
        ).degToRad()

        val argumentOfPeriapsisRad = normalizeRadians(
            longitudeOfPerihelionRad - longitudeOfAscendingNodeRad,
        )
        val meanAnomalyRad = normalizeRadians(meanLongitudeRad - longitudeOfPerihelionRad)
        val eccentricAnomalyRad = solveKeplerEquation(
            meanAnomalyRad = meanAnomalyRad,
            eccentricity = eccentricity,
        )
        val trueAnomalyRad = 2.0 * atan2(
            sqrt(1.0 + eccentricity) * sin(eccentricAnomalyRad / 2.0),
            sqrt(1.0 - eccentricity) * cos(eccentricAnomalyRad / 2.0),
        )

        return OrbitalElements(
            semiMajorAxisM = semiMajorAxisM,
            eccentricity = eccentricity,
            inclinationRad = inclinationRad,
            longitudeOfAscendingNodeRad = longitudeOfAscendingNodeRad,
            argumentOfPeriapsisRad = argumentOfPeriapsisRad,
            trueAnomalyRad = normalizeRadians(trueAnomalyRad),
        )
    }

    private fun solveKeplerEquation(
        meanAnomalyRad: Double,
        eccentricity: Double,
    ): Double {
        var eccentricAnomaly = if (eccentricity < 0.8) meanAnomalyRad else PI
        repeat(24) {
            val functionValue = eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - meanAnomalyRad
            val derivative = 1.0 - eccentricity * cos(eccentricAnomaly)
            val delta = functionValue / derivative
            eccentricAnomaly -= delta
            if (abs(delta) <= 1e-14) {
                return eccentricAnomaly
            }
        }
        return eccentricAnomaly
    }

    private fun rotateEclipticToEquatorial(vector: Vector3d): Vector3d {
        val obliquityRad = J2000_OBLIQUITY_DEG.degToRad()
        val cosObliquity = cos(obliquityRad)
        val sinObliquity = sin(obliquityRad)

        return Vector3d(
            x = vector.x,
            y = cosObliquity * vector.y - sinObliquity * vector.z,
            z = sinObliquity * vector.y + cosObliquity * vector.z,
        )
    }

    private fun normalizeRadians(angle: Double): Double {
        val wrapped = (angle + PI) % (2.0 * PI)
        return if (wrapped < 0.0) wrapped + PI else wrapped - PI
    }

    private data class PlanetarySeries(
        val semiMajorAxisAu0: Double,
        val semiMajorAxisAuPerCentury: Double,
        val eccentricity0: Double,
        val eccentricityPerCentury: Double,
        val inclinationDeg0: Double,
        val inclinationDegPerCentury: Double,
        val meanLongitudeDeg0: Double,
        val meanLongitudeDegPerCentury: Double,
        val longitudeOfPerihelionDeg0: Double,
        val longitudeOfPerihelionDegPerCentury: Double,
        val longitudeOfAscendingNodeDeg0: Double,
        val longitudeOfAscendingNodeDegPerCentury: Double,
    )
}
