package com.sednalabs.solarlab

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
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
class StageFirstSearchFocusInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchFocus_updatesVisibleSelectionCard() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            .performScrollToIfPossible()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            .performScrollToIfPossible()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("halley")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_FIELD).performTextInput("earth")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.stageFirstSearchFocusTag("halley")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.stageFirstSearchFocusTag("earth")).performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                    .assertTextContains("Earth")
            }.isSuccess
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_STATUS_PANEL).assertIsDisplayed()

        if (composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting()) {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON).performClick()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")
        }
    }
}
