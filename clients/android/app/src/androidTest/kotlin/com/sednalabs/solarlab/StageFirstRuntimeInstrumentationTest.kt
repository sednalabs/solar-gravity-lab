package com.sednalabs.solarlab

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstRuntimeInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stageFirstClient_mountsRustRuntimeAndNativeStage() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        SolarLabSemanticActionBridge.clearPendingReplay()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.isStageFirstRuntimeMountedForTesting()
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            val state = composeRule.activity.runtimeFacadeForTesting.uiState.value
            state.sessionHandle != null && state.backendSummary != null
        }

        val runtimeState = composeRule.activity.runtimeFacadeForTesting.uiState.value
        assertTrue(
            "Stage-first client should bind a native runtime session handle",
            requireNotNull(runtimeState.sessionHandle) > 0L,
        )
        val backendSummary = requireNotNull(runtimeState.backendSummary) {
            "Stage-first client should expose requested/effective backend truth"
        }
        assertTrue(
            "Runtime backend summary should include CPU truth: $backendSummary",
            backendSummary.contains("cpu="),
        )
        assertTrue(
            "Runtime backend summary should include GPU truth: $backendSummary",
            backendSummary.contains("gpu="),
        )
        if (BuildConfig.PREFERRED_GPU_BACKEND.equals("vulkan", ignoreCase = true)) {
            assertTrue(
                "Stage-first runtime validation should request and surface Vulkan intent: $backendSummary",
                backendSummary.contains("gpu=vulkan") ||
                    backendSummary.contains("requested vulkan"),
            )
        }
        assertTrue(
            "Native stage should stay mounted after binding backend truth",
            composeRule.activity.isStageFirstRuntimeMountedForTesting(),
        )

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.runOnUiThread {
                findRenderHostView(composeRule.activity.window.decorView)?.let { hostView ->
                    hostView.isAttachedToWindow && hostView.width > 0 && hostView.height > 0
                } == true
            }
        }
        val initialHostView = requireNotNull(
            composeRule.runOnUiThread {
                findRenderHostView(composeRule.activity.window.decorView)
            },
        ) { "Runtime render host should be present before scenario replacement" }

        val showcaseScenarioId = "showcase.jupiter-system"
        runBlocking {
            composeRule.activity.runtimeFacadeForTesting.loadScenario(showcaseScenarioId)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.runtimeFacadeForTesting.uiState.value.snapshot?.scenarioId == showcaseScenarioId
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.runOnUiThread {
                val hostView = findRenderHostView(composeRule.activity.window.decorView)
                hostView != null &&
                    hostView === initialHostView &&
                    hostView.isAttachedToWindow &&
                    hostView.width > 0 &&
                    hostView.height > 0
            }
        }

        val hostView = composeRule.runOnUiThread {
            findRenderHostView(composeRule.activity.window.decorView)
        }
        assertNotNull("Runtime render host should be present", hostView)
        assertTrue(
            "Scenario replacement should preserve the native render host instance",
            hostView === initialHostView,
        )
        assertTrue(
            "Runtime render host should have a measured size",
            composeRule.runOnUiThread {
                requireNotNull(hostView).isAttachedToWindow &&
                    hostView.width > 0 &&
                    hostView.height > 0
            },
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
