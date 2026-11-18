package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.LunarMissionOutcome
import com.sednalabs.solarlab.runtime.LunarMissionPhase
import com.sednalabs.solarlab.runtime.LunarMissionState
import com.sednalabs.solarlab.runtime.REFERENCE_SPACECRAFT_ID
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Actual JNI/world/Vulkan journey. No shell physics or mocked spacecraft transport. */
@RunWith(AndroidJUnit4::class)
class OrbitalFlightJourneyInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val evidencePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/flight-${System.currentTimeMillis()}/"
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Before fun rememberDeviceOrientation() {
        originalOrientation = composeRule.activity.requestedOrientation
    }

    @After fun releaseFlightAndRestoreDeviceOrientation() {
        composeRule.mainClock.autoAdvance = true
        try {
            if (!BuildConfig.STAGE_FIRST_CLIENT || BuildConfig.HOSTED_DEBUG_LITE_MODE) return
            val facade = composeRule.activity.runtimeFacadeForTesting
            runBlocking {
                try {
                    if (facade.flightState() != null) facade.setFlightControl(control = FlightControl.Cut)
                } finally {
                    facade.applyCommand(RuntimeCommand.PausePlayback)
                }
            }
        } finally {
            // Rotation is device-wide state. Restore it even if a native-frame
            // capture/assertion failed so later laboratory tests stay independent.
            composeRule.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    @Test fun launchBurnCheckpointAndSaveUseTheAuthoritativeSpacecraft() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && !BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        composeRule.waitUntil(30_000) { facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" }
        launchOrbitalFlightWhenReady()
        waitUntil { runBlocking { facade.flightState() } != null && nodes(FlightTags.ROOT) }
        val initial = flight(facade)
        assertTrue(initial.propellantMassKg > 0.0)
        assertTrue(initial.hasBoundOrbit)
        val body = runBlocking { requireNotNull(facade.inspectBody(REFERENCE_SPACECRAFT_ID)) }
        assertEquals(0.0, body.sourceMassKg, 0.0)
        assertEquals(initial.totalMassKg, body.massKg, 1e-9)
        composeRule.onNodeWithTag(FlightTags.ALTITUDE).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsDisplayed()
        capture("flight-portrait-orbit.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.camera(2)).performClick()
        capture("flight-initial-chase.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.camera(1)).performClick()
        capture("flight-orbit-after-chase.png", requireCloseShip = true)
        try {
            assertTrue(SolarLabSemanticActionBridge.submit(SolarLabSemanticAction.ResetCamera))
            composeRule.mainClock.advanceTimeByFrame()
            capture("flight-camera-reset.png", requireCloseShip = true)
        } finally {
            SolarLabSemanticActionBridge.clearPendingReplay()
        }
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
            capture("flight-chase-after-burn.png", requireCloseShip = true)
            composeRule.onNodeWithTag(FlightTags.camera(0)).performClick()
            composeRule.mainClock.advanceTimeBy(300)
            assertFlightMap(minimumRadiusM = 10_000_000.0, anchorIds = listOf("earth"))
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
        closeSavedExperimentsDialog()
        composeRule.activityRule.scenario.recreate()
        waitUntil { nodes(FlightTags.ROOT) }
        val retained = composeRule.activity.runtimeFacadeForTesting
        assertEquals(0.0, flight(retained).commandedThrottle, 0.0)
        composeRule.onNodeWithTag(FlightTags.camera(1)).performClick()
        capture("flight-resumed.png", requireCloseShip = true)
        composeRule.activityRule.scenario.recreate()
        waitUntil { nodes(FlightTags.ROOT) }
        capture("flight-recreated-orbit.png", requireCloseShip = true)
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitUntil { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && nodes(FlightTags.ROOT) }
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.CUT).assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag(LabSpatialTags.VIEWPORT).fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue("Landscape flight must leave a usable central scene", viewport.height > 120 * density && viewport.width > 200 * density)
        capture("flight-landscape.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.EXIT).performClick()
        waitUntil { nodes(LabTags.ROOT) && !nodes(FlightTags.ROOT) }
        assertTrue(composeRule.activity.runtimeFacadeForTesting.uiState.value.snapshot?.paused == true)
        assertNotNull(runBlocking { composeRule.activity.runtimeFacadeForTesting.inspectBody(REFERENCE_SPACECRAFT_ID) })
    }

    @Test fun lunarMissionPreservesTheLabAndReachesAnObservedFlyby() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && !BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        advanceAndWait(facade, 42.0)
        val previousEpoch = requireNotNull(facade.uiState.value.snapshot).epochSeconds
        val previousSaves = facade.savedExperiments.value.map { it.id }.toSet()
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
        composeRule.onNodeWithTag(LunarFlightTags.BRIEF).performScrollToIfPossible().performClick()
        composeRule.onNodeWithTag(LunarFlightTags.START).assertIsEnabled().performClick()
        waitUntil(30_000) { runBlocking { facade.lunarMissionState() } != null && nodes(LunarFlightTags.MISSION) }
        val backup = facade.savedExperiments.value.single { it.id !in previousSaves }
        val initial = mission(facade)
        assertEquals(LunarMissionPhase.Ready, initial.phase)
        assertEquals(0.0, initial.elapsedSeconds, 0.0)
        assertEquals(300.0, initial.plannedBurnSeconds, 0.0)
        assertEquals(0.0, requireNotNull(runBlocking { facade.inspectBody(REFERENCE_SPACECRAFT_ID) }).sourceMassKg, 0.0)
        capture("lunar-departure-ready.png", requireCloseShip = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(LunarFlightTags.BURN).assertIsEnabled().performClick()
        waitUntil { mission(facade).guidedBurnActive }
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
        advanceAndWait(facade, 300.0)
        val departed = mission(facade)
        assertEquals(300.0, departed.actualMainFullThrottleSeconds, 1e-7)
        assertTrue("Native integration must cut the completed guided burn", !departed.guidedBurnActive)
        assertEquals(0.0, flight(facade).commandedThrottle, 0.0)
        assertTrue(departed.propellantMassKg < initial.propellantMassKg)
        waitUntil { nodes(LunarFlightTags.WARP) }
        composeRule.onNodeWithTag(LunarFlightTags.WARP).assertIsEnabled().performClick()
        waitUntil { facade.uiState.value.snapshot?.let { !it.paused && it.simSecondsPerRealSecond == 1000.0 } == true }
        waitUntil { mission(facade).elapsedSeconds > departed.elapsedSeconds + 1.0 }
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
        composeRule.onNodeWithTag(FlightTags.camera(0)).performClick()
        assertFlightMap(minimumRadiusM = 100_000_000.0, anchorIds = listOf("earth", "moon"))
        capture("lunar-transfer-map.png")

        // Exercise the production bounded native advance path through the coast;
        // every solver substep still updates the persisted closest observation.
        advanceAndWait(facade, 2.62 * 86_400.0 - mission(facade).elapsedSeconds)
        val flyby = mission(facade)
        assertEquals(LunarMissionOutcome.FlybyAchieved, flyby.outcome)
        assertTrue(flyby.closestObservedMoonAltitudeM in 8.0..1_000_000.0)
        assertTrue("The observed flyby must be past closest approach", flyby.moonRangeRateMps > 0.0)
        waitUntil { facade.uiState.value.snapshot?.paused == true }
        waitUntil { runCatching { composeRule.onNodeWithTag(LunarFlightTags.STATUS).assertTextContains("Lunar flyby") }.isSuccess }
        capture("lunar-flyby-outcome.png")
        val missionSaveName = "Lunar flyby proof ${System.currentTimeMillis()}"
        runBlocking { facade.saveExperiment(missionSaveName) }
        val savedMission = facade.savedExperiments.value.first { it.name == missionSaveName }
        runBlocking { facade.loadExperiment(backup.id) }
        assertEquals(previousEpoch.toBits(), requireNotNull(facade.uiState.value.snapshot).epochSeconds.toBits())
        assertTrue("The previous laboratory must not contain the newly authored ship", runBlocking { facade.flightState() } == null)
        runBlocking { facade.loadExperiment(savedMission.id) }
        val reopened = mission(facade)
        assertEquals(flyby.outcome, reopened.outcome)
        assertEquals(flyby.closestObservedMoonAltitudeM.toBits(), reopened.closestObservedMoonAltitudeM.toBits())
        assertEquals(flyby.actualMainFullThrottleSeconds.toBits(), reopened.actualMainFullThrottleSeconds.toBits())
        assertEquals(0.0, flight(facade).commandedThrottle, 0.0)
    }

    private fun advanceAndWait(facade: RuntimeFacade, seconds: Double) {
        val target = requireNotNull(facade.uiState.value.snapshot).epochSeconds + seconds
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback); facade.applyCommand(RuntimeCommand.AdvanceEpoch(seconds)) }
        waitUntil(120_000) { facade.uiState.value.let { it.manualAdvance == null && (it.snapshot?.epochSeconds ?: -1.0) >= target - 1e-6 } }
    }

    private fun mission(facade: RuntimeFacade): LunarMissionState = runBlocking { requireNotNull(facade.lunarMissionState()) }
    private fun closeSavedExperimentsDialog() {
        // Back can dismiss only the name field's IME, leaving a saved dialog
        // open across recreation. Use the visible user action and establish
        // that the modal really closed before exercising the underlying flight.
        val done = composeRule.onNodeWithText("Done")
        waitUntil { done.isDisplayed() }
        done.assertIsEnabled().performClick()
        waitUntil { !nodes(LabTags.SAVED_DIALOG) }
    }

    private fun launchOrbitalFlightWhenReady() {
        // The native scenario ID is published before Compose receives its body
        // catalogue. Wait for the actual user-facing capability, then retain the
        // same enabled assertion and real launch click.
        waitUntil(30_000) {
            val launch = composeRule.onAllNodesWithTag(FlightTags.LAUNCH).fetchSemanticsNodes().singleOrNull()
            launch != null && !launch.config.contains(SemanticsProperties.Disabled)
        }
        composeRule.onNodeWithTag(FlightTags.LAUNCH).assertIsEnabled().performClick()
    }

    private fun flight(facade: RuntimeFacade): FlightState = runBlocking { requireNotNull(facade.flightState()) }
    private fun nodes(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitUntil(timeoutMs: Long = 20_000, predicate: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeBy(32)
        predicate()
    }

    private fun capture(name: String, requireCloseShip: Boolean = false) {
        if (requireCloseShip) assertCloseFlightView(name)
        var surface: SurfaceView? = null
        var submitted: LabViewportState? = null
        waitUntil {
            composeRule.runOnUiThread {
                surface = findSurface(composeRule.activity.window.decorView)
                submitted = findHost(composeRule.activity.window.decorView)?.currentLabViewportState()
            }
            val frame = submitted
            composeRule.activity.runtimeFacadeForTesting.uiState.value.renderStatus.readiness == RenderHostReadiness.Ready &&
                surface?.holder?.surface?.isValid == true && (surface?.width ?: 0) > 0 && (surface?.height ?: 0) > 0 &&
                frame != null && frame.widthPx == surface?.width && frame.heightPx == surface?.height &&
                frame.bodies.any { it.bodyId == REFERENCE_SPACECRAFT_ID }
        }
        val native = requireNotNull(surface)
        val deadline = SystemClock.uptimeMillis() + 8_000
        var stage: Bitmap? = null
        var window: Bitmap? = null
        var matched = false
        var matchCount = 0
        var sampleCount = 0
        var attempt = 0
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                stage?.recycle()
                stage = null
                window?.recycle()
                window = null
                // Both acquisitions must be fresh. PixelCopy may initially return
                // the preceding Orbit buffer while the window already shows Chase;
                // retrying only the window would compare against that stale buffer forever.
                val nativeImage = copyNativeImage(native, name, deadline)
                stage = nativeImage
                val origin = IntArray(2)
                composeRule.runOnUiThread {
                    native.getLocationOnScreen(origin)
                    submitted = findHost(composeRule.activity.window.decorView)?.currentLabViewportState()
                }
                val windowImage = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                window = windowImage
                attempt += 1
                if (requireCloseShip) {
                    val matches = nativeWindowMatches(nativeImage, windowImage, origin)
                    matchCount = matches.first
                    sampleCount = matches.second
                    // Retain the same geometry and colour-match requirements.
                    val currentRadius = submitted?.camera?.viewRadiusM
                    matched = sampleCount >= 12 && matchCount * 4 >= sampleCount * 3 &&
                        currentRadius != null && currentRadius in 8.0..100.0
                } else matched = true
                Log.i("SolarLabFlightEvidence", "$name pair=$attempt native=${native.width}x${native.height} " +
                    "submittedRadius=${submitted?.camera?.viewRadiusM} nativeMatches=$matchCount/$sampleCount " +
                    "surfaceOrigin=${origin[0]},${origin[1]}")
                if (matched) break
                composeRule.runOnUiThread { composeRule.activity.window.decorView.postInvalidateOnAnimation() }
                if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeByFrame()
                SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceIn(0, 80))
            }
            assertTrue("Native stage and window did not converge: $name; matched=$matchCount/$sampleCount, " +
                "pairs=$attempt, currentRadius=${submitted?.camera?.viewRadiusM}", matched)
        } finally {
            // Keep the actual pair on failure as well as success. Never paste a
            // native buffer into window evidence or substitute a previous capture.
            stage?.let { writeImage(name.removeSuffix(".png") + "-stage.png", it) }
            window?.let { writeImage(if (matched) name else name.removeSuffix(".png") + "-window-unsynchronised.png", it) }
        }
    }

    private fun copyNativeImage(surface: SurfaceView, name: String, deadline: Long): Bitmap {
        val result = AtomicInteger(PixelCopy.ERROR_SOURCE_NO_DATA)
        val attempts = mutableListOf<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val image = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            val completed = CountDownLatch(1)
            PixelCopy.request(surface, image, { result.set(it); completed.countDown() }, Handler(Looper.getMainLooper()))
            val remainingMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
            assertTrue("Native flight PixelCopy callback exceeded its bounded deadline: $name; statuses=$attempts",
                completed.await(remainingMs, TimeUnit.MILLISECONDS))
            val status = result.get()
            attempts += status
            if (status == PixelCopy.SUCCESS) return image
            image.recycle()
            if (status != PixelCopy.ERROR_SOURCE_NO_DATA && status != PixelCopy.ERROR_TIMEOUT) break
            SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceIn(0, 80))
        }
        throw AssertionError("An actual native stage buffer is required: $name; statuses=$attempts, last=${result.get()}")
    }

    private fun nativeWindowMatches(stage: Bitmap, window: Bitmap, origin: IntArray): Pair<Int, Int> {
        var matched = 0
        var samples = 0
        // Sample lit native pixels below the camera/mission overlays. Matching the
        // entire image would also compare Compose chrome absent from PixelCopy.
        for (y in stage.height * 52 / 100 until stage.height * 9 / 10 step (stage.height / 64).coerceAtLeast(1)) {
            for (x in stage.width / 10 until stage.width * 9 / 10 step (stage.width / 64).coerceAtLeast(1)) {
                val expected = stage.getPixel(x, y)
                if (maxOf(Color.red(expected), Color.green(expected), Color.blue(expected)) < 40) continue
                samples += 1
                val screenX = origin[0] + x
                val screenY = origin[1] + y
                if (screenX !in 0 until window.width || screenY !in 0 until window.height) continue
                val actual = window.getPixel(screenX, screenY)
                if (maxOf(abs(Color.red(actual) - Color.red(expected)), abs(Color.green(actual) - Color.green(expected)),
                        abs(Color.blue(actual) - Color.blue(expected))) <= 20) matched += 1
            }
        }
        return matched to samples
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view.takeIf { it.isShown && it.holder.surface.isValid }
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findHost(view: View): SolarSystemRenderHostView? {
        if (view is SolarSystemRenderHostView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun assertFlightMap(minimumRadiusM: Double, anchorIds: List<String>) {
        var viewport: LabViewportState? = null
        var selected: String? = null
        waitUntil {
            composeRule.runOnUiThread {
                findHost(composeRule.activity.window.decorView)?.let {
                    viewport = it.currentLabViewportState()
                    selected = it.selectedBodyId()
                }
            }
            val frame = viewport
            frame != null && frame.camera.viewRadiusM > minimumRadiusM && anchorIds.all { id ->
                frame.bodies.any { body -> body.bodyId == id && body.xPx >= 0 && body.xPx <= frame.widthPx &&
                    body.yPx >= 0 && body.yPx <= frame.heightPx }
            }
        }
        assertEquals("Framing the host world must retain the controlled spacecraft", REFERENCE_SPACECRAFT_ID, selected)
    }

    private fun assertCloseFlightView(label: String) {
        var stableSince = 0L
        var diagnostic = "No submitted viewport"
        try {
            waitUntil {
                var framed = false
                composeRule.runOnUiThread {
                    val host = findHost(composeRule.activity.window.decorView)
                    val frame = host?.currentLabViewportState()
                    val ship = frame?.bodies?.firstOrNull { it.bodyId == REFERENCE_SPACECRAFT_ID }
                    if (host != null && frame != null && ship != null) {
                        val centreDistance = labLength(frame.camera.centerM - ship.worldPositionM)
                        diagnostic = "radius=${frame.camera.viewRadiusM}m centreDistance=${centreDistance}m " +
                            "ship=(${ship.xPx},${ship.yPx}) radiusPx=${ship.radiusPx} occluded=${ship.occluded} " +
                            "viewport=${frame.widthPx}x${frame.heightPx} selection=${host.selectedBodyId()}"
                        framed = host.selectedBodyId() == REFERENCE_SPACECRAFT_ID &&
                            frame.camera.viewRadiusM in 8.0..100.0 && centreDistance < 100.0 &&
                            !ship.occluded && ship.radiusPx >= 12f &&
                            ship.xPx >= 0f && ship.xPx <= frame.widthPx && ship.yPx >= 0f && ship.yPx <= frame.heightPx
                    }
                }
                if (!framed) stableSince = 0L
                else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                framed && SystemClock.uptimeMillis() - stableSince >= 500L
            }
        } catch (failure: Throwable) {
            throw AssertionError("$label must keep a visible, close, spacecraft-centred native view: $diagnostic", failure)
        }
    }

    private fun writeImage(name: String, image: Bitmap) {
        try {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, evidencePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            resolver.openOutputStream(uri)?.use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                ?: error("Could not write flight evidence")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } finally { image.recycle() }
    }
}
