package com.sednalabs.solarlab

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RotationContinuityInstrumentationTest {
    @Test
    fun rotation_recreation_preserves_session_and_runtime_state() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facadeBefore = scenario.withRuntimeFacade()

            waitForState(facadeBefore) {
                it.sessionHandle != null &&
                    it.renderPacketSummary != null &&
                    it.cameraFacingSummary != null &&
                    it.observerModeCode != null &&
                    it.backendSummary != null &&
                    it.snapshot?.activeBranchId != null
            }

            runBlocking {
                facadeBefore.applyCommand(RuntimeCommand.PausePlayback)
                facadeBefore.applyCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.FollowHost))
                facadeBefore.refresh()
            }

            waitForState(facadeBefore) {
                it.sessionHandle != null &&
                    it.snapshotSummary?.contains("paused=true") == true &&
                    it.observerModeCode == RuntimeObserverMode.FollowHost.nativeCode &&
                    it.snapshot?.activeBranchId != null &&
                    it.renderStatus.renderedBodyCount >= 0 &&
                    it.renderStatus.renderedTracerCount >= 0 &&
                    it.renderStatus.renderedTrailCount >= 0 &&
                    it.cameraFacingSummary != null
            }

            val beforeRotation = facadeBefore.uiState.value
            val beforeSessionHandle = beforeRotation.sessionHandle
            val beforeCameraFacingSummary = beforeRotation.cameraFacingSummary
            val beforeBranch = beforeRotation.snapshot?.activeBranchId
            val beforeRenderedBodyCount = beforeRotation.renderStatus.renderedBodyCount
            val beforeRenderedTracerCount = beforeRotation.renderStatus.renderedTracerCount
            val beforeRenderedTrailCount = beforeRotation.renderStatus.renderedTrailCount
            val beforeObserverMode = beforeRotation.observerModeCode
            val beforeProvenance = beforeRotation.backendSummary

            scenario.recreate()

            val facadeAfter = scenario.withRuntimeFacade()
            assertSame(
                "Runtime facade should be retained across configuration changes",
                facadeBefore,
                facadeAfter,
            )

            waitForState(facadeAfter) {
                it.sessionHandle != null &&
                    it.snapshotSummary?.contains("paused=true") == true &&
                    it.observerModeCode == RuntimeObserverMode.FollowHost.nativeCode &&
                    it.snapshot?.activeBranchId == beforeBranch &&
                    it.backendSummary == beforeProvenance &&
                    it.renderStatus.renderedBodyCount == beforeRenderedBodyCount &&
                    it.renderStatus.renderedTracerCount == beforeRenderedTracerCount &&
                    it.renderStatus.renderedTrailCount == beforeRenderedTrailCount &&
                    it.cameraFacingSummary != null
            }

            val afterRotation = facadeAfter.uiState.value
            assertEquals(
                "Session handle should remain stable across recreation",
                beforeSessionHandle,
                afterRotation.sessionHandle,
            )
            assertEquals(
                "Active branch should remain stable across recreation",
                beforeBranch,
                afterRotation.snapshot?.activeBranchId,
            )
            assertEquals(
                "Observer mode should be preserved across recreation",
                RuntimeObserverMode.FollowHost.nativeCode,
                afterRotation.observerModeCode,
            )
            assertEquals(
                "Observer mode code should remain stable after recreation",
                beforeObserverMode,
                afterRotation.observerModeCode,
            )
            assertEquals(
                "Rendered body count should remain stable after recreation",
                beforeRenderedBodyCount,
                afterRotation.renderStatus.renderedBodyCount,
            )
            assertEquals(
                "Rendered tracer count should remain stable after recreation",
                beforeRenderedTracerCount,
                afterRotation.renderStatus.renderedTracerCount,
            )
            assertEquals(
                "Rendered trail count should remain stable after recreation",
                beforeRenderedTrailCount,
                afterRotation.renderStatus.renderedTrailCount,
            )
            assertEquals(
                "Backend provenance should remain stable after recreation",
                beforeProvenance,
                afterRotation.backendSummary,
            )
            assertNotNull(
                "Camera-facing summary should still be exposed after recreation",
                afterRotation.cameraFacingSummary,
            )
            assertTrue(
                "Camera-facing summary should remain stable while playback is paused",
                beforeCameraFacingSummary == afterRotation.cameraFacingSummary,
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
        predicate: (ShellUiState) -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            if (predicate(facade.uiState.value)) {
                return
            }
            Thread.sleep(50)
        }
        val finalState = facade.uiState.value
        throw AssertionError(
            "Timed out waiting for runtime state continuity condition. Final state: $finalState"
        )
    }
}
