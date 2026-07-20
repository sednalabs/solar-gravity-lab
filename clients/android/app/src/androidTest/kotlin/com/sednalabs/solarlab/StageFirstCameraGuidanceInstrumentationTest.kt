package com.sednalabs.solarlab

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstCameraGuidanceInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun compactCameraRailAndHelpExposeTheNavigationContract() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_SCALE_CHIP)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH).assertIsDisplayed()
        composeRule.onNodeWithText(STAGE_CAMERA_COACH_ORBIT_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(STAGE_CAMERA_COACH_PAN_ZOOM_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(STAGE_CAMERA_COACH_SELECTION_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
            .assertHeightIsAtLeast(48.dp)
        CameraScaleBand.entries.forEach { scaleBand ->
            composeRule.onNodeWithTag(
                SolarLabTestTags.stageFirstCameraScalePresetTag(scaleBand.name),
            ).assertHeightIsAtLeast(48.dp)
        }
    }
}
