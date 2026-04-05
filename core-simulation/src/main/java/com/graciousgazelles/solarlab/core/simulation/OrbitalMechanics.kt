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

/**
 * Core utility for orbital mechanics and celestial coordinate transformations.
 *
 * This object implements the mathematical foundations for two-body propagation,
 * ephemeris seeding, and barycentric recentering.
 */
object OrbitalMechanics {

    /**
     * Represents a 3D state vector (position and velocity) in a Cartesian coordinate system.
     */
    data class StateVector(
        val positionM: Vector3d,
        val velocityMps: Vector3d,
    )

    /**
     * Computes a Cartesian [StateVector] from Keplerian [OrbitalElements].
     *
     * The conversion follows standard astrodynamics procedures:
     * 1. Compute the position and velocity in the perifocal coordinate system (orbit plane).
     * 2. Rotate the vectors from perifocal space into the reference equatorial/ecliptic frame
     *    using the longitude of ascending node, inclination, and argument of periapsis.
     *
     * @param primaryMassKg Mass of the central body.
     * @param bodyMassKg Mass of the orbiting body.
     * @param elements The Keplerian elements defining the orbit and current position.
     * @param gravitationalConstant The gravitational constant (G) to use for the simulation.
     * @return A [StateVector] relative to the primary body.
     */
    fun stateVectorAroundPrimary(
        primaryMassKg: Double,
        bodyMassKg: Double,
        elements: OrbitalElements,
        gravitationalConstant: Double,
    ): StateVector {
        // Standard gravitational parameter (mu)
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        val eccentricity = elements.eccentricity
        // Semi-latus rectum (p)
        val p = elements.semiMajorAxisM * (1.0 - eccentricity * eccentricity)
        val cosNu = cos(elements.trueAnomalyRad)
        val sinNu = sin(elements.trueAnomalyRad)

        // Compute position in perifocal coordinates (PQW)
        val radius = p / (1.0 + eccentricity * cosNu)
        val positionPerifocal = Vector3d(
            x = radius * cosNu,
            y = radius * sinNu,
            z = 0.0,
        )

        // Compute velocity in perifocal coordinates (PQW)
        val speedFactor = sqrt(mu / p)
        val velocityPerifocal = Vector3d(
            x = -speedFactor * sinNu,
            y = speedFactor * (eccentricity + cosNu),
            z = 0.0,
        )

        // Rotate from PQW to the reference frame
        return StateVector(
            positionM = rotateFromPerifocal(positionPerifocal, elements),
            velocityMps = rotateFromPerifocal(velocityPerifocal, elements),
        )
    }

    /**
     * Propagates an orbit to a target epoch and returns the resulting [StateVector].
     *
     * This uses mean motion propagation:
     * 1. Calculate the mean anomaly at the target time.
     * 2. Solve Kepler's equation (M = E - e*sinE) for the eccentric anomaly.
     * 3. Convert eccentric anomaly to true anomaly.
     * 4. Build the final state vector via [stateVectorAroundPrimary].
     */
    fun stateVectorAroundPrimaryAtEpoch(
        primaryMassKg: Double,
        bodyMassKg: Double,
        orbit: KeplerianOrbitAtEpoch,
        targetJulianDateTdb: Double,
        gravitationalConstant: Double,
    ): StateVector {
        val mu = gravitationalConstant * (primaryMassKg + bodyMassKg)
        // n = sqrt(mu / a^3)
        val meanMotionRadPerSecond = sqrt(mu / (orbit.semiMajorAxisM * orbit.semiMajorAxisM * orbit.semiMajorAxisM))
        val deltaSeconds = (targetJulianDateTdb - orbit.epochJdTdb) * PhysicalConstants.DAY_SECONDS
        
        // M = M0 + n * dt
        val meanAnomaly = normalizeRadians(orbit.meanAnomalyAtEpochRad + (meanMotionRadPerSecond * deltaSeconds))
        
        // Solve Kepler's equation for E
        val eccentricAnomaly = solveKeplerEquation(meanAnomaly, orbit.eccentricity)
        
        // Compute true anomaly (nu) from eccentric anomaly (E)
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

    /**
     * Recenters a list of bodies so that their collective center of mass (barycenter)
     * sits at the coordinate origin (0,0,0) with zero net velocity.
     *
     * This is essential for seeded scenarios to ensure the system doesn't drift
     * out of the primary simulation view due to uncompensated initial momentum.
     */
    fun recenterToBarycenter(bodies: List<BodyState>): List<BodyState> {
        val totalMass = bodies.sumOf { it.sourceMassKg }
        if (totalMass == 0.0) return bodies

        // Barycenter = sum(m_i * r_i) / sum(m_i)
        val barycenter = bodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.positionM * body.sourceMassKg)
        } / totalMass

        // Barycenter Velocity = sum(m_i * v_i) / sum(m_i)
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

    /**
     * Computes the theoretical speed for a circular orbit at a given radius.
     */
    fun circularOrbitalSpeed(
        primaryMassKg: Double,
        orbitalRadiusM: Double,
        gravitationalConstant: Double,
    ): Double = sqrt(gravitationalConstant * primaryMassKg / orbitalRadiusM)

    /**
     * Performs a 3D rotation from the perifocal frame (orbit plane) to the reference frame.
     *
     * The rotation sequence is:
     * 1. R3(-longitude of ascending node)
     * 2. R1(-inclination)
     * 3. R3(-argument of periapsis)
     */
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

        // Rotation matrix elements for PQW -> Reference frame
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

    /**
     * Solves Kepler's equation (M = E - e * sin(E)) for the eccentric anomaly (E)
     * using Newton-Raphson iteration.
     *
     * Convergence is typically achieved in fewer than 5 iterations for planetary orbits.
     * The loop is capped at 24 iterations as a safety guard.
     */
    internal fun solveKeplerEquation(
        meanAnomalyRad: Double,
        eccentricity: Double,
    ): Double {
        // Initial guess: E = M is sufficient for low eccentricity; 
        // E = PI is better for very high eccentricity.
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

    /**
     * Normalizes an angle in radians to the range [-PI, PI].
     */
    internal fun normalizeRadians(angle: Double): Double {
        val wrapped = (angle + PI) % (2.0 * PI)
        return if (wrapped < 0.0) wrapped + PI else wrapped - PI
    }
}
