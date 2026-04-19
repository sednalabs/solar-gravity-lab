package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderSceneAssembler
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

class SolarSystemRenderHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val capabilities = RenderDeviceCapabilities.query(context)
    private val sceneAssembler = RenderSceneAssembler()

    private val requestedBackend: RenderBackend = RenderBackend.VULKAN
    private var activeSurface: SolarRenderSurface? = null
    private var activeSurfaceView: View? = null
    private var latestScene: RenderSceneFrame? = null
    private var hostResumed: Boolean = false
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var processingMode: RenderProcessingMode = RenderProcessingMode.DEFAULT
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var placementPlaneZ: Double = 0.0
    private var runtimeSessionHandle: Long = 0L
    private var backendStatusListener: ((RenderBackendStatus) -> Unit)? = null
    private var currentStatus: RenderBackendStatus = RenderBackendStatus(
        requested = requestedBackend,
        active = requestedBackend,
        isHardwareAccelerated = true,
        message = "Preparing renderer.",
        hardwareSummary = baseHardwareSummary(),
    )

    init {
        installVulkanOnlySurface(reason = "Renderer host initialised.")
    }

    fun submitSnapshot(snapshot: SimulationSnapshot) {
        submitSceneFrame(sceneAssembler.assemble(snapshot))
    }

    fun submitSceneFrame(frame: RenderSceneFrame) {
        if (runtimeSessionHandle != 0L) {
            latestScene = frame
            return
        }
        if (latestScene?.sourceRevision == frame.sourceRevision) {
            latestScene = frame
            return
        }
        latestScene = frame
        activeSurface?.submitScene(frame)
    }

    fun resetScene() {
        sceneAssembler.clear()
        latestScene = null
    }

    fun resetCamera() {
        activeSurface?.resetCamera()
    }

    fun zoomBy(scaleFactor: Float) {
        activeSurface?.zoomBy(scaleFactor)
    }

    fun focusAndFrameBody(bodyId: String?, observerMode: ObserverMode) {
        selectedBodyId = bodyId
        this.observerMode = observerMode
        activeSurface?.focusAndFrameBody(bodyId, observerMode)
    }

    fun setInteractionListener(listener: RenderInteractionListener?) {
        interactionListener = listener
        activeSurface?.setInteractionListener(listener)
    }

    fun setInteractionMode(mode: SceneInteractionMode) {
        interactionMode = mode
        activeSurface?.setInteractionMode(mode)
    }

    fun interactionMode(): SceneInteractionMode = interactionMode

    fun setProcessingMode(mode: RenderProcessingMode) {
        if (processingMode == mode) return
        processingMode = mode
        activeSurface?.applyViewState(
            runtimeSessionHandle = runtimeSessionHandle,
            processingMode = processingMode,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        )
    }

    fun bindRuntimeSessionHandle(sessionHandle: Long) {
        if (runtimeSessionHandle == sessionHandle) return
        runtimeSessionHandle = sessionHandle
        activeSurface?.applyViewState(
            runtimeSessionHandle = runtimeSessionHandle,
            processingMode = processingMode,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        )
    }

    fun processingMode(): RenderProcessingMode = processingMode

    fun setSelectedBodyId(bodyId: String?) {
        if (selectedBodyId == bodyId) return
        selectedBodyId = bodyId
        activeSurface?.applyViewState(
            runtimeSessionHandle = runtimeSessionHandle,
            processingMode = processingMode,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        )
    }

    fun selectedBodyId(): String? = selectedBodyId

    fun setObserverMode(mode: ObserverMode) {
        if (observerMode == mode) return
        observerMode = mode
        activeSurface?.applyViewState(
            runtimeSessionHandle = runtimeSessionHandle,
            processingMode = processingMode,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        )
    }
    fun setPlacementPlaneZ(worldZ: Double) {
        placementPlaneZ = worldZ
        activeSurface?.setPlacementPlaneZ(worldZ)
    }

    fun observerMode(): ObserverMode = observerMode

    fun backendPreference(): RenderBackend = requestedBackend

    fun setOnBackendStatusChangedListener(listener: ((RenderBackendStatus) -> Unit)?) {
        backendStatusListener = listener
        listener?.invoke(currentStatus)
    }

    fun onHostResume() {
        hostResumed = true
        activeSurface?.onHostResume()
    }

    fun onHostPause() {
        hostResumed = false
        activeSurface?.onHostPause()
    }

    fun release() {
        activeSurface?.onHostPause()
        activeSurface?.release()
    }

    private fun installVulkanOnlySurface(reason: String) {
        activeSurface?.onHostPause()
        activeSurface?.release()
        activeSurface = null
        activeSurfaceView?.let(::removeView)
        activeSurfaceView = null

        if (!capabilities.supportsVulkan || !SolarLabVulkanBridge.isRuntimeAvailable()) {
            updateStatus(
                RenderBackendStatus(
                    requested = requestedBackend,
                    active = RenderBackend.VULKAN,
                    isHardwareAccelerated = false,
                    message = "$reason Vulkan renderer unavailable on this device/build.",
                    hardwareSummary = baseHardwareSummary(),
                ),
            )
            return
        }

        val vulkanView = SolarSystemVulkanSurfaceView(
            context = context,
            statusCallback = { updateStatus(it.copy(requested = requestedBackend)) },
            fatalInitCallback = { message ->
                updateStatus(
                    RenderBackendStatus(
                        requested = requestedBackend,
                        active = RenderBackend.VULKAN,
                        isHardwareAccelerated = false,
                        message = "Vulkan failed: $message",
                        hardwareSummary = baseHardwareSummary(),
                    ),
                )
            },
        )
        attachSurface(vulkanView)
        updateStatus(
            RenderBackendStatus(
                requested = requestedBackend,
                active = RenderBackend.VULKAN,
                isHardwareAccelerated = true,
                message = "$reason Vulkan backend selected.",
                hardwareSummary = baseHardwareSummary(),
            ),
        )
    }

    private fun attachSurface(view: View) {
        activeSurfaceView = view
        activeSurface = view as SolarRenderSurface
        activeSurface?.setInteractionListener(interactionListener)
        activeSurface?.setInteractionMode(interactionMode)
        activeSurface?.applyViewState(
            runtimeSessionHandle = runtimeSessionHandle,
            processingMode = processingMode,
            selectedBodyId = selectedBodyId,
            observerMode = observerMode,
        )
        activeSurface?.setPlacementPlaneZ(placementPlaneZ)
        addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        if (hostResumed) {
            activeSurface?.onHostResume()
        }
        latestScene?.let { activeSurface?.submitScene(it) }
    }

    private fun updateStatus(status: RenderBackendStatus) {
        currentStatus = status
        backendStatusListener?.invoke(status)
    }

    private fun baseHardwareSummary(): String =
        listOf(
            capabilities.hardwareSummary(),
            SolarLabVulkanBridge.cpuCapabilitySummary(),
        ).joinToString(separator = " | ")
}
