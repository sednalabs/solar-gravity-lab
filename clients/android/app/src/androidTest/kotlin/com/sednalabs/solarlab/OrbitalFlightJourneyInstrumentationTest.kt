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
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.REFERENCE_SPACECRAFT_ID
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual JNI/world/Vulkan journey. No shell physics or mocked spacecraft transport. */
@RunWith(AndroidJUnit4::class)
class OrbitalFlightJourneyInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val evidencePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/flight-${System.currentTimeMillis()}/"

    @Test fun launchBurnCheckpointAndSaveUseTheAuthoritativeSpacecraft() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && !BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        composeRule.waitUntil(30_000) { facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" }
        composeRule.onNodeWithTag(FlightTags.LAUNCH).assertIsEnabled().performClick()
        waitUntil { runBlocking { facade.flightState() } != null && nodes(FlightTags.ROOT) }
        val initial = flight(facade)
        assertTrue(initial.propellantMassKg > 0.0)
        assertTrue(initial.hasBoundOrbit)
        val body = runBlocking { requireNotNull(facade.inspectBody(REFERENCE_SPACECRAFT_ID)) }
        assertEquals(0.0, body.sourceMassKg, 0.0)
        assertEquals(initial.totalMassKg, body.massKg, 1e-9)
        composeRule.onNodeWithTag(FlightTags.ALTITUDE).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsDisplayed()
        capture("flight-portrait-orbit.png")
        composeRule.onNodeWithTag(FlightTags.camera(2)).performClick()
        capture("flight-initial-chase.png")
        composeRule.onNodeWithTag(FlightTags.camera(1)).performClick()
        composeRule.onNodeWithTag(FlightTags.CHECKPOINT).performClick()
        waitUntil { facade.uiState.value.activeCheckpointId?.startsWith("flight.mark.") == true }
        val marked = flight(facade)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag(FlightTags.assist(FlightAssist.Prograde)).performClick()
            composeRule.mainClock.advanceTimeBy(120)
            composeRule.onNodeWithTag(FlightTags.THROTTLE).performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
            waitUntil { flight(facade).commandedThrottle > .39 }
            composeRule.onNodeWithTag(FlightTags.PLAY_PAUSE).performClick()
            waitUntil(30_000) { flight(facade).propellantMassKg < marked.propellantMassKg - .2 }
            composeRule.onNodeWithTag(FlightTags.CUT).performClick()
            waitUntil { flight(facade).commandedThrottle == 0.0 }
            runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
            waitUntil { facade.uiState.value.snapshot?.paused == true }
            val burned = flight(facade)
            assertTrue("The burn must consume real propellant", burned.propellantMassKg < marked.propellantMassKg)
            assertTrue("Prograde thrust must raise apoapsis", requireNotNull(burned.apoapsisAltitudeM) > requireNotNull(marked.apoapsisAltitudeM) + 1.0)
            composeRule.onNodeWithTag(FlightTags.camera(2)).performClick()
            composeRule.mainClock.advanceTimeBy(300)
            capture("flight-chase-after-burn.png")
            composeRule.onNodeWithTag(FlightTags.camera(0)).performClick()
            composeRule.mainClock.advanceTimeBy(300)
            capture("flight-coast-prediction.png")
            composeRule.onNodeWithTag(FlightTags.RESET).performClick()
            waitUntil { facade.uiState.value.snapshot?.activeBranchId?.startsWith("flight.retry.") == true }
            val reset = flight(facade)
            assertEquals(marked.epochSeconds.toBits(), reset.epochSeconds.toBits())
            assertEquals(marked.propellantMassKg.toBits(), reset.propellantMassKg.toBits())
            assertEquals(0.0, reset.commandedThrottle, 0.0)
        } finally { composeRule.mainClock.autoAdvance = true }

        val savedState = flight(facade)
        val name = "Odyssey native journey ${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(FlightTags.SAVE).performClick()
        waitUntil { nodes(LabTags.SAVED_DIALOG) }
        composeRule.onNodeWithTag(LabTags.SAVE_NAME).performTextReplacement(name)
        composeRule.onNodeWithTag(LabTags.SAVE_COMMIT).performClick()
        waitUntil { facade.savedExperiments.value.any { it.name == name } }
        val saved = facade.savedExperiments.value.first { it.name == name }
        runBlocking {
            facade.applyCommand(RuntimeCommand.AdvanceEpoch(10.0))
            facade.loadExperiment(saved.id)
        }
        val restored = flight(facade)
        assertEquals(savedState.epochSeconds.toBits(), restored.epochSeconds.toBits())
        assertEquals(savedState.propellantMassKg.toBits(), restored.propellantMassKg.toBits())
        assertEquals(savedState.attitudeW.toBits(), restored.attitudeW.toBits())
        assertEquals(savedState.relativeVelocityY.toBits(), restored.relativeVelocityY.toBits())
        assertTrue(facade.uiState.value.snapshot?.paused == true)
        androidx.test.espresso.Espresso.pressBack()
        composeRule.activityRule.scenario.recreate()
        waitUntil { nodes(FlightTags.ROOT) }
        val retained = composeRule.activity.runtimeFacadeForTesting
        assertEquals(0.0, flight(retained).commandedThrottle, 0.0)
        composeRule.onNodeWithTag(FlightTags.camera(1)).performClick()
        capture("flight-resumed.png")
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitUntil { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && nodes(FlightTags.ROOT) }
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.CUT).assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag(LabSpatialTags.VIEWPORT).fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue("Landscape flight must leave a usable central scene", viewport.height > 120 * density && viewport.width > 200 * density)
        capture("flight-landscape.png")
        composeRule.onNodeWithTag(FlightTags.EXIT).performClick()
        waitUntil { nodes(LabTags.ROOT) && !nodes(FlightTags.ROOT) }
        assertTrue(composeRule.activity.runtimeFacadeForTesting.uiState.value.snapshot?.paused == true)
        assertNotNull(runBlocking { composeRule.activity.runtimeFacadeForTesting.inspectBody(REFERENCE_SPACECRAFT_ID) })
    }

    private fun flight(facade: RuntimeFacade): FlightState = runBlocking { requireNotNull(facade.flightState()) }
    private fun nodes(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitUntil(timeoutMs: Long = 20_000, predicate: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeBy(32)
        predicate()
    }

    private fun capture(name: String) {
        var surface: SurfaceView? = null
        waitUntil {
            composeRule.runOnUiThread { surface = findSurface(composeRule.activity.window.decorView) }
            composeRule.activity.runtimeFacadeForTesting.uiState.value.renderStatus.readiness == RenderHostReadiness.Ready &&
                surface?.holder?.surface?.isValid == true && (surface?.width ?: 0) > 0 && (surface?.height ?: 0) > 0
        }
        val native = requireNotNull(surface)
        // A valid recreated Surface can precede its first presented Vulkan frame.
        val deadline = SystemClock.uptimeMillis() + 5_000
        var stage: Bitmap? = null
        var status = PixelCopy.ERROR_SOURCE_NO_DATA
        while (stage == null && SystemClock.uptimeMillis() < deadline) {
            val image = Bitmap.createBitmap(native.width, native.height, Bitmap.Config.ARGB_8888)
            val completed = CountDownLatch(1)
            PixelCopy.request(native, image, { status = it; completed.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("Native flight surface must present a frame", completed.await(5, TimeUnit.SECONDS))
            if (status == PixelCopy.SUCCESS) stage = image else {
                image.recycle()
                if (status != PixelCopy.ERROR_SOURCE_NO_DATA) break
                SystemClock.sleep(80)
            }
        }
        assertEquals(PixelCopy.SUCCESS, status)
        writeImage(name.removeSuffix(".png") + "-stage.png", requireNotNull(stage))
        writeImage(name, requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun writeImage(name: String, image: Bitmap) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, evidencePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(uri)?.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                ?: error("Could not write flight evidence")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } finally { image.recycle() }
    }
}
