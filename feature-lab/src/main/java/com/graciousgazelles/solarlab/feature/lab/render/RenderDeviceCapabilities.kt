package com.graciousgazelles.solarlab.feature.lab.render

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class RenderDeviceCapabilities(
    val supportsVulkan: Boolean,
    val manufacturer: String,
    val model: String,
    val socManufacturer: String?,
    val socModel: String?,
    val supportedAbis: List<String>,
) {
    val primaryAbi: String? get() = supportedAbis.firstOrNull()

    val runtimeTarget: String
        get() = when {
            primaryAbi?.contains("arm64") == true -> "android-arm64"
            primaryAbi?.contains("armeabi") == true -> "android-arm32"
            primaryAbi?.contains("x86_64") == true -> "android-x86_64"
            primaryAbi?.contains("x86") == true -> "android-x86"
            else -> "android-unknown"
        }

    fun hardwareSummary(): String = buildString {
        append("target=")
        append(runtimeTarget)
        append(" device=")
        append(deviceLabel())
        primaryAbi?.let {
            append(" abi=")
            append(it)
        }
        if (socManufacturer != null || socModel != null) {
            append(" soc=")
            append(listOfNotNull(socManufacturer, socModel).joinToString(" "))
        }
        append(" vulkan=")
        append(if (supportsVulkan) "feature-present" else "feature-absent")
    }

    private fun deviceLabel(): String {
        val normalizedManufacturer = manufacturer.trim()
        val normalizedModel = model.trim()
        return if (normalizedModel.startsWith(normalizedManufacturer, ignoreCase = true)) {
            normalizedModel
        } else {
            "$normalizedManufacturer $normalizedModel".trim()
        }
    }

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
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                socManufacturer = Build.SOC_MANUFACTURER?.takeUnless { it.isBlank() },
                socModel = Build.SOC_MODEL?.takeUnless { it.isBlank() },
                supportedAbis = Build.SUPPORTED_ABIS?.filter { it.isNotBlank() }.orEmpty(),
            )
        }
    }
}
