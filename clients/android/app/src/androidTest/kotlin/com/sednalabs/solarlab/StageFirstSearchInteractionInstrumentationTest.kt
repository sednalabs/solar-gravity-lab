package com.sednalabs.solarlab

import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSearchInteractionInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun objectsSearchCanBeFocusedAndRepeatedFrameRequestsRefreshFeedback() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.isStageFirstRuntimeMountedForTesting() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        fun searchAndFrameEarth(beforeSelect: () -> Unit = {}) {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                .performScrollToIfPossible()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Objects opens as a chooser. The keyboard appears only when the user
            // explicitly starts searching, leaving the scene catalogue readable.
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                .assertIsNotFocused().performClick()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.mainClock.advanceTimeByFrame()
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).assertIsFocused()
                }.isSuccess
            }
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performTextInput("earth")
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            beforeSelect()
            composeRule.onNodeWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).performClick()
        }

        // Keep the feedback timer under test control; the second action occurs before
        // the first timeout and must start a fresh timeout for the same body.
        composeRule.mainClock.autoAdvance = false
        try {
            searchAndFrameEarth()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertIsDisplayed().assertTextContains("Frame requested · Earth")
            val firstObserved = composeRule.mainClock.currentTime
            composeRule.mainClock.advanceTimeBy(1200)
            // Publish delayed state before checking a timer checkpoint. With a
            // manual clock, semantic queries do not advance pending recompositions.
            composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
            composeRule.mainClock.advanceTimeByFrame()
            searchAndFrameEarth {
                // The first event must still be active immediately before the second click.
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                    .assertCountEquals(1)
                assertTrue(composeRule.mainClock.currentTime < firstObserved + 2100)
            }
            composeRule.mainClock.advanceTimeByFrame()
            val secondObserved = composeRule.mainClock.currentTime
            composeRule.mainClock.advanceTimeBy(firstObserved + 2300 - secondObserved)
            composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertIsDisplayed().assertTextContains("Frame requested · Earth")
            composeRule.mainClock.advanceTimeBy(secondObserved + 2300 - composeRule.mainClock.currentTime)
            composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
    @Test
    fun focusedSearchKeepsCloseAndResultsAvailable() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            .performScrollToIfPossible().performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
            .assertIsNotFocused().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).assertIsFocused()
            }.isSuccess
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performTextInput("earth")
        composeRule.onNodeWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth"))
            .assertIsDisplayed()
        composeRule.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).assertCountEquals(0)
    }

    @Test
    fun openSearchAndQuerySurviveActivityRecreation() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        val previousAutoAdvance = composeRule.mainClock.autoAdvance
        var phase = "waiting for Objects"
        var lastObservationFailure: AssertionError? = null
        fun step(value: String) {
            phase = value
            Log.i("SolarLabSearchProof", "Search recreation: $value")
        }
        fun awaitSearchState(condition: () -> Boolean) {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.mainClock.advanceTimeBy(32)
                condition()
            }
        }
        fun searchAssertionHolds(assertion: () -> Unit): Boolean = try {
            assertion()
            lastObservationFailure = null
            true
        } catch (error: AssertionError) {
            lastObservationFailure = error
            false
        }
        // Native viewport updates continue while the world is paused. As in the
        // other search journeys, observe explicit UI frames instead of requiring
        // global Compose idleness during focused text input and recreation.
        composeRule.mainClock.autoAdvance = false
        try {
            step("waiting for Objects")
            awaitSearchState {
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Objects must respond before the asynchronous Rust catalogue has
            // arrived. The visible button must remain enabled during startup.
            step("opening Objects")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                .assertIsEnabled().assertIsDisplayed().performClick()
            awaitSearchState {
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            step("focusing search")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                .assertIsDisplayed().performClick()
            awaitSearchState {
                searchAssertionHolds {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                        .assertIsFocused()
                }
            }
            step("entering query")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performTextInput("earth")
            awaitSearchState {
                searchAssertionHolds {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                        .assertIsDisplayed().assertIsFocused().assertTextContains("earth")
                }
            }
            val originalActivity = composeRule.activity
            step("recreating activity with focused query")
            composeRule.activityRule.scenario.recreate()
            step("waiting for restored query")
            awaitSearchState {
                composeRule.activity !== originalActivity && searchAssertionHolds {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                        .assertIsDisplayed().assertTextContains("earth")
                }
            }
            assertTrue("The activity must actually be recreated", composeRule.activity !== originalActivity)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD)
                .assertIsDisplayed().assertTextContains("earth")
            step("restored query verified")
        } catch (error: Throwable) {
            // ActivityScenarioRule closes the activity after this body. Preserve
            // the original failure if Compose disposal then throws separately.
            lastObservationFailure?.takeIf { it !== error }?.let { error.addSuppressed(it) }
            Log.e("SolarLabSearchProof", "Search recreation failed during $phase", error)
            throw error
        } finally {
            composeRule.mainClock.autoAdvance = previousAutoAdvance
        }
    }

}
