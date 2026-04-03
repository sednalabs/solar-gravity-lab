package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.OrbitalElements
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object OrbitalMechanics {

    data class StateVector(
        val positionM: Vector3d,
        val velocityMps: Vector3d,
    )

    fun stateVectorAroundPrimary(
        primaryMassKg: Double,
        bodyMassKg: Double,
        elements: OrbitalElements,
        gravitationalConstant: Double,
    ): StateVector {
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        val eccentricity = elements.eccentricity
        val p = elements.semiMajorAxisM * (1.0 - eccentricity * eccentricity)
        val cosNu = cos(elements.trueAnomalyRad)
        val sinNu = sin(elements.trueAnomalyRad)

        val radius = p / (1.0 + eccentricity * cosNu)
        val positionPerifocal = Vector3d(
            x = radius * cosNu,
            y = radius * sinNu,
            z = 0.0,
        )

        val speedFactor = sqrt(mu / p)
        val velocityPerifocal = Vector3d(
            x = -speedFactor * sinNu,
            y = speedFactor * (eccentricity + cosNu),
            z = 0.0,
        )

        return StateVector(
            positionM = rotateFromPerifocal(positionPerifocal, elements),
            velocityMps = rotateFromPerifocal(velocityPerifocal, elements),
        )
    }

    fun stateVectorAroundPrimaryAtEpoch(
        primaryMassKg: Double,
        bodyMassKg: Double,
        orbit: KeplerianOrbitAtEpoch,
        targetJulianDateTdb: Double,
        gravitationalConstant: Double,
    ): StateVector {
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        val meanMotionRadPerSecond = sqrt(mu / (orbit.semiMajorAxisM * orbit.semiMajorAxisM * orbit.semiMajorAxisM))
        val deltaSeconds = (targetJulianDateTdb - orbit.epochJdTdb) * PhysicalConstants.DAY_SECONDS
        val meanAnomaly = normalizeRadians(orbit.meanAnomalyAtEpochRad + (meanMotionRadPerSecond * deltaSeconds))
        val eccentricAnomaly = solveKeplerEquation(meanAnomaly, orbit.eccentricity)
        val trueAnomaly = 2.0 * atan2(
            sqrt(1.0 + orbit.eccentricity) * sin(eccentricAnomaly / 2.0),
            sqrt(1.0 - orbit.eccentricity) * cos(eccentricAnomaly / 2.0),
        )
        return stateVectorAroundPrimary(
            primaryMassKg = primaryMassKg,
            bodyMassKg = bodyMassKg,
            elements = OrbitalElements(
                semiMajorAxisM = orbit.semiMajorAxisM,
                eccentricity = orbit.eccentricity,
                inclinationRad = orbit.inclinationRad,
                longitudeOfAscendingNodeRad = orbit.longitudeOfAscendingNodeRad,
                argumentOfPeriapsisRad = orbit.argumentOfPeriapsisRad,
                trueAnomalyRad = normalizeRadians(trueAnomaly),
            ),
            gravitationalConstant = gravitationalConstant,
        )
    }

    fun recenterToBarycenter(bodies: List<BodyState>): List<BodyState> {
        val totalMass = bodies.sumOf { it.sourceMassKg }
        if (totalMass == 0.0) return bodies

        val barycenter = bodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.positionM * body.sourceMassKg)
        } / totalMass

        val baryVelocity = bodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.velocityMps * body.sourceMassKg)
        } / totalMass

        return bodies.map { body ->
            body.copy(
                positionM = body.positionM - barycenter,
                velocityMps = body.velocityMps - baryVelocity,
            )
        }
    }

    fun circularOrbitalSpeed(
        primaryMassKg: Double,
        orbitalRadiusM: Double,
        gravitationalConstant: Double,
    ): Double = sqrt(gravitationalConstant * primaryMassKg / orbitalRadiusM)

    private fun rotateFromPerifocal(
        vector: Vector3d,
        elements: OrbitalElements,
    ): Vector3d {
        val cosOmega = cos(elements.longitudeOfAscendingNodeRad)
        val sinOmega = sin(elements.longitudeOfAscendingNodeRad)
        val cosI = cos(elements.inclinationRad)
        val sinI = sin(elements.inclinationRad)
        val cosW = cos(elements.argumentOfPeriapsisRad)
        val sinW = sin(elements.argumentOfPeriapsisRad)

        val rotation11 = cosOmega * cosW - sinOmega * sinW * cosI
        val rotation12 = -cosOmega * sinW - sinOmega * cosW * cosI
        val rotation21 = sinOmega * cosW + cosOmega * sinW * cosI
        val rotation22 = -sinOmega * sinW + cosOmega * cosW * cosI
        val rotation31 = sinW * sinI
        val rotation32 = cosW * sinI

        return Vector3d(
            x = rotation11 * vector.x + rotation12 * vector.y,
            y = rotation21 * vector.x + rotation22 * vector.y,
            z = rotation31 * vector.x + rotation32 * vector.y,
        )
    }

    internal fun solveKeplerEquation(
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

    internal fun normalizeRadians(angle: Double): Double {
        val wrapped = (angle + PI) % (2.0 * PI)
        return if (wrapped < 0.0) wrapped + PI else wrapped - PI
    }
}
