package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.RenderPlacementPreview
import com.graciousgazelles.solarlab.render.core.RenderSceneAssembler
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame
import com.graciousgazelles.solarlab.render.core.withLayerOptions

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
    private var renderLayerOptions: RenderLayerOptions = RenderLayerOptions()
    private var runtimeSessionHandle: Long = 0L
    private var backendStatusListener: ((RenderBackendStatus) -> Unit)? = null
    private var cameraScaleChangedListener: ((CameraScaleBand) -> Unit)? = null
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

    fun submitSnapshot(
        snapshot: SimulationSnapshot,
        placementPreview: RenderPlacementPreview? = null,
    ) {
        submitSceneFrame(
            sceneAssembler.assemble(
                snapshot = snapshot,
                placementPreview = placementPreview,
            ),
        )
    }

    fun submitSceneFrame(frame: RenderSceneFrame) {
        latestScene = frame
        activeSurface?.submitScene(frame.withLayerOptions(renderLayerOptions))
    }

    fun updateRuntimeMirrorState(
        sessionHandle: Long,
        processingMode: RenderProcessingMode,
        renderLayerOptions: RenderLayerOptions,
        observerMode: ObserverMode,
        selectedBodyId: String?,
        interactionMode: SceneInteractionMode,
        sceneFrame: RenderSceneFrame?,
    ) {
        activeSurface?.deferRendering {
            bindRuntimeSessionHandle(sessionHandle)
            setProcessingMode(processingMode)
            setRenderLayerOptions(renderLayerOptions)
            setObserverMode(observerMode)
            setSelectedBodyId(selectedBodyId)
            setInteractionMode(interactionMode)
            sceneFrame?.let(::submitSceneFrame)
        } ?: run {
            bindRuntimeSessionHandle(sessionHandle)
            setProcessingMode(processingMode)
            setRenderLayerOptions(renderLayerOptions)
            setObserverMode(observerMode)
            setSelectedBodyId(selectedBodyId)
            setInteractionMode(interactionMode)
            sceneFrame?.let(::submitSceneFrame)
        }
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

    fun frameBody(bodyId: String) {
        selectedBodyId = bodyId
        activeSurface?.frameBody(bodyId)
    }

    fun setCameraScaleBand(scaleBand: CameraScaleBand) {
        activeSurface?.setCameraScaleBand(scaleBand)
    }

    fun currentCameraScaleBand(): CameraScaleBand =
        activeSurface?.currentCameraScaleBand() ?: CameraScaleBand.SYSTEM

    fun setOnCameraScaleChangedListener(listener: ((CameraScaleBand) -> Unit)?) {
        cameraScaleChangedListener = listener
        activeSurface?.setOnCameraScaleChangedListener(listener)
        listener?.invoke(currentCameraScaleBand())
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

    fun bindRuntimeSessionHandle(sessionHandle: Long) {
        runtimeSessionHandle = sessionHandle
        activeSurface?.bindRuntimeSessionHandle(sessionHandle)
    }

    fun processingMode(): RenderProcessingMode = processingMode

    fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        activeSurface?.setSelectedBodyId(bodyId)
    }

    fun selectedBodyId(): String? = selectedBodyId

    fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        activeSurface?.setObserverMode(mode)
    }
    fun setPlacementPlaneZ(worldZ: Double) {
        placementPlaneZ = worldZ
        activeSurface?.setPlacementPlaneZ(worldZ)
    }

    fun setRenderLayerOptions(options: RenderLayerOptions) {
        if (renderLayerOptions == options) return
        renderLayerOptions = options
        activeSurface?.setRenderLayerOptions(options)
        latestScene?.let { activeSurface?.submitScene(it.withLayerOptions(options)) }
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
        activeSurface?.bindRuntimeSessionHandle(runtimeSessionHandle)
        activeSurface?.setProcessingMode(processingMode)
        activeSurface?.setSelectedBodyId(selectedBodyId)
        activeSurface?.setObserverMode(observerMode)
        activeSurface?.setPlacementPlaneZ(placementPlaneZ)
        activeSurface?.setRenderLayerOptions(renderLayerOptions)
        activeSurface?.setOnCameraScaleChangedListener(cameraScaleChangedListener)
        addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        if (hostResumed) {
            activeSurface?.onHostResume()
        }
        latestScene?.let { activeSurface?.submitScene(it.withLayerOptions(renderLayerOptions)) }
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
