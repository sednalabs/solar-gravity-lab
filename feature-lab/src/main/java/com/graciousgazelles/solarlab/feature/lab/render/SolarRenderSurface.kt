package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

internal interface SolarRenderSurface {
    fun submitScene(frame: RenderSceneFrame)
    fun resetCamera()
    fun setProcessingMode(mode: RenderProcessingMode) {}
    fun setInteractionListener(listener: RenderInteractionListener?) {}
    fun setInteractionMode(mode: SceneInteractionMode) {}
    fun setSelectedBodyId(bodyId: String?) {}
    fun setObserverMode(mode: ObserverMode) {}
    fun onHostResume() {}
    fun onHostPause() {}
    fun release() {}
}
