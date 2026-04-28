package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graciousgazelles.solarlab.feature.lab.render.SolarSystemRenderHostView
import kotlinx.coroutines.runBlocking
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
        SolarLabSemanticActionBridge.clearPendingReplay()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
            .performScrollToIfPossible()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_MODE_BUTTON)
            .performScrollToIfPossible()
            .assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.showStageFirstRuntimeMirrorForTesting()
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val state = composeRule.activity.runtimeFacadeForTesting.uiState.value
            state.sessionHandle != null && state.backendSummary != null
        }

        val runtimeState = composeRule.activity.runtimeFacadeForTesting.uiState.value
        assertTrue(
            "Runtime mirror should bind a native runtime session handle",
            requireNotNull(runtimeState.sessionHandle) > 0L,
        )
        val backendSummary = requireNotNull(runtimeState.backendSummary) {
            "Runtime mirror should expose requested/effective backend truth"
        }
        assertTrue(
            "Runtime mirror backend summary should include CPU truth: $backendSummary",
            backendSummary.contains("cpu="),
        )
        assertTrue(
            "Runtime mirror backend summary should include GPU truth: $backendSummary",
            backendSummary.contains("gpu="),
        )
        if (BuildConfig.PREFERRED_GPU_BACKEND.equals("vulkan", ignoreCase = true)) {
            assertTrue(
                "Stage-first mirror validation should request and surface Vulkan runtime intent: $backendSummary",
                backendSummary.contains("gpu=vulkan") ||
                    backendSummary.contains("requested vulkan"),
            )
        }
        assertTrue(
            "Runtime mirror surface should stay mounted after binding backend truth",
            composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting(),
        )

        val showcaseScenarioId = "showcase.jupiter-system"
        runBlocking {
            composeRule.activity.runtimeFacadeForTesting.loadScenario(showcaseScenarioId)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.runtimeFacadeForTesting.uiState.value.snapshot?.scenarioId == showcaseScenarioId
        }

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
