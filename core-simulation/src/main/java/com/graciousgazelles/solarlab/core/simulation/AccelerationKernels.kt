package com.graciousgazelles.solarlab.core.simulation

import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.BodyState
import com.graciousgazelles.solarlab.core.model.GravitationalRole
import kotlin.math.sqrt

internal data class SolverBodyState(
    val bodyIndex: Int,
    val gravitationalRole: GravitationalRole,
    val massKg: Double,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
)

internal data class MassiveSourceBuffers(
    val bodyIndices: IntArray,
    val massesKg: DoubleArray,
    val positionX: DoubleArray,
    val positionY: DoubleArray,
    val positionZ: DoubleArray,
) {
    val count: Int
        get() = bodyIndices.size
}

internal data class TargetBodyBuffers(
    val bodyIndices: IntArray,
    val positionX: DoubleArray,
    val positionY: DoubleArray,
    val positionZ: DoubleArray,
) {
    val count: Int
        get() = bodyIndices.size
}

internal data class AccelerationVectorBuffers(
    val bodyIndices: IntArray,
    val accelerationX: DoubleArray,
    val accelerationY: DoubleArray,
    val accelerationZ: DoubleArray,
) {
    val count: Int
        get() = bodyIndices.size
}

internal interface MassiveAccelerationKernel {
    fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers
}

internal interface TracerAccelerationKernel {
    fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers
}

internal object AccelerationKernelBufferFactory {
    fun fromBodyStates(bodies: List<BodyState>): List<SolverBodyState> = bodies.mapIndexed { index, body ->
        SolverBodyState(
            bodyIndex = index,
            gravitationalRole = body.gravitationalRole,
            massKg = body.massKg,
            positionX = body.positionM.x,
            positionY = body.positionM.y,
            positionZ = body.positionM.z,
        )
    }

    fun buildMassiveSourceBuffers(bodies: List<SolverBodyState>): MassiveSourceBuffers {
        val sources = bodies.filter { it.gravitationalRole == GravitationalRole.MASSIVE }
        val count = sources.size
        val bodyIndices = IntArray(count)
        val massesKg = DoubleArray(count)
        val positionX = DoubleArray(count)
        val positionY = DoubleArray(count)
        val positionZ = DoubleArray(count)

        for (index in sources.indices) {
            val source = sources[index]
            bodyIndices[index] = source.bodyIndex
            massesKg[index] = source.massKg
            positionX[index] = source.positionX
            positionY[index] = source.positionY
            positionZ[index] = source.positionZ
        }

        return MassiveSourceBuffers(
            bodyIndices = bodyIndices,
            massesKg = massesKg,
            positionX = positionX,
            positionY = positionY,
            positionZ = positionZ,
        )
    }

    fun buildTargetBuffers(
        bodies: List<SolverBodyState>,
        role: GravitationalRole,
    ): TargetBodyBuffers {
        val targets = bodies.filter { it.gravitationalRole == role }
        val count = targets.size
        val bodyIndices = IntArray(count)
        val positionX = DoubleArray(count)
        val positionY = DoubleArray(count)
        val positionZ = DoubleArray(count)

        for (index in targets.indices) {
            val target = targets[index]
            bodyIndices[index] = target.bodyIndex
            positionX[index] = target.positionX
            positionY[index] = target.positionY
            positionZ[index] = target.positionZ
        }

        return TargetBodyBuffers(
            bodyIndices = bodyIndices,
            positionX = positionX,
            positionY = positionY,
            positionZ = positionZ,
        )
    }
}

internal object DirectMassiveAccelerationKernel : MassiveAccelerationKernel {
    override fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers {
        val accelerationX = DoubleArray(targets.count)
        val accelerationY = DoubleArray(targets.count)
        val accelerationZ = DoubleArray(targets.count)

        for (targetIndex in targets.bodyIndices.indices) {
            val bodyIndex = targets.bodyIndices[targetIndex]
            val bodyX = targets.positionX[targetIndex]
            val bodyY = targets.positionY[targetIndex]
            val bodyZ = targets.positionZ[targetIndex]
            var ax = 0.0
            var ay = 0.0
            var az = 0.0

            for (sourceIndex in sources.bodyIndices.indices) {
                if (sources.bodyIndices[sourceIndex] == bodyIndex) continue

                val dx = sources.positionX[sourceIndex] - bodyX
                val dy = sources.positionY[sourceIndex] - bodyY
                val dz = sources.positionZ[sourceIndex] - bodyZ
                val distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared
                if (distanceSquared == 0.0) continue

                val invDistance = 1.0 / sqrt(distanceSquared)
                val invDistanceCubed = invDistance * invDistance * invDistance
                val scale = gravitationalConstant * sources.massesKg[sourceIndex] * invDistanceCubed

                ax += dx * scale
                ay += dy * scale
                az += dz * scale
            }

            accelerationX[targetIndex] = ax
            accelerationY[targetIndex] = ay
            accelerationZ[targetIndex] = az
        }

        return AccelerationVectorBuffers(
            bodyIndices = targets.bodyIndices,
            accelerationX = accelerationX,
            accelerationY = accelerationY,
            accelerationZ = accelerationZ,
        )
    }
}

internal object DirectTracerAccelerationKernel : TracerAccelerationKernel {
    override fun compute(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers {
        val accelerationX = DoubleArray(targets.count)
        val accelerationY = DoubleArray(targets.count)
        val accelerationZ = DoubleArray(targets.count)

        for (targetIndex in targets.bodyIndices.indices) {
            val bodyX = targets.positionX[targetIndex]
            val bodyY = targets.positionY[targetIndex]
            val bodyZ = targets.positionZ[targetIndex]
            var ax = 0.0
            var ay = 0.0
            var az = 0.0

            for (sourceIndex in sources.bodyIndices.indices) {
                val dx = sources.positionX[sourceIndex] - bodyX
                val dy = sources.positionY[sourceIndex] - bodyY
                val dz = sources.positionZ[sourceIndex] - bodyZ
                val distanceSquared = (dx * dx) + (dy * dy) + (dz * dz) + softeningSquared
                if (distanceSquared == 0.0) continue

                val invDistance = 1.0 / sqrt(distanceSquared)
                val invDistanceCubed = invDistance * invDistance * invDistance
                val scale = gravitationalConstant * sources.massesKg[sourceIndex] * invDistanceCubed

                ax += dx * scale
                ay += dy * scale
                az += dz * scale
            }

            accelerationX[targetIndex] = ax
            accelerationY[targetIndex] = ay
            accelerationZ[targetIndex] = az
        }

        return AccelerationVectorBuffers(
            bodyIndices = targets.bodyIndices,
            accelerationX = accelerationX,
            accelerationY = accelerationY,
            accelerationZ = accelerationZ,
        )
    }
}

internal fun AccelerationVectorBuffers.toVectorsByBodyIndex(bodyCount: Int): List<Vector3d> {
    val vectors = MutableList(bodyCount) { Vector3d.ZERO }
    for (index in bodyIndices.indices) {
        vectors[bodyIndices[index]] = Vector3d(
            accelerationX[index],
            accelerationY[index],
            accelerationZ[index],
        )
    }
    return vectors
}
