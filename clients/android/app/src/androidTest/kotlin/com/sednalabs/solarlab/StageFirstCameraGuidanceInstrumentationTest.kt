package com.sednalabs.solarlab

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstCameraGuidanceInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun visibleCameraControlsAndManualHelpExplainBothViewModes() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.MODE_MAP).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(LabTags.MODE_3D).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH).assertCountEquals(0)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        val orbitText = composeRule.activity.getString(R.string.stage_camera_coach_orbit)
        val panZoomText = composeRule.activity.getString(R.string.stage_camera_coach_pan_zoom)
        val selectionText = composeRule.activity.getString(R.string.stage_camera_coach_selection)
        assertEquals("Orbit map: drag to pan. 3D: drag to orbit.", orbitText)
        assertEquals("Pinch to zoom in either view. In 3D, move two fingers to pan. The + and − beside the scene also zoom.", panZoomText)
        assertEquals("Tap an object or its name to select it. Focus brings it into view; Follow keeps it centred. Objects lists every body.", selectionText)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH).assertIsDisplayed()
        composeRule.onNodeWithText(orbitText).performScrollToIfPossible().assertIsDisplayed()
        composeRule.onNodeWithText(panZoomText).performScrollToIfPossible().assertIsDisplayed()
        composeRule.onNodeWithText(selectionText).performScrollToIfPossible().assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON)
            .performScrollToIfPossible()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(LabTags.TOOLS_PANEL)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        CameraScaleBand.entries.forEach { scaleBand ->
            composeRule.onNodeWithTag(
                SolarLabTestTags.stageFirstCameraScalePresetTag(scaleBand.name),
            ).performScrollToIfPossible().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        pressBack()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(LabTags.TOOLS_PANEL).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
    }
}
