package com.graciousgazelles.solarlab.core.model

data class HardwareAccelerationProfile(
    val target: String,
    val authoritativeSolverBackend: String,
    val simdPath: String? = null,
    val tracerIntegrationBackend: String,
    val vulkanCompactionBackend: String? = null,
    val qnnBackend: String? = null,
)
