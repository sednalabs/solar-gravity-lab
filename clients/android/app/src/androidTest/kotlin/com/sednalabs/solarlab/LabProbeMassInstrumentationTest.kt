package com.sednalabs.solarlab

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native/JNI proof, not a fake-facade or constructor-only mass test. */
@RunWith(AndroidJUnit4::class)
class LabProbeMassInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val facade get() = composeRule.activity.runtimeFacadeForTesting

    @Test fun massDraftSurvivesRecreationThenEditsAndReopensTheNativeBody() {
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon"); facade.applyCommand(RuntimeCommand.PausePlayback) }
        composeRule.waitUntil(20_000) {
            facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" &&
                runCatching { composeRule.onNodeWithTag(LabTags.ADD_PROBE).assertIsEnabled() }.isSuccess
        }
        val count = requireNotNull(facade.uiState.value.snapshot).bodyCount
        composeRule.onNodeWithTag(LabTags.ADD_PROBE).performClick()
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithTag(LabPlacementTags.SECTION_BODY).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LabPlacementTags.SECTION_BODY).performClick()
        composeRule.onNodeWithTag(LabPlacementTags.MASS).performTextReplacement("-1")
        closeSoftKeyboard()
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithTag(LabPlacementTags.MASS).performTextReplacement("1e22")
        closeSoftKeyboard()
        composeRule.onNodeWithTag(LabPlacementTags.GRAVITY).performScrollTo().performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(20_000) {
            runCatching { composeRule.onNodeWithTag(LabPlacementTags.MASS).assertTextContains("1e22") }.isSuccess
        }
        capture("01-mass-draft.png")
        composeRule.waitUntil(30_000) { runCatching { composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsEnabled() }.isSuccess }
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).performClick()
        composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.bodyCount == count + 1 && facade.lastProbeCommit.value != null }
        val id = requireNotNull(facade.lastProbeCommit.value).bodyId
        fun body() = runBlocking { requireNotNull(facade.inspectBody(id)) }
        assertEquals(1e22, body().massKg, 0.0)
        assertEquals(1e22, body().sourceMassKg, 0.0)
        val originalPosition = listOf(body().positionX, body().positionY, body().positionZ)
        composeRule.onNodeWithTag(LabTags.INSPECT).performClick()
        composeRule.waitUntil(20_000) { runCatching { composeRule.onNodeWithTag(LabTags.EDIT).assertIsEnabled() }.isSuccess }
        composeRule.onNodeWithTag(LabTags.EDIT).performClick()
        composeRule.onNodeWithTag(SolarLabTestTags.SPAWN_BODY_MASS_FIELD).performScrollTo().performTextReplacement("2e22")
        closeSoftKeyboard()
        capture("02-edit-existing-mass.png")
        composeRule.onNodeWithTag(LabTags.EDIT_COMMIT).performClick()
        composeRule.waitUntil(20_000) { body().massKg == 2e22 }
        assertEquals(2e22, body().sourceMassKg, 0.0)
        assertEquals(originalPosition,listOf(body().positionX,body().positionY,body().positionZ))
        val savedName = "Mass proof ${System.currentTimeMillis()}"
        runBlocking { facade.saveExperiment(savedName) }
        composeRule.waitUntil(20_000) { facade.savedExperiments.value.any { it.name == savedName } }
        val saved = facade.savedExperiments.value.single { it.name == savedName }
        val b = body()
        runBlocking { facade.applyCommand(RuntimeCommand.SetBodyProperties(id,5000.0,0.0,b.radiusM,
            b.positionX,b.positionY,b.positionZ,b.velocityX,b.velocityY,b.velocityZ)) }
        composeRule.waitUntil(20_000) { body().massKg == 5000.0 }
        assertEquals(0.0,body().sourceMassKg,0.0)
        runBlocking { facade.loadExperiment(saved.id) }
        composeRule.waitUntil(20_000) { facade.experimentStatus.value.openedId == saved.id }
        assertEquals(2e22,body().massKg,0.0); assertEquals(2e22,body().sourceMassKg,0.0)
        assertEquals(count+1,facade.uiState.value.snapshot!!.bodyCount)
        capture("03-reopened-mass.png")
    }

    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS + "/solarlab-validation/lab-evidence/probe-mass/")
        }
        val resolver=i.targetContext.contentResolver
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        try { requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }
}
