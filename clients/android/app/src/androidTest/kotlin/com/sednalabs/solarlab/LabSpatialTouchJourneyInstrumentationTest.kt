package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import kotlin.math.cos
import kotlin.math.sin

/** Real pointer events on the native stage, plus the actual authoritative experiment result. */
@RunWith(AndroidJUnit4::class)
class LabSpatialTouchJourneyInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val evidencePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/touch-${System.currentTimeMillis()}/"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var recordingOutput: ParcelFileDescriptor? = null
    private val recordingPath get() = "/sdcard/${evidencePath}touch-journey.mp4"

    @Test fun navigateIdentifyPlaceCancelCommitRunAndUndoByTouch() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        assertFalse(BuildConfig.HOSTED_DEBUG_LITE_MODE)
        val facade = composeRule.activity.runtimeFacadeForTesting
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        runBlocking { facade.loadScenario("lab.earth-moon") }
        composeRule.waitUntil(30_000) { facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" && currentViewport() != null }
        composeRule.onNodeWithTag(LabTags.MODE_MAP).performClick()
        settle()
        capture("01-earth-map.png")
        val recordingPid = startRecording()
        try {
            val earth = runBlocking { requireNotNull(facade.inspectBody("earth")) }
            val moon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
            val plane = earthMoonAuthoringPlane(earth, moon)
            val before = requireNotNull(currentViewport())
            val focal = Offset(before.widthPx * 0.42f, before.heightPx * 0.5f)
            val anchorBefore = requireNotNull(before.unprojectToPlane(focal.x, focal.y, plane.through(earth)))
            val epochBeforeZoom = facade.uiState.value.snapshot!!.epochSeconds
            injectPinch(focal, 44f, 100f)
            settle()
            val after = requireNotNull(currentViewport())
            assertTrue("A real spread gesture must produce a useful zoom, despite UI recomposition", after.camera.viewRadiusM < before.camera.viewRadiusM * 0.8)
            val anchorAfter = requireNotNull(after.unprojectToPlane(focal.x, focal.y, plane.through(earth)))
            assertTrue("Pinch must retain the world point under the fingers while following", labLength(anchorAfter - anchorBefore) < before.camera.viewRadiusM * 0.015)
            assertEquals("Camera gestures must not advance time", epochBeforeZoom, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            capture("02-native-pinch.png")

            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON).performClick()
            settle()
            composeRule.onNodeWithTag(LabSpatialTags.label("earth")).assertIsDisplayed()
            composeRule.onNodeWithTag(LabSpatialTags.label("moon")).assertIsDisplayed().performTouchInput { click() }
            composeRule.waitUntil(10_000) { facade.uiState.value.focusedBodyId == "moon" }
            capture("03-labelled-system.png")
            composeRule.onNodeWithTag(LabTags.focus("earth")).performClick()
            settle()
            val baselineCount = facade.uiState.value.snapshot!!.bodyCount

            composeRule.onNodeWithTag(LabTags.ADD_PROBE).performClick()
            waitForPreview()
            settle()
            val target = earth.labPosition() + (plane.axisU * cos(1.8) + plane.axisV * sin(1.8)) * (earth.radiusM + 1_500_000.0)
            val point = requireNotNull(currentViewport()).project(target)
            injectTap(Offset(point.xPx, point.yPx))
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performTextReplacement("1500")
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performImeAction()
            waitForPreview()
            assertEquals("Draft previews must not create live bodies", baselineCount, facade.uiState.value.snapshot!!.bodyCount)
            assertNull(runBlocking { facade.inspectBody("probe-1") })
            capture("04-positioned-probe-preview.png")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_ZOOM_OUT_BUTTON).performClick()
            settle()
            composeRule.onNodeWithTag(LabSpatialTags.VELOCITY).assertIsDisplayed().performTouchInput {
                swipe(center, center + Offset(20f, -16f), durationMillis = 400)
            }
            waitForPreview()
            composeRule.onNodeWithTag(LabPlacementTags.HEADING).performScrollTo().assertIsDisplayed()
            val authoredHeading = composeRule.onNodeWithTag(LabPlacementTags.HEADING)
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            capture("04b-velocity-drag.png")
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(15_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && currentViewport() != null
            }
            settle()
            waitForPreview()
            composeRule.onNodeWithTag(LabPlacementTags.HEADING).performScrollTo().assertIsDisplayed()
            assertEquals("Rotating must retain the uncommitted velocity draft", authoredHeading,
                composeRule.onNodeWithTag(LabPlacementTags.HEADING).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertNull(runBlocking { facade.inspectBody("probe-1") })
            capture("04c-retained-landscape-draft.png")
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            composeRule.waitUntil(15_000) {
                composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && currentViewport() != null
            }
            settle()
            composeRule.onNodeWithTag(LabPlacementTags.CANCEL).performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(LabPlacementTags.ROOT).fetchSemanticsNodes().isEmpty() }
            assertEquals(baselineCount, facade.uiState.value.snapshot!!.bodyCount)
            assertNull(runBlocking { facade.inspectBody("probe-1") })

            composeRule.onNodeWithTag(LabTags.ADD_PROBE).performClick()
            waitForPreview()
            settle()
            val retryPoint = requireNotNull(currentViewport()).project(target)
            injectTap(Offset(retryPoint.xPx, retryPoint.yPx))
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performTextReplacement("1500")
            composeRule.onNodeWithTag(LabPlacementTags.ALTITUDE).performImeAction()
            waitForPreview()
            composeRule.onNodeWithTag(LabTags.PROBE_COMMIT).performClick()
            composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.bodyCount == baselineCount + 1 }
            val placed = runBlocking { requireNotNull(facade.inspectBody("probe-1")) }
            val offset = placed.labPosition() - earth.labPosition()
            assertEquals(1_500_000.0, labLength(offset) - earth.radiusM, 1.0)
            assertEquals(0.0, labDot(offset, plane.normal), 0.01)
            assertTrue("A chosen spot must not collapse to the old fixed +X seed", labLength(placed.labPosition() - target) < 2.0)
            settle()
            composeRule.onNodeWithTag(LabSpatialTags.label("probe-1")).assertIsDisplayed()
            capture("05-placed-probe.png")

            composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).performClick()
            composeRule.waitUntil(15_000) { (facade.uiState.value.snapshot?.epochSeconds ?: 0.0) > epochBeforeZoom }
            SystemClock.sleep(700)
            composeRule.onNodeWithTag(LabTags.PLAY_PAUSE).performClick()
            composeRule.waitUntil(10_000) { facade.uiState.value.snapshot?.paused == true }
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON).performClick()
            settle()
            composeRule.onNodeWithTag(LabSpatialTags.label("probe-1")).assertIsDisplayed().performTouchInput { click() }
            capture("06-probe-recovered-at-system-scale.png")
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_FRAME_SELECTED_BUTTON).performClick()
            settle()
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(15_000) { currentViewport()?.let { it.widthPx > it.heightPx } == true }
            settle()
            composeRule.onNodeWithTag(LabSpatialTags.label("probe-1")).assertIsDisplayed()
            capture("07-landscape-probe.png")
            composeRule.onNodeWithTag(LabTags.UNDO).performClick()
            composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.bodyCount == baselineCount }
            assertNull(runBlocking { facade.inspectBody("probe-1") })
            assertTrue(facade.uiState.value.snapshot!!.paused)
            assertEquals(epochBeforeZoom, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            capture("08-undo-restored-experiment.png")
        } finally {
            shell("kill -2 $recordingPid")
            val stopDeadline = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < stopDeadline && shell("pidof screenrecord").split(' ').contains(recordingPid)) {
                SystemClock.sleep(100)
            }
            recordingOutput?.close()
            recordingOutput = null
            shell("settings put system show_touches 0")
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        assertTrue("The native touch journey must retain a screen recording", (shell("stat -c %s $recordingPath").toLongOrNull() ?: 0L) > 1_024L)
    }

    private fun waitForPreview() {
        composeRule.waitUntil(25_000) {
            composeRule.onAllNodesWithTag(LabTags.PROBE_COMMIT).fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            }
        }
    }

    private fun settle() {
        composeRule.waitForIdle()
        SystemClock.sleep(500)
        composeRule.waitUntil(15_000) { currentViewport() != null }
        composeRule.waitForIdle()
    }

    private fun findHost(view: View): SolarSystemRenderHostView? {
        if (view is SolarSystemRenderHostView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun currentViewport(): LabViewportState? {
        var result: LabViewportState? = null
        composeRule.runOnUiThread { result = findHost(composeRule.activity.window.decorView)?.currentLabViewportState() }
        return result
    }

    private fun stageOrigin(): IntArray {
        val origin = IntArray(2)
        composeRule.runOnUiThread { requireNotNull(findHost(composeRule.activity.window.decorView)).getLocationOnScreen(origin) }
        return origin
    }

    private fun injectTap(point: Offset) {
        val origin = stageOrigin()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, listOf(point), origin)
        SystemClock.sleep(60)
        inject(MotionEvent.ACTION_UP, down, listOf(point), origin)
    }

    private fun injectPinch(focal: Offset, initialHalfSpan: Float, finalHalfSpan: Float) {
        val origin = stageOrigin()
        val down = SystemClock.uptimeMillis()
        fun points(span: Float) = listOf(Offset(focal.x - span, focal.y), Offset(focal.x + span, focal.y))
        inject(MotionEvent.ACTION_DOWN, down, points(initialHalfSpan).take(1), origin)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(initialHalfSpan), origin)
        for (step in 1..30) {
            SystemClock.sleep(14)
            inject(MotionEvent.ACTION_MOVE, down, points(initialHalfSpan + (finalHalfSpan - initialHalfSpan) * step / 30f), origin)
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, points(finalHalfSpan), origin)
        inject(MotionEvent.ACTION_UP, down, points(finalHalfSpan).take(1), origin)
    }

    private fun inject(action: Int, downTime: Long, points: List<Offset>, origin: IntArray) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].x + origin[0]; y = points[index].y + origin[1]; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Native pointer event should be injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun capture(name: String) {
        settle()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, evidencePath); put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
    }.getOrDefault("")

    private fun startRecording(): String {
        shell("settings put system show_touches 1")
        val path = "/sdcard/$evidencePath"
        shell("mkdir -p $path")
        // UiAutomation launches a command directly, not through a shell. Keep
        // the returned pipe alive while screenrecord runs, then send SIGINT.
        recordingOutput = instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --size 720x1600 --bit-rate 2500000 --time-limit 180 $recordingPath")
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val pid = shell("pidof screenrecord")
            if (pid.matches(Regex("[0-9]+"))) return pid
            SystemClock.sleep(100)
        }
        recordingOutput?.close()
        recordingOutput = null
        shell("settings put system show_touches 0")
        error("The native screen recording did not start")
    }
}
