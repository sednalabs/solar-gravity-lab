package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstLocalStartupInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stageFirstClient_launches_with_stage_controls_and_live_render_host() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)

        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNode()
            }.isSuccess
        }

        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_ADD_OBJECT_BUTTON).assertIsDisplayed()

        if (BuildConfig.STAGE_FIRST_RUNTIME_MIRROR) {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).assertIsDisplayed()
        } else {
            assertTrue(
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).fetchSemanticsNode()
                }.isFailure,
            )
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            val hostView = findRenderHostView(composeRule.activity.window.decorView)
            hostView != null && hostView.width > 0 && hostView.height > 0
        }

        val hostView = findRenderHostView(composeRule.activity.window.decorView)
        assertTrue("Stage-first render host should be present", hostView != null)
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
