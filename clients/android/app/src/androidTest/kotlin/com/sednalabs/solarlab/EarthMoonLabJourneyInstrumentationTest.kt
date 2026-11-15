package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** User journey plus authoritative checks: explore, edit, run, save, replace, reopen. */
@RunWith(AndroidJUnit4::class)
class EarthMoonLabJourneyInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private val evidenceRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/${System.currentTimeMillis()}/"

    @Test
    fun earthMoonExperimentCanBeEditedRunSavedAndReopened() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        assertFalse("The complete lab journey requires automatic playback; disable hosted-debug-lite for this test",
            BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        composeRule.waitUntil(30_000) { facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" }
        dismissCoach()
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
        capture("earth-moon-initial-overview.png")
        composeRule.onNodeWithTag(LabTags.focus("earth")).performClick()
        composeRule.waitUntil(20_000) { facade.uiState.value.focusedBodyId == "earth" }
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_SELECTION_TITLE).assertTextContains("Earth")
        capture("earth-close-view.png")
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
        capture("earth-moon-probe.png")
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON).performClick()

        val initialEpoch = requireNotNull(facade.uiState.value.snapshot).epochSeconds
        composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).performClick()
        composeRule.waitUntil(10_000) { facade.uiState.value.snapshot?.paused == false }
        Log.i("SolarLabJourney", "Playback acknowledged; waiting for epoch after $initialEpoch")
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
        captureLandscapeLayout()
    }

    private fun captureLandscapeLayout() {
        val originalOrientation = composeRule.activity.requestedOrientation
        try {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            composeRule.waitUntil(20_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    composeRule.onAllNodesWithTag(LabTags.PLAY_PAUSE).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
            val shortWindow = composeRule.activity.resources.configuration.screenHeightDp < 520
            if (shortWindow) {
                val header = composeRule.onNodeWithTag(LabTags.COMPACT_HEADER).fetchSemanticsNode().boundsInRoot
                val playback = composeRule.onNodeWithTag(LabTags.COMPACT_PLAYBACK).fetchSemanticsNode().boundsInRoot
                assertTrue("Landscape playback must leave a separate stage region below the header", header.bottom < playback.top)
            }
            capture("earth-moon-landscape.png")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
            if (shortWindow) {
                val header = composeRule.onNodeWithTag(LabTags.COMPACT_HEADER).fetchSemanticsNode().boundsInRoot
                val playback = composeRule.onNodeWithTag(LabTags.COMPACT_PLAYBACK).fetchSemanticsNode().boundsInRoot
                val tools = composeRule.onNodeWithTag(LabTags.TOOLS_PANEL).fetchSemanticsNode().boundsInRoot
                assertTrue("Expanded tools must stay below the landscape header", tools.top >= header.bottom)
                assertTrue("Expanded tools must not cover landscape playback", tools.bottom <= playback.top)
            }
            composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).assertIsDisplayed()
            capture("earth-moon-landscape-tools.png")
        } finally {
            composeRule.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    private fun dismissCoach() {
        if (composeRule.onAllNodesWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_COACH_DISMISS_BUTTON).performClick()
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        // Compose idle does not wait for the native camera's 240 ms ValueAnimator
        // or for Vulkan presentation. Allow a bounded camera settle, then require
        // an actual SurfaceView buffer through PixelCopy before taking the UI image.
        val settledAfter = SystemClock.uptimeMillis() + 750L
        var surface: SurfaceView? = null
        composeRule.waitUntil(10_000) {
            composeRule.runOnUiThread {
                surface = findNativeSurface(composeRule.activity.window.decorView)
            }
            val state = composeRule.activity.runtimeFacadeForTesting.uiState.value
            state.renderStatus.readiness == RenderHostReadiness.Ready &&
                surface?.holder?.surface?.isValid == true &&
                (surface?.width ?: 0) > 0 && (surface?.height ?: 0) > 0 &&
                SystemClock.uptimeMillis() >= settledAfter
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stage = captureNativeSurface(requireNotNull(surface))
        writeImage("${name.removeSuffix(".png")}-stage.png", stage)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        writeImage(name, image)
    }

    private fun findNativeSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findNativeSurface(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun captureNativeSurface(surface: SurfaceView): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var result = PixelCopy.ERROR_SOURCE_NO_DATA
        do {
            val image = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            PixelCopy.request(surface, image, { status ->
                result = status
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("Native stage PixelCopy should complete", copied.await(5, TimeUnit.SECONDS))
            if (result == PixelCopy.SUCCESS) return image
            image.recycle()
            // A newly rebound surface can be valid before its first Vulkan buffer.
            if (result == PixelCopy.ERROR_SOURCE_NO_DATA) SystemClock.sleep(100L)
        } while (result == PixelCopy.ERROR_SOURCE_NO_DATA && SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Unable to copy a presented native stage buffer (PixelCopy status $result)")
    }

    private fun writeImage(name: String, image: Bitmap) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        try {
            // Shared Downloads survive connected-test package uninstall. A unique
            // run directory avoids confusing this run's images with earlier evidence.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, evidenceRelativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                requireNotNull(resolver.openOutputStream(uri)).use {
                    assertTrue("Native screenshot should encode successfully", image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.flush()
                }
                val published = resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                assertTrue("Native screenshot should be published for collection", published > 0)
                Log.i("SolarLabJourney", "Saved $evidenceRelativePath$name")
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } finally {
            image.recycle()
        }
    }
}
