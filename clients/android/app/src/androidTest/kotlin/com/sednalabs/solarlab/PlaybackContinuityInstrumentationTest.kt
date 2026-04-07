package com.sednalabs.solarlab

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.milliseconds
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
class PlaybackContinuityInstrumentationTest {
    @Test
    fun mainActivity_autoPlayback_advancesEpoch_afterStartup() {
        Log.i(LOG_TAG, "PlaybackContinuityInstrumentationTest.begin")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facade = scenario.withRuntimeFacade()

            waitForState(facade) { state ->
                state.connectionState == SessionConnectionState.Active &&
                    state.snapshot != null &&
                    state.renderStatus.readiness == RenderHostReadiness.Ready &&
                    state.renderFrame != null
            }

            val baselineState = facade.uiState.value
            val baselineEpoch = requireNotNull(baselineState.snapshot).epochSeconds
            val baselinePlaybackRate = requireNotNull(baselineState.snapshot).simSecondsPerRealSecond
            Log.i(
                LOG_TAG,
                "PlaybackContinuityInstrumentationTest.baseline " +
                    "epoch=$baselineEpoch paused=${baselineState.snapshot.paused} " +
                    "rate=${baselinePlaybackRate} bodies=${baselineState.snapshot.bodyCount}",
            )

            waitForState(facade, timeout = 24.seconds, pollInterval = 120.milliseconds) { state ->
                val snapshot = state.snapshot ?: return@waitForState false
                state.connectionState == SessionConnectionState.Active &&
                    state.renderStatus.readiness == RenderHostReadiness.Ready &&
                    !snapshot.paused &&
                    snapshot.epochSeconds > baselineEpoch &&
                    snapshot.simSecondsPerRealSecond > 0.0 &&
                    snapshot.epochSeconds - baselineEpoch >= 0.5
            }

            val finalState = facade.uiState.value
            val finalSnapshot = requireNotNull(finalState.snapshot)
            Log.i(
                LOG_TAG,
                "PlaybackContinuityInstrumentationTest.ready " +
                    "epoch=${finalSnapshot.epochSeconds} paused=${finalSnapshot.paused}",
            )

            assertFalse("Playback should be live after startup", finalSnapshot.paused)
            assertTrue(
                "Epoch should advance in live playback mode. baseline=$baselineEpoch final=${finalSnapshot.epochSeconds}",
                finalSnapshot.epochSeconds > baselineEpoch,
            )
            assertNotNull("Render frame should remain available while playback is live", finalState.renderFrame)
            assertTrue(
                "Render host should keep non-empty bodies while playback advances",
                finalState.renderStatus.renderedBodyCount > 0,
            )
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
        pollInterval: Duration = 50.milliseconds,
        predicate: (ShellUiState) -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        var nextProgressLogMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadlineMs) {
            val state = facade.uiState.value
            if (predicate(state)) {
                return
            }
            if (System.currentTimeMillis() >= nextProgressLogMs) {
                Log.i(LOG_TAG, "PlaybackContinuityInstrumentationTest.waiting ${summarizeState(state)}")
                nextProgressLogMs += PROGRESS_LOG_INTERVAL_MS
            }
            Thread.sleep(pollInterval.inWholeMilliseconds)
        }
        val finalState = facade.uiState.value
        throw AssertionError(
            "Timed out waiting for live playback continuity. Final state: ${summarizeState(finalState)}",
        )
    }

    private fun summarizeState(state: ShellUiState): String =
        "connection=${state.connectionState}, session=${state.sessionHandle}, " +
            "epoch=${state.snapshot?.epochSeconds ?: 0.0}, paused=${state.snapshot?.paused ?: true}, " +
            "renderBodies=${state.renderStatus.renderedBodyCount}, readiness=${state.renderStatus.readiness}"

    private companion object {
        const val LOG_TAG = "SolarLabInstrumentation"
        const val PROGRESS_LOG_INTERVAL_MS = 2_000L
    }
}
