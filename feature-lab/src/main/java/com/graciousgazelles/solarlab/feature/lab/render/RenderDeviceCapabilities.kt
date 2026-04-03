package com.graciousgazelles.solarlab.feature.lab.render

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class RenderDeviceCapabilities(
    val supportsVulkan: Boolean,
    val supportsOpenGlEs2: Boolean,
    val reportedGlEsVersion: Int,
) {
    companion object {
        private const val VULKAN_1_0_VERSION = 0x00400000

        fun query(context: Context): RenderDeviceCapabilities {
            val packageManager = context.packageManager
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val configurationInfo = activityManager.deviceConfigurationInfo
            val glEsVersion = configurationInfo.reqGlEsVersion
            val supportsOpenGlEs2 = glEsVersion >= 0x00020000
            val supportsVulkan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_0_VERSION)
            } else {
                false
            }

            return RenderDeviceCapabilities(
                supportsVulkan = supportsVulkan,
                supportsOpenGlEs2 = supportsOpenGlEs2,
                reportedGlEsVersion = glEsVersion,
            )
        }
    }
}
