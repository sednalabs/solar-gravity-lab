package com.sednalabs.solarlab

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSemanticIntentInstrumentationTest {
    init {
        SolarLabSemanticActionBridge.clearPendingReplay()
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun semanticFocusIntent_updatesSandboxSelectionCard() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && SolarLabSemanticActionBridge.semanticActionsEnabled())

        composeRule.waitForStageFirstControls()
        composeRule.dispatchSemanticIntent(
            command = "focus_body",
            bodyQuery = "earth",
        )

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.hasNodeText(
                tag = SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE,
                text = "Earth",
            )
        }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
            .assertTextContains("Earth")
    }

    @Test
    fun semanticNavigationIntents_switchBetweenRuntimeMirrorAndSandbox() {
        assumeTrue(
            BuildConfig.STAGE_FIRST_CLIENT &&
                BuildConfig.STAGE_FIRST_RUNTIME_MIRROR &&
                SolarLabSemanticActionBridge.semanticActionsEnabled(),
        )

        composeRule.waitForStageFirstControls()
        assertFalse(composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting())

        composeRule.dispatchSemanticIntent(command = "open_immersive")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting()
        }

        composeRule.dispatchSemanticIntent(command = "return_to_sandbox")
        composeRule.waitUntil(timeoutMillis = 20_000) {
            !composeRule.activity.isStageFirstRuntimeMirrorMountedForTesting()
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.waitForStageFirstControls() {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.hasNodeText(
        tag: String,
        text: String,
    ): Boolean {
        return onAllNodes(hasTestTag(tag) and hasText(text, substring = true))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.dispatchSemanticIntent(
        command: String,
        bodyQuery: String? = null,
    ) {
        val intent = Intent(SolarLabSemanticActionBridge.INTENT_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SolarLabSemanticActionBridge.EXTRA_COMMAND, command)
        if (bodyQuery != null) {
            intent.putExtra(SolarLabSemanticActionBridge.EXTRA_BODY_QUERY, bodyQuery)
        }
        runOnUiThread {
            intent.setClass(activity, MainActivity::class.java)
            activity.startActivity(intent)
        }
    }
}
