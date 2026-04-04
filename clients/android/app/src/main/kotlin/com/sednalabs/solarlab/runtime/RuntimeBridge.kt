package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Rust runtime boundary for Android.
 *
 * The real implementation will bind to engine/ffi and translate C-ABI events
 * into typed Kotlin state.
 */
interface RuntimeBridge {
    fun connect(): Flow<RuntimeSignal>
}

class PlaceholderRuntimeBridge : RuntimeBridge {
    override fun connect(): Flow<RuntimeSignal> = flowOf(
        RuntimeSignal.Connected,
        RuntimeSignal.Status("Runtime bridge placeholder: waiting for engine/ffi adapter")
    )
}

sealed interface RuntimeSignal {
    data object Connected : RuntimeSignal
    data class Status(val message: String) : RuntimeSignal
}
