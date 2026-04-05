package com.sednalabs.solarlab

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSmokeInstrumentationTest {
    @Test
    fun mainActivity_launches_and_reaches_first_runtime_frame_without_process_death() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facade = scenario.withRuntimeFacade()

            waitForState(facade) {
                it.connectionState == SessionConnectionState.Active &&
                    it.sessionHandle != null &&
                    it.snapshot != null &&
                    it.renderPacketSummary != null &&
                    when (it.renderStatus.readiness) {
                        RenderHostReadiness.Ready -> it.renderFrame != null
                        RenderHostReadiness.Unavailable -> it.renderStatus.degradationReason != null
                        else -> false
                    }
            }

            scenario.onActivity { activity ->
                assertFalse("MainActivity should not be finishing after startup", activity.isFinishing)
                assertFalse("MainActivity should not be destroyed after startup", activity.isDestroyed)
            }

            val finalState = facade.uiState.value
            assertNotNull("Runtime session handle should be available after startup", finalState.sessionHandle)
            assertNotNull("Runtime snapshot should be available after startup", finalState.snapshot)
            if (finalState.renderStatus.readiness == RenderHostReadiness.Ready) {
                assertNotNull("A decoded render frame should be available when render host is ready", finalState.renderFrame)
            }
        }
    }

    private fun ActivityScenario<MainActivity>.withRuntimeFacade(): RuntimeFacade {
        var facade: RuntimeFacade? = null
        onActivity { activity ->
            facade = activity.runtimeFacadeForTesting
        }
        return requireNotNull(facade) { "Unable to access runtime facade from MainActivity" }
    }

    private fun waitForState(
        facade: RuntimeFacade,
        timeout: Duration = 20.seconds,
        predicate: (ShellUiState) -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            if (predicate(facade.uiState.value)) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError(
            "Timed out waiting for startup smoke condition. Final state: ${facade.uiState.value}"
        )
    }
}
