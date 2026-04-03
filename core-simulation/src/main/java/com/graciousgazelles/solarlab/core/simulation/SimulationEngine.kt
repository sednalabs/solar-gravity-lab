package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.CollisionMode
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

class SimulationEngine(
    initialSnapshot: SimulationSnapshot,
    private val config: SimulationConfig = SimulationConfig(),
) {

    private var epochSeconds: Double = initialSnapshot.epochSeconds
    private var bodies: MutableList<MutableBody> = initialSnapshot.bodies.map(MutableBody::fromState).toMutableList()

    fun snapshot(): SimulationSnapshot = SimulationSnapshot(
        epochSeconds = epochSeconds,
        bodies = bodies.map { it.toState() },
    )

    fun diagnostics(): SystemDiagnostics = computeDiagnostics()

    fun reset(snapshot: SimulationSnapshot) {
        epochSeconds = snapshot.epochSeconds
        bodies = snapshot.bodies.map(MutableBody::fromState).toMutableList()
    }

    fun step(deltaTimeSeconds: Double): SimulationStepResult {
        require(deltaTimeSeconds > 0.0) { "deltaTimeSeconds must be > 0" }

        val firstAccelerations = computeAccelerations()
        val halfDelta = deltaTimeSeconds * 0.5

        for (index in bodies.indices) {
            val body = bodies[index]
            body.velocityMps = body.velocityMps + (firstAccelerations[index] * halfDelta)
        }

        for (body in bodies) {
            body.positionM = body.positionM + (body.velocityMps * deltaTimeSeconds)
        }

        val collisions = when (config.collisionMode) {
            CollisionMode.NONE -> emptyList()
            CollisionMode.MERGE -> resolveMergeCollisions()
        }

        val secondAccelerations = computeAccelerations()

        for (index in bodies.indices) {
            val body = bodies[index]
            body.velocityMps = body.velocityMps + (secondAccelerations[index] * halfDelta)
        }

        epochSeconds += deltaTimeSeconds

        return SimulationStepResult(
            snapshot = snapshot(),
            diagnostics = computeDiagnostics(),
            collisions = collisions,
        )
    }

    private fun computeAccelerations(): List<Vector3d> {
        if (bodies.isEmpty()) return emptyList()

        val softeningSquared = config.softeningLengthM * config.softeningLengthM
        val accelerations = MutableList(bodies.size) { Vector3d.ZERO }
        val massiveIndices = bodies.indices.filter { bodies[it].gravitationalRole == GravitationalRole.MASSIVE }

        for (i in bodies.indices) {
            val body = bodies[i]
            var acceleration = Vector3d.ZERO

            for (j in massiveIndices) {
                if (i == j) continue
                val sourceBody = bodies[j]
                val delta = sourceBody.positionM - body.positionM
                val distanceSquared = delta.magnitudeSquared() + softeningSquared
                if (distanceSquared == 0.0) continue

                val invDistance = 1.0 / sqrt(distanceSquared)
                val invDistanceCubed = invDistance * invDistance * invDistance
                val sourceMass = sourceBody.massKg
                val contribution = delta * (config.gravitationalConstant * sourceMass * invDistanceCubed)
                acceleration += contribution
            }

            accelerations[i] = acceleration
        }

        return accelerations
    }

    private fun resolveMergeCollisions(): List<CollisionEvent> {
        val alive = BooleanArray(bodies.size) { true }
        val collisions = mutableListOf<CollisionEvent>()

        for (i in bodies.indices) {
            if (!alive[i]) continue
            var primary = bodies[i]

            for (j in (i + 1) until bodies.size) {
                if (!alive[j]) continue
                val secondary = bodies[j]
                val combinedRadius = primary.radiusM + secondary.radiusM
                if (combinedRadius <= 0.0) continue

                val distanceSquared = primary.positionM.distanceSquaredTo(secondary.positionM)
                if (distanceSquared > combinedRadius * combinedRadius) continue

                val merged = mergeBodies(primary, secondary)
                val event = CollisionEvent(
                    primaryBodyId = primary.id,
                    secondaryBodyId = secondary.id,
                    mergedBodyId = merged.id,
                    mergedBodyName = merged.name,
                )

                primary = merged
                bodies[i] = merged
                alive[j] = false
                collisions += event
            }
        }

        if (collisions.isNotEmpty()) {
            bodies = bodies.filterIndexed { index, _ -> alive[index] }.toMutableList()
        }

        return collisions
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
        )
    }

    private fun chooseCategory(
        a: BodyCategory,
        b: BodyCategory,
    ): BodyCategory = when {
        a == BodyCategory.STAR || b == BodyCategory.STAR -> BodyCategory.STAR
        a == BodyCategory.PLANET || b == BodyCategory.PLANET -> BodyCategory.PLANET
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
            )
        }
    }
}
