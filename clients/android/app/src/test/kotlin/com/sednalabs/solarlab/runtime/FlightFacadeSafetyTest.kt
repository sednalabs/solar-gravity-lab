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
    }
}
