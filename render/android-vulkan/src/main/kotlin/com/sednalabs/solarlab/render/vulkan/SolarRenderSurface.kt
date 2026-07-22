package com.sednalabs.solarlab.render.vulkan

import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

internal interface SolarRenderSurface {
    fun submitScene(frame: RenderSceneFrame)
    fun deferRendering(block: () -> Unit) {
        block()
    }
    fun resetCamera()
    fun zoomBy(scaleFactor: Float) {}
    fun focusAndFrameBody(bodyId: String?, observerMode: ObserverMode) {}
    fun frameBody(bodyId: String) {}
    fun setCameraScaleBand(scaleBand: CameraScaleBand) {}
    fun currentCameraScaleBand(): CameraScaleBand = CameraScaleBand.SYSTEM
    fun setOnCameraScaleChangedListener(listener: ((CameraScaleBand) -> Unit)?) {}
    fun bindRuntimeSessionHandle(sessionHandle: Long) {}
    fun setProcessingMode(mode: RenderProcessingMode) {}
    fun setInteractionListener(listener: RenderInteractionListener?) {}
    fun setInteractionMode(mode: SceneInteractionMode) {}
    fun setSelectedBodyId(bodyId: String?) {}
    fun setObserverMode(mode: ObserverMode) {}
    fun setPlacementPlaneZ(worldZ: Double) {}
    fun setRenderLayerOptions(options: RenderLayerOptions) {}
    fun onHostResume() {}
    fun onHostPause() {}
    fun release() {}
}
