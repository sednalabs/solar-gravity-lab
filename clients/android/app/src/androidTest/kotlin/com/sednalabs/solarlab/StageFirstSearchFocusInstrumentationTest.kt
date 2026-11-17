package com.sednalabs.solarlab

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit
import android.util.Log
import org.junit.Before
import kotlinx.coroutines.runBlocking
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.RuntimeCommand
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSearchFocusInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun boundIdleDiagnostics() {
        IdlingPolicies.setMasterPolicyTimeout(45, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(30, TimeUnit.SECONDS)
    }

    @Test
    fun searchFocus_updatesVisibleSelectionCard() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        loadCanonicalCatalogue()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            .performScrollToIfPossible()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).fetchSemanticsNodes().isNotEmpty()
        }
        // Filter before asking for Halley: an unfiltered, scroll-clipped
        // catalogue need not expose an offscreen result in its semantics tree.
        // The filtered inclusion/exclusion assertions below remain mandatory.
        Log.i("SolarLabSearchProof", "Search field ready; entering comet filter")
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performClick().performTextInput("comet")
        Log.i("SolarLabSearchProof", "Comet filter entered")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("halley")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performTextReplacement("earth")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("halley")).fetchSemanticsNodes().isEmpty()
        }
        // The 365-body native scene keeps rendering while focus transitions
        // settle. Compose must not require the entire renderer to become idle to
        // inspect a stable selection. Control only the test frame clock here,
        // just as the repeated-search interaction test does for feedback timers.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).performClick()

            var lastSelectionFailure: Throwable? = null
            try {
                composeRule.waitUntil(timeoutMillis = 20_000) {
                    composeRule.mainClock.advanceTimeByFrame()
                    runCatching {
                        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                            .assertTextContains("Earth")
                    }.onFailure { lastSelectionFailure = it }.isSuccess
                }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Selection title did not update to Earth", lastSelectionFailure ?: failure)
            }
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL).assertIsDisplayed()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")
            composeRule.onNodeWithTag(LabTags.FOLLOW).assertIsDisplayed()
            composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.activity.runtimeFacadeForTesting.uiState.value.focusedBodyId == "earth"
            }

            if (composeRule.activity.isStageFirstRuntimeMountedForTesting()) {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
                    .performScrollToIfPossible()
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
                    .performScrollToIfPossible()
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON)
                    .performScrollToIfPossible()
                    .assertIsDisplayed()
                    .performClick()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun connectorFocusAlias_exposesCorrelatedResolvedAcknowledgement() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && BuildConfig.DEBUG)
        loadCanonicalCatalogue()
        val requestId = "connector-request-42"
        val expectedAcknowledgement =
            "Halley. SolarLab semantic focus acknowledged; " +
                "request-id=connector-request-42; query=comet; resolved-body=halley"
        SolarLabSemanticActionBridge.clearPendingReplay()

        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue(
                SolarLabSemanticActionBridge.submit(
                    SolarLabSemanticAction.FocusBody(
                        bodyQuery = "comet",
                        requestId = requestId,
                    )
                )
            )

            composeRule.waitUntil(timeoutMillis = 20_000) {
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                        .assertTextContains("Halley")
                        .assertContentDescriptionEquals(expectedAcknowledgement)
                }.isSuccess
            }
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                .assertTextContains("Halley")
                .assertContentDescriptionEquals(expectedAcknowledgement)
        } finally {
            SolarLabSemanticActionBridge.clearPendingReplay()
        }
    }
    private fun loadCanonicalCatalogue() {
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(timeoutMillis = 20_000) {
            facade.uiState.value.connectionState == SessionConnectionState.Active
        }
        // This fixture qualifies search/catalogue semantics, not moving playback.
        // Continuous clock recomposition prevents Compose's idle barrier;
        // real moving focus and pinch remain covered by LabCameraMotion.
        runBlocking {
            facade.loadScenario("sol-system")
            facade.applyCommand(RuntimeCommand.PausePlayback)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            facade.uiState.value.snapshot?.paused == true &&
                facade.uiState.value.renderFrame?.bodies?.any { it.bodyId == "halley" } == true
        }
    }

}
