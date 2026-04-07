package com.sednalabs.solarlab

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusedCompositionInstrumentationTest {
    @Test
    fun earth_focus_reaches_centered_viewport() {
        verifyFocusBody("earth")
    }

    @Test
    fun jupiter_focus_reaches_centered_viewport() {
        verifyFocusBody("jupiter")
    }

    private fun verifyFocusBody(targetBodyId: String) {
        Log.i(LOG_TAG, "FocusedCompositionInstrumentationTest.begin target=$targetBodyId")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facade = scenario.withRuntimeFacade()

            waitForState(facade) { state ->
                state.renderStatus.readiness == RenderHostReadiness.Ready &&
                    state.renderStatus.issue == null &&
                    state.renderFrame?.bodies?.isNotEmpty() == true
            }

            runBlocking {
                facade.applyCommand(RuntimeCommand.PausePlayback)
                facade.applyCommand(RuntimeCommand.FocusBody(targetBodyId))
                facade.applyCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.FollowSelected))
                facade.refresh()
            }

            waitForState(facade, timeout = 25.seconds) { state ->
                val focusedMatch = state.focusedBodyId.equals(targetBodyId, ignoreCase = true)
                val renderMatch = state.renderFrame?.bodies?.any { body ->
                    body.bodyId.equals(targetBodyId, ignoreCase = true)
                } == true
                focusedMatch && renderMatch && state.renderStatus.readiness == RenderHostReadiness.Ready
            }

            val finalState = facade.uiState.value
            Log.i(LOG_TAG, "FocusedCompositionInstrumentationTest.ready target=$targetBodyId state=${summarizeState(finalState)}")
            assertTrue(
                "Focused shell state should report $targetBodyId",
                finalState.focusedBodyId.equals(targetBodyId, ignoreCase = true),
            )
        }
    }

    private fun summarizeState(state: ShellUiState): String =
        "focus=${state.focusedBodyId ?: "none"}, readiness=${state.renderStatus.readiness}, issue=${state.renderStatus.issue ?: "none"}"

    private fun waitForState(
        facade: RuntimeFacade,
        timeout: Duration = 25.seconds,
        predicate: (ShellUiState) -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            val state = facade.uiState.value
            if (predicate(state)) {
                Log.i(LOG_TAG, "FocusedCompositionInstrumentationTest.conditionMet ${summarizeState(state)}")
                return
            }
            if (state.renderStatus.issue != null) {
                Log.w(LOG_TAG, "FocusedCompositionInstrumentationTest.issue ${state.renderStatus.issue}")
            }
            Thread.sleep(75)
        }
        val finalState = facade.uiState.value
        Log.e(LOG_TAG, "FocusedCompositionInstrumentationTest.timeout ${summarizeState(finalState)}")
        throw AssertionError(
            "Timed out waiting for focus state. Final state: $finalState",
        )
    }

    private fun ActivityScenario<MainActivity>.withRuntimeFacade(): RuntimeFacade {
        var facade: RuntimeFacade? = null
        onActivity { activity ->
            facade = activity.runtimeFacadeForTesting
        }
        return requireNotNull(facade) { "Unable to access runtime facade" }
    }

    private companion object {
        const val LOG_TAG = "SolarLabInstrumentation"
    }
}
