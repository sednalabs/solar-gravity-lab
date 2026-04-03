package com.graciousgazelles.solarlab.feature.lab.render

import com.graciousgazelles.solarlab.render.core.RenderSceneFrame

internal interface SolarRenderSurface {
    fun submitScene(frame: RenderSceneFrame)
    fun resetCamera()
    fun onHostResume() {}
    fun onHostPause() {}
    fun release() {}
}
