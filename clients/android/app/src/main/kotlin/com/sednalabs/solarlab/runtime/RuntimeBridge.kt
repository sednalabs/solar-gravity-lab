package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.charset.StandardCharsets

/**
 * Rust runtime boundary for Android.
 *
 * Kotlin owns orchestration and lifecycle semantics.
 * Native transport owns ABI calls into `engine/ffi` via a JNI shim.
 */
interface RuntimeBridge {
    fun connect(): Flow<RuntimeSignal>
}

class JniRuntimeBridge(
    private val transport: NativeRuntimeTransport = JniNativeRuntimeTransport
) : RuntimeBridge {
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

        trySend(RuntimeSignal.Connected(handle = handle))
        if (createResult.abiVersion != ABI_VERSION) {
            trySend(
                RuntimeSignal.Status(
                    "Runtime ABI mismatch: expected=$ABI_VERSION, native=${createResult.abiVersion}"
                )
            )
        }

        val runtimeInfoResult = runCatching {
            transport.runtimeInfo(handle)
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Status(
                    "Runtime info unavailable: ${error.message ?: error::class.java.simpleName}"
                )
            )
            awaitClose {
                transport.destroySession(handle)
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

        val snapshotResult = runCatching {
            transport.snapshotSummary(handle)
        }.getOrElse { error ->
            trySend(
                RuntimeSignal.Status(
                    "Snapshot summary unavailable: ${error.message ?: error::class.java.simpleName}"
                )
            )
            awaitClose {
                transport.destroySession(handle)
            }
            return@callbackFlow
        }
        if (snapshotResult.result.isOk()) {
            trySend(
                RuntimeSignal.Status(
                    "Session ready: scenario=${snapshotResult.scenarioId}, branch=${snapshotResult.activeBranchId}, bodies=${snapshotResult.bodyCount}"
                )
            )
        } else {
            trySend(RuntimeSignal.Status("Snapshot summary unavailable: ${snapshotResult.result.describe()}"))
        }

        awaitClose {
            transport.destroySession(handle)
        }
    }

    private companion object {
        private const val ABI_VERSION = 1
        private const val DEFAULT_SCENARIO_ID = "sol-system"
        private const val DEFAULT_ROOT_BRANCH_ID = "main"
    }
}

sealed interface RuntimeSignal {
    data class Connected(val handle: Long) : RuntimeSignal
    data class Status(val message: String) : RuntimeSignal
    data class Unavailable(val message: String, val detail: String? = null) : RuntimeSignal
}

internal interface NativeRuntimeTransport {
    fun ensureLibraryLoaded(): NativeLibraryLoadOutcome

    fun createSession(
        scenarioId: String,
        rootBranchId: String,
    ): NativeCreateSessionResult

    fun runtimeInfo(handle: Long): NativeRuntimeInfoResult

    fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult

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
                        val summary = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName
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
)
