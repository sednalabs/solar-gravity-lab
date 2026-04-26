package com.sednalabs.solarlab.runtime

import android.util.Log
import com.sednalabs.solarlab.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
/**
 * Rust runtime boundary for Android.
 * 
 * --- Handle Ownership Rules ---
 * 1. Kotlin owns the lifecycle of the `activeSessionHandle`.
 * 2. Successful `connect()` or `createSession()` calls return an "owned" handle.
 * 3. The caller MUST ensure `destroySession()` is called via the transport when the 
 *    session is no longer needed or if the boundary connection fails.
 * 4. Failure to release handles results in native memory leaks in the Rust world.
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
        logInfo("connect.ensureLibraryLoaded.begin")
        val loadOutcome = transport.ensureLibraryLoaded()
        if (loadOutcome is NativeLibraryLoadOutcome.Failure) {
            logError("connect.ensureLibraryLoaded.failure reason=${loadOutcome.reason}")
            trySend(RuntimeSignal.Unavailable(loadOutcome.reason))
            close()
            return@callbackFlow
        }
        logInfo("connect.ensureLibraryLoaded.success")

        trySend(
            RuntimeSignal.Notice(
                message = "Native runtime library loaded",
                level = RuntimeNoticeLevel.Success,
            )
        )

        logInfo(
            "connect.createSession.begin scenario=$DEFAULT_SCENARIO_ID branch=$DEFAULT_ROOT_BRANCH_ID abi=$ABI_VERSION gpu=${BuildConfig.PREFERRED_GPU_BACKEND}"
        )
        val createResult = runCatching {
            transport.createSession(
                scenarioId = DEFAULT_SCENARIO_ID,
                rootBranchId = DEFAULT_ROOT_BRANCH_ID,
            )
        }.getOrElse { error ->
            logError(
                "connect.createSession.failure error=${error.message ?: error::class.java.simpleName}",
                error,
            )
            trySend(
                RuntimeSignal.Unavailable(
                    message = "Native runtime session adapter is unavailable",
                    detail = error.message ?: error::class.java.simpleName
                )
            )
            close()
            return@callbackFlow
        }
        logInfo(
            "connect.createSession.result handle=${createResult.handle} status=${createResult.result.describe()} abi=${createResult.abiVersion}"
        )

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

        logInfo("connect.runtimeInfo.begin handle=$handle")
        val runtimeInfoResult = runCatching {
            transport.runtimeInfo(handle)
        }.getOrElse { error ->
            logError(
                "connect.runtimeInfo.failure handle=$handle error=${error.message ?: error::class.java.simpleName}",
                error,
            )
            trySend(
                RuntimeSignal.Notice(
                    message = "Runtime info unavailable: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
            awaitClose {
                releaseActiveSession(handle)
            }
            return@callbackFlow
        }
        logInfo(
            "connect.runtimeInfo.result handle=$handle status=${runtimeInfoResult.result.describe()} cpu=${runtimeInfoResult.cpuBackendLabel()} gpu=${runtimeInfoResult.gpuBackendLabel()}"
        )

        if (runtimeInfoResult.result.isOk()) {
            trySend(
                RuntimeSignal.RuntimeInfoAvailable(
                    cpuBackendLabel = runtimeInfoResult.cpuBackendLabel(),
                    requestedGpuBackendLabel = preferredGpuBackendLabel(BuildConfig.PREFERRED_GPU_BACKEND),
                    gpuBackendLabel = runtimeInfoResult.gpuBackendLabel(),
                    workloadSummary = runtimeInfoResult.gpuWorkloadSummary(),
                    interopErrorBudgetSummary = runtimeInfoResult.gpuInteropErrorBudgetSummary(),
                )
            )
        } else {
            trySend(
                RuntimeSignal.Notice(
                    message = "Runtime info unavailable: ${runtimeInfoResult.result.describe()}",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        val initialSignals = refreshSignalsForHandle(
            handle,
            includeSummary = true,
            traceLabel = "connect.initial-refresh",
        )
        initialSignals.forEach { trySend(it) }
        var latestSummary = extractLatestSnapshotSummary(initialSignals)

        if (extractBodyCountFrom(initialSignals) == 0L) {
            logInfo("connect.seed.begin handle=$handle")
            ensureStartupSeedApplied(handle).forEach { trySend(it) }
            logInfo("connect.seed.refresh.begin handle=$handle")
            val seededSignals = refreshSignalsForHandle(
                handle,
                includeSummary = true,
                traceLabel = "connect.seeded-refresh",
            )
            seededSignals.forEach { trySend(it) }
            latestSummary = extractLatestSnapshotSummary(seededSignals) ?: latestSummary
        }

        latestSummary?.let { summary ->
            logInfo(
                "connect.playback-config.begin handle=$handle paused=${summary.paused} rate=${summary.simSecondsPerRealSecond}"
            )
            ensureStartupPlaybackConfigured(handle, summary).forEach { trySend(it) }
        }

        val refreshJob = launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val activeHandle = synchronized(stateLock) { activeSessionHandle }
                if (activeHandle == 0L) {
                    continue
                }
                refreshSignalsForHandle(
                    handle = activeHandle,
                    includeSummary = true,
                    advancePlayback = true,
                ).forEach { trySend(it) }
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
            return listOf(
                RuntimeSignal.Notice(
                    message = "Refresh skipped: no active runtime session",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        return refreshSignalsForHandle(handle, includeSummary = true)
    }

    // Dispatches UI command into native runtime and returns resulting status + snapshot signals.
    override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
        val handle = synchronized(stateLock) { activeSessionHandle }
        if (handle == 0L) {
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command skipped: no active runtime session",
                    level = RuntimeNoticeLevel.Warning,
                )
            )
        }

        val commandResult = runCatching {
            transport.applyCommand(handle, command.toNativePayload())
        }.getOrElse { error ->
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command failed: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Error,
                )
            )
        }

        if (!commandResult.result.isOk()) {
            return listOf(
                RuntimeSignal.Notice(
                    message = "Command failed: ${commandResult.result.describe()}",
                    level = RuntimeNoticeLevel.Error,
                )
            )
        }

        val signals = mutableListOf<RuntimeSignal>()
        signals += RuntimeSignal.CommandApplied(
            command = command,
            commandLabel = command.label,
            summary = commandResult,
        )
        signals += refreshSignalsForHandle(handle, includeSummary = false)
        return signals
    }

    // Collects one snapshot refresh bundle for one handle:
    // optional world-state summary plus render packet lease.
    private fun refreshSignalsForHandle(
        handle: Long,
        includeSummary: Boolean,
        advancePlayback: Boolean = false,
        traceLabel: String? = null,
    ): List<RuntimeSignal> {
        val signals = mutableListOf<RuntimeSignal>()
        traceLabel?.let { label ->
            logInfo(
                "$label.begin handle=$handle includeSummary=$includeSummary advancePlayback=$advancePlayback"
            )
        }

        if (includeSummary) {
            traceLabel?.let { label ->
                logInfo("$label.refreshSession.begin handle=$handle")
            }
            var summary = runCatching {
                transport.refreshSession(handle)
            }.getOrElse { error ->
                traceLabel?.let { label ->
                    logError(
                        "$label.refreshSession.failure handle=$handle error=${error.message ?: error::class.java.simpleName}",
                        error,
                    )
                }
                signals += RuntimeSignal.Notice(
                    message = "Refresh unavailable: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Error,
                )
                return signals
            }
            traceLabel?.let { label ->
                logInfo(
                    "$label.refreshSession.result handle=$handle status=${summary.result.describe()} paused=${summary.paused} bodyCount=${summary.bodyCount} rate=${summary.simSecondsPerRealSecond}"
                )
            }

            if (summary.result.isOk()) {
                if (advancePlayback && !summary.paused) {
                    val deltaSeconds = (REFRESH_INTERVAL_MS.toDouble() / 1_000.0) *
                        summary.simSecondsPerRealSecond.coerceAtLeast(0.0)
                    if (deltaSeconds > 0.0) {
                        val advanceResult = runCatching {
                            transport.applyCommand(
                                handle,
                                NativeRuntimeCommandPayload(
                                    kind = NATIVE_COMMAND_ADVANCE_EPOCH,
                                    deltaSeconds = deltaSeconds,
                                ),
                            )
                        }.getOrElse { error ->
                            signals += RuntimeSignal.Notice(
                                message = "Live playback advance failed: ${error.message ?: error::class.java.simpleName}",
                                level = RuntimeNoticeLevel.Warning,
                            )
                            null
                        }

                        if (advanceResult != null) {
                            if (advanceResult.result.isOk()) {
                                summary = advanceResult
                            } else {
                                signals += RuntimeSignal.Notice(
                                    message = "Live playback advance rejected: ${advanceResult.result.describe()}",
                                    level = RuntimeNoticeLevel.Warning,
                                )
                            }
                        }
                    }
                }
                signals += RuntimeSignal.SnapshotUpdated(summary)
            } else {
                signals += RuntimeSignal.Notice(
                    message = "Refresh failed: ${summary.result.describe()}",
                    level = RuntimeNoticeLevel.Error,
                )
            }
        }

        synchronized(stateLock) {
            if (activeSessionHandle != handle) {
                traceLabel?.let { label ->
                    logInfo(
                        "$label.render.refresh.skipped handle=$handle activeHandle=$activeSessionHandle"
                    )
                }
                return signals
            }
            // Render packets are refreshed only for the current active handle; stale handle
            // refresh is intentionally dropped to avoid cross-session packet aliasing.
            traceLabel?.let { label ->
                logInfo("$label.render.refresh.begin handle=$handle")
            }
            val refreshResult = runCatching {
                renderHostAdapter.refreshPacket()
            }.getOrElse { error ->
                traceLabel?.let { label ->
                    logError(
                        "$label.render.refresh.failure handle=$handle error=${error.message ?: error::class.java.simpleName}",
                        error,
                    )
                }
                signals += RuntimeSignal.RenderUnavailable(
                    reason = "Render export unavailable: ${error.message ?: error::class.java.simpleName}"
                )
                return signals
            }
            if (refreshResult.lease != null) {
                traceLabel?.let { label ->
                    logInfo("$label.render.refresh.result handle=$handle lease=ready")
                }
                signals += RuntimeSignal.RenderPacketReady(refreshResult.lease)
            } else {
                traceLabel?.let { label ->
                    logInfo(
                        "$label.render.refresh.result handle=$handle lease=missing reason=${refreshResult.unavailableReason ?: "unknown"}"
                    )
                }
                signals += RuntimeSignal.RenderUnavailable(
                    reason = refreshResult.unavailableReason ?: "Render export unavailable"
                )
            }
        }

        traceLabel?.let { label ->
            logInfo("$label.end handle=$handle signalCount=${signals.size}")
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

    private fun extractBodyCountFrom(signals: List<RuntimeSignal>): Long {
        return signals
            .asSequence()
            .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
            .firstOrNull()
            ?.summary
            ?.bodyCount
            ?.toLong()
            ?: 0L
    }

    private fun extractLatestSnapshotSummary(
        signals: List<RuntimeSignal>,
    ): NativeSnapshotSummaryResult? {
        return signals
            .asSequence()
            .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
            .lastOrNull()
            ?.summary
    }

    private fun ensureStartupPlaybackConfigured(
        handle: Long,
        summary: NativeSnapshotSummaryResult,
    ): List<RuntimeSignal> {
        logInfo(
            "ensureStartupPlaybackConfigured.begin handle=$handle paused=${summary.paused} rate=${summary.simSecondsPerRealSecond}"
        )
        val signals = mutableListOf<RuntimeSignal>()
        var shouldRefresh = false

        if (summary.paused) {
            val resumeResult = runCatching {
                transport.applyCommand(
                    handle,
                    NativeRuntimeCommandPayload(
                        kind = NATIVE_COMMAND_RESUME_PLAYBACK,
                    ),
                )
            }.getOrElse { error ->
                signals += RuntimeSignal.Notice(
                    message = "Startup playback resume failed: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Warning,
                )
                null
            }

            if (resumeResult != null) {
                if (resumeResult.result.isOk()) {
                    shouldRefresh = true
                    signals += RuntimeSignal.Notice(
                        message = "Startup playback resumed to keep the solar-system view in motion",
                        level = RuntimeNoticeLevel.Success,
                    )
                } else {
                    signals += RuntimeSignal.Notice(
                        message = "Startup playback resume rejected: ${resumeResult.result.describe()}",
                        level = RuntimeNoticeLevel.Warning,
                    )
                }
            }
        }

        if (summary.simSecondsPerRealSecond < STARTUP_MIN_VISIBLE_PLAYBACK_RATE) {
            val rateResult = runCatching {
                transport.applyCommand(
                    handle,
                    NativeRuntimeCommandPayload(
                        kind = NATIVE_COMMAND_SET_PLAYBACK_RATE,
                        simSecondsPerRealSecond = STARTUP_DEFAULT_PLAYBACK_RATE,
                    ),
                )
            }.getOrElse { error ->
                signals += RuntimeSignal.Notice(
                    message = "Startup playback rate update failed: ${error.message ?: error::class.java.simpleName}",
                    level = RuntimeNoticeLevel.Warning,
                )
                null
            }

            if (rateResult != null) {
                if (rateResult.result.isOk()) {
                    shouldRefresh = true
                    signals += RuntimeSignal.Notice(
                        message = "Startup playback rate set to ${STARTUP_DEFAULT_PLAYBACK_RATE.toLong()} sim-seconds per real-second",
                        level = RuntimeNoticeLevel.Info,
                    )
                } else {
                    signals += RuntimeSignal.Notice(
                        message = "Startup playback rate update rejected: ${rateResult.result.describe()}",
                        level = RuntimeNoticeLevel.Warning,
                    )
                }
            }
        }

        if (shouldRefresh) {
            signals += refreshSignalsForHandle(
                handle,
                includeSummary = true,
                traceLabel = "connect.playback-refresh",
            )
        }

        logInfo(
            "ensureStartupPlaybackConfigured.end handle=$handle shouldRefresh=$shouldRefresh signalCount=${signals.size}"
        )

        return signals
    }

    private fun ensureStartupSeedApplied(handle: Long): List<RuntimeSignal> {
        logInfo("ensureStartupSeedApplied.begin handle=$handle")
        val signals = mutableListOf<RuntimeSignal>()
        val commandResult = runCatching {
            transport.applyCommand(handle, RuntimeCommand.SeedCanonicalSolarSystem.toNativePayload())
        }.getOrElse { error ->
            logError(
                "ensureStartupSeedApplied.failure handle=$handle error=${error.message ?: error::class.java.simpleName}",
                error,
            )
            signals += RuntimeSignal.Notice(
                message = "Startup canonical seed command failed: ${error.message ?: error::class.java.simpleName}",
                level = RuntimeNoticeLevel.Error,
            )
            return signals
        }
        logInfo(
            "ensureStartupSeedApplied.result handle=$handle status=${commandResult.result.describe()}"
        )

        if (!commandResult.result.isOk()) {
            signals += RuntimeSignal.Notice(
                message = "Startup canonical seed command rejected: ${commandResult.result.describe()}",
                level = RuntimeNoticeLevel.Warning,
            )
            return signals
        }

        signals += RuntimeSignal.Notice(
            message = "Seeded canonical solar system via Rust authority for session $handle",
            level = RuntimeNoticeLevel.Info,
        )

        logInfo("ensureStartupSeedApplied.end handle=$handle signalCount=${signals.size}")
        return signals
    }


    private companion object {
        private const val LOG_TAG = "SolarLabRuntimeBridge"
        private const val ABI_VERSION = 3
        private const val DEFAULT_SCENARIO_ID = "sol-system"
        private const val DEFAULT_ROOT_BRANCH_ID = "main"
        private const val REFRESH_INTERVAL_MS = 500L
        private const val STARTUP_MIN_VISIBLE_PLAYBACK_RATE = 3_600.0
        private const val STARTUP_DEFAULT_PLAYBACK_RATE = 21_600.0

        private fun logInfo(message: String) {
            if (runCatching { Log.i(LOG_TAG, message) }.isFailure) {
                println("$LOG_TAG I $message")
            }
        }

        private fun logError(message: String, error: Throwable? = null) {
            if (runCatching { Log.e(LOG_TAG, message, error) }.isFailure) {
                println("$LOG_TAG E $message")
                error?.printStackTrace()
            }
        }
    }
}

internal sealed interface RuntimeSignal {
    data class Connected(val handle: Long) : RuntimeSignal
    data class RuntimeInfoAvailable(
        val cpuBackendLabel: String,
        val gpuBackendLabel: String,
        val requestedGpuBackendLabel: String? = null,
        val workloadSummary: String? = null,
        val interopErrorBudgetSummary: String? = null,
    ) : RuntimeSignal
    data class Notice(
        val message: String,
        val level: RuntimeNoticeLevel = RuntimeNoticeLevel.Info,
    ) : RuntimeSignal
    data class SnapshotUpdated(val summary: NativeSnapshotSummaryResult) : RuntimeSignal
    data class CommandApplied(
        val command: RuntimeCommand,
        val commandLabel: String,
        val summary: NativeSnapshotSummaryResult,
    ) : RuntimeSignal
    data class RenderPacketReady(val lease: PacketLease) : RuntimeSignal
    data class RenderUnavailable(val reason: String) : RuntimeSignal
    data class Unavailable(val message: String, val detail: String? = null) : RuntimeSignal
}

internal enum class RuntimeNoticeLevel {
    Info,
    Success,
    Warning,
    Error,
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

    data object SeedCanonicalSolarSystem : RuntimeCommand {
        override val label: String = "world.seed_canonical_solar_system"
    }

    data class SpawnBody(
        val bodyId: String,
        val bodyClass: RuntimeBodyClass = RuntimeBodyClass.Planet,
        val positionX: Double = 0.0,
        val positionY: Double = 0.0,
        val positionZ: Double = 0.0,
        val velocityX: Double = 0.0,
        val velocityY: Double = 0.0,
        val velocityZ: Double = 0.0,
        val massKg: Double,
        val radiusM: Double,
    ) : RuntimeCommand {
        override val label: String = "body.spawn"
    }

    data class RemoveBody(val bodyId: String) : RuntimeCommand {
        override val label: String = "body.remove"
    }

    data class SetBodyKinematics(
        val bodyId: String,
        val positionX: Double,
        val positionY: Double,
        val positionZ: Double,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
    ) : RuntimeCommand {
        override val label: String = "body.set_kinematics"
    }

    data class CreateCheckpoint(
        val checkpointId: String? = null,
        val checkpointLabel: String? = null,
    ) : RuntimeCommand {
        override val label: String = "branching.create_checkpoint"
    }

    data class CreateBranchFromCheckpoint(
        val checkpointId: String,
        val newBranchId: String? = null,
    ) : RuntimeCommand {
        override val label: String = "branching.create_branch_from_checkpoint"
    }
}

enum class RuntimeBodyClass(val nativeCode: Int) {
    Star(0),
    Planet(1),
    DwarfPlanet(2),
    Moon(3),
    SmallBody(4),
    Tracer(5),
    Spacecraft(6),
    Custom(7),
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
    val bodyClass: Int = NATIVE_BODY_CLASS_PLANET,
    val bodyPositionX: Double = 0.0,
    val bodyPositionY: Double = 0.0,
    val bodyPositionZ: Double = 0.0,
    val bodyVelocityX: Double = 0.0,
    val bodyVelocityY: Double = 0.0,
    val bodyVelocityZ: Double = 0.0,
    val bodyMassKg: Double = 0.0,
    val bodyRadiusM: Double = 0.0,
    val checkpointIdUtf8: ByteArray? = null,
    val checkpointLabelUtf8: ByteArray? = null,
    val newBranchIdUtf8: ByteArray? = null,
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

    RuntimeCommand.SeedCanonicalSolarSystem -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SEED_CANONICAL_SOLAR_SYSTEM,
    )

    is RuntimeCommand.SpawnBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SPAWN_BODY,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
        bodyClass = bodyClass.nativeCode,
        bodyPositionX = positionX,
        bodyPositionY = positionY,
        bodyPositionZ = positionZ,
        bodyVelocityX = velocityX,
        bodyVelocityY = velocityY,
        bodyVelocityZ = velocityZ,
        bodyMassKg = massKg,
        bodyRadiusM = radiusM,
    )

    is RuntimeCommand.RemoveBody -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_REMOVE_BODY,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
    )

    is RuntimeCommand.SetBodyKinematics -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_SET_BODY_KINEMATICS,
        bodyIdUtf8 = bodyId.toByteArray(StandardCharsets.UTF_8),
        bodyPositionX = positionX,
        bodyPositionY = positionY,
        bodyPositionZ = positionZ,
        bodyVelocityX = velocityX,
        bodyVelocityY = velocityY,
        bodyVelocityZ = velocityZ,
    )

    is RuntimeCommand.CreateCheckpoint -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_CREATE_CHECKPOINT,
        checkpointIdUtf8 = checkpointId?.toByteArray(StandardCharsets.UTF_8),
        checkpointLabelUtf8 = checkpointLabel?.toByteArray(StandardCharsets.UTF_8),
    )

    is RuntimeCommand.CreateBranchFromCheckpoint -> NativeRuntimeCommandPayload(
        kind = NATIVE_COMMAND_CREATE_BRANCH_FROM_CHECKPOINT,
        checkpointIdUtf8 = checkpointId.toByteArray(StandardCharsets.UTF_8),
        newBranchIdUtf8 = newBranchId?.toByteArray(StandardCharsets.UTF_8),
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

    fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult?

    fun releaseVulkanScene(packetHandle: Long)

    fun destroySession(handle: Long)
}

internal sealed interface NativeLibraryLoadOutcome {
    data object Success : NativeLibraryLoadOutcome
    data class Failure(val reason: String) : NativeLibraryLoadOutcome
}

internal const val NATIVE_TIMELINE_SEMANTICS_BRANCHED_SANDBOX = 1
internal const val NATIVE_CPU_BACKEND_SIMD_ARM64 = 1
internal const val NATIVE_GPU_BACKEND_NONE = 0
internal const val NATIVE_GPU_BACKEND_VULKAN = 1
internal const val NATIVE_GPU_BACKEND_METAL = 2
internal const val NATIVE_GPU_BACKEND_WEBGPU_CLASS = 3
internal const val NATIVE_GPU_BACKEND_OPENCL = 4

internal fun preferredGpuBackendCode(preferredBackendRaw: String): Int {
    val normalized = preferredBackendRaw.trim()
        .lowercase(Locale.US)
        .replace(Regex("\\s+"), "")
    return when (normalized) {
        "", "none" -> NATIVE_GPU_BACKEND_NONE
        "vulkan" -> NATIVE_GPU_BACKEND_VULKAN
        "metal" -> NATIVE_GPU_BACKEND_METAL
        "webgpu", "webgpu-class", "webgpu_class" -> NATIVE_GPU_BACKEND_WEBGPU_CLASS
        "vulkan+opencl", "opencl+vulkan", "vulkan,opencl", "opencl,vulkan" -> NATIVE_GPU_BACKEND_OPENCL
        "opencl", "open-cl", "open_cl" -> NATIVE_GPU_BACKEND_OPENCL
        else -> NATIVE_GPU_BACKEND_NONE
    }
}

internal fun preferredGpuBackendLabel(preferredBackendRaw: String): String =
    when (
        preferredBackendRaw.trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "")
    ) {
        "", "none" -> "none"
        "vulkan" -> "vulkan"
        "metal" -> "metal"
        "webgpu", "webgpu-class", "webgpu_class" -> "webgpu-class"
        "vulkan+opencl", "opencl+vulkan", "vulkan,opencl", "opencl,vulkan" -> "vulkan+opencl"
        "opencl", "open-cl", "open_cl" -> "opencl"
        else -> "unsupported:${preferredBackendRaw.trim()}"
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
            gpuBackend = preferredGpuBackendCode(BuildConfig.PREFERRED_GPU_BACKEND),
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
        bodyClass = command.bodyClass,
        bodyPositionX = command.bodyPositionX,
        bodyPositionY = command.bodyPositionY,
        bodyPositionZ = command.bodyPositionZ,
        bodyVelocityX = command.bodyVelocityX,
        bodyVelocityY = command.bodyVelocityY,
        bodyVelocityZ = command.bodyVelocityZ,
        bodyMassKg = command.bodyMassKg,
        bodyRadiusM = command.bodyRadiusM,
        checkpointIdUtf8 = command.checkpointIdUtf8,
        checkpointLabelUtf8 = command.checkpointLabelUtf8,
        newBranchIdUtf8 = command.newBranchIdUtf8,
        observerMode = command.observerMode,
        deltaSeconds = command.deltaSeconds,
        simSecondsPerRealSecond = command.simSecondsPerRealSecond,
        recordedAtUnixMs = command.recordedAtUnixMs,
    )

    override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? =
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
        bodyClass: Int,
        bodyPositionX: Double,
        bodyPositionY: Double,
        bodyPositionZ: Double,
        bodyVelocityX: Double,
        bodyVelocityY: Double,
        bodyVelocityZ: Double,
        bodyMassKg: Double,
        bodyRadiusM: Double,
        checkpointIdUtf8: ByteArray?,
        checkpointLabelUtf8: ByteArray?,
        newBranchIdUtf8: ByteArray?,
        observerMode: Int,
        deltaSeconds: Double,
        simSecondsPerRealSecond: Double,
        recordedAtUnixMs: Long,
    ): NativeSnapshotSummaryResult

    private external fun nativeExportVulkanScene(handle: Long): NativeVulkanScenePacketResult?

    private external fun nativeReleaseVulkanScene(packetHandle: Long): NativeResult

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
        NATIVE_GPU_BACKEND_NONE -> "none"
        NATIVE_GPU_BACKEND_VULKAN -> "vulkan"
        NATIVE_GPU_BACKEND_METAL -> "metal"
        NATIVE_GPU_BACKEND_WEBGPU_CLASS -> "webgpu-class"
        NATIVE_GPU_BACKEND_OPENCL -> "opencl"
        else -> "unknown($gpuBackend)"
    }

    fun gpuWorkloadSummary(): String? = when (gpuBackend) {
        NATIVE_GPU_BACKEND_OPENCL ->
            "simulation=opencl(long-horizon tracer, forecast), rendering=vulkan(in-frame)"
        NATIVE_GPU_BACKEND_VULKAN -> "rendering=vulkan(realtime + in-frame)"
        else -> null
    }

    fun gpuInteropErrorBudgetSummary(): String? = when (gpuBackend) {
        NATIVE_GPU_BACKEND_OPENCL ->
            "sync=checkpoint-publication, budget=position<=5m velocity<=1mm/s drift<=10ppm"
        else -> null
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

internal const val NATIVE_COMMAND_ADVANCE_EPOCH = 0
private const val NATIVE_COMMAND_PAUSE_PLAYBACK = 1
internal const val NATIVE_COMMAND_RESUME_PLAYBACK = 2
internal const val NATIVE_COMMAND_SET_PLAYBACK_RATE = 3
private const val NATIVE_COMMAND_SET_OBSERVER_MODE = 4
private const val NATIVE_COMMAND_FOCUS_BODY = 5
private const val NATIVE_COMMAND_SPAWN_BODY = 6
private const val NATIVE_COMMAND_REMOVE_BODY = 7
private const val NATIVE_COMMAND_SET_BODY_KINEMATICS = 8
private const val NATIVE_COMMAND_CREATE_CHECKPOINT = 9
private const val NATIVE_COMMAND_CREATE_BRANCH_FROM_CHECKPOINT = 10
internal const val NATIVE_COMMAND_SEED_CANONICAL_SOLAR_SYSTEM = 11
private const val NATIVE_BODY_CLASS_STAR = 0
private const val NATIVE_BODY_CLASS_PLANET = 1
private const val NATIVE_BODY_CLASS_DWARF_PLANET = 2
private const val NATIVE_BODY_CLASS_MOON = 3
private const val NATIVE_BODY_CLASS_SMALL_BODY = 4
private const val NATIVE_BODY_CLASS_TRACER = 5
private const val NATIVE_BODY_CLASS_SPACECRAFT = 6
private const val NATIVE_BODY_CLASS_CUSTOM = 7
