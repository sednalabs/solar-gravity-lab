package com.sednalabs.solarlab

import com.graciousgazelles.solarlab.feature.lab.render.RenderProcessingMode

internal object HostedDebugMode {
    val enabled: Boolean = BuildConfig.HOSTED_DEBUG_LITE_MODE

    val initialRenderProcessingMode: RenderProcessingMode =
        if (enabled) {
            RenderProcessingMode.LOW
        } else {
            RenderProcessingMode.DEFAULT
        }
}
