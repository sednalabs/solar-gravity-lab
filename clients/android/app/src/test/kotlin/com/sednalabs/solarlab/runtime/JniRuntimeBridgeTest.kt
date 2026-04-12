package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class JniRuntimeBridgeTest {
    @Test
    fun connect_seedsDefaultSolarSystem_whenInitialSnapshotIsEmpty() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(bodyCount = 0),
                    snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT),
                )
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            val seeded = collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == STARTUP_EXPECTED_BODY_COUNT }
            val startupPlaybackConfigured = collected
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Startup playback rate set") }
            seeded && startupPlaybackConfigured
        }

        assertEquals(listOf(42L), transport.runtimeInfoHandles)
        assertEquals(listOf(42L, 42L, 42L), transport.refreshedHandles)
        assertEquals(listOf(42L), renderHostAdapter.boundSessionHandles)
        assertEquals(3, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)

        assertTrue(
            "Expected startup to seed canonical world",
            transport.appliedCommands.any { it.kind == 11 },
        )
        assertTrue(
            "Expected startup to resume playback for visible motion",
            transport.appliedCommands.any { it.kind == 2 },
        )
        assertTrue(
            "Expected startup to set visible playback rate",
            transport.appliedCommands.any { it.kind == 3 },
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded canonical solar system via Rust authority") }
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == STARTUP_EXPECTED_BODY_COUNT }
        )
    }

    @Test
    fun connect_doesNotSeedDefaultSolarSystem_whenSnapshotAlreadyContainsBodies() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(snapshotSummary(bodyCount = 2))
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            val snapshotReady = collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == 2 }
            val startupPlaybackConfigured = collected
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Startup playback rate set") }
            snapshotReady && startupPlaybackConfigured
        }

        assertFalse(
            "Seed should not be issued when snapshot already has bodies",
            transport.appliedCommands.any { it.kind == 11 },
        )
        assertTrue(
            "Expected startup to resume playback for visible motion",
            transport.appliedCommands.any { it.kind == 2 },
        )
        assertTrue(
            "Expected startup to set visible playback rate",
            transport.appliedCommands.any { it.kind == 3 },
        )
        assertEquals(listOf(42L, 42L), transport.refreshedHandles)
        assertEquals(2, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)
        assertFalse(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded canonical solar system via Rust authority") }
        )
    }

    @Test
    fun connect_emitsOpenClRuntimeInfoLabel_whenNativeReportsOpenClBackend() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(snapshotSummary(bodyCount = 1))
            ),
            runtimeInfoGpuBackend = 4,
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            collected.any { it is RuntimeSignal.RuntimeInfoAvailable }
        }

        val runtimeInfo = signals
            .filterIsInstance<RuntimeSignal.RuntimeInfoAvailable>()
            .last()

        assertEquals("simd-arm64", runtimeInfo.cpuBackendLabel)
        assertEquals("opencl", runtimeInfo.gpuBackendLabel)
        assertTrue(runtimeInfo.workloadSummary?.contains("long-horizon") == true)
        assertTrue(runtimeInfo.interopErrorBudgetSummary?.contains("checkpoint-publication") == true)
    }

    @Test
    fun connect_keepsRefreshing_whenPlaybackIsLive() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(bodyCount = 2, paused = false, simSecondsPerRealSecond = 60.0),
                    snapshotSummary(bodyCount = 2, paused = false, simSecondsPerRealSecond = 60.0),
                )
            ),
        )
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = FakeRenderHostAdapter(),
        )

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { /* keep session alive for periodic refresh */ }
        }

        withTimeout(2_500) {
            while (transport.refreshedHandles.size < 3) {
                delay(25)
            }
        }

        job.cancel()
        scope.cancel()

        assertTrue(
            "Expected live playback to keep issuing periodic refreshes",
            transport.refreshedHandles.size >= 3,
        )
        assertTrue(
            "Expected live playback to keep emitting advance-epoch commands",
            transport.appliedCommands.any { command ->
                command.kind == 0 && command.deltaSeconds > 0.0
            },
        )
    }

    private suspend fun collectSignalsUntil(
        bridge: RuntimeBridge,
        predicate: (List<RuntimeSignal>) -> Boolean,
    ): List<RuntimeSignal> {
        val collected = mutableListOf<RuntimeSignal>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val job = scope.launch {
            bridge.connect().collect { signal ->
                collected += signal
                if (predicate(collected)) {
                    cancel()
                }
            }
        }

        withTimeout(2_000) {
            job.join()
        }

        scope.cancel()
        return collected.toList()
    }

    private class FakeRenderHostAdapter : RenderHostAdapter {
        val boundSessionHandles = mutableListOf<Long>()
        var refreshCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set

        override fun bindSession(sessionHandle: Long) {
            boundSessionHandles += sessionHandle
        }

        override fun refreshPacket(): RenderPacketRefreshResult {
            refreshCount += 1
            return RenderPacketRefreshResult(
                unavailableReason = "Render export unavailable: test stub"
            )
        }

        override fun releasePacket() {
            releaseCount += 1
        }
    }

    private class FakeNativeRuntimeTransport(
        private val refreshResults: ArrayDeque<NativeSnapshotSummaryResult>,
        private val runtimeInfoCpuBackend: Int = 1,
        private val runtimeInfoGpuBackend: Int = 0,
    ) : NativeRuntimeTransport {
        val runtimeInfoHandles = CopyOnWriteArrayList<Long>()
        val refreshedHandles = CopyOnWriteArrayList<Long>()
        val appliedCommands = CopyOnWriteArrayList<NativeRuntimeCommandPayload>()

        override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome = NativeLibraryLoadOutcome.Success

        override fun createSession(
            scenarioId: String,
            rootBranchId: String,
        ): NativeCreateSessionResult = NativeCreateSessionResult(
            result = NativeResult(code = 0),
            handle = 42L,
            abiVersion = 2,
            cpuBackend = runtimeInfoCpuBackend,
            gpuBackend = runtimeInfoGpuBackend,
        )

        override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult {
            runtimeInfoHandles += handle
            return NativeRuntimeInfoResult(
                result = NativeResult(code = 0),
                abiVersion = 2,
                cpuBackend = runtimeInfoCpuBackend,
                gpuBackend = runtimeInfoGpuBackend,
            )
        }

        override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult =
            error("snapshotSummary is unused in this test")

        override fun refreshSession(handle: Long): NativeSnapshotSummaryResult {
            refreshedHandles += handle
            return refreshResults.removeFirstOrNull()
                ?: snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT)
        }

        override fun applyCommand(
            handle: Long,
            command: NativeRuntimeCommandPayload,
        ): NativeSnapshotSummaryResult {
            appliedCommands += command
            return snapshotSummary(bodyCount = STARTUP_EXPECTED_BODY_COUNT)
        }

        override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? =
            error("exportVulkanScene is unused in this test")

        override fun releaseVulkanScene(packetHandle: Long) = Unit

        override fun destroySession(handle: Long) = Unit
    }

    private companion object {
        const val STARTUP_EXPECTED_BODY_COUNT = 365

        fun snapshotSummary(
            bodyCount: Int,
            paused: Boolean = true,
            simSecondsPerRealSecond: Double = 1.0,
        ): NativeSnapshotSummaryResult = NativeSnapshotSummaryResult(
            result = NativeResult(code = 0),
            scenarioId = "sol-system",
            activeBranchId = "main",
            bodyCount = bodyCount,
            epochSeconds = 0.0,
            paused = paused,
            simSecondsPerRealSecond = simSecondsPerRealSecond,
            observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
            timelineSemantics = 1,
        )
    }
}
