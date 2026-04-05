package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.core.model.TimelineMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Authoritative simulation engine for N-body dynamics and continuous collision resolution.
 *
 * This engine uses a double-precision Kick-Drift-Kick (Leapfrog/Velocity-Verlet) integrator
 * to maintain long-term energy stability in orbital systems.
 */
class SimulationEngine(
    initialSnapshot: SimulationSnapshot,
    private val config: SimulationConfig = SimulationConfig(),
) {

    private var epochSeconds: Double = initialSnapshot.epochSeconds
    private var referenceEpochJdTdb: Double? = initialSnapshot.referenceEpochJdTdb
    private var timelineMode: TimelineMode = initialSnapshot.timelineMode
    private var provenanceLabel: String? = initialSnapshot.provenanceLabel
    private var provenanceSource: String? = initialSnapshot.provenanceSource
    private var bodies: MutableList<MutableBody> = initialSnapshot.bodies.map(MutableBody::fromState).toMutableList()
    private var diagnosticsCache: SystemDiagnostics = computeDiagnostics()
    private var diagnosticsDirty: Boolean = false

    fun snapshot(): SimulationSnapshot = SimulationSnapshot(
        epochSeconds = epochSeconds,
        bodies = bodies.map { it.toState() },
        referenceEpochJdTdb = referenceEpochJdTdb,
        timelineMode = timelineMode,
        provenanceLabel = provenanceLabel,
        provenanceSource = provenanceSource,
    )

    fun diagnostics(forceRecompute: Boolean = true): SystemDiagnostics {
        if (forceRecompute) {
            diagnosticsCache = computeDiagnostics()
            diagnosticsDirty = false
        }
        return diagnosticsCache
    }

    fun reset(snapshot: SimulationSnapshot) {
        epochSeconds = snapshot.epochSeconds
        referenceEpochJdTdb = snapshot.referenceEpochJdTdb
        timelineMode = snapshot.timelineMode
        provenanceLabel = snapshot.provenanceLabel
        provenanceSource = snapshot.provenanceSource
        bodies = snapshot.bodies.map(MutableBody::fromState).toMutableList()
        markDiagnosticsDirty()
    }

    fun body(bodyId: String): BodyState? = bodies.firstOrNull { it.id == bodyId }?.toState()

    fun addBody(body: BodyState) {
        markSandboxBranch("User-edited sandbox", "local-edit")
        bodies += MutableBody.fromState(body)
        markDiagnosticsDirty()
    }

    fun updateBody(body: BodyState): Boolean {
        val index = bodies.indexOfFirst { it.id == body.id }
        if (index < 0) return false
        markSandboxBranch("User-edited sandbox", "local-edit")
        bodies[index] = MutableBody.fromState(body)
        markDiagnosticsDirty()
        return true
    }

    fun removeBody(bodyId: String): Boolean {
        val index = bodies.indexOfFirst { it.id == bodyId }
        if (index < 0) return false
        markSandboxBranch("User-edited sandbox", "local-edit")
        bodies.removeAt(index)
        markDiagnosticsDirty()
        return true
    }

    /**
     * Advances the simulation state by [deltaTimeSeconds].
     *
     * Integration follows the Kick-Drift-Kick sequence:
     * 1. **Kick 1**: Update velocities by half a time step using current accelerations.
     * 2. **Drift**: Update positions using the updated velocities. If collision modes
     *    are enabled, this phase performs continuous sweep tests and resolves impacts
     *    iteratively to prevent tunneling.
     * 3. **Kick 2**: Recompute accelerations at new positions and update velocities
     *    by the remaining half time step.
     *
     * This interleaved approach is second-order accurate and symplectic, meaning it
     * preserves the phase-space volume and prevents the artificial energy inflation
     * common in Euler or simple Runge-Kutta methods when applied to orbital systems.
     */
    fun step(
        deltaTimeSeconds: Double,
        recomputeDiagnostics: Boolean = true,
    ): SimulationStepResult {
        require(deltaTimeSeconds > 0.0) { "deltaTimeSeconds must be > 0" }

        if (bodies.isEmpty()) {
            epochSeconds += deltaTimeSeconds
            markDiagnosticsDirty()
            val diagnostics = diagnostics(forceRecompute = recomputeDiagnostics)
            return SimulationStepResult(
                snapshot = snapshot(),
                diagnostics = diagnostics,
                collisions = emptyList(),
                diagnosticsFresh = !diagnosticsDirty,
            )
        }

        // --- Step 1: Kick (first half) ---
        val firstAccelerations = computeAccelerations()
        val halfDelta = deltaTimeSeconds * 0.5

        for (index in bodies.indices) {
            val body = bodies[index]
            body.velocityMps = body.velocityMps + (firstAccelerations[index] * halfDelta)
        }

        // --- Step 2: Drift (full step) + Collision Resolution ---
        val collisions = when (config.collisionMode) {
            CollisionMode.NONE -> {
                advancePositions(deltaTimeSeconds)
                emptyList()
            }
            CollisionMode.MERGE,
            CollisionMode.FRAGMENTATION,
            CollisionMode.ELASTIC,
            -> resolveCollisionsDuringDrift(deltaTimeSeconds)
        }

        // --- Step 3: Kick (second half) ---
        // Accelerations must be recomputed at the NEW positions
        val secondAccelerations = computeAccelerations()

        for (index in bodies.indices) {
            val body = bodies[index]
            body.velocityMps = body.velocityMps + (secondAccelerations[index] * halfDelta)
        }

        epochSeconds += deltaTimeSeconds
        if (collisions.isNotEmpty()) {
            markSandboxBranch("Collision-evolved sandbox", "collision")
        }
        markDiagnosticsDirty()
        val diagnostics = diagnostics(forceRecompute = recomputeDiagnostics)

        return SimulationStepResult(
            snapshot = snapshot(),
            diagnostics = diagnostics,
            collisions = collisions,
            diagnosticsFresh = !diagnosticsDirty,
        )
    }

    private fun computeAccelerations(): List<Vector3d> {
        if (bodies.isEmpty()) return emptyList()

        val bodyCount = bodies.size
        val gravitationalConstant = config.gravitationalConstant
        val softeningSquared = config.softeningLengthM * config.softeningLengthM
        val accelerations = MutableList(bodyCount) { Vector3d.ZERO }
        val massiveCount = bodies.count { it.gravitationalRole == GravitationalRole.MASSIVE }
        if (massiveCount == 0) return accelerations

        val sourceIndices = IntArray(massiveCount)
        val sourceMasses = DoubleArray(massiveCount)
        val sourcePosX = DoubleArray(massiveCount)
        val sourcePosY = DoubleArray(massiveCount)
        val sourcePosZ = DoubleArray(massiveCount)
        var sourceCursor = 0
        for (bodyIndex in 0 until bodyCount) {
            val sourceBody = bodies[bodyIndex]
            if (sourceBody.gravitationalRole != GravitationalRole.MASSIVE) continue

            sourceIndices[sourceCursor] = bodyIndex
            sourceMasses[sourceCursor] = sourceBody.massKg
            sourcePosX[sourceCursor] = sourceBody.positionM.x
            sourcePosY[sourceCursor] = sourceBody.positionM.y
            sourcePosZ[sourceCursor] = sourceBody.positionM.z
            sourceCursor += 1
        }

        for (i in 0 until bodyCount) {
            val body = bodies[i]
            val bodyPosition = body.positionM
            val bodyX = bodyPosition.x
            val bodyY = bodyPosition.y
            val bodyZ = bodyPosition.z
            var accelerationX = 0.0
            var accelerationY = 0.0
            var accelerationZ = 0.0

            for (sourceIndex in 0 until massiveCount) {
                if (sourceIndices[sourceIndex] == i) continue

                val dx = sourcePosX[sourceIndex] - bodyX
                val dy = sourcePosY[sourceIndex] - bodyY
                val dz = sourcePosZ[sourceIndex] - bodyZ
                val distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared
                if (distanceSquared == 0.0) continue

                val invDistance = 1.0 / sqrt(distanceSquared)
                val invDistanceCubed = invDistance * invDistance * invDistance
                val scale = gravitationalConstant * sourceMasses[sourceIndex] * invDistanceCubed

                accelerationX += dx * scale
                accelerationY += dy * scale
                accelerationZ += dz * scale
            }

            accelerations[i] = Vector3d(accelerationX, accelerationY, accelerationZ)
        }

        return accelerations
    }

    /**
     * Resolves collisions during the position-drift phase using continuous sweep tests.
     *
     * Because multiple bodies may collide in a single time step, this loop resolves the
     * earliest impact, advances all bodies to that point in time, resolves the impact
     * (merging or bouncing), and then repeats for the remaining time in the step.
     */
    private fun resolveCollisionsDuringDrift(deltaTimeSeconds: Double): List<CollisionEvent> {
        val collisions = mutableListOf<CollisionEvent>()
        var remaining = deltaTimeSeconds
        var iterations = 0

        while (remaining > COLLISION_TIME_EPSILON && bodies.size > 1) {
            if (++iterations > MAX_COLLISION_ITERATIONS_PER_STEP) {
                // Safety break to prevent infinite loops in extremely dense or unstable systems.
                advancePositions(remaining)
                break
            }

            // Correct any initial overlaps that may have occurred due to external state changes
            if (config.collisionMode != CollisionMode.NONE) {
                correctPassiveOverlaps()
            }

            // Find the earliest collision in the remaining time window
            val candidate = findEarliestCollision(remaining)
            if (candidate == null) {
                advancePositions(remaining)
                break
            }

            // Move everyone to the exact moment of the first impact
            val drift = candidate.timeSeconds.coerceIn(0.0, remaining)
            if (drift > 0.0) {
                advancePositions(drift)
                remaining -= drift
            }

            // Resolve the impact and update topology/velocities
            val impactOffset = deltaTimeSeconds - remaining
            val event = when (config.collisionMode) {
                CollisionMode.MERGE -> resolveMergeCollision(candidate, impactOffset)
                CollisionMode.ELASTIC -> resolveElasticCollision(candidate, impactOffset)
                CollisionMode.FRAGMENTATION -> resolveFragmentationCollision(candidate, impactOffset)
                CollisionMode.NONE -> error("Collision resolution called while collision mode is NONE")
            }
            collisions += event

            if (drift <= COLLISION_TIME_EPSILON) {
                remaining = max(0.0, remaining - COLLISION_TIME_EPSILON)
            }
        }

        return collisions
    }

    private fun advancePositions(deltaTimeSeconds: Double) {
        if (deltaTimeSeconds <= 0.0) return
        for (body in bodies) {
            body.positionM = body.positionM + (body.velocityMps * deltaTimeSeconds)
        }
    }

    private fun correctPassiveOverlaps() {
        for (i in 0 until bodies.size) {
            for (j in (i + 1) until bodies.size) {
                val a = bodies[i]
                val b = bodies[j]
                val combinedRadius = a.radiusM + b.radiusM
                if (combinedRadius <= 0.0) continue

                val delta = b.positionM - a.positionM
                val distance = delta.magnitude()
                if (distance >= combinedRadius) continue

                val relativeVelocity = b.velocityMps - a.velocityMps
                val approaching = delta.dot(relativeVelocity) < 0.0
                if (approaching) continue

                val normal = collisionNormal(a, b)
                separateBodiesToContact(a, b, normal)
            }
        }
    }

    private fun findEarliestCollision(maxTimeSeconds: Double): CollisionCandidate? {
        var best: CollisionCandidate? = null

        for (i in 0 until bodies.size) {
            for (j in (i + 1) until bodies.size) {
                val a = bodies[i]
                val b = bodies[j]
                val combinedRadius = a.radiusM + b.radiusM
                if (combinedRadius <= 0.0) continue

                val relativePosition = b.positionM - a.positionM
                val relativeVelocity = b.velocityMps - a.velocityMps
                val collisionTime = collisionTimeSeconds(
                    relativePosition = relativePosition,
                    relativeVelocity = relativeVelocity,
                    combinedRadiusM = combinedRadius,
                    maxTimeSeconds = maxTimeSeconds,
                ) ?: continue

                if (best == null || collisionTime < best.timeSeconds - COLLISION_TIME_EPSILON) {
                    best = CollisionCandidate(i = i, j = j, timeSeconds = collisionTime)
                }
            }
        }

        return best
    }

    private fun collisionTimeSeconds(
        relativePosition: Vector3d,
        relativeVelocity: Vector3d,
        combinedRadiusM: Double,
        maxTimeSeconds: Double,
    ): Double? {
        val c = relativePosition.magnitudeSquared() - (combinedRadiusM * combinedRadiusM)
        val b = 2.0 * relativePosition.dot(relativeVelocity)
        val a = relativeVelocity.magnitudeSquared()

        if (c <= 0.0) {
            return when (config.collisionMode) {
                CollisionMode.MERGE -> 0.0
                CollisionMode.FRAGMENTATION -> 0.0
                CollisionMode.ELASTIC -> if (relativePosition.dot(relativeVelocity) < 0.0) 0.0 else null
                CollisionMode.NONE -> null
            }
        }

        if (a <= COLLISION_TIME_EPSILON) {
            return null
        }

        val discriminant = (b * b) - (4.0 * a * c)
        if (discriminant < 0.0) return null

        val sqrtDiscriminant = sqrt(discriminant)
        val denominator = 2.0 * a
        val entryTime = (-b - sqrtDiscriminant) / denominator

        return when {
            entryTime < -COLLISION_TIME_EPSILON -> null
            entryTime > maxTimeSeconds + COLLISION_TIME_EPSILON -> null
            entryTime <= COLLISION_TIME_EPSILON -> 0.0
            else -> entryTime
        }
    }

    private fun resolveMergeCollision(
        candidate: CollisionCandidate,
        impactTimeOffsetSeconds: Double,
    ): CollisionEvent {
        val primary = bodies[candidate.i]
        val secondary = bodies[candidate.j]
        val merged = mergeBodies(primary, secondary)

        bodies[candidate.i] = merged
        bodies.removeAt(candidate.j)

        return CollisionEvent(
            collisionMode = CollisionMode.MERGE,
            primaryBodyId = primary.id,
            secondaryBodyId = secondary.id,
            resultBodyIds = listOf(merged.id),
            resultLabel = merged.name,
            impactTimeOffsetSeconds = impactTimeOffsetSeconds,
        )
    }

    private fun resolveFragmentationCollision(
        candidate: CollisionCandidate,
        impactTimeOffsetSeconds: Double,
    ): CollisionEvent {
        val primary = bodies[candidate.i]
        val secondary = bodies[candidate.j]
        val fragments = fragmentsFromCollision(primary, secondary)

        bodies[candidate.i] = fragments[0]
        bodies[candidate.j] = fragments[1]

        return CollisionEvent(
            collisionMode = CollisionMode.FRAGMENTATION,
            primaryBodyId = primary.id,
            secondaryBodyId = secondary.id,
            resultBodyIds = fragments.map { it.id },
            resultLabel = "${primary.name} + ${secondary.name} → ${fragments.first().name}, ${fragments.last().name}",
            impactTimeOffsetSeconds = impactTimeOffsetSeconds,
        )
    }

    private fun fragmentsFromCollision(
        primary: MutableBody,
        secondary: MutableBody,
    ): List<MutableBody> {
        val totalMass = primary.massKg + secondary.massKg
        val fragmentMasses = when {
            totalMass <= 0.0 -> listOf(0.0, 0.0)
            else -> {
                val fragmentOneMass = totalMass * FRAGMENTATION_PRIMARY_MASS_FRACTION
                val fragmentTwoMass = totalMass - fragmentOneMass
                listOf(fragmentOneMass, fragmentTwoMass)
            }
        }

        val fragmentDensity = when {
            totalMass <= 0.0 -> max(primary.densityKgPerM3, secondary.densityKgPerM3)
            else -> {
                val massWeightedDensity = (primary.massKg * primary.densityKgPerM3) + (secondary.massKg * secondary.densityKgPerM3)
                if (massWeightedDensity > 0.0) massWeightedDensity / totalMass else max(primary.densityKgPerM3, secondary.densityKgPerM3)
            }
        }

        val normal = collisionNormal(primary, secondary)
        val relativeNormalSpeed = abs((secondary.velocityMps - primary.velocityMps).dot(normal))
        val desiredOffsetSpeed = max(relativeNormalSpeed * FRAGMENTATION_VELOCITY_FRACTION, FRAGMENTATION_MIN_OFFSET_SPEED)

        val firstMass = fragmentMasses[0]
        val secondMass = fragmentMasses[1]
        val firstVelocityOffset = if (firstMass > 0.0) desiredOffsetSpeed * (secondMass / totalMass) else 0.0
        val secondVelocityOffset = if (secondMass > 0.0) desiredOffsetSpeed * (firstMass / totalMass) else 0.0

        val fragmentRole = if (primary.gravitationalRole == GravitationalRole.MASSIVE || secondary.gravitationalRole == GravitationalRole.MASSIVE) {
            GravitationalRole.MASSIVE
        } else {
            GravitationalRole.TRACER
        }
        val category = chooseCategory(primary.category, secondary.category)
        val color = blendColors(primary.colorArgb, secondary.colorArgb, firstMass, secondMass)
        val combinedMomentum = (primary.velocityMps * primary.massKg) + (secondary.velocityMps * secondary.massKg)
        val centerOfMassVelocity = if (totalMass > 0.0) combinedMomentum / totalMass else (primary.velocityMps + secondary.velocityMps) * 0.5

        val firstRadius = BodyFactory.radiusFromMassAndDensity(
            massKg = firstMass,
            densityKgPerM3 = fragmentDensity,
        )
        val secondRadius = BodyFactory.radiusFromMassAndDensity(
            massKg = secondMass,
            densityKgPerM3 = fragmentDensity,
        )

        val centerOfMassPosition = if (totalMass > 0.0) {
            ((primary.positionM * primary.massKg) + (secondary.positionM * secondary.massKg)) / totalMass
        } else {
            (primary.positionM + secondary.positionM) * 0.5
        }

        val separation = (firstRadius + secondRadius) * FRAGMENTATION_SEPARATION_MULTIPLIER
        val firstPosition = centerOfMassPosition - (normal * separation * 0.5)
        val secondPosition = centerOfMassPosition + (normal * separation * 0.5)
        val firstVelocity = centerOfMassVelocity - (normal * firstVelocityOffset)
        val secondVelocity = centerOfMassVelocity + (normal * secondVelocityOffset)

        return listOf(
            MutableBody(
                id = "frag:${primary.id}+${secondary.id}:1",
                name = "${primary.name} fragment 1",
                category = category,
                gravitationalRole = fragmentRole,
                massKg = firstMass,
                radiusM = firstRadius,
                densityKgPerM3 = fragmentDensity,
                positionM = firstPosition,
                velocityMps = firstVelocity,
                colorArgb = color,
                hostBodyId = null,
            ),
            MutableBody(
                id = "frag:${primary.id}+${secondary.id}:2",
                name = "${secondary.name} fragment 2",
                category = category,
                gravitationalRole = fragmentRole,
                massKg = secondMass,
                radiusM = secondRadius,
                densityKgPerM3 = fragmentDensity,
                positionM = secondPosition,
                velocityMps = secondVelocity,
                colorArgb = color,
                hostBodyId = null,
            ),
        )
    }

    private fun resolveElasticCollision(
        candidate: CollisionCandidate,
        impactTimeOffsetSeconds: Double,
    ): CollisionEvent {
        val a = bodies[candidate.i]
        val b = bodies[candidate.j]
        val normal = collisionNormal(a, b)
        separateBodiesToContact(a, b, normal)

        val massA = a.massKg
        val massB = b.massKg
        val u1 = a.velocityMps.dot(normal)
        val u2 = b.velocityMps.dot(normal)
        val tangentA = a.velocityMps - (normal * u1)
        val tangentB = b.velocityMps - (normal * u2)
        val relativeNormalSpeed = u1 - u2

        if (relativeNormalSpeed > 0.0) {
            when {
                massA > 0.0 && massB > 0.0 -> {
                    val totalMass = massA + massB
                    val v1n = (((massA - massB) * u1) + (2.0 * massB * u2)) / totalMass
                    val v2n = (((massB - massA) * u2) + (2.0 * massA * u1)) / totalMass
                    a.velocityMps = tangentA + (normal * v1n)
                    b.velocityMps = tangentB + (normal * v2n)
                }
                massA <= 0.0 && massB > 0.0 -> {
                    a.velocityMps = tangentA + (normal * ((2.0 * u2) - u1))
                }
                massB <= 0.0 && massA > 0.0 -> {
                    b.velocityMps = tangentB + (normal * ((2.0 * u1) - u2))
                }
                else -> {
                    a.velocityMps = tangentA + (normal * u2)
                    b.velocityMps = tangentB + (normal * u1)
                }
            }
        }

        return CollisionEvent(
            collisionMode = CollisionMode.ELASTIC,
            primaryBodyId = a.id,
            secondaryBodyId = b.id,
            resultBodyIds = listOf(a.id, b.id),
            resultLabel = "${a.name} ↔ ${b.name}",
            impactTimeOffsetSeconds = impactTimeOffsetSeconds,
        )
    }

    private fun collisionNormal(a: MutableBody, b: MutableBody): Vector3d {
        val delta = b.positionM - a.positionM
        val deltaMagnitude = delta.magnitude()
        if (deltaMagnitude > COLLISION_DISTANCE_EPSILON) {
            return delta / deltaMagnitude
        }

        val relativeVelocity = b.velocityMps - a.velocityMps
        val velocityMagnitude = relativeVelocity.magnitude()
        if (velocityMagnitude > COLLISION_DISTANCE_EPSILON) {
            return relativeVelocity / velocityMagnitude
        }

        return Vector3d(1.0, 0.0, 0.0)
    }

    private fun separateBodiesToContact(
        a: MutableBody,
        b: MutableBody,
        normal: Vector3d,
    ) {
        val targetDistance = a.radiusM + b.radiusM
        val currentDistance = a.positionM.distanceTo(b.positionM)
        if (targetDistance <= 0.0) return

        val penetration = targetDistance - currentDistance
        if (penetration <= 0.0) return

        val totalMass = a.massKg + b.massKg
        when {
            totalMass > 0.0 -> {
                val moveA = if (b.massKg > 0.0) penetration * (b.massKg / totalMass) else penetration
                val moveB = if (a.massKg > 0.0) penetration * (a.massKg / totalMass) else penetration
                a.positionM = a.positionM - (normal * moveA)
                b.positionM = b.positionM + (normal * moveB)
            }
            else -> {
                val halfPenetration = penetration * 0.5
                a.positionM = a.positionM - (normal * halfPenetration)
                b.positionM = b.positionM + (normal * halfPenetration)
            }
        }
    }

    private fun mergeBodies(
        a: MutableBody,
        b: MutableBody,
    ): MutableBody {
        val mergedMass = a.massKg + b.massKg
        val mergedRole = if (a.gravitationalRole == GravitationalRole.MASSIVE || b.gravitationalRole == GravitationalRole.MASSIVE) {
            GravitationalRole.MASSIVE
        } else {
            GravitationalRole.TRACER
        }

        val mergedPosition = if (mergedMass > 0.0) {
            ((a.positionM * a.massKg) + (b.positionM * b.massKg)) / mergedMass
        } else {
            (a.positionM + b.positionM) * 0.5
        }

        val mergedVelocity = if (mergedMass > 0.0) {
            ((a.velocityMps * a.massKg) + (b.velocityMps * b.massKg)) / mergedMass
        } else {
            (a.velocityMps + b.velocityMps) * 0.5
        }

        val volumeA = volumeFromRadius(a.radiusM)
        val volumeB = volumeFromRadius(b.radiusM)
        val mergedRadius = radiusFromCombinedVolume(volumeA + volumeB)
        val mergedDensity = if (mergedMass > 0.0 && mergedRadius > 0.0) {
            BodyFactory.densityFromMassAndRadius(mergedMass, mergedRadius)
        } else {
            max(a.densityKgPerM3, b.densityKgPerM3)
        }

        return MutableBody(
            id = "merge:${a.id}+${b.id}",
            name = "${a.name} + ${b.name}",
            category = chooseCategory(a.category, b.category),
            gravitationalRole = mergedRole,
            massKg = mergedMass,
            radiusM = mergedRadius,
            densityKgPerM3 = mergedDensity,
            positionM = mergedPosition,
            velocityMps = mergedVelocity,
            colorArgb = blendColors(a.colorArgb, b.colorArgb, a.massKg, b.massKg),
            hostBodyId = null,
        )
    }

    private fun chooseCategory(
        a: BodyCategory,
        b: BodyCategory,
    ): BodyCategory = when {
        a == BodyCategory.STAR || b == BodyCategory.STAR -> BodyCategory.STAR
        a == BodyCategory.PLANET || b == BodyCategory.PLANET -> BodyCategory.PLANET
        a == BodyCategory.MOON || b == BodyCategory.MOON -> BodyCategory.MOON
        a == BodyCategory.DWARF_PLANET || b == BodyCategory.DWARF_PLANET -> BodyCategory.DWARF_PLANET
        a == BodyCategory.TEST_OBJECT || b == BodyCategory.TEST_OBJECT -> BodyCategory.TEST_OBJECT
        a == BodyCategory.COMET || b == BodyCategory.COMET -> BodyCategory.COMET
        a == BodyCategory.ASTEROID || b == BodyCategory.ASTEROID -> BodyCategory.ASTEROID
        else -> BodyCategory.PROBE
    }

    private fun computeDiagnostics(): SystemDiagnostics {
        val totalMass = bodies.sumOf { it.massKg }
        val massiveCount = bodies.count { it.gravitationalRole == GravitationalRole.MASSIVE }
        val tracerCount = bodies.size - massiveCount

        val kinetic = bodies.sumOf { body ->
            0.5 * body.massKg * body.velocityMps.magnitudeSquared()
        }

        var potential = 0.0
        for (i in 0 until bodies.size) {
            for (j in (i + 1) until bodies.size) {
                val bodyA = bodies[i]
                val bodyB = bodies[j]
                if (bodyA.massKg == 0.0 || bodyB.massKg == 0.0) continue
                if (bodyA.gravitationalRole != GravitationalRole.MASSIVE && bodyB.gravitationalRole != GravitationalRole.MASSIVE) {
                    continue
                }

                val distance = bodyA.positionM.distanceTo(bodyB.positionM)
                if (distance == 0.0) continue
                potential -= config.gravitationalConstant * bodyA.massKg * bodyB.massKg / distance
            }
        }

        val linearMomentum = bodies.fold(Vector3d.ZERO) { acc, body ->
            acc + (body.velocityMps * body.massKg)
        }

        val angularMomentum = bodies.fold(Vector3d.ZERO) { acc, body ->
            acc + body.positionM.cross(body.velocityMps * body.massKg)
        }

        val barycenter = if (totalMass > 0.0) {
            bodies.fold(Vector3d.ZERO) { acc, body ->
                acc + (body.positionM * body.massKg)
            } / totalMass
        } else {
            Vector3d.ZERO
        }

        val baryVelocity = if (totalMass > 0.0) {
            linearMomentum / totalMass
        } else {
            Vector3d.ZERO
        }

        return SystemDiagnostics(
            totalMassKg = totalMass,
            totalKineticEnergyJ = kinetic,
            totalPotentialEnergyJ = potential,
            totalEnergyJ = kinetic + potential,
            linearMomentumKgMps = linearMomentum,
            angularMomentumKgM2PerS = angularMomentum,
            barycenterM = barycenter,
            barycenterVelocityMps = baryVelocity,
            massiveBodyCount = massiveCount,
            tracerBodyCount = tracerCount,
        )
    }

    private fun volumeFromRadius(radiusM: Double): Double = (4.0 / 3.0) * PI * radiusM * radiusM * radiusM

    private fun radiusFromCombinedVolume(volumeM3: Double): Double {
        if (volumeM3 <= 0.0) return 0.0
        return Math.cbrt((3.0 * volumeM3) / (4.0 * PI))
    }

    private fun blendColors(
        colorA: Int,
        colorB: Int,
        weightA: Double,
        weightB: Double,
    ): Int {
        val total = weightA + weightB
        val ratioA = if (total > 0.0) weightA / total else 0.5
        val ratioB = if (total > 0.0) weightB / total else 0.5

        fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF
        fun mixed(shift: Int): Int = ((channel(colorA, shift) * ratioA) + (channel(colorB, shift) * ratioB)).toInt().coerceIn(0, 255)

        return (mixed(24) shl 24) or (mixed(16) shl 16) or (mixed(8) shl 8) or mixed(0)
    }

    private fun markSandboxBranch(label: String, source: String) {
        if (timelineMode != TimelineMode.SANDBOX_BRANCH) {
            timelineMode = TimelineMode.SANDBOX_BRANCH
        }
        provenanceLabel = label
        provenanceSource = source
    }

    private fun markDiagnosticsDirty() {
        diagnosticsDirty = true
    }

    private data class CollisionCandidate(
        val i: Int,
        val j: Int,
        val timeSeconds: Double,
    )

    private data class MutableBody(
        val id: String,
        val name: String,
        val category: BodyCategory,
        val gravitationalRole: GravitationalRole,
        val massKg: Double,
        val radiusM: Double,
        val densityKgPerM3: Double,
        var positionM: Vector3d,
        var velocityMps: Vector3d,
        val colorArgb: Int,
        val hostBodyId: String?,
    ) {
        fun toState(): BodyState = BodyState(
            id = id,
            name = name,
            category = category,
            gravitationalRole = gravitationalRole,
            massKg = massKg,
            radiusM = radiusM,
            densityKgPerM3 = densityKgPerM3,
            positionM = positionM,
            velocityMps = velocityMps,
            colorArgb = colorArgb,
            hostBodyId = hostBodyId,
        )

        companion object {
            fun fromState(state: BodyState): MutableBody = MutableBody(
                id = state.id,
                name = state.name,
                category = state.category,
                gravitationalRole = state.gravitationalRole,
                massKg = state.massKg,
                radiusM = state.radiusM,
                densityKgPerM3 = state.densityKgPerM3,
                positionM = state.positionM,
                velocityMps = state.velocityMps,
                colorArgb = state.colorArgb,
                hostBodyId = state.hostBodyId,
            )
        }
    }

    private companion object {
        private const val COLLISION_TIME_EPSILON: Double = 1.0e-9
        private const val COLLISION_DISTANCE_EPSILON: Double = 1.0e-6
        private const val MAX_COLLISION_ITERATIONS_PER_STEP: Int = 1024
        private const val FRAGMENTATION_PRIMARY_MASS_FRACTION: Double = 0.5
        private const val FRAGMENTATION_VELOCITY_FRACTION: Double = 0.5
        private const val FRAGMENTATION_MIN_OFFSET_SPEED: Double = 0.5
        private const val FRAGMENTATION_SEPARATION_MULTIPLIER: Double = 1.05
    }
}
