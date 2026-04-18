package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstRuntimeMirrorInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun runtimeMirror_mode_switch_exposes_runtime_controls() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && BuildConfig.STAGE_FIRST_RUNTIME_MIRROR)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_IMMERSIVE_CONFIRM_BUTTON).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_IMMERSIVE_CONFIRM_BUTTON).performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON).fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON).assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            val hostView = findRenderHostView(composeRule.activity.window.decorView)
            hostView != null && hostView.width > 0 && hostView.height > 0
        }

        val hostView = findRenderHostView(composeRule.activity.window.decorView)
        assertNotNull("Runtime mirror render host should be present", hostView)
        assertTrue(
            "Runtime mirror render host should have a measured size",
            requireNotNull(hostView).width > 0 && hostView.height > 0,
        )

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).performClick()
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

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNodes().isNotEmpty()
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
