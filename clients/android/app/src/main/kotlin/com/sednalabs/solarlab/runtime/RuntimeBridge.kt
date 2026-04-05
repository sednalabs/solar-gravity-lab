package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Rust runtime boundary for Android.
 *
 * Kotlin owns orchestration and lifecycle semantics.
 * Native transport owns ABI calls into `engine/ffi` via a JNI shim.
 */
internal interface RuntimeBridge {
    // Streamed connection events from the runtime host.
    fun connect(): Flow<RuntimeSignal>

    // Synchronous refresh/query path for already-bound handles.
    suspend fun refresh(): List<RuntimeSignal>

    // Command path: apply intent and surface resulting state deltas.
    suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal>
}

internal class JniRuntimeBridge(
    private val transport: NativeRuntimeTransport = JniNativeRuntimeTransport,
    private val renderHostAdapter: RenderHostAdapter = NativeRenderHostAdapter(transport),
) : RuntimeBridge {
    // Serialize access to activeSessionHandle and avoid races between connect/refresh/apply.
    private val stateLock = Any()
    @Volatile
    private var activeSessionHandle: Long = 0L

    // Creates the native session and starts the periodic snapshot refresh loop.
    // Emitted signals are boundary-only; all rendering state remains host-owned.
    override fun connect(): Flow<RuntimeSignal> = callbackFlow {
        val loadOutcome = transport.ensureLibraryLoaded()
        if (loadOutcome is NativeLibraryLoadOutcome.Failure) {
            trySend(RuntimeSignal.Unavailable(loadOutcome.reason))
            close()
            return@callbackFlow
        }

        trySend(RuntimeSignal.Status("Native runtime library loaded"))

        val createResult = runCatching {
            transport.createSession(
                scenarioId = DEFAULT_SCENARIO_ID,
                rootBranchId = DEFAULT_ROOT_BRANCH_ID,
            )
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime session adapter is unavailable",
                    detail = error.message ?: error::class.java.simpleName
                )
            )
            close()
            return@callbackFlow
        }

        if (!createResult.result.isOk()) {
            if (createResult.handle != 0L) {
                transport.destroySession(createResult.handle)
            }
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime session create failed",
                    detail = "${createResult.result.describe()} (${createResult.result.context})"
                )
            )
            close()
            return@callbackFlow
        }

        val handle = createResult.handle
        if (handle == 0L) {
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime returned an empty session handle",
                    detail = "This indicates the JNI adapter did not provide a valid `SlRuntimeHandle`."
                )
            )
            close()
            return@callbackFlow
        }

        if (createResult.abiVersion != ABI_VERSION) {
            transport.destroySession(handle)
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime ABI mismatch",
                    detail = "expected=$ABI_VERSION, native=${createResult.abiVersion}"
                )
            )
            close()
            return@callbackFlow
        }

        synchronized(stateLock) {
            activeSessionHandle = handle
            renderHostAdapter.bindSession(handle)
        }

        trySend(RuntimeSignal.Connected(handle = handle))

        val runtimeInfoResult = runCatching {
            transport.runtimeInfo(handle)
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Status(
                    "Runtime info unavailable: ${error.message ?: error::class.java.simpleName}"
                )
            )
            awaitClose {
                releaseActiveSession(handle)
            }
            return@callbackFlow
        }

        if (runtimeInfoResult.result.isOk()) {
            trySend(
                RuntimeSignal.Status(
                    "Runtime backend: ${runtimeInfoResult.cpuBackendLabel()} + ${runtimeInfoResult.gpuBackendLabel()}"
                )
            )
        } else {
            trySend(RuntimeSignal.Status("Runtime info unavailable: ${runtimeInfoResult.result.describe()}"))
        }

        refreshSignalsForHandle(handle, includeSummary = true).forEach { trySend(it) }

        val refreshJob = launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val activeHandle = synchronized(stateLock) { activeSessionHandle }
                if (activeHandle == 0L) {
                    continue
                }
                refreshSignalsForHandle(activeHandle, includeSummary = true).forEach { trySend(it) }
            }
        }

        awaitClose {
            refreshJob.cancel()
            releaseActiveSession(handle)
        }
    }

    // Explicit pull refresh for currently bound session; reuses handle snapshot guard.
    override suspend fun refresh(): List<RuntimeSignal> {
        val handle = synchronized(stateLock) { activeSessionHandle }
        if (handle == 0L) {
            return listOf(RuntimeSignal.Status("Refresh skipped: no active runtime session"))
        }

        return refreshSignalsForHandle(handle, includeSummary = true)
    }

    // Dispatches UI command into native runtime and returns resulting status + snapshot signals.
    override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
        val handle = synchronized(stateLock) { activeSessionHandle }
        if (handle == 0L) {
            return listOf(RuntimeSignal.Status("Command skipped: no active runtime session"))
        }

        val commandResult = runCatching {
            transport.applyCommand(handle, command.toNativePayload())
        }.getOrElse { error ->
            return listOf(
                RuntimeSignal.Status(
                    "Command failed: ${error.message ?: error::class.java.simpleName}"
                )
            )
        }

        if (!commandResult.result.isOk()) {
            return listOf(RuntimeSignal.Status("Command failed: ${commandResult.result.describe()}"))
        }

        val signals = mutableListOf<RuntimeSignal>()
        signals += RuntimeSignal.CommandApplied(command.label, commandResult)
        signals += refreshSignalsForHandle(handle, includeSummary = false)
        return signals
    }

    // Collects one snapshot refresh bundle for one handle:
    // optional world-state summary plus render packet lease.
    private fun refreshSignalsForHandle(handle: Long, includeSummary: Boolean): List<RuntimeSignal> {
        val signals = mutableListOf<RuntimeSignal>()

        if (includeSummary) {
            val summary = runCatching {
                transport.refreshSession(handle)
            }.getOrElse { error ->
                signals += RuntimeSignal.Status(
                    "Refresh unavailable: ${error.message ?: error::class.java.simpleName}"
                )
                return signals
            }

            if (summary.result.isOk()) {
                signals += RuntimeSignal.SnapshotUpdated(summary)
            } else {
                signals += RuntimeSignal.Status("Refresh failed: ${summary.result.describe()}")
            }
        }

        synchronized(stateLock) {
            if (activeSessionHandle != handle) {
                return signals
            }
            // Render packets are refreshed only for the current active handle; stale handle
            // refresh is intentionally dropped to avoid cross-session packet aliasing.
            val refreshResult = renderHostAdapter.refreshPacket()
            if (refreshResult.lease != null) {
                signals += RuntimeSignal.RenderPacketReady(refreshResult.lease)
            } else {
                signals += RuntimeSignal.Status(
                    refreshResult.unavailableReason ?: "Render export unavailable"
                )
            }
        }

        return signals
    }

    private fun releaseActiveSession(expectedHandle: Long) {
        synchronized(stateLock) {
            if (activeSessionHandle != expectedHandle) {
                return
            }
            // Session teardown order is host-defined:
            // lease -> native transport release -> zero active handle.
            // Packet-backed ByteBuffer views are only valid while the native packet handle is alive.
            // Release packet leases before tearing down the owning runtime session.
            renderHostAdapter.releasePacket()
            transport.destroySession(expectedHandle)
            activeSessionHandle = 0L
        }
    }

    private companion object {
        private const val ABI_VERSION = 1
        private const val DEFAULT_SCENARIO_ID = "sol-system"
        private const val DEFAULT_ROOT_BRANCH_ID = "main"
        private const val REFRESH_INTERVAL_MS = 1_000L
    }
}

internal sealed interface RuntimeSignal {
    data class Connected(val handle: Long) : RuntimeSignal
    data class Status(val message: String) : RuntimeSignal
    data class SnapshotUpdated(val summary: NativeSnapshotSummaryResult) : RuntimeSignal
    data class CommandApplied(
        val commandLabel: String,
        val summary: NativeSnapshotSummaryResult,
    ) : RuntimeSignal
    data class RenderPacketReady(val lease: PacketLease) : RuntimeSignal
    data class Unavailable(val message: String, val detail: String? = null) : RuntimeSignal
}

sealed interface RuntimeCommand {
    val label: String

    data class AdvanceEpoch(val deltaSeconds: Double) : RuntimeCommand {
        override val label: String = "timeline.advance_epoch"
    }

    data object PausePlayback : RuntimeCommand {
        override val label: String = "playback.pause"
    }

    data object ResumePlayback : RuntimeCommand {
        override val label: String = "playback.resume"
    }

    data class SetPlaybackRate(val simSecondsPerRealSecond: Double) : RuntimeCommand {
        override val label: String = "playback.set_rate"
    }

    data class SetObserverMode(val mode: RuntimeObserverMode) : RuntimeCommand {
        override val label: String = "observer.set_mode"
    }

    data class FocusBody(val bodyId: String?) : RuntimeCommand {
        override val label: String = "observer.focus_body"
    }
}

enum class RuntimeObserverMode(val nativeCode: Int) {
    Free(0),
    FollowSelected(1),
    FollowHost(2),
    SystemFrame(3),
}

internal data class NativeRuntimeCommandPayload(
    val kind: Int,
    val bodyIdUtf8: ByteArray? = null,
    val observerMode: Int = RuntimeObserverMode.Free.nativeCode,
    val deltaSeconds: Double = 0.0,
    val simSecondsPerRealSecond: Double = 0.0,
    val recordedAtUnixMs: Long = System.currentTimeMillis(),
)

private fun RuntimeCommand.toNativePayload(): NativeRuntimeCommandPayload = when (this) {
    is RuntimeCommand.AdvanceEpoch -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_ADVANCE_EPOCH,
        deltaSeconds = deltaSeconds,
    )

    RuntimeCommand.PausePlayback -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_PAUSE_PLAYBACK,
    )

    RuntimeCommand.ResumePlayback -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_RESUME_PLAYBACK,
    )

    is RuntimeCommand.SetPlaybackRate -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_PLAYBACK_RATE,
        simSecondsPerRealSecond = simSecondsPerRealSecond,
    )

    is RuntimeCommand.SetObserverMode -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_OBSERVER_MODE,
        observerMode = mode.nativeCode,
    )

    is RuntimeCommand.FocusBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_FOCUS_BODY,
        bodyIdUtf8 = bodyId?.toByteArray(StandardCharsets.UTF_8),
    )
}

internal interface NativeRuntimeTransport {
    fun ensureLibraryLoaded(): NativeLibraryLoadOutcome

    fun createSession(
        scenarioId: String,
        rootBranchId: String,
    ): NativeCreateSessionResult

    fun runtimeInfo(handle: Long): NativeRuntimeInfoResult

    fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult

    fun refreshSession(handle: Long): NativeSnapshotSummaryResult

    fun applyCommand(handle: Long, command: NativeRuntimeCommandPayload): NativeSnapshotSummaryResult

    fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult

    fun releaseVulkanScene(packetHandle: Long)

    fun destroySession(handle: Long)
}

internal sealed interface NativeLibraryLoadOutcome {
    data object Success : NativeLibraryLoadOutcome
    data class Failure(val reason: String) : NativeLibraryLoadOutcome
}

internal object JniNativeRuntimeTransport : NativeRuntimeTransport {
    private const val LIBRARY_NAME = "solarlab_v2"

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var loadFailure: String? = null

    override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    val failure = runCatching { System.loadLibrary(LIBRARY_NAME) }
                        .exceptionOrNull()
                    loadFailure = failure?.let { throwable ->
                        val summary = throwable.message?.takeIf { it.isNotBlank() }
                            ?: throwable::class.java.simpleName
                        "Unable to load native library '$LIBRARY_NAME': $summary"
                    }
                    loadAttempted = true
                }
            }
        }

        return loadFailure?.let(NativeLibraryLoadOutcome::Failure) ?: NativeLibraryLoadOutcome.Success
    }

    override fun createSession(scenarioId: String, rootBranchId: String): NativeCreateSessionResult {
        val scenarioBytes = scenarioId.toByteArray(StandardCharsets.UTF_8)
        val branchBytes = rootBranchId.toByteArray(StandardCharsets.UTF_8)

        return nativeCreateSession(
            scenarioIdUtf8 = scenarioBytes,
            rootBranchIdUtf8 = branchBytes,
            createdAtUnixMs = System.currentTimeMillis(),
            timelineSemantics = NATIVE_TIMELINE_SEMANTICS_BRANCHED_SANDBOX,
            liveUpdatesEnabled = true,
            cpuBackend = NATIVE_CPU_BACKEND_SIMD_ARM64,
            gpuBackend = NATIVE_GPU_BACKEND_NONE,
        )
    }

    override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult = nativeRuntimeInfo(handle)

    override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult = nativeSnapshotSummary(handle)

    override fun refreshSession(handle: Long): NativeSnapshotSummaryResult = nativeRefreshSession(handle)

    override fun applyCommand(
        handle: Long,
        command: NativeRuntimeCommandPayload,
    ): NativeSnapshotSummaryResult = nativeApplyCommand(
        handle = handle,
        kind = command.kind,
        bodyIdUtf8 = command.bodyIdUtf8,
        observerMode = command.observerMode,
        deltaSeconds = command.deltaSeconds,
        simSecondsPerRealSecond = command.simSecondsPerRealSecond,
        recordedAtUnixMs = command.recordedAtUnixMs,
    )

    override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult =
        nativeExportVulkanScene(handle)

    override fun releaseVulkanScene(packetHandle: Long) {
        if (packetHandle == 0L) return
        runCatching {
            nativeReleaseVulkanScene(packetHandle)
        }
    }

    override fun destroySession(handle: Long) {
        if (handle == 0L) return
        runCatching {
            nativeDestroySession(handle)
        }
    }

    private external fun nativeCreateSession(
        scenarioIdUtf8: ByteArray,
        rootBranchIdUtf8: ByteArray,
        createdAtUnixMs: Long,
        timelineSemantics: Int,
        liveUpdatesEnabled: Boolean,
        cpuBackend: Int,
        gpuBackend: Int,
    ): NativeCreateSessionResult

    private external fun nativeDestroySession(handle: Long): NativeResult

    private external fun nativeRuntimeInfo(handle: Long): NativeRuntimeInfoResult

    private external fun nativeSnapshotSummary(handle: Long): NativeSnapshotSummaryResult

    private external fun nativeRefreshSession(handle: Long): NativeSnapshotSummaryResult

    private external fun nativeApplyCommand(
        handle: Long,
        kind: Int,
        bodyIdUtf8: ByteArray?,
        observerMode: Int,
        deltaSeconds: Double,
        simSecondsPerRealSecond: Double,
        recordedAtUnixMs: Long,
    ): NativeSnapshotSummaryResult

    private external fun nativeExportVulkanScene(handle: Long): NativeVulkanScenePacketResult

    private external fun nativeReleaseVulkanScene(packetHandle: Long): NativeResult

    private const val NATIVE_TIMELINE_SEMANTICS_BRANCHED_SANDBOX = 1
    private const val NATIVE_CPU_BACKEND_SIMD_ARM64 = 1
    private const val NATIVE_GPU_BACKEND_NONE = 0
}

internal data class NativeResult(
    val code: Int,
    val context: String = "no context"
) {
    fun isOk(): Boolean = code == NATIVE_STATUS_OK

    fun describe(): String = when (code) {
        NATIVE_STATUS_OK -> "ok"
        NATIVE_STATUS_INVALID_ARGUMENT -> "invalid argument"
        NATIVE_STATUS_NOT_READY -> "not ready"
        NATIVE_STATUS_INTERNAL_ERROR -> "internal error"
        else -> "unknown($code)"
    }

    private companion object {
        private const val NATIVE_STATUS_OK = 0
        private const val NATIVE_STATUS_INVALID_ARGUMENT = 1
        private const val NATIVE_STATUS_NOT_READY = 2
        private const val NATIVE_STATUS_INTERNAL_ERROR = 3
    }
}

internal data class NativeCreateSessionResult(
    val result: NativeResult,
    val handle: Long,
    val abiVersion: Int,
    val cpuBackend: Int,
    val gpuBackend: Int,
)

internal data class NativeRuntimeInfoResult(
    val result: NativeResult,
    val abiVersion: Int,
    val cpuBackend: Int,
    val gpuBackend: Int,
) {
    fun cpuBackendLabel(): String = when (cpuBackend) {
        0 -> "reference-scalar"
        1 -> "simd-arm64"
        2 -> "simd-x64"
        else -> "unknown($cpuBackend)"
    }

    fun gpuBackendLabel(): String = when (gpuBackend) {
        0 -> "none"
        1 -> "vulkan"
        2 -> "metal"
        3 -> "webgpu-class"
        else -> "unknown($gpuBackend)"
    }
}

internal data class NativeSnapshotSummaryResult(
    val result: NativeResult,
    val scenarioId: String,
    val activeBranchId: String,
    val bodyCount: Int,
    val epochSeconds: Double,
    val paused: Boolean,
    val simSecondsPerRealSecond: Double,
    val observerMode: Int,
    val timelineSemantics: Int,
)

internal data class NativeVulkanScenePacketResult(
    val result: NativeResult,
    val packet: NativeVulkanScenePacket? = null,
)

internal data class NativeVulkanCameraPacket(
    val frameOriginX: Double,
    val frameOriginY: Double,
    val frameOriginZ: Double,
    val positionFromOriginX: Float,
    val positionFromOriginY: Float,
    val positionFromOriginZ: Float,
    val targetFromOriginX: Float,
    val targetFromOriginY: Float,
    val targetFromOriginZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float,
    val verticalFovDegrees: Float,
    val exposure: Float,
)

internal data class NativeRenderDiagnostics(
    val frameNumber: Long,
    val cpuExtractMs: Float,
    val gpuUploadMs: Float,
    val droppedFrames: Int,
)

internal data class NativeVulkanScenePacket(
    val packetHandle: Long,
    val sceneRevision: String,
    val epochSeconds: Double,
    val observerMode: Int,
    val timelineSemantics: Int,
    val camera: NativeVulkanCameraPacket,
    val bodyCount: Int,
    val tracerCount: Int,
    val trailSpanCount: Int,
    val trailVertexCount: Int,
    val directionalLightCount: Int,
    val diagnostics: NativeRenderDiagnostics,
    val provenanceSource: String?,
    val provenanceVersion: String?,
    val provenanceManifestId: String?,
    val provenanceManifestDigest: String?,
    val provenancePackageDigest: String?,
    val bodyInstances: ByteBuffer?,
    val tracerInstances: ByteBuffer?,
    val trailSpans: ByteBuffer?,
    val trailVertices: ByteBuffer?,
    val directionalLights: ByteBuffer?,
) {
    fun summaryLine(): String {
        val uploadBytes = listOf(
            bodyInstances?.capacity() ?: 0,
            tracerInstances?.capacity() ?: 0,
            trailSpans?.capacity() ?: 0,
            trailVertices?.capacity() ?: 0,
            directionalLights?.capacity() ?: 0,
        ).sum()
        return "bodies=$bodyCount, tracers=$tracerCount, trails=$trailSpanCount/$trailVertexCount, lights=$directionalLightCount, uploadBytes=$uploadBytes"
    }
}

private const val NATIVE_COMMAND_ADVANCE_EPOCH = 0
private const val NATIVE_COMMAND_PAUSE_PLAYBACK = 1
private const val NATIVE_COMMAND_RESUME_PLAYBACK = 2
private const val NATIVE_COMMAND_SET_PLAYBACK_RATE = 3
private const val NATIVE_COMMAND_SET_OBSERVER_MODE = 4
private const val NATIVE_COMMAND_FOCUS_BODY = 5
