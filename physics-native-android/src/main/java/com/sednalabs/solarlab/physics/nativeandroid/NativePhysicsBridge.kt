package com.sednalabs.solarlab.physics.nativeandroid

import com.graciousgazelles.solarlab.core.simulation.AccelerationVectorBuffers
import com.graciousgazelles.solarlab.core.simulation.MassiveSourceBuffers
import com.graciousgazelles.solarlab.core.simulation.TargetBodyBuffers

internal object NativePhysicsBridge {
    private const val LIBRARY_NAME: String = "solarlab_physics_native"

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var loadError: Throwable? = null

    fun isAvailable(): Boolean {
        ensureLoadedOrCapture()
        return loadError == null
    }

    fun backendSummary(): String {
        ensureLoaded()
        return nativeCpuBackendSummary()
    }

    fun computeMassiveAccelerations(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers {
        ensureLoaded()
        val packed = nativeComputeMassiveAccelerationsPacked(
            sourceBodyIndices = sources.bodyIndices,
            sourceMassesKg = sources.massesKg,
            sourcePosX = sources.positionX,
            sourcePosY = sources.positionY,
            sourcePosZ = sources.positionZ,
            targetBodyIndices = targets.bodyIndices,
            targetPosX = targets.positionX,
            targetPosY = targets.positionY,
            targetPosZ = targets.positionZ,
            gravitationalConstant = gravitationalConstant,
            softeningSquared = softeningSquared,
        )
        return unpackAccelerations(targets.bodyIndices, packed)
    }

    fun computeTracerAccelerations(
        sources: MassiveSourceBuffers,
        targets: TargetBodyBuffers,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): AccelerationVectorBuffers {
        ensureLoaded()
        val packed = nativeComputeTracerAccelerationsPacked(
            sourceBodyIndices = sources.bodyIndices,
            sourceMassesKg = sources.massesKg,
            sourcePosX = sources.positionX,
            sourcePosY = sources.positionY,
            sourcePosZ = sources.positionZ,
            targetBodyIndices = targets.bodyIndices,
            targetPosX = targets.positionX,
            targetPosY = targets.positionY,
            targetPosZ = targets.positionZ,
            gravitationalConstant = gravitationalConstant,
            softeningSquared = softeningSquared,
        )
        return unpackAccelerations(targets.bodyIndices, packed)
    }

    private fun unpackAccelerations(
        bodyIndices: IntArray,
        packed: DoubleArray,
    ): AccelerationVectorBuffers {
        require(packed.size == bodyIndices.size * 3) {
            "Expected ${bodyIndices.size * 3} packed acceleration values, got ${packed.size}"
        }
        val accelerationX = DoubleArray(bodyIndices.size)
        val accelerationY = DoubleArray(bodyIndices.size)
        val accelerationZ = DoubleArray(bodyIndices.size)
        for (index in bodyIndices.indices) {
            val offset = index * 3
            accelerationX[index] = packed[offset]
            accelerationY[index] = packed[offset + 1]
            accelerationZ[index] = packed[offset + 2]
        }
        return AccelerationVectorBuffers(
            bodyIndices = bodyIndices,
            accelerationX = accelerationX,
            accelerationY = accelerationY,
            accelerationZ = accelerationZ,
        )
    }

    private fun ensureLoadedOrCapture() {
        if (loadAttempted) return
        synchronized(this) {
            if (loadAttempted) return
            loadAttempted = true
            loadError = try {
                System.loadLibrary(LIBRARY_NAME)
                null
            } catch (error: Throwable) {
                error
            }
        }
    }

    private fun ensureLoaded() {
        ensureLoadedOrCapture()
        check(loadError == null) { "Failed to load native physics bridge: ${loadError?.message}" }
    }

    private external fun nativeCpuBackendSummary(): String

    private external fun nativeComputeMassiveAccelerationsPacked(
        sourceBodyIndices: IntArray,
        sourceMassesKg: DoubleArray,
        sourcePosX: DoubleArray,
        sourcePosY: DoubleArray,
        sourcePosZ: DoubleArray,
        targetBodyIndices: IntArray,
        targetPosX: DoubleArray,
        targetPosY: DoubleArray,
        targetPosZ: DoubleArray,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): DoubleArray

    private external fun nativeComputeTracerAccelerationsPacked(
        sourceBodyIndices: IntArray,
        sourceMassesKg: DoubleArray,
        sourcePosX: DoubleArray,
        sourcePosY: DoubleArray,
        sourcePosZ: DoubleArray,
        targetBodyIndices: IntArray,
        targetPosX: DoubleArray,
        targetPosY: DoubleArray,
        targetPosZ: DoubleArray,
        gravitationalConstant: Double,
        softeningSquared: Double,
    ): DoubleArray
}
