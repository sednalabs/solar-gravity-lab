package com.graciousgazelles.solarlab.feature.lab.render

import android.content.res.AssetManager
import android.view.Surface
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraState
import com.graciousgazelles.solarlab.render.core.NativeScenePacket
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.TraceLayerMode

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
        if (handle == lastSubmittedHandle && packet.contentMatches(lastSubmittedPacket)) {
            return
        }
        nativeSubmitScene(
            handle = handle,
            sourceRevision = packet.sourceRevision,
            sceneOriginXM = packet.sceneOriginXM,
            sceneOriginYM = packet.sceneOriginYM,
            sceneOriginZM = packet.sceneOriginZM,
            authoritativePositionsM = packet.authoritativePositionsM,
            authoritativeSourceMassesKg = packet.authoritativeSourceMassesKg,
            authoritativeRadiiM = packet.authoritativeRadiiM,
            authoritativeColorsArgb = packet.authoritativeColorsArgb,
            authoritativeKinds = packet.authoritativeKinds,
            tracerNearPositionsM = packet.tracerNearPositionsM,
            tracerNearRadiiM = packet.tracerNearRadiiM,
            tracerNearColorsArgb = packet.tracerNearColorsArgb,
            tracerNearKinds = packet.tracerNearKinds,
            tracerMediumPositionsM = packet.tracerMediumPositionsM,
            tracerMediumVelocitiesMps = packet.tracerMediumVelocitiesMps,
            tracerMediumStableIds = packet.tracerMediumStableIds,
            tracerMediumRadiiM = packet.tracerMediumRadiiM,
            tracerMediumColorsArgb = packet.tracerMediumColorsArgb,
            tracerMediumKinds = packet.tracerMediumKinds,
            tracerFarPositionsM = packet.tracerFarPositionsM,
            tracerFarVelocitiesMps = packet.tracerFarVelocitiesMps,
            tracerFarStableIds = packet.tracerFarStableIds,
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

    fun setCamera(
        handle: Long,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        viewRadiusM: Double,
        yawRadians: Double,
        pitchRadians: Double,
    ) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetCamera(handle, centerX, centerY, centerZ, viewRadiusM, yawRadians, pitchRadians)
        }
    }

    fun cameraState(handle: Long): CameraState? {
        if (!isLibraryLoaded || handle == 0L) return null
        return nativeGetCameraState(handle)?.toCameraState()
    }

    fun resolveRuntimeHomeCamera(handle: Long): CameraState? {
        if (!isLibraryLoaded || handle == 0L) return null
        return nativeResolveRuntimeHomeCamera(handle)?.toCameraState()
    }

    fun resolveRuntimeBodyFrame(handle: Long, bodyId: String): CameraState? {
        if (!isLibraryLoaded || handle == 0L) return null
        return nativeResolveRuntimeBodyFrame(handle, bodyId)?.toCameraState()
    }

    fun bindRuntimeSession(handle: Long, runtimeSessionHandle: Long) {
        if (!isLibraryLoaded || handle == 0L) return
        clearSubmissionCache(handle)
        nativeBindRuntimeSession(handle, runtimeSessionHandle)
    }

    fun unbindRuntimeSession(handle: Long) {
        if (!isLibraryLoaded || handle == 0L) return
        clearSubmissionCache(handle)
        nativeUnbindRuntimeSession(handle)
    }

    fun setRuntimeProcessingMode(handle: Long, mode: RenderProcessingMode) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetRuntimeProcessingMode(handle, mode.toNativeCode())
        }
    }

    fun setRuntimeObserverMode(handle: Long, mode: ObserverMode) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetRuntimeObserverMode(handle, mode.toNativeCode())
        }
    }

    fun setRuntimeSelectedBodyId(handle: Long, bodyId: String?) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetRuntimeSelectedBodyId(handle, bodyId)
        }
    }

    fun setRuntimeTraceLayerMode(handle: Long, mode: TraceLayerMode) {
        if (isLibraryLoaded && handle != 0L) {
            nativeSetRuntimeTraceLayerMode(handle, mode.toNativeCode())
        }
    }

    fun resetRuntimeCamera(handle: Long) {
        if (isLibraryLoaded && handle != 0L) {
            nativeResetRuntimeCamera(handle)
        }
    }

    fun panRuntimeCamera(
        handle: Long,
        distanceXPx: Float,
        distanceYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ) {
        if (isLibraryLoaded && handle != 0L) {
            nativePanRuntimeCamera(handle, distanceXPx, distanceYPx, viewportWidthPx, viewportHeightPx)
        }
    }

    fun zoomRuntimeCamera(
        handle: Long,
        scaleFactor: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ) {
        if (isLibraryLoaded && handle != 0L) {
            nativeZoomRuntimeCamera(handle, scaleFactor, focusXPx, focusYPx, viewportWidthPx, viewportHeightPx)
        }
    }

    fun panAndZoomRuntimeCamera(
        handle: Long,
        distanceXPx: Float,
        distanceYPx: Float,
        scaleFactor: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ) {
        if (isLibraryLoaded && handle != 0L) {
            nativePanAndZoomRuntimeCamera(
                handle,
                distanceXPx,
                distanceYPx,
                scaleFactor,
                focusXPx,
                focusYPx,
                viewportWidthPx,
                viewportHeightPx,
            )
        }
    }

    fun orbitRuntimeCamera(
        handle: Long,
        deltaXPx: Float,
        deltaYPx: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ) {
        if (isLibraryLoaded && handle != 0L) {
            nativeOrbitRuntimeCamera(handle, deltaXPx, deltaYPx, focusXPx, focusYPx, viewportWidthPx, viewportHeightPx)
        }
    }

    fun pickRuntimeBodyId(
        handle: Long,
        screenXPx: Float,
        screenYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): String? {
        if (!isLibraryLoaded || handle == 0L) return null
        return nativePickRuntimeBodyId(handle, screenXPx, screenYPx, viewportWidthPx, viewportHeightPx)?.takeIf { it.isNotBlank() }
    }

    fun render(handle: Long): Boolean = isLibraryLoaded && handle != 0L && nativeRender(handle)

    private fun DoubleArray.toCameraState(): CameraState? {
        if (size < 6) return null
        return CameraState(
            centerM = Vector3d(this[0], this[1], this[2]),
            viewRadiusM = this[3],
            yawRadians = this[4],
            pitchRadians = this[5],
        ).sanitized()
    }

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
        lastSubmittedPacket = null
    }

    /**
     * Heuristic to avoid redundant native scene uploads when the visual state is
     * functionally identical to the previous frame.
     *
     * Because `sourceRevision` increments on every assembled snapshot (even if only
     * the camera moved), we perform a deep comparison of the primitive arrays to
     * distinguish between "camera-only" changes and "material simulation" changes.
     */
    private fun NativeScenePacket.contentMatches(other: NativeScenePacket?): Boolean {
        if (other == null) return false
        if (this === other) return true
        // `sourceRevision` changes on every assembled snapshot, so dedupe must
        // compare the rendered packet content rather than the revision counter.
        return authoritativePositionsM.contentEquals(other.authoritativePositionsM) &&
            authoritativeSourceMassesKg.contentEquals(other.authoritativeSourceMassesKg) &&
            authoritativeRadiiM.contentEquals(other.authoritativeRadiiM) &&
            authoritativeColorsArgb.contentEquals(other.authoritativeColorsArgb) &&
            authoritativeKinds.contentEquals(other.authoritativeKinds) &&
            tracerNearPositionsM.contentEquals(other.tracerNearPositionsM) &&
            tracerNearRadiiM.contentEquals(other.tracerNearRadiiM) &&
            tracerNearColorsArgb.contentEquals(other.tracerNearColorsArgb) &&
            tracerNearKinds.contentEquals(other.tracerNearKinds) &&
            tracerMediumPositionsM.contentEquals(other.tracerMediumPositionsM) &&
            tracerMediumVelocitiesMps.contentEquals(other.tracerMediumVelocitiesMps) &&
            tracerMediumStableIds.contentEquals(other.tracerMediumStableIds) &&
            tracerMediumRadiiM.contentEquals(other.tracerMediumRadiiM) &&
            tracerMediumColorsArgb.contentEquals(other.tracerMediumColorsArgb) &&
            tracerMediumKinds.contentEquals(other.tracerMediumKinds) &&
            tracerFarPositionsM.contentEquals(other.tracerFarPositionsM) &&
            tracerFarVelocitiesMps.contentEquals(other.tracerFarVelocitiesMps) &&
            tracerFarStableIds.contentEquals(other.tracerFarStableIds) &&
            tracerFarRadiiM.contentEquals(other.tracerFarRadiiM) &&
            tracerFarColorsArgb.contentEquals(other.tracerFarColorsArgb) &&
            tracerFarKinds.contentEquals(other.tracerFarKinds) &&
            trailPositionsM.contentEquals(other.trailPositionsM) &&
            trailColorsArgb.contentEquals(other.trailColorsArgb) &&
            trailVertexCounts.contentEquals(other.trailVertexCounts)
    }

    private fun RenderProcessingMode.toNativeCode(): Int = when (this) {
        RenderProcessingMode.DEFAULT -> 0
        RenderProcessingMode.LOW -> 1
    }

    private fun ObserverMode.toNativeCode(): Int = when (this) {
        ObserverMode.FREE -> 0
        ObserverMode.FOLLOW_SELECTED -> 1
        ObserverMode.FOLLOW_SELECTED_HOST -> 2
    }

    private fun TraceLayerMode.toNativeCode(): Int = when (this) {
        TraceLayerMode.FOCUS -> 0
        TraceLayerMode.ALL -> 1
        TraceLayerMode.OFF -> 2
    }

    private external fun nativeIsVulkanRuntimeAvailable(): Boolean
    private external fun nativeGetCpuCapabilitySummary(): String
    private external fun nativeCreateRenderer(assetManager: AssetManager): Long
    private external fun nativeDestroyRenderer(handle: Long)
    private external fun nativeOnSurfaceCreated(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceChanged(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeOnSurfaceDestroyed(handle: Long)
    private external fun nativeSubmitScene(
        handle: Long,
        sourceRevision: Long,
        sceneOriginXM: Double,
        sceneOriginYM: Double,
        sceneOriginZM: Double,
        authoritativePositionsM: DoubleArray,
        authoritativeSourceMassesKg: DoubleArray,
        authoritativeRadiiM: FloatArray,
        authoritativeColorsArgb: IntArray,
        authoritativeKinds: IntArray,
        tracerNearPositionsM: DoubleArray,
        tracerNearRadiiM: FloatArray,
        tracerNearColorsArgb: IntArray,
        tracerNearKinds: IntArray,
        tracerMediumPositionsM: DoubleArray,
        tracerMediumVelocitiesMps: DoubleArray,
        tracerMediumStableIds: IntArray,
        tracerMediumRadiiM: FloatArray,
        tracerMediumColorsArgb: IntArray,
        tracerMediumKinds: IntArray,
        tracerFarPositionsM: DoubleArray,
        tracerFarVelocitiesMps: DoubleArray,
        tracerFarStableIds: IntArray,
        tracerFarRadiiM: FloatArray,
        tracerFarColorsArgb: IntArray,
        tracerFarKinds: IntArray,
        trailPositionsM: DoubleArray,
        trailColorsArgb: IntArray,
        trailVertexCounts: IntArray,
    )
    private external fun nativeSetCamera(
        handle: Long,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        viewRadiusM: Double,
        yawRadians: Double,
        pitchRadians: Double,
    )
    private external fun nativeGetCameraState(handle: Long): DoubleArray?
    private external fun nativeResolveRuntimeHomeCamera(handle: Long): DoubleArray?
    private external fun nativeResolveRuntimeBodyFrame(handle: Long, bodyId: String): DoubleArray?
    private external fun nativeBindRuntimeSession(handle: Long, runtimeSessionHandle: Long)
    private external fun nativeUnbindRuntimeSession(handle: Long)
    private external fun nativeSetRuntimeProcessingMode(handle: Long, processingModeCode: Int)
    private external fun nativeSetRuntimeObserverMode(handle: Long, observerModeCode: Int)
    private external fun nativeSetRuntimeSelectedBodyId(handle: Long, bodyId: String?)
    private external fun nativeSetRuntimeTraceLayerMode(handle: Long, traceLayerModeCode: Int)
    private external fun nativeResetRuntimeCamera(handle: Long)
    private external fun nativePanRuntimeCamera(
        handle: Long,
        distanceXPx: Float,
        distanceYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    )
    private external fun nativeZoomRuntimeCamera(
        handle: Long,
        scaleFactor: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    )
    private external fun nativePanAndZoomRuntimeCamera(
        handle: Long,
        distanceXPx: Float,
        distanceYPx: Float,
        scaleFactor: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    )
    private external fun nativeOrbitRuntimeCamera(
        handle: Long,
        deltaXPx: Float,
        deltaYPx: Float,
        focusXPx: Float,
        focusYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    )
    private external fun nativePickRuntimeBodyId(
        handle: Long,
        screenXPx: Float,
        screenYPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): String?
    private external fun nativeRender(handle: Long): Boolean
    private external fun nativeGetLastError(handle: Long): String
    private external fun nativeGetBackendLabel(handle: Long): String
    private external fun nativeGetSceneSummary(handle: Long): String
    private external fun nativeGetHardwareSummary(handle: Long): String
}
