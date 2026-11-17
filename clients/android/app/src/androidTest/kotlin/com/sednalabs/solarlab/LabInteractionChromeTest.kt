package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraScaleBand
import com.graciousgazelles.solarlab.render.core.RenderBodyKind
import com.graciousgazelles.solarlab.render.core.TraceLayerMode
import com.sednalabs.solarlab.runtime.PlaybackSpeedPreset
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import com.sednalabs.solarlab.runtime.SnapshotPresentation
import com.sednalabs.solarlab.runtime.StepQuantumPreset
import com.sednalabs.solarlab.ui.theme.SolarLabTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** UI intent and hit-region checks; native gesture/placement proof lives in the journey suite. */
@RunWith(AndroidJUnit4::class)
class LabInteractionChromeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun portraitZoomIsVisibleAndDistinctFromTimeSpeed() {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        val received = mutableListOf<String>()
        showChrome(received)
        assertReachable(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
        assertReachable(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
        assertReachable(LabTags.MODE_MAP)
        assertReachable(LabTags.MODE_3D)
        assertReachable(LabTags.ADD_PROBE)
        composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON).performClick()
        composeRule.onNodeWithTag(LabTags.SPEED_PICKER).performClick()
        composeRule.onNodeWithText("How quickly the experiment moves through time.").assertIsDisplayed()
        composeRule.onNodeWithTag(LabTags.SPEED_UP).performClick()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertEquals(listOf("zoom-in", "speed-up"), received) }
        assertSeparateRegions()
    }

    @Test
    fun landscapeKeepsObjectActionsAndCameraOutsidePlayback() {
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        val received = mutableListOf<String>()
        showChrome(received, canUndo = true)
        assertReachable(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_IN_BUTTON)
        assertReachable(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON)
        assertReachable(LabTags.FOLLOW)
        assertReachable(LabTags.INSPECT)
        assertReachable(LabTags.PLAY_PAUSE)
        assertReachable(LabTags.ADD_PROBE)
        assertReachable(LabTags.UNDO)
        assertSeparateRegions()
        val header = composeRule.onNodeWithTag(LabTags.COMPACT_HEADER).fetchSemanticsNode().boundsInRoot
        val undo = composeRule.onNodeWithTag(LabTags.UNDO).fetchSemanticsNode().boundsInRoot
        val zoomOut = composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape Undo stays inside the header, clear of zoom-out",
            undo.top >= header.top && undo.bottom <= header.bottom && undo.bottom <= zoomOut.top)
        composeRule.onNodeWithTag(LabTags.MODE_3D).performClick()
        composeRule.onNodeWithTag(LabTags.FOLLOW).performClick()
        composeRule.onNodeWithTag(LabTags.UNDO).performClick()
        composeRule.runOnIdle { assertEquals(listOf("mode-THREE_DIMENSIONAL", "follow", "undo"), received) }
    }

    @Test
    fun boundedPlacementKeepsCommitVisibleAndUsesModelValues() {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        var state by mutableStateOf(LabPlacementUiState(
            hostBodyId = "earth", hosts = listOf(LabPlacementHost("earth", "Earth"), LabPlacementHost("moon", "Moon")),
            planeLabel = "Earth–Moon orbital plane", altitudeKmText = "800", altitudeRangeKm = 10f..100_000f,
            motion = LabPlacementMotion.CIRCULAR, speedKmPerSecondText = "7.45",
            direction = LabPlacementDirection.PROGRADE, previewStatus = "Calculating the proposed path…", canCreate = false,
        ))
        var commits = 0
        composeRule.setContent {
            SolarLabTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF02050B)), contentAlignment = Alignment.BottomCenter) {
                    LabPlacementSheet(state, LabPlacementActions(
                        onHostChange = { state = state.copy(hostBodyId = it) },
                        onAltitudeChange = { state = state.copy(altitudeKmText = it) },
                        onMotionChange = { state = state.copy(motion = it) },
                        onSpeedChange = { state = state.copy(speedKmPerSecondText = it) },
                        onDirectionChange = { state = state.copy(direction = it) },
                        onCreate = { commits += 1 }, onCancel = {},
                        onAltitudeLockChange = { state = state.copy(altitudeLocked = it) },
                        onHeadingChange = { state = state.copy(headingDegreesText = it) },
                    ), Modifier.fillMaxWidth().padding(12.dp).height(LabPlacementMetrics.PortraitHeight))
                }
            }
        }
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag(LabPlacementTags.CANCEL).assertIsDisplayed().assertIsEnabled()
        assertReachable(LabPlacementTags.SECTION_POSITION)
        assertReachable(LabPlacementTags.SECTION_MOTION)
        composeRule.onNodeWithTag(LabPlacementTags.SECTION_POSITION).assertIsSelected()
        composeRule.onNodeWithTag(LabPlacementTags.host("moon"))
            .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE)
            .performScrollTo().assertIsDisplayed().performTextReplacement("1500")
        composeRule.onNodeWithTag(LabPlacementTags.SECTION_MOTION).performClick().assertIsSelected()
        for (motion in listOf(LabPlacementMotion.RELEASE, LabPlacementMotion.CIRCULAR, LabPlacementMotion.CUSTOM)) {
            composeRule.onNodeWithTag(LabPlacementTags.motion(motion))
                .performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .performClick().assertIsSelected()
            composeRule.runOnIdle { assertEquals("The visible motion choice updates the model", motion, state.motion) }
        }
        composeRule.onNodeWithTag(LabPlacementTags.HEADING)
            .performScrollTo().assertIsDisplayed().performTextReplacement("135")
        composeRule.onNodeWithTag(LabPlacementTags.SPEED)
            .performScrollTo().assertIsDisplayed().performTextReplacement("1.2")
        composeRule.runOnIdle {
            assertEquals("moon", state.hostBodyId)
            assertEquals("1500", state.altitudeKmText)
            assertEquals("135", state.headingDegreesText)
            assertEquals("1.2", state.speedKmPerSecondText)
            state = state.copy(canCreate = true, previewStatus = "Preview ready", previewHorizonText = "Next 2 hours")
        }
        composeRule.onNodeWithTag(LabPlacementTags.CREATE).assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, commits) }
        val panel = composeRule.onNodeWithTag(LabPlacementTags.ROOT).fetchSemanticsNode().boundsInRoot
        val create = composeRule.onNodeWithTag(LabPlacementTags.CREATE).fetchSemanticsNode().boundsInRoot
        assertTrue("Placement commit stays inside its reserved panel", create.top >= panel.top && create.bottom <= panel.bottom)
        val tabs = composeRule.onNodeWithTag(LabPlacementTags.SECTION_MOTION).fetchSemanticsNode().boundsInRoot
        assertTrue("Section navigation stays visible above the scrolled numeric fields", tabs.top >= panel.top && tabs.bottom < create.top)
    }

    @Test
    fun landscapeKeyboardKeepsExactInputAndCommitReachable() {
        val previousKeyboardSetting = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        try {
            orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            composeRule.activityRule.scenario.onActivity {
                WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            val keyboardBottom = AtomicInteger(0)
            var state by mutableStateOf(LabPlacementUiState(
                hostBodyId = "earth", hosts = listOf(LabPlacementHost("earth", "Earth"), LabPlacementHost("moon", "Moon")),
                planeLabel = "Earth–Moon orbital plane", altitudeKmText = "800", altitudeRangeKm = 1f..384_400f,
                motion = LabPlacementMotion.CIRCULAR, speedKmPerSecondText = "7.45",
                direction = LabPlacementDirection.PROGRADE, previewStatus = "Calculating the proposed path…", canCreate = false,
            ))
            composeRule.setContent {
                SolarLabTheme {
                    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect { keyboardBottom.set(imeBottom) }
                    // While typing, the host gives the bounded panel all height above the IME.
                    Box(Modifier.fillMaxSize().background(Color(0xFF02050B)).imePadding()
                        .statusBarsPadding().navigationBarsPadding().padding(8.dp), contentAlignment = Alignment.CenterEnd) {
                        LabPlacementSheet(state, LabPlacementActions(
                            onHostChange = { state = state.copy(hostBodyId = it) },
                            onAltitudeChange = { state = state.copy(altitudeKmText = it) },
                            onMotionChange = { state = state.copy(motion = it) },
                            onSpeedChange = { state = state.copy(speedKmPerSecondText = it) },
                            onDirectionChange = { state = state.copy(direction = it) },
                            onCreate = {}, onCancel = {},
                        ), Modifier.width(LabPlacementMetrics.LandscapeWidth).fillMaxHeight())
                    }
                }
            }
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performScrollTo().performClick()
            waitForKeyboard(keyboardBottom, shown = true)
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performTextReplacement("1250")
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performScrollTo().assertIsDisplayed()
            assertReachable(LabPlacementTags.CREATE)
            assertReachable(LabPlacementTags.CANCEL)
            assertReachable(LabPlacementTags.SECTION_MOTION)
            captureChrome("placement-landscape-ime.png")
            val input = composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).fetchSemanticsNode().boundsInRoot
            val content = composeRule.onNodeWithTag(LabPlacementTags.CONTENT).fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag(LabPlacementTags.ROOT).fetchSemanticsNode().boundsInRoot
            val create = composeRule.onNodeWithTag(LabPlacementTags.CREATE).fetchSemanticsNode().boundsInRoot
            assertTrue("The exact input stays wholly visible above the keyboard", input.top >= content.top && input.bottom <= content.bottom)
            assertTrue("The commit action stays inside the keyboard-resized panel", create.bottom <= panel.bottom)
            composeRule.onNodeWithTag(LabPlacementTags.SECTION_MOTION).performClick().assertIsSelected()
            waitForKeyboard(keyboardBottom, shown = false)
            composeRule.onNodeWithTag(LabPlacementTags.motion(LabPlacementMotion.CUSTOM)).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals("1250", state.altitudeKmText) }
            captureChrome("placement-landscape-motion.png")
        } finally {
            if (previousKeyboardSetting == "0" || previousKeyboardSetting == "1") {
                shell("settings put secure show_ime_with_hard_keyboard $previousKeyboardSetting")
            } else {
                shell("settings delete secure show_ime_with_hard_keyboard")
            }
        }
    }

    private fun waitForKeyboard(bottom: AtomicInteger, shown: Boolean) {
        var previous = -1
        var changedAt = SystemClock.elapsedRealtime()
        composeRule.waitUntil(10_000) {
            val current = bottom.get()
            if (current != previous) {
                previous = current
                changedAt = SystemClock.elapsedRealtime()
            }
            (current > 0) == shown && SystemClock.elapsedRealtime() - changedAt >= 250
        }
        composeRule.waitForIdle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun captureChrome(name: String) {
        composeRule.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val resolver = composeRule.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/chrome-${System.currentTimeMillis()}/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(uri).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun orient(requested: Int, configuration: Int) {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        composeRule.waitUntil(10_000) { composeRule.activity.resources.configuration.orientation == configuration }
    }

    private fun assertReachable(tag: String) {
        composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    private fun assertSeparateRegions() {
        val header = composeRule.onNodeWithTag(LabTags.COMPACT_HEADER).fetchSemanticsNode().boundsInRoot
        val zoom = composeRule.onNodeWithTag(LabTags.ZOOM_RAIL).fetchSemanticsNode().boundsInRoot
        val dock = composeRule.onNodeWithTag(LabTags.COMPACT_PLAYBACK).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag(LabTags.ROOT).fetchSemanticsNode().boundsInRoot
        assertTrue("Header leaves a usable scene above the dock", header.bottom < dock.top)
        assertTrue("Zoom belongs to the scene, clear of playback", zoom.top >= header.bottom && zoom.bottom <= dock.top)
        val minimumSceneFraction = if (root.width > root.height) 0.52f else 0.64f
        assertTrue("The scene, rather than the controls, occupies most of the display",
            dock.top - header.bottom >= root.height * minimumSceneFraction)
    }

    private fun showChrome(received: MutableList<String>, canUndo: Boolean = false) {
        val state = ShellUiState(statusLine = "Ready", connectionState = SessionConnectionState.Active,
            snapshot = SnapshotPresentation(scenarioId = "lab.earth-moon", activeBranchId = "main", bodyCount = 3,
                epochSeconds = 0.0, paused = true, simSecondsPerRealSecond = 3_600.0, observerModeLabel = "Free"))
        val earth = RuntimeStageBody("earth", "Earth", Vector3d(0.0, 0.0, 0.0), 6_371_000.0,
            0xFF68AFE8.toInt(), RenderBodyKind.PLANET, "sun")
        composeRule.setContent {
            SolarLabTheme {
                EarthMoonLabChrome(state, earth, "Earth", "Earth, selected", "Earth–Moon laboratory",
                    setOf("earth", "moon", "sun"), StageChromeMode.COLLAPSED, TraceLayerMode.FOCUS,
                    CameraScaleBand.LOCAL, StepQuantumPreset.ONE_HOUR, PlaybackSpeedPreset.HOUR_PER_SECOND,
                    null, LabStageActions(focus = {}, home = {}, frame = {}, togglePlayback = {},
                        speedDown = { received += "speed-down" }, speedUp = { received += "speed-up" },
                        step = {}, cycleStep = {}, inspect = {}, addProbe = {}, saved = {}, science = {}, search = {},
                        scenarios = {}, toggleTools = {}, hideChrome = {}, help = {},
                        zoomIn = { received += "zoom-in" }, zoomOut = { received += "zoom-out" },
                        setScale = {}, fewerTraces = {}, moreTraces = {}, debug = {}, cancelAdvance = {},
                        setViewMode = { received += "mode-${it.name}" }, toggleFollow = { received += "follow" },
                        undo = { received += "undo" }),
                    navigation = LabNavigationUiState(metresPerPixel = 50_000.0, canUndo = canUndo,
                        selectionSubtitle = "6,371 km radius"))
            }
        }
    }
}
