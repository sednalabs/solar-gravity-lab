package com.sednalabs.solarlab

import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFirstSemanticIntentInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun focusIntent_updatesSelectionAndAcknowledgesRequest() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && BuildConfig.DEBUG)
        SolarLabSemanticActionBridge.clearPendingReplay()
        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val intent = Intent(SolarLabSemanticActionBridge.INTENT_ACTION)
                .setClass(composeRule.activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SolarLabSemanticActionBridge.EXTRA_COMMAND, "focus_body")
                .putExtra(SolarLabSemanticActionBridge.EXTRA_BODY_QUERY, "earth")
                .putExtra(SolarLabSemanticActionBridge.EXTRA_REQUEST_ID, "intent-focus-42")
            composeRule.runOnUiThread { composeRule.activity.startActivity(intent) }

            val expected = "Earth. SolarLab semantic focus acknowledged; " +
                "request-id=intent-focus-42; query=earth; resolved-body=earth"
            composeRule.waitUntil(timeoutMillis = 20_000) {
                runCatching {
                    composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE)
                        .assertTextContains("Earth")
                        .assertContentDescriptionEquals(expected)
                }.isSuccess
            }
        } finally {
            SolarLabSemanticActionBridge.clearPendingReplay()
        }
    }
}
