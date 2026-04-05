package com.sednalabs.solarlab

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
                    it.snapshot != null &&
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
                    it.snapshot?.paused == true &&
                    it.observerModeCode == RuntimeObserverMode.FollowHost.nativeCode &&
                    it.snapshot?.activeBranchId != null &&
                    it.renderStatus.renderedBodyCount >= 0 &&
                    it.renderStatus.renderedTracerCount >= 0 &&
                    it.renderStatus.renderedTrailCount >= 0 &&
                    hasStableRenderState(it)
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
                    it.snapshot?.paused == true &&
                    it.observerModeCode == RuntimeObserverMode.FollowHost.nativeCode &&
                    it.snapshot?.activeBranchId == beforeBranch &&
                    hasCompatibleBackendSummary(
                        before = beforeRotation,
                        after = it,
                    ) &&
                    hasCompatibleRenderMetrics(
                        before = beforeRotation,
                        after = it,
                    ) &&
                    hasStableRenderState(it)
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
            assertNotNull(
                "Render packet summary should remain exposed after recreation",
                afterRotation.renderPacketSummary,
            )
            if (
                beforeRotation.renderStatus.readiness == RenderHostReadiness.Ready &&
                afterRotation.renderStatus.readiness == RenderHostReadiness.Ready
            ) {
                assertEquals(
                    "Backend provenance should remain stable after recreation while render host is ready",
                    beforeProvenance,
                    afterRotation.backendSummary,
                )
                assertEquals(
                    "Rendered body count should remain stable after recreation while render host is ready",
                    beforeRenderedBodyCount,
                    afterRotation.renderStatus.renderedBodyCount,
                )
                assertEquals(
                    "Rendered tracer count should remain stable after recreation while render host is ready",
                    beforeRenderedTracerCount,
                    afterRotation.renderStatus.renderedTracerCount,
                )
                assertEquals(
                    "Rendered trail count should remain stable after recreation while render host is ready",
                    beforeRenderedTrailCount,
                    afterRotation.renderStatus.renderedTrailCount,
                )
                assertTrue(
                    "Camera-facing summary should remain stable while playback is paused",
                    beforeCameraFacingSummary == afterRotation.cameraFacingSummary,
                )
            }
        }
    }

    private fun hasStableRenderState(state: ShellUiState): Boolean =
        when (state.renderStatus.readiness) {
            RenderHostReadiness.Ready -> state.renderFrame != null && state.cameraFacingSummary != null
            RenderHostReadiness.Unavailable -> state.renderStatus.degradationReason != null
            else -> false
        }

    private fun hasCompatibleRenderMetrics(
        before: ShellUiState,
        after: ShellUiState,
    ): Boolean {
        val beforeReady = before.renderStatus.readiness == RenderHostReadiness.Ready
        val afterReady = after.renderStatus.readiness == RenderHostReadiness.Ready
        if (!beforeReady || !afterReady) {
            return true
        }
        return after.renderStatus.renderedBodyCount == before.renderStatus.renderedBodyCount &&
            after.renderStatus.renderedTracerCount == before.renderStatus.renderedTracerCount &&
            after.renderStatus.renderedTrailCount == before.renderStatus.renderedTrailCount
    }

    private fun hasCompatibleBackendSummary(
        before: ShellUiState,
        after: ShellUiState,
    ): Boolean {
        val beforeReady = before.renderStatus.readiness == RenderHostReadiness.Ready
        val afterReady = after.renderStatus.readiness == RenderHostReadiness.Ready
        if (!beforeReady || !afterReady) {
            return after.backendSummary != null
        }
        return after.backendSummary == before.backendSummary
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
