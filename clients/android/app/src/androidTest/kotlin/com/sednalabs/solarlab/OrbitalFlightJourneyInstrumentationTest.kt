package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.DockingGuidance
import com.sednalabs.solarlab.runtime.DockingState
import com.sednalabs.solarlab.runtime.DockingStatus
import com.sednalabs.solarlab.runtime.FlightAssist
import com.sednalabs.solarlab.runtime.FlightControl
import com.sednalabs.solarlab.runtime.FlightState
import com.sednalabs.solarlab.runtime.LunarMissionOutcome
import com.sednalabs.solarlab.runtime.LunarMissionPhase
import com.sednalabs.solarlab.runtime.LunarMissionState
import com.sednalabs.solarlab.runtime.RendezvousGuidance
import com.sednalabs.solarlab.runtime.RendezvousState
import com.sednalabs.solarlab.runtime.RendezvousStatus
import com.sednalabs.solarlab.runtime.REFERENCE_SPACECRAFT_ID
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.render.vulkan.LabBodyAnchor
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

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
        composeRule.onNodeWithTag(FlightTags.PERIAPSIS).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.DIRECTOR).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsDisplayed()
        composeRule.onNodeWithTag(FlightTags.GUIDE).performClick()
        composeRule.onNodeWithText("Fly with intent").assertIsDisplayed()
        composeRule.onNodeWithText("Back to flight").performClick()
        composeRule.onNodeWithTag(FlightTags.PLAN).performClick()
        composeRule.onNodeWithTag(FlightPlanningTags.assist(FlightAssist.Prograde)).performScrollToIfPossible().performClick()
        composeRule.onNodeWithTag(FlightPlanningTags.PREVIEW).performScrollToIfPossible().performClick()
        waitUntil(30_000) { nodes(FlightPlanningTags.RESULT) }
        composeRule.onNodeWithTag(FlightPlanningTags.RESULT).performScrollToIfPossible().assertIsDisplayed()
        val afterPreview = flight(facade)
        assertEquals("Planning must not advance the world", initial.epochSeconds.toBits(), afterPreview.epochSeconds.toBits())
        assertEquals("Planning must not spend live fuel", initial.propellantMassKg.toBits(), afterPreview.propellantMassKg.toBits())
        assertEquals("Planning must not change live controls", initial.control, afterPreview.control)
        composeRule.onNodeWithText("Back to flight").performClick()
        capture("flight-portrait-orbit.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.camera(2)).performClick()
        capture("flight-initial-chase.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.camera(1)).performClick()
        capture("flight-orbit-after-chase.png", requireCloseShip = true)
        composeRule.onNodeWithTag(FlightTags.RECENTER).performClick()
        capture("flight-centre-control.png", requireCloseShip = true)
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

    @Test fun stationEncounterReusesFlightControlsAndPersistsNativeGuidance() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && !BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        advanceAndWait(facade, 42.0)
        launchOrbitalFlightWhenReady()
        waitUntil { nodes(FlightTags.ROOT) && runBlocking { facade.flightState() } != null }
        val oldEpoch = flight(facade).epochSeconds
        val previousSaves = facade.savedExperiments.value.map { it.id }.toSet()
        composeRule.onNodeWithTag(FlightTags.MISSIONS).performClick()
        composeRule.onNodeWithTag(RendezvousTags.BRIEF).performScrollToIfPossible().performClick()
        composeRule.onNodeWithTag(RendezvousTags.START).assertIsEnabled().performClick()
        waitUntil(30_000) { runBlocking { facade.rendezvousState() } != null && nodes(RendezvousTags.PANEL) }
        val backup = facade.savedExperiments.value.single { it.id !in previousSaves }
        val initial = encounter(facade)
        assertEquals(0.0, initial.epochSeconds, 0.0)
        assertEquals(RendezvousGuidance.Manual, initial.guidance)
        assertTrue(initial.targetAvailable)
        assertTrue(initial.rangeM in 990.0..1_010.0)
        assertEquals(0.0, requireNotNull(runBlocking { facade.inspectBody(initial.targetBodyId) }).sourceMassKg, 0.0)
        assertEncounterView(initial.targetBodyId)
        composeRule.onNodeWithTag(RendezvousTags.RANGE).assertIsDisplayed()
        composeRule.onNodeWithTag(RendezvousTags.DIRECTION).assertIsDisplayed()
        capture("rendezvous-departure.png", encounterTargetId = initial.targetBodyId,
            requireUnobscuredEncounter = true)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(RendezvousTags.MATCH).assertIsEnabled().performClick()
        waitUntil { encounter(facade).guidance == RendezvousGuidance.MatchVelocity && facade.uiState.value.snapshot?.paused == false }
        assertEquals(1.0, requireNotNull(facade.uiState.value.snapshot).simSecondsPerRealSecond, 0.0)
        advanceAndWait(facade, 90.0)
        val matched = encounter(facade)
        assertTrue("Native RCS must remove relative velocity", matched.relativeSpeedMps < .03)
        assertTrue(matched.rangeM > 900.0)
        assertTrue(matched.remainingPropellantKg < initial.remainingPropellantKg)

        // Actual held input during real playback exercises force and release;
        // advancing a Compose clock cannot substitute for spacecraft dynamics.
        composeRule.onNodeWithTag(FlightTags.PLAY_PAUSE).performClick()
        waitUntil { facade.uiState.value.snapshot?.paused == false }
        val right = composeRule.onNodeWithTag(FlightTags.axis("Right")).assertIsDisplayed()
        right.performTouchInput { down(center) }
        try {
            waitUntil { flight(facade).control.translationX > .99 && encounter(facade).guidance == RendezvousGuidance.Manual }
            val held = flight(facade)
            assertEquals("Taking over one axis must clear autonomous Y demand", 0.0, held.control.translationY, 0.0)
            assertEquals("Taking over one axis must clear autonomous Z demand", 0.0, held.control.translationZ, 0.0)
            waitUntil(15_000) { flight(facade).epochSeconds >= held.epochSeconds + 2.0 }
        } finally { right.performTouchInput { up() } }
        waitUntil { !flight(facade).control.hasManualInput && flight(facade).commandedThrottle == 0.0 }
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
        val releasedFuel = flight(facade).propellantMassKg
        advanceAndWait(facade, 1.0)
        assertEquals("Released manual RCS must not leave hidden thrust", releasedFuel, flight(facade).propellantMassKg, 1e-9)

        val readyToApproach = encounter(facade)
        assertEquals(RendezvousGuidance.Manual, readyToApproach.guidance)
        assertTrue(readyToApproach.targetAvailable && readyToApproach.remainingPropellantKg > 0.0)
        // Native commit is observable before Compose has consumed the terminal
        // advance state. With the test clock paused, drive frames until the real
        // control acknowledges readiness; a stuck/broken capability still fails.
        waitUntil {
            val approach = composeRule.onAllNodesWithTag(RendezvousTags.APPROACH).fetchSemanticsNodes().singleOrNull()
            facade.uiState.value.manualAdvance == null && approach != null &&
                !approach.config.contains(SemanticsProperties.Disabled)
        }
        composeRule.onNodeWithTag(RendezvousTags.APPROACH).assertIsEnabled().performClick()
        waitUntil { encounter(facade).guidance == RendezvousGuidance.Approach }
        advanceAndWait(facade, 650.0)
        val arrived = encounter(facade)
        assertTrue(arrived.standOffAchieved)
        assertEquals(RendezvousStatus.Holding, arrived.status)
        assertTrue(arrived.standOffErrorM < 1.0)
        assertTrue(arrived.relativeSpeedMps < .02)
        assertFalse(arrived.keepOutEntered)
        assertTrue(arrived.closestObservedRangeM > arrived.keepOutDistanceM)
        assertEncounterView(arrived.targetBodyId)
        capture("rendezvous-stand-off.png", encounterTargetId = arrived.targetBodyId, requireEncounterDetail = true,
            requireUnobscuredEncounter = true)

        // Import paused active guidance through the production save boundary;
        // old-session UI disposal must not cut the newly imported control state.
        val guidedName = "Guided station hold ${System.currentTimeMillis()}"
        runBlocking { facade.saveExperiment(guidedName) }
        val guidedSave = facade.savedExperiments.value.first { it.name == guidedName }
        advanceAndWait(facade, 10.0)
        runBlocking { facade.loadExperiment(guidedSave.id) }
        waitUntil { encounter(facade).epochSeconds == arrived.epochSeconds && nodes(RendezvousTags.PANEL) }
        assertEncounterView(arrived.targetBodyId)
        val restored = encounter(facade)
        assertEquals(RendezvousGuidance.Approach, restored.guidance)
        assertEquals(arrived.targetBodyId, restored.targetBodyId)
        assertEquals(arrived.closestObservedRangeM.toBits(), restored.closestObservedRangeM.toBits())
        assertEquals(arrived.remainingPropellantKg.toBits(), restored.remainingPropellantKg.toBits())
        assertTrue(facade.uiState.value.snapshot?.paused == true)
        advanceAndWait(facade, 10.0)
        assertTrue(encounter(facade).standOffErrorM < 1.0)
        assertEquals("Old-session disposal must not silently cancel restored guidance", RendezvousGuidance.Approach, encounter(facade).guidance)
        saveAndReopenEncounterThroughUi(facade, backup.id, oldEpoch, initial.targetBodyId)
    }

    @Test fun dockingCapturesSavesAndReleasesThroughTheNativeFlightExperience() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT && !BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        advanceAndWait(facade, 12.0)
        launchOrbitalFlightWhenReady()
        waitUntil { nodes(FlightTags.ROOT) && runBlocking { facade.flightState() } != null }
        val oldEpoch = flight(facade).epochSeconds
        val previousSaves = facade.savedExperiments.value.map { it.id }.toSet()
        composeRule.onNodeWithTag(FlightTags.MISSIONS).performClick()
        composeRule.onNodeWithTag(DockingTags.BRIEF).performScrollToIfPossible().performClick()
        clickReadyFlightAction(DockingTags.START)
        waitUntil(30_000) { runBlocking { facade.dockingState() } != null && nodes(DockingTags.PANEL) }
        val backup = facade.savedExperiments.value.single { it.id !in previousSaves }
        val initial = docking(facade)
        assertEquals(0.0, initial.epochSeconds, 0.0)
        assertEquals(DockingGuidance.Manual, initial.guidance)
        assertFalse(initial.attached || initial.armed || initial.unsafeContact)
        assertTrue(initial.targetAvailable)
        assertEquals(30.0, initial.centreRangeM, 1e-6)
        assertEquals(22.44, initial.positionPortZM, 1e-6)
        assertTrue(runBlocking { facade.rendezvousState() } == null)
        assertEquals(0.0, requireNotNull(runBlocking { facade.inspectBody(initial.targetBodyId) }).sourceMassKg, 0.0)
        composeRule.onNodeWithTag(DockingTags.GAP).assertIsDisplayed()
        composeRule.onNodeWithTag(DockingTags.CLOSING).assertIsDisplayed()
        composeRule.onNodeWithTag(DockingTags.ALIGNMENT).assertIsDisplayed()
        composeRule.onNodeWithTag(DockingTags.OFFSET).assertIsDisplayed()
        assertEncounterView(initial.targetBodyId)
        capture("docking-ready.png", encounterTargetId = initial.targetBodyId,
            requireEncounterDetail = true, requireUnobscuredEncounter = true)

        composeRule.mainClock.autoAdvance = false
        clickReadyFlightAction(DockingTags.DOCK)
        waitUntil { docking(facade).guidance == DockingGuidance.Approach && docking(facade).armed }
        assertTrue("Dock must leave playback under the Run/Pause control", facade.uiState.value.snapshot?.paused == true)
        assertEquals(initial.epochSeconds, docking(facade).epochSeconds, 0.0)
        clickReadyFlightAction(DockingTags.ABORT)
        waitUntil { docking(facade).guidance == DockingGuidance.Manual && !docking(facade).armed }
        assertFalse(flight(facade).control.requiresRealTime)
        clickReadyFlightAction(DockingTags.DOCK)
        waitUntil { docking(facade).armed && facade.uiState.value.manualAdvance == null }
        composeRule.onNodeWithTag(FlightTags.PLAY_PAUSE).assertIsEnabled().performClick()
        waitUntil { facade.uiState.value.snapshot?.paused == false && docking(facade).epochSeconds > initial.epochSeconds + .2 }
        assertEquals(1.0, requireNotNull(facade.uiState.value.snapshot).simSecondsPerRealSecond, 0.0)
        // The bounded advance uses the production native contact solver. It is
        // simulated-time correctness evidence, not a real-time device benchmark.
        advanceAndWait(facade, 180.0)
        val attached = docking(facade)
        assertTrue("Native port contact must create the physical attachment: $attached", attached.attached)
        assertEquals(DockingStatus.Docked, attached.status)
        assertFalse(attached.unsafeContact)
        assertTrue(attached.remainingPropellantKg < initial.remainingPropellantKg)
        assertTrue(attached.lateralErrorM <= attached.captureLateralLimitM + .01)
        assertTrue(abs(attached.positionPortZM) < .05)
        waitUntil { nodes(DockingTags.UNDOCK) }
        composeRule.onNodeWithTag(FlightTags.THROTTLE).assertIsNotEnabled()
        composeRule.onNodeWithTag(FlightTags.axis("Back")).assertIsNotEnabled()
        assertFalse(flight(facade).control.requiresRealTime)
        assertEncounterView(attached.targetBodyId)
        capture("docking-attached.png", encounterTargetId = attached.targetBodyId,
            requireEncounterDetail = true, requireUnobscuredEncounter = true)

        composeRule.onNodeWithTag(FlightTags.SAVE).assertIsEnabled().performClick()
        waitUntil { nodes(LabTags.SAVED_DIALOG) && docking(facade).attached }
        val savedState = docking(facade)
        val savedName = "Docked at Relay ${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(LabTags.SAVE_NAME).performTextReplacement(savedName)
        composeRule.onNodeWithTag(LabTags.SAVE_COMMIT).performClick()
        waitUntil { facade.savedExperiments.value.any { it.name == savedName } }
        val userSave = facade.savedExperiments.value.first { it.name == savedName }
        closeSavedExperimentsDialog()
        advanceAndWait(facade, 5.0)
        assertTrue(docking(facade).attached)
        assertTrue(abs(docking(facade).positionPortZM - savedState.positionPortZM) < .01)
        runBlocking { facade.loadExperiment(backup.id) }
        assertEquals(oldEpoch.toBits(), flight(facade).epochSeconds.toBits())
        assertTrue(runBlocking { facade.dockingState() } == null)
        assertTrue(runBlocking { facade.inspectBody(initial.targetBodyId) } == null)
        runBlocking { facade.loadExperiment(userSave.id) }
        waitUntil { runBlocking { facade.dockingState() }?.attached == true && nodes(DockingTags.UNDOCK) }
        val restored = docking(facade)
        assertEquals(savedState.targetBodyId, restored.targetBodyId)
        assertEquals(savedState.epochSeconds.toBits(), restored.epochSeconds.toBits())
        assertEquals(savedState.remainingPropellantKg.toBits(), restored.remainingPropellantKg.toBits())
        assertEquals(savedState.positionPortZM.toBits(), restored.positionPortZM.toBits())
        assertTrue(facade.uiState.value.snapshot?.paused == true)
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitUntil { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && nodes(DockingTags.UNDOCK) }
        assertEncounterView(restored.targetBodyId)
        capture("docking-reopened-landscape.png", encounterTargetId = restored.targetBodyId,
            requireEncounterDetail = true, requireUnobscuredEncounter = true)

        val beforeRelease = requireNotNull(runBlocking { facade.inspectBody(REFERENCE_SPACECRAFT_ID) })
        clickReadyFlightAction(DockingTags.UNDOCK)
        waitUntil { !docking(facade).attached && docking(facade).guidance == DockingGuidance.Manual }
        val afterRelease = requireNotNull(runBlocking { facade.inspectBody(REFERENCE_SPACECRAFT_ID) })
        assertTrue(facade.uiState.value.snapshot?.paused == true)
        assertEquals(beforeRelease.positionX.toBits(), afterRelease.positionX.toBits())
        assertEquals(beforeRelease.positionY.toBits(), afterRelease.positionY.toBits())
        assertEquals(beforeRelease.positionZ.toBits(), afterRelease.positionZ.toBits())
        assertEquals(beforeRelease.velocityX.toBits(), afterRelease.velocityX.toBits())
        assertEquals(beforeRelease.velocityY.toBits(), afterRelease.velocityY.toBits())
        assertEquals(beforeRelease.velocityZ.toBits(), afterRelease.velocityZ.toBits())
        assertFalse(flight(facade).control.requiresRealTime)
        val releasedGap = docking(facade).positionPortZM
        composeRule.onNodeWithTag(FlightTags.PLAY_PAUSE).performClick()
        waitUntil { facade.uiState.value.snapshot?.paused == false }
        val back = composeRule.onNodeWithTag(FlightTags.axis("Back")).assertIsDisplayed().assertIsEnabled()
        back.performTouchInput { down(center) }
        try {
            waitUntil { flight(facade).control.translationZ < -.99 }
            val began = flight(facade)
            waitUntil(15_000) { flight(facade).epochSeconds >= began.epochSeconds + 3.0 }
            assertTrue(flight(facade).propellantMassKg < began.propellantMassKg)
        } finally { back.performTouchInput { up() } }
        waitUntil { !flight(facade).control.hasManualInput }
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
        assertFalse(docking(facade).attached)
        assertTrue("Back must physically separate the ports", docking(facade).positionPortZM > releasedGap + .1)
        capture("docking-departed-landscape.png", encounterTargetId = restored.targetBodyId,
            requireEncounterDetail = true, requireUnobscuredEncounter = true)
    }

    private fun saveAndReopenEncounterThroughUi(facade: RuntimeFacade, backupId: String, oldEpoch: Double, targetId: String) {
        // The user-facing Saves flow intentionally cuts guidance before opening.
        composeRule.onNodeWithTag(FlightTags.SAVE).performClick()
        waitUntil { nodes(LabTags.SAVED_DIALOG) && encounter(facade).guidance == RendezvousGuidance.Manual }
        val savedName = "Station rendezvous ${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(LabTags.SAVE_NAME).performTextReplacement(savedName)
        composeRule.onNodeWithTag(LabTags.SAVE_COMMIT).performClick()
        waitUntil { facade.savedExperiments.value.any { it.name == savedName } }
        val userSave = facade.savedExperiments.value.first { it.name == savedName }
        closeSavedExperimentsDialog()
        runBlocking { facade.loadExperiment(backupId) }
        assertEquals(oldEpoch.toBits(), flight(facade).epochSeconds.toBits())
        assertTrue(runBlocking { facade.rendezvousState() } == null)
        assertTrue(runBlocking { facade.inspectBody(targetId) } == null)
        runBlocking { facade.loadExperiment(userSave.id) }
        waitUntil { encounter(facade).standOffAchieved && nodes(RendezvousTags.PANEL) }
        assertEquals(targetId, encounter(facade).targetBodyId)
        assertEquals(RendezvousGuidance.Manual, encounter(facade).guidance)
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitUntil { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && nodes(RendezvousTags.PANEL) }
        assertEncounterView(targetId)
        composeRule.onNodeWithTag(RendezvousTags.APPROACH).assertIsDisplayed()
        capture("rendezvous-restored-landscape.png", encounterTargetId = targetId, requireEncounterDetail = true,
            requireUnobscuredEncounter = true)
    }

    private fun encounter(facade: RuntimeFacade): RendezvousState = runBlocking { requireNotNull(facade.rendezvousState()) }
    private fun docking(facade: RuntimeFacade): DockingState = runBlocking { requireNotNull(facade.dockingState()) }

    private data class EncounterTargetObservation(val targetBodyId: String, val targetAvailable: Boolean, val rangeM: Double)
    private fun encounterTarget(facade: RuntimeFacade): EncounterTargetObservation = runBlocking {
        facade.dockingState()?.let { EncounterTargetObservation(it.targetBodyId, it.targetAvailable, it.centreRangeM) }
            ?: requireNotNull(facade.rendezvousState()).let { EncounterTargetObservation(it.targetBodyId, it.targetAvailable, it.rangeM) }
    }

    private fun closeSavedExperimentsDialog() {
        // Back can dismiss only the name field's IME, leaving a saved dialog
        // open across recreation. Use the visible user action and establish
        // that the modal really closed before exercising the underlying flight.
        val done = composeRule.onNodeWithText("Done")
        waitUntil { done.isDisplayed() }
        done.assertIsEnabled().performClick()
        waitUntil { !nodes(LabTags.SAVED_DIALOG) }
    }

    private fun clickReadyFlightAction(tag: String) {
        waitUntil {
            val node = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            node != null && !node.config.contains(SemanticsProperties.Disabled)
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
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

    private fun assertEncounterView(targetId: String) {
        var stableSince = 0L
        var diagnostic = "No submitted encounter view"
        try {
            waitUntil {
                val authoritative = encounterTarget(composeRule.activity.runtimeFacadeForTesting)
                var framed = false
                composeRule.runOnUiThread {
                    val host = findHost(composeRule.activity.window.decorView)
                    val frame = host?.currentLabViewportState()
                    val ship = frame?.bodies?.firstOrNull { it.bodyId == REFERENCE_SPACECRAFT_ID }
                    val target = frame?.bodies?.firstOrNull { it.bodyId == targetId }
                    if (host != null && frame != null && ship != null && target != null) {
                        val midpoint = (ship.worldPositionM + target.worldPositionM) * .5
                        val range = labLength(ship.worldPositionM - target.worldPositionM)
                        diagnostic = "radius=${frame.camera.viewRadiusM} renderedRange=$range nativeRange=${authoritative.rangeM} " +
                            "shipRadius=${ship.radiusPx} targetRadius=${target.radiusPx}"
                        framed = host.selectedBodyId() == REFERENCE_SPACECRAFT_ID &&
                            authoritative.targetAvailable && authoritative.targetBodyId == targetId &&
                            abs(range - authoritative.rangeM) < 1.0 &&
                            frame.camera.viewRadiusM >= 20.0 && frame.camera.viewRadiusM < 2 * range.coerceAtLeast(50.0) &&
                            labLength(frame.camera.centerM - midpoint) < 10.0 && listOf(ship, target).all {
                                !it.occluded && it.radiusPx >= 1f && it.xPx in 0f..frame.widthPx.toFloat() &&
                                    it.yPx in 0f..frame.heightPx.toFloat()
                            }
                    }
                }
                if (!framed) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                framed && SystemClock.uptimeMillis() - stableSince >= 500L
            }
        } catch (error: Throwable) { throw AssertionError("Both native encounter meshes must be framed: $diagnostic", error) }
    }

    private fun advanceAndWait(facade: RuntimeFacade, seconds: Double) {
        val target = requireNotNull(facade.uiState.value.snapshot).epochSeconds + seconds
        runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback); facade.applyCommand(RuntimeCommand.AdvanceEpoch(seconds)) }
        waitUntil(120_000) { facade.uiState.value.let { it.manualAdvance == null && (it.snapshot?.epochSeconds ?: -1.0) >= target - 1e-6 } }
    }

    private fun mission(facade: RuntimeFacade): LunarMissionState = runBlocking { requireNotNull(facade.lunarMissionState()) }
    private fun flight(facade: RuntimeFacade): FlightState = runBlocking { requireNotNull(facade.flightState()) }
    private fun nodes(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitUntil(timeoutMs: Long = 20_000, predicate: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeBy(32)
        predicate()
    }

    private fun capture(name: String, requireCloseShip: Boolean = false,
        encounterTargetId: String? = null, requireEncounterDetail: Boolean = false,
        requireUnobscuredEncounter: Boolean = false) {
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
        var meshDiagnostic = ""
        var encounterEnvelopesClear = !requireUnobscuredEncounter
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                // Both acquisitions must be fresh. PixelCopy may initially return
                // the preceding Orbit buffer while the window already shows Chase;
                // retrying only the window would compare against that stale buffer forever.
                val nativeImage = copyNativeImage(native, name, deadline)
                val origin = IntArray(2)
                composeRule.runOnUiThread {
                    native.getLocationOnScreen(origin)
                    submitted = findHost(composeRule.activity.window.decorView)?.currentLabViewportState()
                }
                val windowImage = try {
                    requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                } catch (error: Throwable) {
                    nativeImage.recycle()
                    throw error
                }
                // Retain the last complete, genuine pair until its replacement
                // arrives. A final PixelCopy can exhaust the shared deadline;
                // it must not erase earlier mismatching buffers that explain
                // the failure.
                stage?.recycle()
                window?.recycle()
                stage = nativeImage
                window = windowImage
                attempt += 1
                if (encounterTargetId != null) {
                    val frame = submitted
                    val ids = listOf(REFERENCE_SPACECRAFT_ID, encounterTargetId)
                    val anchors = ids.mapNotNull { id -> frame?.bodies?.firstOrNull { it.bodyId == id } }
                    val overlays = encounterOverlayBoundsOnScreen()
                    val evidence = anchors.map { encounterMeshMatches(nativeImage, windowImage, origin, it, overlays) }
                    val envelopes = if (requireUnobscuredEncounter) anchors.map {
                        encounterEnvelope(nativeImage, windowImage, origin, it, overlays)
                    } else emptyList()
                    encounterEnvelopesClear = !requireUnobscuredEncounter ||
                        (envelopes.size == 2 && envelopes.all { it.clear } && frame != null &&
                            frame.widthPx == nativeImage.width && frame.heightPx == nativeImage.height)
                    matchCount = evidence.sumOf { it.matches }
                    sampleCount = evidence.sumOf { it.samples }
                    val authoritative = encounterTarget(composeRule.activity.runtimeFacadeForTesting)
                    val renderedRange = anchors.takeIf { it.size == 2 }?.let {
                        labLength(it[0].worldPositionM - it[1].worldPositionM)
                    }
                    // A distant, true-scale hull may cover fewer than twelve
                    // material pixels. It must still contribute matching pixels;
                    // the readable arrival/reopened views prove each mesh alone.
                    matched = encounterEnvelopesClear && evidence.size == 2 && sampleCount >= 12 && matchCount * 4 >= sampleCount * 3 &&
                        evidence.all { it.samples > 0 && it.matches * 4 >= it.samples * 3 &&
                            (!requireEncounterDetail || it.samples >= 12) } &&
                        authoritative.targetAvailable && authoritative.targetBodyId == encounterTargetId &&
                        renderedRange != null && abs(renderedRange - authoritative.rangeM) < 1.0
                    meshDiagnostic = " renderedRange=$renderedRange nativeRange=${authoritative.rangeM} " +
                        evidence.joinToString { "${it.bodyId}@${it.x},${it.y},r=${it.radius}:" +
                            "${it.matches}/${it.samples},hudExcluded=${it.hudExcluded}" } +
                        if (requireUnobscuredEncounter) " envelopes=" + envelopes.joinToString { it.diagnostic } else ""
                } else if (requireCloseShip) {
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
                    "surfaceOrigin=${origin[0]},${origin[1]}$meshDiagnostic")
                if (matched) break
                composeRule.runOnUiThread { composeRule.activity.window.decorView.postInvalidateOnAnimation() }
                if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeByFrame()
                SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceIn(0, 80))
            }
            if (requireUnobscuredEncounter) assertTrue(
                "Both encounter hull envelopes must be fully visible outside the actual HUD: $name;$meshDiagnostic",
                encounterEnvelopesClear)
            assertTrue("Native stage and window did not converge: $name; matched=$matchCount/$sampleCount, " +
                "pairs=$attempt, currentRadius=${submitted?.camera?.viewRadiusM}$meshDiagnostic", matched)
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

    private data class EncounterMeshEvidence(val bodyId: String, val x: Float, val y: Float, val radius: Float,
        val matches: Int, val samples: Int, val hudExcluded: Int)

    private data class EncounterEnvelopeEvidence(val clear: Boolean, val diagnostic: String)

    private fun encounterEnvelope(stage: Bitmap, window: Bitmap, origin: IntArray,
        anchor: LabBodyAnchor, overlays: List<RectF>): EncounterEnvelopeEvidence {
        if (!anchor.xPx.isFinite() || !anchor.yPx.isFinite() || !anchor.radiusPx.isFinite() || anchor.radiusPx <= 0f) {
            return EncounterEnvelopeEvidence(false, "${anchor.bodyId}:invalid projected hull")
        }
        // The native radius encloses the physical hull, including unlit material.
        // Enough matching pixels below an overlay cannot prove an unobscured ship.
        // The landscape cockpit must leave the whole projected envelope clear,
        // with a two-pixel raster margin against surface/window edges and the HUD.
        val radius = anchor.radiusPx + 2f
        val x = origin[0] + anchor.xPx
        val y = origin[1] + anchor.yPx
        val envelope = RectF(x - radius, y - radius, x + radius, y + radius)
        val surfaceBounds = RectF(origin[0].toFloat(), origin[1].toFloat(),
            origin[0] + stage.width.toFloat(), origin[1] + stage.height.toFloat())
        val inSurface = surfaceBounds.contains(envelope)
        val inWindow = RectF(0f, 0f, window.width.toFloat(), window.height.toFloat()).contains(envelope)
        val intersections = overlays.filter { RectF.intersects(envelope, it) }
        val clear = !anchor.occluded && inSurface && inWindow && intersections.isEmpty()
        return EncounterEnvelopeEvidence(clear,
            "${anchor.bodyId}:clear=$clear,hull=$envelope,inSurface=$inSurface,inWindow=$inWindow," +
                "occluded=${anchor.occluded},hud=$intersections")
    }

    private fun encounterOverlayBoundsOnScreen(): List<RectF> {
        val screen = IntArray(2)
        val inWindow = IntArray(2)
        composeRule.runOnUiThread {
            composeRule.activity.window.decorView.getLocationOnScreen(screen)
            composeRule.activity.window.decorView.getLocationInWindow(inWindow)
        }
        val tags = listOf(RendezvousTags.PANEL, RendezvousTags.BRIEF, LunarFlightTags.BRIEF, DockingTags.PANEL, DockingTags.BRIEF,
            FlightTags.RECENTER, FlightTags.GUIDE, FlightTags.DIRECTOR, FlightTags.PLAN, FlightTags.MISSIONS) +
            (0..3).map(FlightTags::camera)
        return tags.flatMap { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes() }.mapNotNull {
            val bounds = it.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) null else RectF(
                bounds.left + screen[0] - inWindow[0] - 2f, bounds.top + screen[1] - inWindow[1] - 2f,
                bounds.right + screen[0] - inWindow[0] + 2f, bounds.bottom + screen[1] - inWindow[1] + 2f)
        }
    }

    private fun encounterMeshMatches(stage: Bitmap, window: Bitmap, origin: IntArray,
        anchor: LabBodyAnchor, overlays: List<RectF>): EncounterMeshEvidence {
        val radius = anchor.radiusPx.coerceAtLeast(1f)
        // Native anchors enclose the physical hulls. The annulus uses the native
        // image, which contains no Compose overlays, to estimate the background.
        // Matching smooth Earth/sky pixels cannot stand in for visible material.
        val background = (0 until 32).mapNotNull { index ->
            val angle = 2.0 * PI * index / 32
            val x = (anchor.xPx + cos(angle) * (radius * 1.35f + 2f)).roundToInt()
            val y = (anchor.yPx + sin(angle) * (radius * 1.35f + 2f)).roundToInt()
            if (x in 0 until stage.width && y in 0 until stage.height) stage.getPixel(x, y) else null
        }
        if (background.isEmpty()) return EncounterMeshEvidence(anchor.bodyId, anchor.xPx, anchor.yPx, radius, 0, 0, 0)
        val red = background.map(Color::red).sorted()[background.size / 2]
        val green = background.map(Color::green).sorted()[background.size / 2]
        val blue = background.map(Color::blue).sorted()[background.size / 2]
        var matched = 0
        var samples = 0
        var hudExcluded = 0
        val step = (radius / 24f).toInt().coerceAtLeast(1)
        for (y in floor(anchor.yPx - radius).toInt().coerceAtLeast(0)..
            ceil(anchor.yPx + radius).toInt().coerceAtMost(stage.height - 1) step step) {
            for (x in floor(anchor.xPx - radius).toInt().coerceAtLeast(0)..
                ceil(anchor.xPx + radius).toInt().coerceAtMost(stage.width - 1) step step) {
                val dx = x + .5f - anchor.xPx
                val dy = y + .5f - anchor.yPx
                if (dx * dx + dy * dy > radius * radius) continue
                val screenX = origin[0] + x
                val screenY = origin[1] + y
                if (overlays.any { it.contains(screenX + .5f, screenY + .5f) }) {
                    hudExcluded += 1
                    continue
                }
                val expected = stage.getPixel(x, y)
                if (maxOf(Color.red(expected), Color.green(expected), Color.blue(expected)) < 40 ||
                    maxOf(abs(Color.red(expected) - red), abs(Color.green(expected) - green),
                        abs(Color.blue(expected) - blue)) <= 20) continue
                samples += 1
                if (screenX !in 0 until window.width || screenY !in 0 until window.height) continue
                val actual = window.getPixel(screenX, screenY)
                if (maxOf(abs(Color.red(actual) - Color.red(expected)), abs(Color.green(actual) - Color.green(expected)),
                        abs(Color.blue(actual) - Color.blue(expected))) <= 20) matched += 1
            }
        }
        return EncounterMeshEvidence(anchor.bodyId, anchor.xPx, anchor.yPx, radius, matched, samples, hudExcluded)
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
