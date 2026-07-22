package com.sednalabs.solarlab

import com.sednalabs.solarlab.render.vulkan.RenderProcessingMode

internal object HostedDebugMode {
    val enabled: Boolean = BuildConfig.HOSTED_DEBUG_LITE_MODE

    val initialRenderProcessingMode: RenderProcessingMode =
        if (enabled) {
            RenderProcessingMode.LOW
        } else {
            RenderProcessingMode.DEFAULT
        }
}
