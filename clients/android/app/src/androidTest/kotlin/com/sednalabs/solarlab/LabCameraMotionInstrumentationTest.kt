package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graciousgazelles.solarlab.render.core.ObserverMode
import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.render.core.CameraState
import com.sednalabs.solarlab.render.vulkan.LabPlane
import com.sednalabs.solarlab.render.vulkan.LabViewportState
import com.sednalabs.solarlab.render.vulkan.SolarSystemRenderHostView
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RenderFrame
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.SessionConnectionState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Native camera/picking geometry, real MotionEvents and observable Vulkan resource work. */
@RunWith(AndroidJUnit4::class)
class LabCameraMotionInstrumentationTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val facade get() = composeRule.activity.runtimeFacadeForTesting
    private val evidencePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation/lab-evidence/camera-${System.currentTimeMillis()}/"
    private val proof = JSONObject()

    @Test
    fun canonicalTracersUseThreeDimensionalComputeAndReuseResourcesAcrossResize() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        try {
            runBlocking {
                facade.loadScenario("stress.trail-density")
                facade.applyCommand(RuntimeCommand.PausePlayback)
                facade.applyCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.Free))
            }
            composeRule.waitUntil(30_000) {
                facade.uiState.value.snapshot?.let { it.scenarioId == "stress.trail-density" && it.paused } == true &&
                    facade.uiState.value.renderFrame?.tracers?.size?.let { it >= 192 } == true &&
                    viewport()?.bodies?.any { it.bodyId == "neptune" } == true
            }
            val pausedEpoch = facade.uiState.value.snapshot!!.epochSeconds
            composeRule.onNodeWithTag(LabTags.MODE_3D).performClick()
            val sun = runBlocking { requireNotNull(facade.inspectBody("sun")) }
            setAllTracesVisible(false)
            val frame = requireNotNull(facade.uiState.value.renderFrame)
            val current = requireNotNull(viewport())
            // Existing deterministic belt tracers supply real, nonzero Z. Choose a
            // shallow canonical view with both medium and far points inside the screen.
            val camera = (0 until 8).map { index ->
                CameraState(sun.labPosition(), COMPUTE_RADIUS_M, index * Math.PI / 4.0, CameraState.MIN_PITCH_RADIANS)
            }.maxBy { candidate ->
                val simulated = current.copy(camera = candidate,
                    metresPerPixel = candidate.viewRadiusM * 2.0 / min(current.widthPx, current.heightPx))
                val expected = projectedComputePoints(simulated, frame)
                min(expected.mediumVisible.count { safelyInside(it, simulated) }, expected.farVisible.count { safelyInside(it, simulated) })
            }
            onHost { restoreLabCamera(camera) }
            composeRule.waitUntil(20_000) {
                viewport()?.let { abs(it.camera.viewRadiusM - COMPUTE_RADIUS_M) < 1.0 &&
                    abs(kotlin.math.sin(it.camera.yawRadians - camera.yawRadians)) < 1e-6 &&
                    abs(it.camera.pitchRadians - camera.pitchRadians) < 1e-6 } == true
            }
            proveComputeProjectionAndReuse("compute-portrait")

            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(25_000) {
                viewport()?.let { it.widthPx > it.heightPx && abs(it.camera.viewRadiusM - COMPUTE_RADIUS_M) < 1.0 } == true
            }
            proveComputeProjectionAndReuse("compute-landscape")
            assertEquals("Render stress and resize cannot advance the paused world", pausedEpoch, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            proof.put("scenario", "stress.trail-density").put("epochSeconds", pausedEpoch).put("passed", true)
        } catch (error: Throwable) {
            proof.put("passed", false).put("failure", error.toString()).put("rendererAtFailure", rendererStatus())
            runCatching { capture("compute-failure.png") }
            throw error
        } finally {
            if (proof.optBoolean("passed")) writeJson("compute-proof.json", proof)
            else runCatching { writeJson("compute-proof.json", proof) }
            runCatching { runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) } }
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test
    fun focusTracksPlaybackPinchCancelsPendingFocusAndLunarProbeKeepsContext() {
        assumeTrue(BuildConfig.STAGE_FIRST_CLIENT)
        assertFalse("Native motion proof requires advancing full-fidelity playback", BuildConfig.HOSTED_DEBUG_LITE_MODE)
        composeRule.waitUntil(30_000) { facade.uiState.value.connectionState == SessionConnectionState.Active }
        try {
            runBlocking { facade.loadScenario("lab.earth-moon") }
            composeRule.waitUntil(30_000) {
                facade.uiState.value.snapshot?.scenarioId == "lab.earth-moon" && viewport()?.bodies?.any { it.bodyId == "moon" } == true
            }
            composeRule.onNodeWithTag(LabTags.MODE_MAP).performClick()
            val initialEarth = runBlocking { requireNotNull(facade.inspectBody("earth")) }
            val initialMoon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
            val plane = earthMoonAuthoringPlane(initialEarth, initialMoon)
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CAMERA_HOME_BUTTON).performClick()
            waitForSystem()
            runBlocking {
                facade.applyCommand(RuntimeCommand.SetPlaybackRate(3_600.0))
                facade.applyCommand(RuntimeCommand.ResumePlayback)
            }
            composeRule.waitUntil(20_000) { facade.uiState.value.snapshot?.let { !it.paused && it.epochSeconds > 0.0 } == true }
            assertEquals(3_600.0, facade.uiState.value.snapshot!!.simSecondsPerRealSecond, 0.0)
            composeRule.onNodeWithTag(LabTags.focus("earth")).performClick()
            waitForCentred("earth", initialEarth.radiusM * 2.1)
            observeMovingEarth()
            capture("01-earth-focused-while-playing.png")

            runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) }
            composeRule.waitUntil(10_000) { facade.uiState.value.snapshot?.paused == true }
            val pausedEarth = runBlocking { requireNotNull(facade.inspectBody("earth")) }
            composeRule.waitUntil(15_000) {
                viewport()?.bodies?.firstOrNull { it.bodyId == "earth" }?.let {
                    labLength(it.worldPositionM - pausedEarth.labPosition()) < 1.0
                } == true
            }
            val pausedEpoch = facade.uiState.value.snapshot!!.epochSeconds
            onHost { zoomBy(0.25f) }
            composeRule.waitUntil(15_000) { viewport()?.camera?.viewRadiusM?.let { it > initialEarth.radiusM * 7.5 } == true }
            val beforePinch = requireNotNull(viewport())
            val focal = Offset(beforePinch.widthPx * 0.44f, beforePinch.heightPx * 0.46f)
            val placementPlane = LabPlane(pausedEarth.labPosition(), plane.normal)
            val anchoredBefore = requireNotNull(beforePinch.unprojectToPlane(focal.x, focal.y, placementPlane))
            injectPinchDuringPendingFocus(focal, 44f, 100f)
            val expectedRadius = beforePinch.camera.viewRadiusM * 44.0 / 100.0
            composeRule.waitUntil(15_000) { viewport()?.camera?.viewRadiusM?.let { abs(it / expectedRadius - 1.0) < 0.08 } == true }
            // The query result and its 240 ms animation have ample time to return. A stale
            // frame would shrink back to 2 Earth radii instead of retaining this ~3.5-radius zoom.
            repeat(8) {
                SystemClock.sleep(100)
                val current = requireNotNull(viewport())
                assertTrue("A cancelled pending Focus must not reclaim the camera", abs(current.camera.viewRadiusM / expectedRadius - 1.0) < 0.08)
            }
            val afterPinch = requireNotNull(viewport())
            val anchoredAfter = requireNotNull(afterPinch.unprojectToPlane(focal.x, focal.y, placementPlane))
            val anchorErrorM = labLength(anchoredAfter - anchoredBefore)
            assertTrue("Real pinch retains its focal world point", anchorErrorM < beforePinch.camera.viewRadiusM * 0.015)
            assertEquals("Camera commands cannot advance physics", pausedEpoch, facade.uiState.value.snapshot!!.epochSeconds, 0.0)
            proof.put("pendingFocusPinch", JSONObject().put("moveEvents", 30).put("beforeRadiusM", beforePinch.camera.viewRadiusM)
                .put("expectedRadiusM", expectedRadius).put("afterRadiusM", afterPinch.camera.viewRadiusM).put("anchorErrorM", anchorErrorM))
            capture("02-pinch-keeps-control.png")
            verifyStableResourceReuse()

            composeRule.onNodeWithTag(LabTags.focus("moon")).performClick()
            waitForCentred("moon", initialMoon.radiusM * 2.1)
            val moon = runBlocking { requireNotNull(facade.inspectBody("moon")) }
            val candidate = requireNotNull(LabProbeDraft(moon, plane, PROBE_ID, pausedEpoch).candidate())
            val countBefore = facade.uiState.value.snapshot!!.bodyCount
            val preview = runBlocking { facade.previewProbe(candidate, "moon", horizonSeconds = 600.0) }
            runBlocking { facade.commitProbe(preview) }
            composeRule.waitUntil(20_000) {
                facade.uiState.value.snapshot?.bodyCount == countBefore + 1 && viewport()?.bodies?.any { it.bodyId == PROBE_ID } == true
            }
            val actualProbe = runBlocking { requireNotNull(facade.inspectBody(PROBE_ID)) }
            assertEquals(0.0, actualProbe.sourceMassKg, 0.0)
            assertEquals(800_000.0, labLength(actualProbe.labPosition() - moon.labPosition()) - moon.radiusM, 1.0)
            assertTrue(SolarLabSemanticActionBridge.submit(SolarLabSemanticAction.FocusBody(PROBE_ID)))
            composeRule.waitUntil(15_000) { facade.uiState.value.focusedBodyId == PROBE_ID }
            waitForLunarProbeFrame()
            proof.put("lunarFramePortrait", viewportJson(requireNotNull(viewport())))
            capture("03-lunar-probe-context.png")

            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(20_000) { viewport()?.let { it.widthPx > it.heightPx } == true }
            waitForLunarProbeFrame()
            proof.put("lunarFrameLandscape", viewportJson(requireNotNull(viewport())))
            capture("04-lunar-probe-landscape.png")
            runBlocking { facade.applyCommand(RuntimeCommand.RemoveBody(PROBE_ID)) }
            composeRule.onNodeWithTag(LabTags.focus("earth")).performClick()
            composeRule.waitUntil(20_000) {
                facade.uiState.value.snapshot?.bodyCount == countBefore && viewport()?.bodies?.none { it.bodyId == PROBE_ID } == true
            }
            waitForCentred("earth", initialEarth.radiusM * 2.1)
            proof.put("afterRemoval", viewportJson(requireNotNull(viewport())))
            proof.put("afterRemovalRenderer", rendererStatus())
            capture("05-after-probe-removal.png")
            proof.put("passed", true)
        } catch (error: Throwable) {
            proof.put("passed", false).put("failure", error.toString())
            runCatching { capture("failure.png") }
            throw error
        } finally {
            if (proof.optBoolean("passed")) writeJson("camera-proof.json", proof)
            else runCatching { writeJson("camera-proof.json", proof) }
            runCatching { runBlocking { facade.applyCommand(RuntimeCommand.PausePlayback) } }
            SolarLabSemanticActionBridge.clearPendingReplay()
            composeRule.runOnUiThread { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private fun observeMovingEarth() {
        val samples = JSONArray()
        var previous = requireNotNull(viewport()).bodies.first { it.bodyId == "earth" }.worldPositionM
        val startingEpoch = facade.uiState.value.snapshot!!.epochSeconds
        composeRule.waitUntil(20_000) {
            val current = viewport() ?: return@waitUntil false
            val earth = current.bodies.firstOrNull { it.bodyId == "earth" } ?: return@waitUntil false
            assertTrue("Focused Earth must remain centred in each submitted native frame", centreErrorPx(current, "earth") < 5.0)
            if (labLength(earth.worldPositionM - previous) > 10.0) {
                samples.put(viewportJson(current).put("epochSeconds", facade.uiState.value.snapshot!!.epochSeconds))
                previous = earth.worldPositionM
            }
            samples.length() >= 6 && facade.uiState.value.snapshot!!.epochSeconds > startingEpoch + 120.0
        }
        proof.put("focusWhilePlaying", JSONObject().put("requestedSimSecondsPerRealSecond", 3_600.0).put("nativeSamples", samples))
    }

    private fun verifyStableResourceReuse(proofKey: String = "resourceReuse") {
        // Warm every staging-ring slot before comparing cumulative allocations.
        repeat(8) { refreshNativeWithoutMovingCamera() }
        val before = resourceCounters(rendererStatus())
        repeat(5) { refreshNativeWithoutMovingCamera() }
        val after = resourceCounters(rendererStatus())
        assertEquals("Compatible native updates retain GPU allocations", before.getValue("alloc"), after.getValue("alloc"))
        assertEquals("Stable native bindings retain recorded commands", before.getValue("record"), after.getValue("record"))
        assertTrue("Each new scene upload should reuse commands", after.getValue("reuse") >= before.getValue("reuse") + 5)
        assertTrue("The proof must execute real uploads", after.getValue("cpuBytes") > before.getValue("cpuBytes"))
        proof.put(proofKey, JSONObject().put("before", JSONObject(before)).put("after", JSONObject(after)).put("renderer", rendererStatus()))
    }

    private data class ProjectedTracer(val id: String, val world: Vector3d, val x: Float, val y: Float, val depthM: Double)
    private data class ComputeProjection(
        val mediumCount: Int, val farCount: Int,
        val mediumVisible: List<ProjectedTracer>, val farVisible: List<ProjectedTracer>,
    )

    private fun projectedComputePoints(view: LabViewportState, frame: RenderFrame): ComputeProjection {
        val origin = Vector3d(frame.camera.frameOriginX, frame.camera.frameOriginY, frame.camera.frameOriginZ)
        val medium = mutableListOf<ProjectedTracer>()
        val far = mutableListOf<ProjectedTracer>()
        // COMPUTE_RADIUS_M is in the native Local band. This pack is below every
        // budget, so its exact bucket assignment follows these geometric extents.
        for (tracer in frame.tracers) {
            val world = origin + Vector3d(tracer.x.toDouble(), tracer.y.toDouble(), tracer.z.toDouble())
            val point = view.project(world)
            val extent = max(max(abs(point.xPx - view.widthPx * 0.5) * view.metresPerPixel,
                abs(point.yPx - view.heightPx * 0.5) * view.metresPerPixel), abs(point.depthM))
            val projected = ProjectedTracer(tracer.sourceBodyId, world, point.xPx, point.yPx, point.depthM)
            when {
                extent <= view.camera.viewRadiusM * 1.6 -> Unit
                extent <= view.camera.viewRadiusM * 5.5 -> medium.add(projected)
                extent <= view.camera.viewRadiusM * 18.0 -> far.add(projected)
            }
        }
        fun inside(point: ProjectedTracer, expansion: Double) =
            abs(point.x - view.widthPx * 0.5) <= view.widthPx * 0.5 * expansion &&
                abs(point.y - view.heightPx * 0.5) <= view.heightPx * 0.5 * expansion
        val stride = 1 + floor(log2(max(view.metresPerPixel / 2.5e8, 1.0))).toInt().coerceIn(0, 4)
        return ComputeProjection(medium.size, far.size,
            medium.filterIndexed { index, point -> index % stride == 0 && inside(point, 1.05) },
            far.filter { inside(it, 1.08) })
    }

    private fun safelyInside(point: ProjectedTracer, view: LabViewportState): Boolean =
        point.x > 6f && point.y > 6f && point.x < view.widthPx - 6 && point.y < view.heightPx - 6

    private fun setAllTracesVisible(visible: Boolean) {
        val previousAutoAdvance = composeRule.mainClock.autoAdvance
        // The stress scene continues submitting native frames while this menu is
        // open. Qualify each control state on explicit Compose frames instead of
        // requiring the entire renderer and sheet to become globally idle.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(SolarLabTestTags.STAGE_FIRST_CONTROLS_BUTTON).performClick()
            composeRule.mainClock.advanceTimeBy(400)
            val tag = if (visible) SolarLabTestTags.STAGE_FIRST_TRACE_MORE_BUTTON else SolarLabTestTags.STAGE_FIRST_TRACE_LESS_BUTTON
            repeat(2) {
                val node = composeRule.onNodeWithTag(tag).performScrollToIfPossible()
                composeRule.mainClock.advanceTimeBy(400)
                if (!node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)) node.performClick()
                composeRule.mainClock.advanceTimeBy(64)
            }
            composeRule.onNodeWithTag(LabTags.TOOLS_CLOSE).performScrollToIfPossible()
            composeRule.mainClock.advanceTimeBy(400)
            composeRule.onNodeWithTag(LabTags.TOOLS_CLOSE).performClick()
            composeRule.waitUntil(10_000) {
                composeRule.mainClock.advanceTimeBy(32)
                composeRule.onAllNodesWithTag(LabTags.TOOLS_PANEL).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            composeRule.mainClock.autoAdvance = previousAutoAdvance
        }
    }

    private fun computeFields(status: String, stream: String): Map<String, String> {
        val payload = Regex("compute=\\[([^]]+)]").find(status)?.groupValues?.get(1)
            ?: throw AssertionError("Native compute diagnostics missing: $status")
        val fields = payload.split(',').first { it.startsWith("$stream:") }.substringAfter(':').split('/')
        return mapOf("dispatch" to fields.first()) + fields.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun inputCount(status: String, stream: String): Int =
        requireNotNull(Regex("(?:^|\\s)$stream=(\\d+)").find(status)).groupValues[1].toInt()

    private fun requestNativeRedraw() = composeRule.runOnUiThread {
        val surface = requireNotNull(findSurface(composeRule.activity.window.decorView))
        // A normal surface redraw submits the same scene without inventing a new
        // camera revision. Its fence exposes the preceding compute readback.
        (surface as SurfaceHolder.Callback2).surfaceRedrawNeeded(surface.holder)
    }

    private fun awaitComputeReadback() {
        var requestedAt = 0L
        composeRule.waitUntil(20_000) {
            val now = SystemClock.uptimeMillis()
            if (now - requestedAt >= 250L) {
                requestNativeRedraw()
                requestedAt = now
            }
            runCatching {
                val status = rendererStatus()
                listOf("TM", "TF").all { stream ->
                    val fields = computeFields(status, stream)
                    !fields.getValue("dispatch").startsWith('-') && fields.getValue("dispatch").toInt() > 0 &&
                        fields.getValue("vis").toIntOrNull() != null
                } && computeFields(status, "TF").getValue("active").toIntOrNull() != null
            }.getOrDefault(false)
        }
    }

    private fun proveComputeProjectionAndReuse(name: String) {
        setAllTracesVisible(false)
        composeRule.waitUntil(15_000) { runCatching { inputCount(rendererStatus(), "TM") == 0 && inputCount(rendererStatus(), "TF") == 0 }.getOrDefault(false) }
        val hidden = copyStageBitmap()
        try {
            setAllTracesVisible(true)
            composeRule.waitUntil(20_000) { runCatching { inputCount(rendererStatus(), "TM") > 0 && inputCount(rendererStatus(), "TF") > 0 }.getOrDefault(false) }
            verifyStableResourceReuse("$name-reuse")
            awaitComputeReadback()
            val view = requireNotNull(viewport())
            val frame = requireNotNull(facade.uiState.value.renderFrame)
            val expected = projectedComputePoints(view, frame)
            val status = rendererStatus()
            val medium = computeFields(status, "TM")
            val far = computeFields(status, "TF")
            val stageProof = viewportJson(view).put("renderer", status)
                .put("expectedMediumSources", expected.mediumCount).put("expectedFarSources", expected.farCount)
                .put("expectedMediumVisible", expected.mediumVisible.size).put("expectedFarVisible", expected.farVisible.size)
            proof.put(name, stageProof)
            assertEquals(expected.mediumCount, inputCount(status, "TM"))
            assertEquals(expected.farCount, inputCount(status, "TF"))
            assertTrue("Both actual compute pipelines must be present", status.contains("cp=[mc,fc]"))
            assertEquals("Medium GPU visibility matches the current 3D camera", expected.mediumVisible.size, medium.getValue("vis").toInt())
            assertTrue("Canonical camera must exercise visible medium output", expected.mediumVisible.size >= 4)
            assertTrue("Canonical camera must exercise visible far output", far.getValue("vis").toInt() >= 4)
            assertEquals("Far accepted plus dropped points cover the visible source", expected.farVisible.size,
                far.getValue("vis").toInt() + far.getValue("drop").toInt())
            val tileColumns = (view.widthPx + 15) / 16
            val tileCount = tileColumns * ((view.heightPx + 15) / 16)
            assertEquals("Tile layout follows the actual native surface", tileCount, far.getValue("tiles").toInt())
            assertEquals(tileCount * 8, far.getValue("cap").toInt())
            fun tile(point: ProjectedTracer): Int = (point.x.toInt().coerceIn(0, view.widthPx - 1) / 16) +
                (point.y.toInt().coerceIn(0, view.heightPx - 1) / 16) * tileColumns
            val tiles = expected.farVisible.groupBy(::tile)
            assertEquals("Far GPU tile counters match projected source positions", tiles.size, far.getValue("active").toInt())
            assertEquals(tiles.values.maxOf { it.size }, far.getValue("peak").toInt())
            val actual = copyStageBitmap()
            try {
                assertEquals(hidden.width, actual.width)
                assertEquals(hidden.height, actual.height)
                val samples = JSONObject()
                for ((stream, candidates) in listOf("medium" to expected.mediumVisible,
                    "far" to expected.farVisible.filter { tiles.getValue(tile(it)).size == 1 })) {
                    val targets = candidates.filter { point ->
                        safelyInside(point, view) && view.bodies.none { body ->
                            body.depthM < point.depthM && hypot((body.xPx - point.x).toDouble(), (body.yPx - point.y).toDouble()) < body.radiusPx + 5.0
                        }
                    }.take(16)
                    assertTrue("Need independently visible $stream points for pixel proof", targets.size >= 4)
                    val checks = targets.map { point ->
                        val x = point.x.roundToInt()
                        val y = point.y.roundToInt()
                        val lit = (-3..3).any { dx -> (-3..3).any { dy ->
                            val before = hidden.getPixel(x + dx, y + dy)
                            val after = actual.getPixel(x + dx, y + dy)
                            listOf(0, 8, 16).any { shift -> ((after ushr shift) and 255) - ((before ushr shift) and 255) >= 10 }
                        } }
                        JSONObject().put("id", point.id).put("xPx", point.x).put("yPx", point.y)
                            .put("zM", point.world.z).put("visiblePixel", lit)
                    }
                    assertTrue("$stream computed vertices must appear at their native projected positions", checks.count { it.getBoolean("visiblePixel") } >= max(3, (checks.size * 0.75).toInt()))
                    samples.put(stream, JSONArray(checks))
                }
                stageProof.put("pixels", samples)
                writeImage("$name-stage.png", requireNotNull(actual.copy(Bitmap.Config.ARGB_8888, false)))
                writeImage("$name.png", requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
            } finally { actual.recycle() }
        } finally {
            writeImage("$name-hidden-stage.png", hidden)
        }
    }

    private fun refreshNativeWithoutMovingCamera() {
        val uploads = resourceCounters(rendererStatus()).getValue("upload")
        onHost { zoomBy(1f) }
        composeRule.waitUntil(15_000) { resourceCounters(rendererStatus()).getValue("upload") > uploads }
    }

    private fun resourceCounters(status: String): Map<String, Long> {
        val payload = Regex("resources=\\[([^]]+)]").find(status)?.groupValues?.get(1)
            ?: throw AssertionError("Native resource counters missing: $status")
        return payload.split(',').associate { field -> field.substringBefore(':') to field.substringAfter(':').toLong() }
    }

    private fun waitForSystem() = composeRule.waitUntil(20_000) {
        val current = viewport() ?: return@waitUntil false
        val earth = current.bodies.firstOrNull { it.bodyId == "earth" } ?: return@waitUntil false
        val moon = current.bodies.firstOrNull { it.bodyId == "moon" } ?: return@waitUntil false
        current.camera.viewRadiusM > labLength(moon.worldPositionM - earth.worldPositionM) * 0.5 &&
            listOf(earth, moon).all { it.xPx in 0f..current.widthPx.toFloat() && it.yPx in 0f..current.heightPx.toFloat() }
    }

    private fun waitForCentred(bodyId: String, maxRadiusM: Double) = composeRule.waitUntil(20_000) {
        viewport()?.let { it.camera.viewRadiusM <= maxRadiusM && centreErrorPx(it, bodyId) < 3.0 } == true
    }

    private fun waitForLunarProbeFrame() = composeRule.waitUntil(20_000) {
        val current = viewport() ?: return@waitUntil false
        val moon = current.bodies.firstOrNull { it.bodyId == "moon" } ?: return@waitUntil false
        // An Earth-context frame is hundreds of millions of metres wide and cannot pass.
        current.camera.viewRadiusM in 3_000_000.0..8_000_000.0 && centreErrorPx(current, PROBE_ID) < 3.0 &&
            !moon.occluded && moon.xPx - moon.radiusPx > 0f && moon.xPx + moon.radiusPx < current.widthPx &&
            moon.yPx - moon.radiusPx > 0f && moon.yPx + moon.radiusPx < current.heightPx
    }

    private fun centreErrorPx(viewport: LabViewportState, bodyId: String): Double {
        val body = viewport.bodies.firstOrNull { it.bodyId == bodyId } ?: return Double.POSITIVE_INFINITY
        return hypot(body.xPx.toDouble() - viewport.widthPx * 0.5, body.yPx.toDouble() - viewport.heightPx * 0.5)
    }

    private fun findHost(view: View): SolarSystemRenderHostView? {
        if (view is SolarSystemRenderHostView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun onHost(action: SolarSystemRenderHostView.() -> Unit) = composeRule.runOnUiThread {
        requireNotNull(findHost(composeRule.activity.window.decorView)).action()
    }

    private fun viewport(): LabViewportState? {
        var result: LabViewportState? = null
        composeRule.runOnUiThread { result = findHost(composeRule.activity.window.decorView)?.currentLabViewportState() }
        return result
    }

    private fun rendererStatus(): String {
        var result = ""
        composeRule.runOnUiThread { result = findHost(composeRule.activity.window.decorView)?.currentBackendStatus()?.message.orEmpty() }
        return result
    }

    private fun injectPinchDuringPendingFocus(focal: Offset, initialSpan: Float, finalSpan: Float) {
        var downTime = 0L
        fun points(span: Float) = listOf(Offset(focal.x - span, focal.y), Offset(focal.x + span, focal.y))
        onHost {
            focusAndFrameBody("earth", ObserverMode.FOLLOW_SELECTED)
            downTime = SystemClock.uptimeMillis()
            // The async query cannot dispatch its callback until this UI runnable ends.
            dispatchPointers(this, MotionEvent.ACTION_DOWN, downTime, points(initialSpan).take(1))
            dispatchPointers(this, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), downTime, points(initialSpan))
        }
        repeat(30) { step ->
            SystemClock.sleep(14)
            onHost { dispatchPointers(this, MotionEvent.ACTION_MOVE, downTime, points(initialSpan + (finalSpan - initialSpan) * (step + 1) / 30f)) }
        }
        onHost {
            dispatchPointers(this, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), downTime, points(finalSpan))
            dispatchPointers(this, MotionEvent.ACTION_UP, downTime, points(finalSpan).take(1))
        }
    }

    private fun dispatchPointers(host: SolarSystemRenderHostView, action: Int, downTime: Long, points: List<Offset>) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].x; y = points[index].y; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("The real host/SurfaceView must accept every pinch event", host.dispatchTouchEvent(event)) }
        finally { event.recycle() }
    }

    private fun viewportJson(viewport: LabViewportState): JSONObject = JSONObject()
        .put("widthPx", viewport.widthPx).put("heightPx", viewport.heightPx).put("radiusM", viewport.camera.viewRadiusM)
        .put("cameraM", JSONArray(listOf(viewport.camera.centerM.x, viewport.camera.centerM.y, viewport.camera.centerM.z)))
        .put("bodies", JSONArray(viewport.bodies.map { JSONObject().put("id", it.bodyId).put("xPx", it.xPx)
            .put("yPx", it.yPx).put("radiusPx", it.radiusPx).put("occluded", it.occluded)
            .put("worldM", JSONArray(listOf(it.worldPositionM.x, it.worldPositionM.y, it.worldPositionM.z))) }))

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun capture(name: String) {
        writeImage(name.removeSuffix(".png") + "-stage.png", copyStageBitmap())
        writeImage(name, requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
    }

    private fun copyStageBitmap(): Bitmap {
        instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        lateinit var stage: Bitmap
        composeRule.runOnUiThread {
            val surface = requireNotNull(findSurface(composeRule.activity.window.decorView))
            stage = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, stage, { code -> result = code; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Native stage copy should complete", latch.await(10, TimeUnit.SECONDS))
        assertEquals("Native surface should be capturable", PixelCopy.SUCCESS, result)
        return stage
    }

    private fun writeImage(name: String, bitmap: Bitmap) {
        try { writeDownload(name, "image/png") { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun writeJson(name: String, value: JSONObject) = writeDownload(name, "application/json") {
        it.write(value.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun writeDownload(name: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, evidencePath); put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use(write)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    private companion object {
        const val PROBE_ID = "camera-lunar-probe"
        const val COMPUTE_RADIUS_M = 0.45 * 149_597_870_700.0
    }
}
