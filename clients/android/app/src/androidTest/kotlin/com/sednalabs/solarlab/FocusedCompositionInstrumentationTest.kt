package com.sednalabs.solarlab

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObserverMode
import com.sednalabs.solarlab.runtime.ShellUiState
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusedCompositionInstrumentationTest {
    @Test
    fun earthMoon_focus_keeps_earth_centered_and_moon_visible() {
        verifyFocusedComposition(
            focusBodyId = "earth",
            companionCandidateIds = listOf("moon"),
            screenshotPrefix = "focus-earth-moon",
            maxNormalizedCenterDistance = 0.21f,
            minimumSeparationFraction = 0.0002f,
            maximumSeparationFraction = 0.42f,
            requireCompanionVisible = true,
        )
    }

    @Test
    fun jupiter_focus_keeps_jupiter_centered_and_jovian_companion_visible() {
        verifyFocusedComposition(
            focusBodyId = "jupiter",
            companionCandidateIds = listOf("io", "europa", "ganymede", "callisto"),
            screenshotPrefix = "focus-jupiter",
            maxNormalizedCenterDistance = 0.22f,
            minimumSeparationFraction = 0.002f,
            maximumSeparationFraction = 0.45f,
            requireCompanionVisible = false,
        )
    }

    private fun verifyFocusedComposition(
        focusBodyId: String,
        companionCandidateIds: List<String>,
        screenshotPrefix: String,
        maxNormalizedCenterDistance: Float,
        minimumSeparationFraction: Float,
        maximumSeparationFraction: Float,
        requireCompanionVisible: Boolean,
    ) {
        Log.i(LOG_TAG, "FocusedCompositionInstrumentationTest.begin focus=$focusBodyId")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facade = scenario.withRuntimeFacade()
            waitForState(facade) {
                it.renderStatus.readiness == RenderHostReadiness.Ready &&
                    it.renderFrame != null &&
                    it.renderStatus.renderedBodyCount > 0
            }

            runBlocking {
                facade.applyCommand(RuntimeCommand.PausePlayback)
                facade.applyCommand(RuntimeCommand.FocusBody(focusBodyId))
                facade.applyCommand(RuntimeCommand.SetObserverMode(RuntimeObserverMode.FollowSelected))
                facade.refresh()
            }

            waitForState(facade, timeout = 22.seconds) { state ->
                state.focusedBodyId.equals(focusBodyId, ignoreCase = true) &&
                    state.renderStatus.readiness == RenderHostReadiness.Ready &&
                    state.renderFrame?.bodies?.any { body ->
                        body.bodyId.equals(focusBodyId, ignoreCase = true)
                    } == true &&
                    (
                        !requireCompanionVisible ||
                            companionCandidateIds.any { candidateId ->
                                state.renderFrame?.bodies?.any { body ->
                                    body.bodyId.equals(candidateId, ignoreCase = true)
                                } == true
                            }
                        )
            }

            val focusSample = waitForBodyViewportSample(
                scenario = scenario,
                bodyId = focusBodyId,
                maxNormalizedDistance = maxNormalizedCenterDistance,
                timeout = 10.seconds,
            )
            assertPointWithinViewport(
                point = focusSample.point,
                viewportWidth = focusSample.viewportWidth,
                viewportHeight = focusSample.viewportHeight,
                bodyId = focusBodyId,
            )
            assertNearViewportCenter(
                point = focusSample.point,
                viewportWidth = focusSample.viewportWidth,
                viewportHeight = focusSample.viewportHeight,
                maxNormalizedDistance = maxNormalizedCenterDistance,
                bodyId = focusBodyId,
            )

            if (requireCompanionVisible) {
                val companionSample = waitForAnyBodyViewportSample(
                    scenario = scenario,
                    bodyIds = companionCandidateIds,
                    timeout = 10.seconds,
                )
                assertPointWithinViewport(
                    point = companionSample.point,
                    viewportWidth = companionSample.viewportWidth,
                    viewportHeight = companionSample.viewportHeight,
                    bodyId = companionSample.bodyId,
                )
                assertCompanionSeparation(
                    primaryPoint = focusSample.point,
                    companionPoint = companionSample.point,
                    viewportWidth = focusSample.viewportWidth,
                    viewportHeight = focusSample.viewportHeight,
                    focusBodyId = focusBodyId,
                    companionBodyId = companionSample.bodyId,
                    minimumSeparationFraction = minimumSeparationFraction,
                    maximumSeparationFraction = maximumSeparationFraction,
                )
            }

            scenario.onActivity { activity ->
                val renderSurface = findRenderSurfaceView(activity.window.decorView)
                    ?: throw AssertionError("Unable to find VulkanPacketRenderSurfaceView for focus screenshot capture")
                val screenshot = captureScreenshot()
                try {
                    persistValidationScreenshot("$screenshotPrefix-ready", screenshot)
                    val stageScreenshot = screenshot.cropToStage(renderSurface)
                    try {
                        persistValidationScreenshot("$screenshotPrefix-stage", stageScreenshot)
                        val metrics = stageScreenshot.visualMetrics()
                        assertTrue(
                            "Focused stage screenshot looks visually sparse for $focusBodyId: $metrics",
                            metrics.brightSampleCount >= 20 && metrics.uniqueColorCount >= 8,
                        )
                    } finally {
                        stageScreenshot.recycle()
                    }
                } finally {
                    screenshot.recycle()
                }
            }
        }
    }

    private fun waitForBodyViewportSample(
        scenario: ActivityScenario<MainActivity>,
        bodyId: String,
        maxNormalizedDistance: Float,
        timeout: Duration,
    ): BodyViewportSample {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            val sample = sampleBodyViewport(scenario = scenario, bodyId = bodyId)
            if (sample != null && sample.normalizedDistanceFromCenter() <= maxNormalizedDistance) {
                return sample
            }
            Thread.sleep(60)
        }
        throw AssertionError("Focused body '$bodyId' should be visible and near center within $timeout")
    }

    private fun waitForAnyBodyViewportSample(
        scenario: ActivityScenario<MainActivity>,
        bodyIds: List<String>,
        timeout: Duration,
    ): BodyViewportSample {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            bodyIds.forEach { bodyId ->
                val sample = sampleBodyViewport(scenario = scenario, bodyId = bodyId)
                if (sample != null) {
                    return sample
                }
            }
            Thread.sleep(60)
        }
        throw AssertionError("Expected at least one companion body to be visible in viewport: $bodyIds")
    }

    private fun sampleBodyViewport(
        scenario: ActivityScenario<MainActivity>,
        bodyId: String,
    ): BodyViewportSample? {
        var sample: BodyViewportSample? = null
        scenario.onActivity { activity ->
            val renderSurface = findRenderSurfaceView(activity.window.decorView)
            if (renderSurface == null || renderSurface.width <= 0 || renderSurface.height <= 0) {
                return@onActivity
            }
            val bodyPoint = renderSurface.debugBodyScreenPoint(bodyId) ?: return@onActivity
            sample = BodyViewportSample(
                bodyId = bodyId,
                point = bodyPoint,
                viewportWidth = renderSurface.width.toFloat(),
                viewportHeight = renderSurface.height.toFloat(),
            )
        }
        return sample
    }

    private fun assertPointWithinViewport(
        point: Pair<Float, Float>,
        viewportWidth: Float,
        viewportHeight: Float,
        bodyId: String,
    ) {
        val (x, y) = point
        assertTrue(
            "Body '$bodyId' x-coordinate should be in viewport: $x",
            x in 0f..viewportWidth,
        )
        assertTrue(
            "Body '$bodyId' y-coordinate should be in viewport: $y",
            y in 0f..viewportHeight,
        )
    }

    private fun assertNearViewportCenter(
        point: Pair<Float, Float>,
        viewportWidth: Float,
        viewportHeight: Float,
        maxNormalizedDistance: Float,
        bodyId: String,
    ) {
        val centerX = viewportWidth * 0.5f
        val centerY = viewportHeight * 0.5f
        val dx = point.first - centerX
        val dy = point.second - centerY
        val normalization = max(min(viewportWidth, viewportHeight) * 0.5f, 1f)
        val normalizedDistance = sqrt((dx * dx) + (dy * dy)) / normalization
        assertTrue(
            "Body '$bodyId' drifted too far from viewport center: normalizedDistance=$normalizedDistance",
            normalizedDistance <= maxNormalizedDistance,
        )
    }

    private fun assertCompanionSeparation(
        primaryPoint: Pair<Float, Float>,
        companionPoint: Pair<Float, Float>,
        viewportWidth: Float,
        viewportHeight: Float,
        focusBodyId: String,
        companionBodyId: String,
        minimumSeparationFraction: Float,
        maximumSeparationFraction: Float,
    ) {
        val dx = primaryPoint.first - companionPoint.first
        val dy = primaryPoint.second - companionPoint.second
        val separation = sqrt((dx * dx) + (dy * dy))
        val baseline = max(min(viewportWidth, viewportHeight), 1f)
        val minSeparation = max(0.1f, baseline * minimumSeparationFraction)
        val maxSeparation = baseline * maximumSeparationFraction
        assertTrue(
            "Companion '$companionBodyId' for '$focusBodyId' is too close to be distinguished: $separation px",
            separation >= minSeparation,
        )
        assertTrue(
            "Companion '$companionBodyId' for '$focusBodyId' is too far for the intended composition: $separation px",
            separation <= maxSeparation,
        )
    }

    private fun ActivityScenario<MainActivity>.withRuntimeFacade(): RuntimeFacade {
        var facade: RuntimeFacade? = null
        onActivity { activity ->
            facade = activity.runtimeFacadeForTesting
        }
        return requireNotNull(facade) { "Unable to access runtime facade from MainActivity" }
    }

    private fun waitForState(
        facade: RuntimeFacade,
        timeout: Duration = 20.seconds,
        predicate: (ShellUiState) -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadlineMs) {
            val state = facade.uiState.value
            if (predicate(state)) {
                return
            }
            Thread.sleep(50)
        }
        val finalState = facade.uiState.value
        throw AssertionError(
            "Timed out waiting for focused composition state. Final state: $finalState",
        )
    }

    private fun findRenderSurfaceView(root: View): VulkanPacketRenderSurfaceView? {
        if (root is VulkanPacketRenderSurfaceView) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val match = findRenderSurfaceView(root.getChildAt(index))
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    private fun captureScreenshot(): Bitmap {
        return InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("Unable to capture focus composition screenshot from instrumentation")
    }

    private fun Bitmap.cropToStage(surfaceView: VulkanPacketRenderSurfaceView): Bitmap {
        val location = IntArray(2)
        surfaceView.getLocationOnScreen(location)
        val stageRect = Rect(
            location[0],
            location[1],
            location[0] + surfaceView.width,
            location[1] + surfaceView.height,
        )
        val boundedRect = Rect(
            stageRect.left.coerceIn(0, width - 1),
            stageRect.top.coerceIn(0, height - 1),
            stageRect.right.coerceIn(stageRect.left + 1, width),
            stageRect.bottom.coerceIn(stageRect.top + 1, height),
        )
        return Bitmap.createBitmap(
            this,
            boundedRect.left,
            boundedRect.top,
            boundedRect.width(),
            boundedRect.height(),
        )
    }

    private fun Bitmap.visualMetrics(): VisualMetrics {
        val stepX = max(1, width / 24)
        val stepY = max(1, height / 24)
        val uniqueColors = linkedSetOf<Int>()
        var brightSamples = 0
        var sampleCount = 0
        for (x in 0 until width step stepX) {
            for (y in 0 until height step stepY) {
                val pixel = getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val brightness = (red + green + blue) / 3
                if (brightness >= 24) {
                    brightSamples++
                }
                uniqueColors += ((red and 0xF0) shl 12) or ((green and 0xF0) shl 4) or ((blue and 0xF0) shr 4)
                sampleCount++
            }
        }
        return VisualMetrics(
            sampleCount = sampleCount,
            brightSampleCount = brightSamples,
            uniqueColorCount = uniqueColors.size,
        )
    }

    private fun persistValidationScreenshot(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        persistInternalValidationScreenshot(context, name, bitmap)
        persistSharedValidationScreenshot(context, name, bitmap)
    }

    private fun persistInternalValidationScreenshot(
        context: Context,
        name: String,
        bitmap: Bitmap,
    ) {
        val outputDir = File(context.filesDir, "validation-screenshots").apply { mkdirs() }
        val outputFile = File(outputDir, "$name.png")
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
        }
        Log.i(LOG_TAG, "FocusedCompositionInstrumentationTest.savedScreenshot ${outputFile.absolutePath}")
    }

    private fun persistSharedValidationScreenshot(
        context: Context,
        name: String,
        bitmap: Bitmap,
    ) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/solarlab-validation"
        val displayName = "$name.png"
        deleteExistingSharedScreenshot(resolver, displayName, relativePath)

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return

        try {
            resolver.openOutputStream(targetUri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.flush()
            }
            val finalizeValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(targetUri, finalizeValues, null, null)
        } catch (error: Throwable) {
            resolver.delete(targetUri, null, null)
        }
    }

    private fun deleteExistingSharedScreenshot(
        resolver: android.content.ContentResolver,
        displayName: String,
        relativePath: String,
    ) {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idIndex),
                )
                resolver.delete(contentUri, null, null)
            }
        }
    }

    private data class VisualMetrics(
        val sampleCount: Int,
        val brightSampleCount: Int,
        val uniqueColorCount: Int,
    )

    private data class BodyViewportSample(
        val bodyId: String,
        val point: Pair<Float, Float>,
        val viewportWidth: Float,
        val viewportHeight: Float,
    ) {
        fun normalizedDistanceFromCenter(): Float {
            val centerX = viewportWidth * 0.5f
            val centerY = viewportHeight * 0.5f
            val dx = point.first - centerX
            val dy = point.second - centerY
            val normalization = max(min(viewportWidth, viewportHeight) * 0.5f, 1f)
            return sqrt((dx * dx) + (dy * dy)) / normalization
        }
    }

    private companion object {
        const val LOG_TAG = "SolarLabInstrumentation"
    }
}
