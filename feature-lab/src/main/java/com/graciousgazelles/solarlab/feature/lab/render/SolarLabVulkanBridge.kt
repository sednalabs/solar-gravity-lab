package com.graciousgazelles.solarlab.feature.lab.render

import android.content.res.AssetManager
import android.view.Surface
import com.graciousgazelles.solarlab.render.core.NativeScenePacket

internal object SolarLabVulkanBridge {
    private const val LIBRARY_NAME = "solarlab_vulkan"

    val isLibraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
        }.isSuccess
    }
    private var lastSubmittedHandle: Long = 0L
    private var lastSubmittedPacket: NativeScenePacket? = null

    fun isRuntimeAvailable(): Boolean = isLibraryLoaded && nativeIsVulkanRuntimeAvailable()

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
        if (handle == lastSubmittedHandle && packet.contentMatches(lastSubmittedPacket)) {
            return
        }
        nativeSubmitScene(
            handle = handle,
            sourceRevision = packet.sourceRevision,
            authoritativePositionsM = packet.authoritativePositionsM,
            authoritativeRadiiM = packet.authoritativeRadiiM,
            authoritativeColorsArgb = packet.authoritativeColorsArgb,
            authoritativeKinds = packet.authoritativeKinds,
            tracerNearPositionsM = packet.tracerNearPositionsM,
            tracerNearRadiiM = packet.tracerNearRadiiM,
            tracerNearColorsArgb = packet.tracerNearColorsArgb,
            tracerNearKinds = packet.tracerNearKinds,
            tracerMediumPositionsM = packet.tracerMediumPositionsM,
            tracerMediumRadiiM = packet.tracerMediumRadiiM,
            tracerMediumColorsArgb = packet.tracerMediumColorsArgb,
            tracerMediumKinds = packet.tracerMediumKinds,
            tracerFarPositionsM = packet.tracerFarPositionsM,
            tracerFarRadiiM = packet.tracerFarRadiiM,
            tracerFarColorsArgb = packet.tracerFarColorsArgb,
            tracerFarKinds = packet.tracerFarKinds,
            trailPositionsM = packet.trailPositionsM,
            trailColorsArgb = packet.trailColorsArgb,
            trailVertexCounts = packet.trailVertexCounts,
        )
        lastSubmittedHandle = handle
        lastSubmittedPacket = packet
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

    private fun clearSubmissionCache(handle: Long) {
        if (lastSubmittedHandle != handle) return
        lastSubmittedHandle = 0L
        lastSubmittedPacket = null
    }

    private fun NativeScenePacket.contentMatches(other: NativeScenePacket?): Boolean {
        if (other == null) return false
        if (this === other) return true
        return sourceRevision == other.sourceRevision &&
            authoritativePositionsM.contentEquals(other.authoritativePositionsM) &&
            authoritativeRadiiM.contentEquals(other.authoritativeRadiiM) &&
            authoritativeColorsArgb.contentEquals(other.authoritativeColorsArgb) &&
            authoritativeKinds.contentEquals(other.authoritativeKinds) &&
            tracerNearPositionsM.contentEquals(other.tracerNearPositionsM) &&
            tracerNearRadiiM.contentEquals(other.tracerNearRadiiM) &&
            tracerNearColorsArgb.contentEquals(other.tracerNearColorsArgb) &&
            tracerNearKinds.contentEquals(other.tracerNearKinds) &&
            tracerMediumPositionsM.contentEquals(other.tracerMediumPositionsM) &&
            tracerMediumRadiiM.contentEquals(other.tracerMediumRadiiM) &&
            tracerMediumColorsArgb.contentEquals(other.tracerMediumColorsArgb) &&
            tracerMediumKinds.contentEquals(other.tracerMediumKinds) &&
            tracerFarPositionsM.contentEquals(other.tracerFarPositionsM) &&
            tracerFarRadiiM.contentEquals(other.tracerFarRadiiM) &&
            tracerFarColorsArgb.contentEquals(other.tracerFarColorsArgb) &&
            tracerFarKinds.contentEquals(other.tracerFarKinds) &&
            trailPositionsM.contentEquals(other.trailPositionsM) &&
            trailColorsArgb.contentEquals(other.trailColorsArgb) &&
            trailVertexCounts.contentEquals(other.trailVertexCounts)
    }

    private external fun nativeIsVulkanRuntimeAvailable(): Boolean
    private external fun nativeCreateRenderer(assetManager: AssetManager): Long
    private external fun nativeDestroyRenderer(handle: Long)
    private external fun nativeOnSurfaceCreated(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceChanged(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceDestroyed(handle: Long)
    private external fun nativeSubmitScene(
        handle: Long,
        sourceRevision: Long,
        authoritativePositionsM: DoubleArray,
        authoritativeRadiiM: FloatArray,
        authoritativeColorsArgb: IntArray,
        authoritativeKinds: IntArray,
        tracerNearPositionsM: DoubleArray,
        tracerNearRadiiM: FloatArray,
        tracerNearColorsArgb: IntArray,
        tracerNearKinds: IntArray,
        tracerMediumPositionsM: DoubleArray,
        tracerMediumRadiiM: FloatArray,
        tracerMediumColorsArgb: IntArray,
        tracerMediumKinds: IntArray,
        tracerFarPositionsM: DoubleArray,
        tracerFarRadiiM: FloatArray,
        tracerFarColorsArgb: IntArray,
        tracerFarKinds: IntArray,
        trailPositionsM: DoubleArray,
        trailColorsArgb: IntArray,
        trailVertexCounts: IntArray,
    )
    private external fun nativeSetCamera(handle: Long, centerX: Double, centerY: Double, centerZ: Double, viewRadiusM: Double)
    private external fun nativeRender(handle: Long): Boolean
    private external fun nativeGetLastError(handle: Long): String
    private external fun nativeGetBackendLabel(handle: Long): String
    private external fun nativeGetSceneSummary(handle: Long): String
}
