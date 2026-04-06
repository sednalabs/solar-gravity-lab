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
            collected
                .filterIsInstance<RuntimeSignal.SnapshotUpdated>()
                .any { it.summary.bodyCount == STARTUP_EXPECTED_BODY_COUNT }
        }

        assertEquals(listOf(42L), transport.runtimeInfoHandles)
        assertEquals(listOf(42L, 42L), transport.refreshedHandles)
        assertEquals(listOf(42L), renderHostAdapter.boundSessionHandles)
        assertEquals(2, renderHostAdapter.refreshCount)
        assertEquals(1, renderHostAdapter.releaseCount)

        val spawnedBodyIds = transport.appliedCommands.map { payload ->
            payload.bodyIdUtf8?.let { String(it, StandardCharsets.UTF_8) }
        }
        assertEquals(STARTUP_EXPECTED_BODY_COUNT, spawnedBodyIds.size)
        assertTrue(
            spawnedBodyIds.containsAll(
                listOf(
                    "sun",
                    "moon",
                    "haumea",
                    "makemake",
                    "eris",
                    "vesta",
                    "halley",
                    "belt-0",
                    "belt-239",
                    "oort-0",
                    "oort-95",
                )
            )
        )
        assertEquals(
            STARTUP_EXPECTED_TRACER_COUNT,
            transport.appliedCommands.count { it.bodyClass == RuntimeBodyClass.Tracer.nativeCode },
        )
        assertEquals(
            STARTUP_EXPECTED_SMALL_BODY_COUNT,
            transport.appliedCommands.count { it.bodyClass == RuntimeBodyClass.SmallBody.nativeCode },
        )
        assertEquals(
            STARTUP_EXPECTED_DWARF_PLANET_COUNT,
            transport.appliedCommands.count { it.bodyClass == RuntimeBodyClass.DwarfPlanet.nativeCode },
        )

        val earthPayload = transport.commandForBody("earth")
        val moonPayload = transport.commandForBody("moon")
        val halleyPayload = transport.commandForBody("halley")
        val beltPayload = transport.commandForBody("belt-0")
        val oortPayload = transport.commandForBody("oort-0")
        assertNotNull("Expected Earth spawn payload in startup seed", earthPayload)
        assertNotNull("Expected Moon spawn payload in startup seed", moonPayload)
        assertNotNull("Expected Halley spawn payload in startup seed", halleyPayload)
        assertNotNull("Expected synthetic belt tracer in startup seed", beltPayload)
        assertNotNull("Expected synthetic Oort tracer in startup seed", oortPayload)

        earthPayload!!
        moonPayload!!
        halleyPayload!!
        beltPayload!!
        oortPayload!!

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
        assertEquals(RuntimeBodyClass.SmallBody.nativeCode, halleyPayload.bodyClass)
        assertTrue("Halley should keep a physical mass in the startup seed", halleyPayload.bodyMassKg > 0.0)
        assertEquals(RuntimeBodyClass.Tracer.nativeCode, beltPayload.bodyClass)
        assertEquals(0.0, beltPayload.bodyMassKg, 0.0)
        assertTrue("Synthetic belt tracer radius should stay bounded", beltPayload.bodyRadiusM in 500.0..50_000.0)
        assertEquals(RuntimeBodyClass.Tracer.nativeCode, oortPayload.bodyClass)
        assertEquals(0.0, oortPayload.bodyMassKg, 0.0)
        assertTrue("Synthetic Oort tracer radius should stay bounded", oortPayload.bodyRadiusM in 1_000.0..20_000.0)
        assertTrue(
            signals
                .filterIsInstance<RuntimeSignal.Notice>()
                .any { it.message.contains("Seeded default startup solar system") }
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
        const val STARTUP_EXPECTED_BODY_COUNT = 365
        const val STARTUP_EXPECTED_SMALL_BODY_COUNT = 14
        const val STARTUP_EXPECTED_TRACER_COUNT = 336
        const val STARTUP_EXPECTED_DWARF_PLANET_COUNT = 5

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
