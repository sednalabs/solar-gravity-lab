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
import org.junit.Assert.assertTrue
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
                    it.renderStatus.readiness == RenderHostReadiness.Ready &&
                    it.renderFrame != null &&
                    hasRicherSceneContent(it)
            }

            scenario.onActivity { activity ->
                assertFalse("MainActivity should not be finishing after startup", activity.isFinishing)
                assertFalse("MainActivity should not be destroyed after startup", activity.isDestroyed)
            }

            val finalState = facade.uiState.value
            assertNotNull("Runtime session handle should be available after startup", finalState.sessionHandle)
            assertNotNull("Runtime snapshot should be available after startup", finalState.snapshot)
            assertNotNull(
                "A decoded render frame should be available after startup. Final state: $finalState",
                finalState.renderFrame,
            )
            assertTrue(
                "Startup should reach a populated ready scene. Final state: $finalState",
                hasRicherSceneContent(finalState),
            )
        }
    }

    private fun hasRicherSceneContent(state: ShellUiState): Boolean {
        val snapshotBodyCount = state.snapshot?.bodyCount ?: 0
        val frameBodyCount = state.renderFrame?.bodies?.size ?: 0
        val renderBodyCount = state.renderStatus.renderedBodyCount
        val renderTracerCount = state.renderStatus.renderedTracerCount
        val renderTrailCount = state.renderStatus.renderedTrailCount
        val totalRenderableElements = renderBodyCount + renderTracerCount + renderTrailCount

        return snapshotBodyCount >= 10 &&
            frameBodyCount >= 10 &&
            renderBodyCount >= 10 &&
            totalRenderableElements >= 10 &&
            totalRenderableElements >= renderBodyCount &&
            state.renderStatus.issue == null
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
