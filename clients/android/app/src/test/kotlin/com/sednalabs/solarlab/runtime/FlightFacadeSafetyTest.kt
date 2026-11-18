package com.sednalabs.solarlab.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Verifies retained lifecycle behaviour through the production facade orchestration. */
class FlightFacadeSafetyTest {
    @Test fun queuedGuidanceCannotRestartFlightAfterLifecycleStop() = runBlocking {
        for (intent in listOf("lunar", "rendezvous", "docking", "undock")) {
            val executor = Executors.newSingleThreadExecutor()
            executor.asCoroutineDispatcher().use { dispatcher ->
                val bridge = FlightBridge()
                val facade = BridgeBackedRuntimeFacade(bridge,
                    developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
                    boundaryDispatcher = dispatcher)
                facade.startSession()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocked = executor.submit { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                try {
                    val queued = async(start = CoroutineStart.UNDISPATCHED) {
                        when (intent) {
                            "lunar" -> facade.beginLunarDeparture(expectedSessionHandle = 77L)
                            "rendezvous" -> facade.setRendezvousGuidance(RendezvousGuidance.Approach, expectedSessionHandle = 77L)
                            "docking" -> facade.setDockingGuidance(DockingGuidance.Approach, expectedSessionHandle = 77L)
                            "undock" -> facade.undock(expectedSessionHandle = 77L)
                        }
                    }
                    facade.suspendFlightControls(expectedSessionHandle = 77L)
                    release.countDown()
                    queued.await()
                    withTimeout(2_000) { bridge.paused.await() }
                    assertTrue("Stopped guidance cannot reach the native world", bridge.guidanceRequests.isEmpty())
                    assertEquals(listOf(FlightControl.Cut), bridge.controls)
                    assertEquals("Stopped guidance cannot resume time", listOf(RuntimeCommand.PausePlayback), bridge.commands)
                } finally { release.countDown(); blocked.get(2, TimeUnit.SECONDS) }
            }
        }
    }

    @Test fun dockingIntentAndReleasePreservePauseAndRejectStaleSession() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val bridge = FlightBridge()
            val facade = BridgeBackedRuntimeFacade(bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
                boundaryDispatcher = dispatcher)
            facade.startSession()
            facade.setDockingGuidance(DockingGuidance.Approach, expectedSessionHandle = 76L)
            facade.undock(expectedSessionHandle = 76L)
            assertTrue(bridge.guidanceRequests.isEmpty())
            facade.setDockingGuidance(DockingGuidance.Approach, expectedSessionHandle = 77L)
            facade.setDockingGuidance(DockingGuidance.Manual, expectedSessionHandle = 77L)
            facade.undock(expectedSessionHandle = 77L)
            assertEquals(listOf("docking.Approach", "docking.Manual", "undock"), bridge.guidanceRequests)
            assertTrue("Run/Pause alone owns time", bridge.commands.isEmpty())
        }
    }

    @Test fun staleSessionInputAndDisposalCannotCutOrOverwriteTheNewSession() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { dispatcher ->
            val bridge = FlightBridge()
            val facade = BridgeBackedRuntimeFacade(bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
                boundaryDispatcher = dispatcher)
            facade.startSession()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocked = executor.submit { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                val current = async(start = CoroutineStart.UNDISPATCHED) {
                    facade.setFlightControl(FlightControl(throttle = .25), expectedSessionHandle = 77L)
                }
                facade.suspendFlightControls(expectedSessionHandle = 76L)
                val stale = async(start = CoroutineStart.UNDISPATCHED) {
                    facade.setFlightControl(FlightControl(throttle = .9), expectedSessionHandle = 76L)
                }
                release.countDown()
                current.await(); stale.await()
                assertEquals(listOf(FlightControl(throttle = .25)), bridge.controls)
                assertTrue("Disposing the old world cannot pause the imported world", bridge.commands.isEmpty())
            } finally { release.countDown(); blocked.get(2, TimeUnit.SECONDS) }
        }
    }

    @Test fun missingShipDoesNotPreventBackgroundPause() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val bridge = FlightBridge(failCut = true)
            val facade = BridgeBackedRuntimeFacade(bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
                boundaryDispatcher = dispatcher)
            facade.startSession()
            facade.suspendFlightControls()
            withTimeout(2_000) { bridge.paused.await() }
            assertEquals(listOf(FlightControl.Cut), bridge.controls)
            assertEquals(listOf(RuntimeCommand.PausePlayback), bridge.commands)
        }
    }

    @Test fun queuedInputFromOldActivityCannotRestartThrustAfterStop() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { dispatcher ->
            val bridge = FlightBridge()
            val facade = BridgeBackedRuntimeFacade(bridge,
                developerTelemetryRecorder = DeveloperTelemetryRecorder(enabled = false, sinks = emptyList()),
                boundaryDispatcher = dispatcher)
            facade.startSession()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocked = executor.submit { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                val stale = async(start = CoroutineStart.UNDISPATCHED) { facade.setFlightControl(FlightControl(throttle = .9)) }
                facade.suspendFlightControls()
                release.countDown()
                stale.await()
                withTimeout(2_000) { bridge.paused.await() }
                assertEquals("Only the retained cut may reach the native boundary", listOf(FlightControl.Cut), bridge.controls)
            } finally { release.countDown(); blocked.get(2, TimeUnit.SECONDS) }
        }
    }

    private class FlightBridge(private val failCut: Boolean = false) : RuntimeBridge {
        val controls = mutableListOf<FlightControl>()
        val commands = mutableListOf<RuntimeCommand>()
        val paused = CompletableDeferred<Unit>()
        val guidanceRequests = mutableListOf<String>()
        override fun connect() = flowOf(RuntimeSignal.Connected(77L))
        override suspend fun loadScenario(scenarioId: String) = emptyList<RuntimeSignal>()
        override suspend fun refresh() = emptyList<RuntimeSignal>()
        override suspend fun applyCommand(command: RuntimeCommand): List<RuntimeSignal> {
            commands += command
            if (command == RuntimeCommand.PausePlayback) paused.complete(Unit)
            return emptyList()
        }
        override suspend fun setFlightControl(shipId: String, control: FlightControl) {
            controls += control
            if (failCut) error("Spacecraft was removed")
        }
        override suspend fun setDockingGuidance(shipId: String, mode: DockingGuidance): List<RuntimeSignal> {
            guidanceRequests += "docking.${mode.name}"
            return emptyList()
        }
        override suspend fun undock(shipId: String): List<RuntimeSignal> {
            guidanceRequests += "undock"
            return emptyList()
        }
        override suspend fun beginLunarDeparture(shipId: String): List<RuntimeSignal> {
            guidanceRequests += "lunar"
            return emptyList()
        }
        override suspend fun setRendezvousGuidance(shipId: String, guidance: RendezvousGuidance): List<RuntimeSignal> {
            guidanceRequests += guidance.name
            return emptyList()
        }
    }
}
