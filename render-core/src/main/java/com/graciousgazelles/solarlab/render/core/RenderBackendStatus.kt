package com.graciousgazelles.solarlab.render.core

data class RenderBackendStatus(
    val requested: RenderBackend,
    val active: RenderBackend,
    val isHardwareAccelerated: Boolean,
    val message: String,
    val hardwareSummary: String? = null,
)
