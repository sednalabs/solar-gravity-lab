package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderFuturePathVisibilityMode
import com.graciousgazelles.solarlab.render.core.RenderSceneAssemblyOptions
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
    private var latestSnapshot: SimulationSnapshot? = null
    private var latestScene: RenderSceneFrame? = null
    private var hostResumed: Boolean = false
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var processingMode: RenderProcessingMode = RenderProcessingMode.DEFAULT
    private var tracerMutualGravityEnabled: Boolean = false
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var overlaySettings: RenderSceneOverlaySettings = RenderSceneOverlaySettings()
    private var backendStatusListener: ((RenderBackendStatus) -> Unit)? = null
    private var currentStatus: RenderBackendStatus = RenderBackendStatus(
        requested = requestedBackend,
        active = requestedBackend,
        isHardwareAccelerated = true,
        message = "Preparing renderer.",
        hardwareSummary = baseHardwareSummary(),
        hardwareDetails = baseHardwareDetails(),
    )

    init {
        installVulkanOnlySurface(reason = "Renderer host initialised.")
    }

    fun submitSnapshot(snapshot: SimulationSnapshot) {
        latestSnapshot = snapshot
        rebuildScene()
    }

    fun resetScene() {
        sceneAssembler.clear()
        latestSnapshot = null
        latestScene = null
    }

    fun resetCamera() {
        activeSurface?.resetCamera()
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
        processingMode = mode
        activeSurface?.setProcessingMode(mode)
    }

    fun processingMode(): RenderProcessingMode = processingMode

    fun setTracerMutualGravityEnabled(enabled: Boolean) {
        tracerMutualGravityEnabled = enabled
        activeSurface?.setTracerMutualGravityEnabled(enabled)
    }

    fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        activeSurface?.setSelectedBodyId(bodyId)
        rebuildScene()
    }

    fun selectedBodyId(): String? = selectedBodyId

    fun setSceneOverlaySettings(settings: RenderSceneOverlaySettings) {
        overlaySettings = settings
        activeSurface?.setSceneOverlaySettings(settings)
        rebuildScene()
    }

    fun sceneOverlaySettings(): RenderSceneOverlaySettings = overlaySettings

    fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        activeSurface?.setObserverMode(mode)
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
                    hardwareDetails = baseHardwareDetails(),
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
                        hardwareDetails = baseHardwareDetails(),
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
                hardwareDetails = baseHardwareDetails(),
            ),
        )
    }

    private fun attachSurface(view: View) {
        activeSurfaceView = view
        activeSurface = view as SolarRenderSurface
        activeSurface?.setInteractionListener(interactionListener)
        activeSurface?.setInteractionMode(interactionMode)
        activeSurface?.setProcessingMode(processingMode)
        activeSurface?.setTracerMutualGravityEnabled(tracerMutualGravityEnabled)
        activeSurface?.setSelectedBodyId(selectedBodyId)
        activeSurface?.setObserverMode(observerMode)
        activeSurface?.setSceneOverlaySettings(overlaySettings)
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

    private fun rebuildScene() {
        val snapshot = latestSnapshot ?: return
        val scene = sceneAssembler.assemble(
            snapshot = snapshot,
            options = RenderSceneAssemblyOptions(
                selectedBodyId = selectedBodyId,
                trailVisibilityMode = overlaySettings.trailVisibilityMode,
                futurePathVisibilityMode = if (overlaySettings.showPredictedTrails) {
                    RenderFuturePathVisibilityMode.SELECTED_ONLY
                } else {
                    RenderFuturePathVisibilityMode.NONE
                },
            ),
        )
        latestScene = scene
        activeSurface?.submitScene(scene)
    }

    private fun baseHardwareSummary(): String =
        listOf(
            capabilities.hardwareSummary(),
            SolarLabVulkanBridge.cpuCapabilitySummary(),
        ).joinToString(separator = " | ")

    private fun baseHardwareDetails(): String =
        listOf(
            capabilities.hardwareDetails(),
            SolarLabVulkanBridge.cpuCapabilityDetails(),
        ).joinToString(separator = "\n")
}
