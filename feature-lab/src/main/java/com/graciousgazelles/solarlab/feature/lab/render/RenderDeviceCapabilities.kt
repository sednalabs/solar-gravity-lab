package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class RenderDeviceCapabilities(
    val supportsVulkan: Boolean,
) {
    companion object {
        private const val VULKAN_1_0_VERSION = 0x00400000

        fun query(context: Context): RenderDeviceCapabilities {
            val packageManager = context.packageManager
            val supportsVulkan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0_VERSION)
            } else {
                false
            }

            return RenderDeviceCapabilities(
                supportsVulkan = supportsVulkan,
            )
        }
    }
}
