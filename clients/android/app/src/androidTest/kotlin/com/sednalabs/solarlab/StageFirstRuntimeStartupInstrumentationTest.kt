package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstRuntimeStartupInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stageFirstClient_launchesRustWorldWithStageControlsAndLiveRenderHost() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
            .performScrollToIfPossible().assertIsDisplayed()

        val duration = composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_STEP_DURATION)
        duration.performScrollToIfPossible().assertTextContains("6 h").performClick()
        duration.performScrollToIfPossible().assertTextContains("1 d").performClick()
        duration.performScrollToIfPossible().assertTextContains("7 d").performClick()
        duration.performScrollToIfPossible().assertTextContains("30 d").performClick()
        duration.performScrollToIfPossible().assertTextContains("1 s")

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_TRACE_LESS_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_TRACE_MORE_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
            .performClick()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
        pressBack()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(LabTags.TOOLS_PANEL).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val hostView = findRenderHostView(composeRule.activity.window.decorView)
            hostView != null && hostView.width > 0 && hostView.height > 0
        }

        val hostView = findRenderHostView(composeRule.activity.window.decorView)
        assertNotNull("Stage-first render host should be present", hostView)
        assertTrue(
            "Stage-first render host should have a measured size",
            requireNotNull(hostView).width > 0 && hostView.height > 0 && hostView.isShown && hostView.isAttachedToWindow,
        )
    }

    private fun findRenderHostView(root: View): SolarSystemRenderHostView? {
        if (root is SolarSystemRenderHostView) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val match = findRenderHostView(root.getChildAt(index))
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }
}
