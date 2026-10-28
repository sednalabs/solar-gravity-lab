package com.sednalabs.solarlab

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSearchInteractionInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchAutofocusAndRepeatedFrameRequestsRefreshFeedback() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.isStageFirstRuntimeMountedForTesting() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            .performScrollToIfPossible()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                .fetchSemanticsNodes().isNotEmpty()
        }

        fun searchAndFrameEarth() {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
                .performScrollToIfPossible()
                .performClick()
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
            composeRule.mainClock.advanceTimeBy(1400)
            searchAndFrameEarth()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(1000)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertIsDisplayed().assertTextContains("Frame requested · Earth")
            composeRule.mainClock.advanceTimeBy(1400)
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FRAME_REQUEST)
                .assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}
