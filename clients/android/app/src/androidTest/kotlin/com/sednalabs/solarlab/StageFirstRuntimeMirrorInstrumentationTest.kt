package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
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
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).fetchSemanticsNode()
            }.isSuccess
        }

        composeRule.runOnUiThread {
            composeRule.activity.showStageFirstRuntimeMirrorForTesting()
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            val runtimeState = composeRule.activity.runtimeFacadeForTesting.uiState.value
            runtimeState.connectionState == com.sednalabs.solarlab.runtime.SessionConnectionState.Active &&
                runtimeState.sessionHandle != null &&
                runtimeState.snapshot != null
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNode()
            }.isSuccess &&
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNode()
                }.isSuccess &&
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON).fetchSemanticsNode()
                }.isSuccess
        }

        assertTrue(
            "Runtime mirror sandbox button should be exposed",
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_RUNTIME_SANDBOX_BUTTON).fetchSemanticsNode()
            }.isSuccess,
        )
        assertTrue(
            "Runtime mirror search button should be exposed",
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SEARCH_BUTTON).fetchSemanticsNode()
            }.isSuccess,
        )
        assertTrue(
            "Runtime mirror debug button should be exposed",
            runCatching {
                composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_DEBUG_BUTTON).fetchSemanticsNode()
            }.isSuccess,
        )

        composeRule.waitUntil(timeoutMillis = 20_000) {
            val hostView = findRenderHostView(composeRule.activity.window.decorView)
            hostView != null && hostView.width > 0 && hostView.height > 0
        }

        val hostView = findRenderHostView(composeRule.activity.window.decorView)
        assertTrue("Runtime mirror render host should be present", hostView != null)
        assertTrue(
            "Runtime mirror render host should have a measured size",
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
