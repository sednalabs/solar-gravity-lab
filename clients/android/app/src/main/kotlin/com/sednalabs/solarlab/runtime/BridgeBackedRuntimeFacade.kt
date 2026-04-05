package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/**
 * Android-local implementation of `RuntimeFacade`.
 *
 * It is the shell's runtime adapter: receives boundary signals and materializes UI state
 * while keeping all business/physics behavior inside the native runtime.
 */
class BridgeBackedRuntimeFacade internal constructor(
    private val bridge: RuntimeBridge
) : RuntimeFacade {
    constructor() : this(JniRuntimeBridge())

    private val _uiState = MutableStateFlow(
        ShellUiState(
            statusLine = "Preparing Rust runtime session",
            detailLine = "Android shell owns presentation and command flow only"
        )
    )

    override val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    // Session handoff is one-way from bridge to UI state.
    // The flow is treated as the only driver for initial connection lifecycle.
    override suspend fun startSession() {
        bridge.connect().collect(::applySignal)
    }

    // Explicit refresh and command paths are intentionally mapped 1:1 from UI intent to
    // runtime boundary outputs and then to immutable UI copies.
    override suspend fun refresh() {
        bridge.refresh().forEach(::applySignal)
    }

    override suspend fun applyCommand(command: RuntimeCommand) {
        bridge.applyCommand(command).forEach(::applySignal)
    }

    private fun applySignal(signal: RuntimeSignal) {
        _uiState.value = when (signal) {
            is RuntimeSignal.Connected -> _uiState.value.copy(
                statusLine = "Connected to runtime boundary",
                detailLine = "Native session handle=${signal.handle}",
                sessionHandle = signal.handle
            )

            is RuntimeSignal.Status -> _uiState.value.copy(
                detailLine = signal.message
            )

            is RuntimeSignal.SnapshotUpdated -> _uiState.value.copy(
                statusLine = "Runtime snapshot refreshed",
                detailLine = "epoch=${signal.summary.epochSeconds}, bodies=${signal.summary.bodyCount}",
                snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, paused=${signal.summary.paused}"
            )

            is RuntimeSignal.CommandApplied -> _uiState.value.copy(
                statusLine = "Command applied",
                detailLine = "${signal.commandLabel} -> epoch=${signal.summary.epochSeconds}",
                snapshotSummary = "scenario=${signal.summary.scenarioId}, branch=${signal.summary.activeBranchId}, bodies=${signal.summary.bodyCount}"
            )

            is RuntimeSignal.RenderPacketReady -> {
                val lease = signal.lease
                try {
                    // Decode packet payloads only on the UI boundary and close the packet lease
                    // in finally to guarantee host-side release independent of decode outcome.
                    val renderFrame = VulkanPacketRenderFrameDecoder.decode(lease.packet)
                    _uiState.value.copy(
                        statusLine = "Vulkan render host ready",
                        detailLine = "Scene revision=${lease.sceneRevision}",
                        renderPacketSummary = lease.summaryLine,
                        renderFrame = renderFrame,
                    )
                } catch (error: Throwable) {
                    _uiState.value.copy(
                        statusLine = "Render packet decode failed",
                        detailLine = error.message ?: error::class.java.simpleName,
                        renderPacketSummary = lease.summaryLine,
                        renderFrame = null,
                    )
                } finally {
                    lease.close()
                }
            }

            is RuntimeSignal.Unavailable -> _uiState.value.copy(
                statusLine = signal.message,
                detailLine = signal.detail,
                renderFrame = null,
            )
        }
    }
}
