package com.sednalabs.solarlab

import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.runtime.SessionConnectionState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSemanticIntentInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun focusIntent_updatesSelectionAndAcknowledgesRequest() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && BuildConfig.DEBUG)
        SolarLabSemanticActionBridge.clearPendingReplay()
        val activity = composeRule.activity
        val facade = activity.runtimeFacadeForTesting
        val originalIntent = activity.intent
        try {
            // The title exists before the native catalogue and automatic Earth
            // framing are ready. Finish that startup before taking over the
            // Compose clock to test the two correlated feedback deadlines.
            composeRule.waitUntil(timeoutMillis = 20_000) {
                val state = facade.uiState.value
                activity.isStageFirstRuntimeMountedForTesting() &&
                    state.connectionState == SessionConnectionState.Active &&
                    state.renderFrame?.bodies?.any { it.bodyId == "earth" } == true &&
                    state.focusedBodyId == "earth" && state.pendingActionLabel == null &&
                    runCatching {
                        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                            .assertIsDisplayed().assertTextContains("Earth")
                        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsEnabled()
                    }.isSuccess
            }
            composeRule.mainClock.autoAdvance = false

            fun sendFocus(requestId: String) {
                val intent = Intent(SolarLabSemanticActionBridge.INTENT_ACTION)
                    .setClass(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SolarLabSemanticActionBridge.EXTRA_COMMAND, "focus_body")
                    .putExtra(SolarLabSemanticActionBridge.EXTRA_BODY_QUERY, "earth")
                    .putExtra(SolarLabSemanticActionBridge.EXTRA_REQUEST_ID, requestId)
                composeRule.runOnUiThread { activity.startActivity(intent) }
                val expected = "Earth. SolarLab semantic focus acknowledged; " +
                    "request-id=$requestId; query=earth; resolved-body=earth"
                var lastObservationFailure: AssertionError? = null
                try {
                    composeRule.waitUntil(timeoutMillis = 20_000) {
                        composeRule.mainClock.advanceTimeByFrame()
                        try {
                            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                                .assertTextContains("Earth")
                                .assertContentDescriptionEquals(expected)
                            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                                .assertIsDisplayed().assertTextContains("Frame requested · Earth")
                            true
                        } catch (error: AssertionError) {
                            lastObservationFailure = error
                            false
                        }
                    }
                } catch (error: ComposeTimeoutException) {
                    var deliveredRequest: String? = null
                    composeRule.runOnUiThread {
                        deliveredRequest = activity.intent.getStringExtra(SolarLabSemanticActionBridge.EXTRA_REQUEST_ID)
                    }
                    val selection = runCatching {
                        composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                            .fetchSemanticsNodes().joinToString { it.config.toString() }.take(2_000)
                    }.getOrElse { it.toString() }
                    val state = facade.uiState.value
                    throw AssertionError("Focus request $requestId was not acknowledged; delivered=$deliveredRequest, " +
                        "replay=${SolarLabSemanticActionBridge.commands.replayCache}, selected=${state.focusedBodyId}, " +
                        "connection=${state.connectionState}, pending=${state.pendingActionLabel}, " +
                        "selection=$selection, last assertion=${lastObservationFailure?.message}", error)
                }
            }

            sendFocus("intent-focus-42")
            val firstObserved = composeRule.mainClock.currentTime
            composeRule.mainClock.advanceTimeBy(1200)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertIsDisplayed()
            sendFocus("intent-focus-43")
            val secondObserved = composeRule.mainClock.currentTime
            assertTrue(secondObserved < firstObserved + 2100)
            composeRule.mainClock.advanceTimeBy(firstObserved + 2300 - secondObserved)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertIsDisplayed().assertTextContains("Frame requested · Earth")
            composeRule.mainClock.advanceTimeBy(secondObserved + 2300 - composeRule.mainClock.currentTime)
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertCountEquals(0)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                .assertContentDescriptionEquals("Earth. SolarLab semantic focus acknowledged; " +
                    "request-id=intent-focus-43; query=earth; resolved-body=earth")
        } finally {
            composeRule.mainClock.autoAdvance = true
            // ActivityScenario tracks lifecycle events against the launch intent.
            // Restore it after exercising onNewIntent so rule teardown can track this activity.
            try {
                composeRule.runOnUiThread { activity.intent = originalIntent }
            } finally {
                SolarLabSemanticActionBridge.clearPendingReplay()
            }
        }
    }
}
