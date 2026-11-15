package com.sednalabs.solarlab

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** User journey plus authoritative checks: explore, edit, run, save, replace, reopen. */
@RunWith(AndroidJUnit4::class)
class EarthMoonLabJourneyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun earthMoonExperimentCanBeEditedRunSavedAndReopened() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "lab-evidence")
            .deleteRecursively()
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        composeRule.waitUntil(30_000) { facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" }
        dismissCoach()
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.focus("moon")).performClick()
        composeRule.waitUntil(20_000) { facade.uiState.value.focusedBodyId == "moon" }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Moon")
        capture("earth-moon-inspection.png")

        val originalMoon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
        val earth = runBlocking { requireNotNull(facade.inspectBody("earth")) }
        composeRule.onNodeWithTag(LabTags.INSPECT).performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(LabTags.EDIT).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LabTags.EDIT).performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(LabTags.EDITOR).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LabTags.component("velocity", "x"))
            .performScrollToIfPossible().performTextReplacement("0.125")
        composeRule.onNodeWithTag(LabTags.EDIT_COMMIT).performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(LabTags.EDITOR).fetchSemanticsNodes().isEmpty() }
        val editedMoon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
        assertEquals(earth.velocityX + 125.0, editedMoon.velocityX, 1e-8)
        assertEquals(originalMoon.velocityY.toBits(), editedMoon.velocityY.toBits())
        assertEquals(originalMoon.positionX.toBits(), editedMoon.positionX.toBits())

        val countBefore = requireNotNull(facade.uiState.value.snapshot).bodyCount
        composeRule.onNodeWithTag(LabTags.ADD_PROBE).performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(LabTags.PROBE_COMMIT).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LabTags.PROBE_COMMIT).performClick()
        composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.bodyCount == countBefore + 1 }
        val probe = runBlocking { requireNotNull(facade.inspectBody("probe-1")) }
        assertEquals(0.0, probe.sourceMassKg, 0.0)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON).performClick()
        capture("earth-moon-probe.png")

        val initialEpoch = requireNotNull(facade.uiState.value.snapshot).epochSeconds
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).performClick()
        composeRule.waitUntil(20_000) { (facade.uiState.value.snapshot?.epochSeconds ?: initialEpoch) > initialEpoch }
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).performClick()
        composeRule.waitUntil(10_000) { facade.uiState.value.snapshot?.paused == true }
        val savedMoon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
        val savedEpoch = requireNotNull(facade.uiState.value.snapshot).epochSeconds
        val name = "Journey ${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(LabTags.SAVED).performClick()
        composeRule.onNodeWithTag(LabTags.SAVE_NAME).performTextReplacement(name)
        composeRule.onNodeWithTag(LabTags.SAVE_COMMIT).performClick()
        composeRule.waitUntil(20_000) { facade.savedExperiments.value.any { it.name == name } }
        val saved = facade.savedExperiments.value.first { it.name == name }
        composeRule.onNodeWithText("Done").performClick()

        // Replace all current bodies, then reconstruct through the saved file, not a live checkpoint.
        runBlocking { facade.loadScenario("showcase.inner-system") }
        composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.scenarioId == "showcase.inner-system" }
        composeRule.onNodeWithTag(LabTags.SAVED).performClick()
        composeRule.onNodeWithTag(LabTags.load(saved.id)).performScrollToIfPossible().performClick()
        composeRule.waitUntil(20_000) {
            facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" &&
                composeRule.onAllNodesWithTag(LabTags.SAVED_DIALOG).fetchSemanticsNodes().isEmpty()
        }
        val reopened = requireNotNull(facade.uiState.value.snapshot)
        assertTrue(reopened.paused)
        assertEquals(savedEpoch.toBits(), reopened.epochSeconds.toBits())
        assertEquals(countBefore + 1, reopened.bodyCount)
        val reopenedMoon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
        assertEquals(savedMoon.positionX.toBits(), reopenedMoon.positionX.toBits())
        assertEquals(savedMoon.velocityX.toBits(), reopenedMoon.velocityX.toBits())
        assertNotNull(runBlocking { facade.inspectBody("probe-1") })
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(LabTags.PLAY_PAUSE).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(composeRule.activity.runtimeFacadeForTesting.uiState.value.snapshot?.paused == true)
        capture("earth-moon-reopened.png")
    }

    private fun dismissCoach() {
        if (composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON).performClick()
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "lab-evidence").apply { mkdirs() }
        File(directory, name).outputStream().use {
            assertTrue("Native screenshot should encode successfully", image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }
}
