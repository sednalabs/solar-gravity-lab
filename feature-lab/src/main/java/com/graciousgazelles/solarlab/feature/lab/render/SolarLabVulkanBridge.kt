package com.graciousgazelles.solarlab.feature.lab.render

import android.content.res.AssetManager
import android.view.Surface
import com.graciousgazelles.solarlab.render.core.NativeFrameState
import com.graciousgazelles.solarlab.render.core.NativeScenePacket
import com.graciousgazelles.solarlab.render.core.NativeSceneSeedPacket

internal object SolarLabVulkanBridge {
    private const val LIBRARY_NAME = "solarlab_vulkan"

    val isLibraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
        }.isSuccess
    }
    private var lastSubmittedHandle: Long = 0L
    private var lastSubmittedSeed: NativeSceneSeedPacket? = null

    fun isRuntimeAvailable(): Boolean = isLibraryLoaded && nativeIsVulkanRuntimeAvailable()

    fun cpuCapabilitySummary(): String =
        if (isLibraryLoaded) nativeGetCpuCapabilitySummary() else "cpu=native-library-unavailable"

    fun createRenderer(assetManager: AssetManager): Long = if (isLibraryLoaded) nativeCreateRenderer(assetManager) else 0L

    fun destroyRenderer(handle: Long) {
        if (isLibraryLoaded && handle != 0L) {
            clearSubmissionCache(handle)
            nativeDestroyRenderer(handle)
        }
    }

    fun onSurfaceCreated(handle: Long, surface: Surface, width: Int, height: Int): Boolean {
        if (!isLibraryLoaded || handle == 0L) return false
        val created = nativeOnSurfaceCreated(handle, surface, width, height)
        if (created) {
            clearSubmissionCache(handle)
        }
        return created
    }

    fun onSurfaceChanged(handle: Long, surface: Surface, width: Int, height: Int): Boolean {
        if (!isLibraryLoaded || handle == 0L) return false
        val changed = nativeOnSurfaceChanged(handle, surface, width, height)
        if (changed) {
            clearSubmissionCache(handle)
        }
        return changed
    }

    fun onSurfaceDestroyed(handle: Long) {
        if (isLibraryLoaded && handle != 0L) {
            clearSubmissionCache(handle)
            nativeOnSurfaceDestroyed(handle)
        }
    }

    fun submitScene(handle: Long, packet: NativeScenePacket) {
        if (!isLibraryLoaded || handle == 0L) return
        val seedPacket = packet.toSceneSeedPacket()
        if (handle != lastSubmittedHandle || !seedPacket.seedContentMatches(lastSubmittedSeed)) {
            nativeSubmitSceneSeed(
                handle = handle,
                sourceRevision = seedPacket.sourceRevision,
                tracerMediumHandles = seedPacket.tracerMediumHandles,
                tracerMediumPositionsM = seedPacket.tracerMediumPositionsM,
                tracerMediumVelocitiesMps = seedPacket.tracerMediumVelocitiesMps,
                tracerMediumRadiiM = seedPacket.tracerMediumRadiiM,
                tracerMediumColorsArgb = seedPacket.tracerMediumColorsArgb,
                tracerMediumKinds = seedPacket.tracerMediumKinds,
                tracerFarHandles = seedPacket.tracerFarHandles,
                tracerFarPositionsM = seedPacket.tracerFarPositionsM,
                tracerFarVelocitiesMps = seedPacket.tracerFarVelocitiesMps,
                tracerFarRadiiM = seedPacket.tracerFarRadiiM,
                tracerFarColorsArgb = seedPacket.tracerFarColorsArgb,
                tracerFarKinds = seedPacket.tracerFarKinds,
            )
            lastSubmittedHandle = handle
            lastSubmittedSeed = seedPacket
        }
    }

    fun setFrameState(handle: Long, frameState: NativeFrameState) {
        if (!isLibraryLoaded || handle == 0L) return
        nativeSetFrameState(
            handle = handle,
            sourceRevision = frameState.sourceRevision,
            epochSeconds = frameState.epochSeconds,
            simulationAdvanceSeconds = frameState.simulationAdvanceSeconds,
            includeTracerMutualGravity = frameState.includeTracerMutualGravity,
            authoritativePositionsM = frameState.authoritativePositionsM,
            authoritativeSourceMassesKg = frameState.authoritativeSourceMassesKg,
            authoritativeRadiiM = frameState.authoritativeRadiiM,
            authoritativeColorsArgb = frameState.authoritativeColorsArgb,
            authoritativeKinds = frameState.authoritativeKinds,
            tracerNearPositionsM = frameState.tracerNearPositionsM,
            tracerNearSourceMassesKg = frameState.tracerNearSourceMassesKg,
            tracerNearRadiiM = frameState.tracerNearRadiiM,
            tracerNearColorsArgb = frameState.tracerNearColorsArgb,
            tracerNearKinds = frameState.tracerNearKinds,
            tracerMediumHandles = frameState.tracerMediumHandles,
            tracerMediumPositionsM = frameState.tracerMediumPositionsM,
            tracerMediumSourceMassesKg = frameState.tracerMediumSourceMassesKg,
            tracerFarHandles = frameState.tracerFarHandles,
            tracerFarPositionsM = frameState.tracerFarPositionsM,
            tracerFarSourceMassesKg = frameState.tracerFarSourceMassesKg,
            trailPositionsM = frameState.trailPositionsM,
            trailColorsArgb = frameState.trailColorsArgb,
            trailVertexCounts = frameState.trailVertexCounts,
        )
    }

    fun setCamera(handle: Long, centerX: Double, centerY: Double, centerZ: Double, viewRadiusM: Double) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetCamera(handle, centerX, centerY, centerZ, viewRadiusM)
        }
    }

    fun render(handle: Long): Boolean = isLibraryLoaded && handle != 0L && nativeRender(handle)

    fun lastError(handle: Long): String =
        if (isLibraryLoaded && handle != 0L) nativeGetLastError(handle) else "Native Vulkan library is not loaded."

    fun backendLabel(handle: Long): String =
        if (isLibraryLoaded && handle != 0L) nativeGetBackendLabel(handle) else "Vulkan runtime unavailable"

    fun sceneSummary(handle: Long): String =
        if (isLibraryLoaded && handle != 0L) nativeGetSceneSummary(handle) else "Scene summary unavailable"

    fun hardwareSummary(handle: Long): String =
        if (isLibraryLoaded && handle != 0L) nativeGetHardwareSummary(handle) else "gpu=vulkan-runtime-unavailable"

    private fun clearSubmissionCache(handle: Long) {
        if (lastSubmittedHandle != handle) return
        lastSubmittedHandle = 0L
        lastSubmittedSeed = null
    }

    private fun NativeSceneSeedPacket.seedContentMatches(other: NativeSceneSeedPacket?): Boolean {
        if (other == null) return false
        if (this === other) return true
        if (sourceRevision < other.sourceRevision) {
            return false
        }
        return tracerMediumHandles.contentEquals(other.tracerMediumHandles) &&
            tracerMediumRadiiM.contentEquals(other.tracerMediumRadiiM) &&
            tracerMediumColorsArgb.contentEquals(other.tracerMediumColorsArgb) &&
            tracerMediumKinds.contentEquals(other.tracerMediumKinds) &&
            tracerFarHandles.contentEquals(other.tracerFarHandles) &&
            tracerFarRadiiM.contentEquals(other.tracerFarRadiiM) &&
            tracerFarColorsArgb.contentEquals(other.tracerFarColorsArgb) &&
            tracerFarKinds.contentEquals(other.tracerFarKinds)
    }

    private external fun nativeIsVulkanRuntimeAvailable(): Boolean
    private external fun nativeGetCpuCapabilitySummary(): String
    private external fun nativeCreateRenderer(assetManager: AssetManager): Long
    private external fun nativeDestroyRenderer(handle: Long)
    private external fun nativeOnSurfaceCreated(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceChanged(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceDestroyed(handle: Long)
    private external fun nativeSubmitSceneSeed(
        handle: Long,
        sourceRevision: Long,
        tracerMediumHandles: LongArray,
        tracerMediumPositionsM: DoubleArray,
        tracerMediumVelocitiesMps: DoubleArray,
        tracerMediumRadiiM: FloatArray,
        tracerMediumColorsArgb: IntArray,
        tracerMediumKinds: IntArray,
        tracerFarHandles: LongArray,
        tracerFarPositionsM: DoubleArray,
        tracerFarVelocitiesMps: DoubleArray,
        tracerFarRadiiM: FloatArray,
        tracerFarColorsArgb: IntArray,
        tracerFarKinds: IntArray,
    )
    private external fun nativeSetFrameState(
        handle: Long,
        sourceRevision: Long,
        epochSeconds: Double,
        simulationAdvanceSeconds: Double,
        includeTracerMutualGravity: Boolean,
        authoritativePositionsM: DoubleArray,
        authoritativeSourceMassesKg: DoubleArray,
        authoritativeRadiiM: FloatArray,
        authoritativeColorsArgb: IntArray,
        authoritativeKinds: IntArray,
        tracerNearPositionsM: DoubleArray,
        tracerNearSourceMassesKg: DoubleArray,
        tracerNearRadiiM: FloatArray,
        tracerNearColorsArgb: IntArray,
        tracerNearKinds: IntArray,
        tracerMediumHandles: LongArray,
        tracerMediumPositionsM: DoubleArray,
        tracerMediumSourceMassesKg: DoubleArray,
        tracerFarHandles: LongArray,
        tracerFarPositionsM: DoubleArray,
        tracerFarSourceMassesKg: DoubleArray,
        trailPositionsM: DoubleArray,
        trailColorsArgb: IntArray,
        trailVertexCounts: IntArray,
    )
    private external fun nativeSetCamera(handle: Long, centerX: Double, centerY: Double, centerZ: Double, viewRadiusM: Double)
    private external fun nativeRender(handle: Long): Boolean
    private external fun nativeGetLastError(handle: Long): String
    private external fun nativeGetBackendLabel(handle: Long): String
    private external fun nativeGetSceneSummary(handle: Long): String
    private external fun nativeGetHardwareSummary(handle: Long): String
}
