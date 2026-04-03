package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.graciousgazelles.solarlab.core.model.SimulationSnapshot
import com.graciousgazelles.solarlab.feature.lab.ui.SolarSystemGLSurfaceView
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

    private var requestedBackend: RenderBackend = if (capabilities.supportsVulkan) RenderBackend.AUTO else RenderBackend.OPENGL
    private var activeBackend: RenderBackend = RenderBackend.OPENGL
    private var activeSurface: SolarRenderSurface? = null
    private var activeSurfaceView: View? = null
    private var latestScene: RenderSceneFrame? = null
    private var hostResumed: Boolean = false
    private var interactionListener: RenderInteractionListener? = null
    private var interactionMode: SceneInteractionMode = SceneInteractionMode.NAVIGATE_AND_SELECT
    private var selectedBodyId: String? = null
    private var observerMode: ObserverMode = ObserverMode.FREE
    private var backendStatusListener: ((RenderBackendStatus) -> Unit)? = null
    private var currentStatus: RenderBackendStatus = RenderBackendStatus(
        requested = requestedBackend,
        active = activeBackend,
        isHardwareAccelerated = true,
        message = "Preparing renderer.",
    )

    init {
        installPreferredSurface(reason = "Renderer host initialised.")
    }

    fun submitSnapshot(snapshot: SimulationSnapshot) {
        val scene = sceneAssembler.assemble(snapshot)
        latestScene = scene
        activeSurface?.submitScene(scene)
    }

    fun resetScene() {
        sceneAssembler.clear()
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

    fun setSelectedBodyId(bodyId: String?) {
        selectedBodyId = bodyId
        activeSurface?.setSelectedBodyId(bodyId)
    }

    fun selectedBodyId(): String? = selectedBodyId

    fun setObserverMode(mode: ObserverMode) {
        observerMode = mode
        activeSurface?.setObserverMode(mode)
    }

    fun observerMode(): ObserverMode = observerMode

    fun cycleBackendPreference() {
        requestedBackend = when (requestedBackend) {
            RenderBackend.AUTO -> RenderBackend.VULKAN
            RenderBackend.VULKAN -> RenderBackend.OPENGL
            RenderBackend.OPENGL -> RenderBackend.AUTO
        }
        installPreferredSurface(reason = "Backend preference switched to ${requestedBackend.name}.")
    }

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

    private fun installPreferredSurface(reason: String) {
        val preferred = when (requestedBackend) {
            RenderBackend.AUTO -> if (capabilities.supportsVulkan) RenderBackend.VULKAN else RenderBackend.OPENGL
            else -> requestedBackend
        }

        activeSurface?.onHostPause()
        activeSurface?.release()
        activeSurface = null
        activeSurfaceView?.let(::removeView)
        activeSurfaceView = null

        when (preferred) {
            RenderBackend.VULKAN -> installVulkanFirst(reason)
            RenderBackend.OPENGL -> installOpenGl("$reason Using OpenGL ES renderer.")
            RenderBackend.AUTO -> installOpenGl("$reason Auto fell back to OpenGL ES.")
        }
    }

    private fun installVulkanFirst(reason: String) {
        if (!capabilities.supportsVulkan || !SolarLabVulkanBridge.isRuntimeAvailable()) {
            installOpenGl("$reason Vulkan unavailable, using OpenGL ES fallback.")
            return
        }

        val vulkanView = SolarSystemVulkanSurfaceView(
            context = context,
            statusCallback = { updateStatus(it.copy(requested = requestedBackend)) },
            fatalInitCallback = { message ->
                installOpenGl("Vulkan failed: $message Falling back to OpenGL ES.")
            },
        )
        attachSurface(vulkanView, RenderBackend.VULKAN)
        updateStatus(
            RenderBackendStatus(
                requested = requestedBackend,
                active = RenderBackend.VULKAN,
                isHardwareAccelerated = true,
                message = "$reason Vulkan backend selected.",
            ),
        )
    }

    private fun installOpenGl(message: String) {
        val glView = SolarSystemGLSurfaceView(context)
        attachSurface(glView, RenderBackend.OPENGL)
        updateStatus(
            RenderBackendStatus(
                requested = requestedBackend,
                active = RenderBackend.OPENGL,
                isHardwareAccelerated = true,
                message = message,
            ),
        )
    }

    private fun attachSurface(view: View, backend: RenderBackend) {
        activeBackend = backend
        activeSurfaceView = view
        activeSurface = view as SolarRenderSurface
        activeSurface?.setInteractionListener(interactionListener)
        activeSurface?.setInteractionMode(interactionMode)
        activeSurface?.setSelectedBodyId(selectedBodyId)
        activeSurface?.setObserverMode(observerMode)
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
}
