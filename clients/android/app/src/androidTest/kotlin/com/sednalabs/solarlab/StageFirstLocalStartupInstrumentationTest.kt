package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import org.junit.Assert.assertNotNull
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
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Debug").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Add object").fetchSemanticsNodes().isNotEmpty())

        if (BuildConfig.STAGE_FIRST_RUNTIME_MIRROR) {
            assertTrue(composeRule.onAllNodesWithText("Runtime").fetchSemanticsNodes().isNotEmpty())
        } else {
            assertTrue(composeRule.onAllNodesWithText("Runtime").fetchSemanticsNodes().isEmpty())
        }

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
