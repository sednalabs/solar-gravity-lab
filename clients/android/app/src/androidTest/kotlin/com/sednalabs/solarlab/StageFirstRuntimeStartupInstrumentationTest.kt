package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
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
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_SCALE_CHIP)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HELP_BUTTON)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
            .performClick()

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

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val hostView = findRenderHostView(composeRule.activity.window.decorView)
            hostView != null && hostView.width > 0 && hostView.height > 0
        }

        val hostView = findRenderHostView(composeRule.activity.window.decorView)
        assertNotNull("Stage-first render host should be present", hostView)
        assertTrue(
            "Stage-first render host should have a measured size",
            requireNotNull(hostView).width > 0 && hostView.height > 0,
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
