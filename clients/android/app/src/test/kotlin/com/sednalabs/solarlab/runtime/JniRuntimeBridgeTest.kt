package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

class JniRuntimeBridgeTest {
    @Test
    fun connect_seedsDefaultSolarSystem_whenInitialSnapshotIsEmpty() = runBlocking {
        val transport = FakeNativeRuntimeTransport(
            refreshResults = ArrayDeque(
                listOf(
                    snapshotSummary(bodyCount = 0),
                    snapshotSummary(bodyCount = 12),
                )
            ),
        )
        val renderHostAdapter = FakeRenderHostAdapter()
        val bridge = JniRuntimeBridge(
            transport = transport,
            renderHostAdapter = renderHostAdapter,
        )

        val signals = collectSignalsUntil(bridge) { collected ->
            collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == 12 }
        }

        assertEquals(listOf(42L), transport.runtimeInfoHandles)
        assertEquals(listOf(42L, 42L), transport.refreshedHandles)
        assertEquals(listOf(42L), renderHostAdapter.boundSessionHandles)
        assertEquals(2, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)

        val spawnedBodyIds = transport.appliedCommands.map { payload ->
            payload.bodyIdUtf8?.let { String(it, StandardCharsets.UTF_8) }
        }
        assertEquals(listOf("sun", "mercury", "venus", "earth", "moon", "mars", "jupiter", "saturn", "uranus", "neptune", "pluto", "ceres"), spawnedBodyIds)
        assertEquals(12, spawnedBodyIds.size)

        val earthPayload = transport.commandForBody("earth")
        val moonPayload = transport.commandForBody("moon")
        assertNotNull("Expected Earth spawn payload in startup seed", earthPayload)
        assertNotNull("Expected Moon spawn payload in startup seed", moonPayload)

        earthPayload!!
        moonPayload!!

        val earthMoonDistanceMeters = distanceBetween(
            earthPayload.bodyPositionX,
            earthPayload.bodyPositionY,
            earthPayload.bodyPositionZ,
            moonPayload.bodyPositionX,
            moonPayload.bodyPositionY,
            moonPayload.bodyPositionZ,
        )
        assertTrue(
            "Earth-Moon startup distance should be physically plausible, found $earthMoonDistanceMeters m",
            earthMoonDistanceMeters in 3.0e8..4.5e8,
        )

        val earthMoonRelativeSpeedMetersPerSecond = distanceBetween(
            earthPayload.bodyVelocityX,
            earthPayload.bodyVelocityY,
            earthPayload.bodyVelocityZ,
            moonPayload.bodyVelocityX,
            moonPayload.bodyVelocityY,
            moonPayload.bodyVelocityZ,
        )
        assertTrue(
            "Earth-Moon startup relative speed should be physically plausible, found $earthMoonRelativeSpeedMetersPerSecond m/s",
            earthMoonRelativeSpeedMetersPerSecond in 500.0..1_500.0,
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded default startup solar system") }
        )
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == 12 }
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
            collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == 2 }
        }

        assertTrue(transport.appliedCommands.isEmpty())
        assertEquals(listOf(42L), transport.refreshedHandles)
        assertEquals(1, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)
        assertFalse(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded default startup solar system") }
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
    ) : NativeRuntimeTransport {
        val runtimeInfoHandles = mutableListOf<Long>()
        val refreshedHandles = mutableListOf<Long>()
        val appliedCommands = mutableListOf<NativeRuntimeCommandPayload>()

        override fun ensureLibraryLoaded(): NativeLibraryLoadOutcome = NativeLibraryLoadOutcome.Success

        override fun createSession(
            scenarioId: String,
            rootBranchId: String,
        ): NativeCreateSessionResult = NativeCreateSessionResult(
            result = NativeResult(code = 0),
            handle = 42L,
            abiVersion = 1,
            cpuBackend = 1,
            gpuBackend = 0,
        )

        override fun runtimeInfo(handle: Long): NativeRuntimeInfoResult {
            runtimeInfoHandles += handle
            return NativeRuntimeInfoResult(
                result = NativeResult(code = 0),
                abiVersion = 1,
                cpuBackend = 1,
                gpuBackend = 0,
            )
        }

        override fun snapshotSummary(handle: Long): NativeSnapshotSummaryResult =
            error("snapshotSummary is unused in this test")

        override fun refreshSession(handle: Long): NativeSnapshotSummaryResult {
            refreshedHandles += handle
            return refreshResults.removeFirstOrNull()
                ?: snapshotSummary(bodyCount = 12)
        }

        override fun applyCommand(
            handle: Long,
            command: NativeRuntimeCommandPayload,
        ): NativeSnapshotSummaryResult {
            appliedCommands += command
            return snapshotSummary(bodyCount = 12)
        }

        fun commandForBody(bodyId: String): NativeRuntimeCommandPayload? =
            appliedCommands.firstOrNull { payload ->
                payload.bodyIdUtf8?.let { String(it, StandardCharsets.UTF_8) } == bodyId
            }

        override fun exportVulkanScene(handle: Long): NativeVulkanScenePacketResult? =
            error("exportVulkanScene is unused in this test")

        override fun releaseVulkanScene(packetHandle: Long) = Unit

        override fun destroySession(handle: Long) = Unit
    }

    private companion object {
        fun distanceBetween(
            ax: Double,
            ay: Double,
            az: Double,
            bx: Double,
            by: Double,
            bz: Double,
        ): Double {
            val dx = ax - bx
            val dy = ay - by
            val dz = az - bz
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun snapshotSummary(bodyCount: Int): NativeSnapshotSummaryResult = NativeSnapshotSummaryResult(
            result = NativeResult(code = 0),
            scenarioId = "sol-system",
            activeBranchId = "main",
            bodyCount = bodyCount,
            epochSeconds = 0.0,
            paused = true,
            simSecondsPerRealSecond = 1.0,
            observerMode = RuntimeObserverMode.SystemFrame.nativeCode,
            timelineSemantics = 1,
        )
    }
}
