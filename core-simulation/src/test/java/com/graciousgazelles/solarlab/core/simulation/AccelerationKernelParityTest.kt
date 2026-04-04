package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyCategory
import com.graciousgazelles.solarlab.core.model.BodyFactory
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.DensityPreset
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.core.model.SimulationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AccelerationKernelParityTest {

    @Test
    fun `split kernels match legacy mixed acceleration loop`() {
        val config = SimulationConfig()
        val bodies = mixedGravityScenario()
        val solverBodies = AccelerationKernelBufferFactory.fromBodyStates(bodies)
        val sources = AccelerationKernelBufferFactory.buildGravitySourceBuffers(
            bodies = solverBodies,
            includeTracerSources = false,
        )

        val splitAccelerations = MutableList(bodies.size) { Vector3d.ZERO }
        applyOutput(
            splitAccelerations,
            DirectMassiveAccelerationKernel.compute(
                sources = sources,
                targets = AccelerationKernelBufferFactory.buildTargetBuffers(solverBodies, GravitationalRole.MASSIVE),
                gravitationalConstant = config.gravitationalConstant,
                softeningSquared = config.softeningLengthM * config.softeningLengthM,
            ),
        )
        applyOutput(
            splitAccelerations,
            DirectTracerAccelerationKernel.compute(
                sources = sources,
                targets = AccelerationKernelBufferFactory.buildTargetBuffers(solverBodies, GravitationalRole.TRACER),
                gravitationalConstant = config.gravitationalConstant,
                softeningSquared = config.softeningLengthM * config.softeningLengthM,
            ),
        )

        val legacyAccelerations = legacyMixedLoopAccelerations(bodies, config)
        for (index in bodies.indices) {
            assertEquals(legacyAccelerations[index].x, splitAccelerations[index].x, 1e-18)
            assertEquals(legacyAccelerations[index].y, splitAccelerations[index].y, 1e-18)
            assertEquals(legacyAccelerations[index].z, splitAccelerations[index].z, 1e-18)
        }
    }

    @Test
    fun `split kernels return zero output when there are no massive sources`() {
        val config = SimulationConfig()
        val tracerBodies = listOf(
            BodyFactory.sphericalBody(
                id = "probe-a",
                name = "Probe A",
                category = BodyCategory.PROBE,
                gravitationalRole = GravitationalRole.TRACER,
                massKg = 1.0,
                densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
                positionM = Vector3d(0.0, 0.0, 0.0),
                velocityMps = Vector3d.ZERO,
                colorArgb = 0xFFFFFFFF.toInt(),
            ),
            BodyFactory.sphericalBody(
                id = "probe-b",
                name = "Probe B",
                category = BodyCategory.PROBE,
                gravitationalRole = GravitationalRole.TRACER,
                massKg = 1.0,
                densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
                positionM = Vector3d(100.0, 0.0, 0.0),
                velocityMps = Vector3d.ZERO,
                colorArgb = 0xFFFFFFFF.toInt(),
            ),
        )

        val solverBodies = AccelerationKernelBufferFactory.fromBodyStates(tracerBodies)
        val sources = AccelerationKernelBufferFactory.buildGravitySourceBuffers(
            bodies = solverBodies,
            includeTracerSources = false,
        )
        val tracerTargets = AccelerationKernelBufferFactory.buildTargetBuffers(solverBodies, GravitationalRole.TRACER)
        val accelerations = DirectTracerAccelerationKernel.compute(
            sources = sources,
            targets = tracerTargets,
            gravitationalConstant = config.gravitationalConstant,
            softeningSquared = config.softeningLengthM * config.softeningLengthM,
        )

        assertTrue(sources.count == 0)
        for (vector in accelerations.toVectorsByBodyIndex(tracerBodies.size)) {
            assertEquals(0.0, vector.x, 0.0)
            assertEquals(0.0, vector.y, 0.0)
            assertEquals(0.0, vector.z, 0.0)
        }
    }

    @Test
    fun `gravity source buffers can include tracers when tracer mutual gravity is enabled`() {
        val bodies = mixedGravityScenario()
        val solverBodies = AccelerationKernelBufferFactory.fromBodyStates(bodies)

        val sources = AccelerationKernelBufferFactory.buildGravitySourceBuffers(
            bodies = solverBodies,
            includeTracerSources = true,
        )

        assertEquals(bodies.size, sources.count)
    }

    private fun applyOutput(
        accelerations: MutableList<Vector3d>,
        output: AccelerationVectorBuffers,
    ) {
        for (index in output.bodyIndices.indices) {
            accelerations[output.bodyIndices[index]] = Vector3d(
                output.accelerationX[index],
                output.accelerationY[index],
                output.accelerationZ[index],
            )
        }
    }

    private fun legacyMixedLoopAccelerations(
        bodies: List<BodyState>,
        config: SimulationConfig,
    ): List<Vector3d> {
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
            val bodyX = body.positionM.x
            val bodyY = body.positionM.y
            val bodyZ = body.positionM.z
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

    private fun mixedGravityScenario(): List<BodyState> {
        val sun = BodyFactory.sphericalBody(
            id = "sun",
            name = "Sun",
            category = BodyCategory.STAR,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 1.98847e30,
            densityKgPerM3 = DensityPreset.GAS_GIANT_KG_PER_M3,
            positionM = Vector3d.ZERO,
            velocityMps = Vector3d.ZERO,
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val earth = BodyFactory.sphericalBody(
            id = "earth",
            name = "Earth",
            category = BodyCategory.PLANET,
            gravitationalRole = GravitationalRole.MASSIVE,
            massKg = 5.972168e24,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0, 0.0),
            velocityMps = Vector3d(0.0, 29_780.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val moonTracer = BodyFactory.sphericalBody(
            id = "moon-tracer",
            name = "Moon tracer",
            category = BodyCategory.MOON,
            gravitationalRole = GravitationalRole.TRACER,
            massKg = 7.342e22,
            densityKgPerM3 = DensityPreset.ROCKY_KG_PER_M3,
            positionM = Vector3d(
                PhysicalConstants.ASTRONOMICAL_UNIT_M + 384_400_000.0,
                0.0,
                0.0,
            ),
            velocityMps = Vector3d(0.0, 30_802.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        val probeTracer = BodyFactory.sphericalBody(
            id = "probe",
            name = "Probe",
            category = BodyCategory.PROBE,
            gravitationalRole = GravitationalRole.TRACER,
            massKg = 10.0,
            densityKgPerM3 = DensityPreset.METALLIC_KG_PER_M3,
            positionM = Vector3d(0.7 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.2 * PhysicalConstants.ASTRONOMICAL_UNIT_M, 0.0),
            velocityMps = Vector3d(-8_000.0, 22_000.0, 0.0),
            colorArgb = 0xFFFFFFFF.toInt(),
        )
        return listOf(sun, earth, moonTracer, probeTracer)
    }
}
