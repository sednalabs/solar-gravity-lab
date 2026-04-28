package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderLayerOptions
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

internal interface SolarRenderSurface {
    fun submitScene(frame: RenderSceneFrame)
    fun resetCamera()
    fun zoomBy(scaleFactor: Float) {}
    fun focusAndFrameBody(bodyId: String?, observerMode: ObserverMode) {}
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
