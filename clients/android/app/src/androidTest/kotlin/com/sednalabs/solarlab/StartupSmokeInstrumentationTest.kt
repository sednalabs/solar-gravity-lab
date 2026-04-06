package com.sednalabs.solarlab

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSmokeInstrumentationTest {
    @Test
    fun mainActivity_launches_and_reaches_first_runtime_frame_without_process_death() {
        Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.begin")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val facade = scenario.withRuntimeFacade()

            waitForState(facade) {
                it.connectionState == SessionConnectionState.Active &&
                    it.sessionHandle != null &&
                    it.snapshot != null &&
                    it.renderPacketSummary != null &&
                    it.renderStatus.readiness == RenderHostReadiness.Ready &&
                    it.renderFrame != null &&
                    hasRicherSceneContent(it)
            }

            scenario.onActivity { activity ->
                assertVisualReadiness(activity)
                assertFalse("MainActivity should not be finishing after startup", activity.isFinishing)
                assertFalse("MainActivity should not be destroyed after startup", activity.isDestroyed)
            }

            val finalState = facade.uiState.value
            Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.ready ${summarizeState(finalState)}")
            assertNotNull("Runtime session handle should be available after startup", finalState.sessionHandle)
            assertNotNull("Runtime snapshot should be available after startup", finalState.snapshot)
            assertNotNull(
                "A decoded render frame should be available after startup. Final state: $finalState",
                finalState.renderFrame,
            )
            assertTrue(
                "Startup should reach a populated ready scene. Final state: $finalState",
                hasRicherSceneContent(finalState),
            )
        }
    }

    private fun hasRicherSceneContent(state: ShellUiState): Boolean {
        val snapshotBodyCount = state.snapshot?.bodyCount ?: 0
        val frameBodyCount = state.renderFrame?.bodies?.size ?: 0
        val renderBodyCount = state.renderStatus.renderedBodyCount
        val renderTracerCount = state.renderStatus.renderedTracerCount
        val renderTrailCount = state.renderStatus.renderedTrailCount
        val totalRenderableElements = renderBodyCount + renderTracerCount + renderTrailCount

        return snapshotBodyCount >= 10 &&
            frameBodyCount >= 10 &&
            renderBodyCount >= 10 &&
            totalRenderableElements >= 10 &&
            totalRenderableElements >= renderBodyCount &&
            state.renderStatus.issue == null
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
        var nextProgressLogMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadlineMs) {
            val state = facade.uiState.value
            if (predicate(state)) {
                Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.conditionMet ${summarizeState(state)}")
                return
            }
            if (System.currentTimeMillis() >= nextProgressLogMs) {
                Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.waiting ${summarizeState(state)}")
                nextProgressLogMs += PROGRESS_LOG_INTERVAL_MS
            }
            Thread.sleep(50)
        }
        val finalState = facade.uiState.value
        Log.e(LOG_TAG, "StartupSmokeInstrumentationTest.timeout ${summarizeState(finalState)}")
        throw AssertionError(
            "Timed out waiting for startup smoke condition. Final state: $finalState"
        )
    }

    private fun summarizeState(state: ShellUiState): String =
        "connection=${state.connectionState}, session=${state.sessionHandle}, " +
            "snapshotBodies=${state.snapshot?.bodyCount ?: 0}, frameBodies=${state.renderFrame?.bodies?.size ?: 0}, " +
            "renderBodies=${state.renderStatus.renderedBodyCount}, renderTracers=${state.renderStatus.renderedTracerCount}, " +
            "renderTrails=${state.renderStatus.renderedTrailCount}, readiness=${state.renderStatus.readiness}, " +
            "issue=${state.renderStatus.issue ?: "none"}"

    private fun assertVisualReadiness(activity: MainActivity) {
        val renderSurface = findRenderSurfaceView(activity.window.decorView)
            ?: throw AssertionError("Unable to find VulkanPacketRenderSurfaceView for stage capture")
        assertTrue(
            "Render surface should have a non-zero size before screenshot capture",
            renderSurface.width > 0 && renderSurface.height > 0,
        )
        val screenshot = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .takeScreenshot()
            ?: throw AssertionError("Unable to capture startup screenshot from instrumentation")
        try {
            persistValidationScreenshot("startup-ready", screenshot)
            val stageScreenshot = screenshot.cropToStage(renderSurface)
            try {
                persistValidationScreenshot("startup-ready-stage", stageScreenshot)
                val metrics = stageScreenshot.visualMetrics()
                Log.i(
                    LOG_TAG,
                    "StartupSmokeInstrumentationTest.visualMetrics sampled=${metrics.sampleCount}, " +
                        "bright=${metrics.brightSampleCount}, unique=${metrics.uniqueColorCount}, " +
                        "stage=${stageScreenshot.width}x${stageScreenshot.height}"
                )
                assertTrue(
                    "Startup stage screenshot looks too visually empty: $metrics",
                    metrics.brightSampleCount >= 20 && metrics.uniqueColorCount >= 8,
                )
            } finally {
                stageScreenshot.recycle()
            }
            Log.i(
                LOG_TAG,
                "StartupSmokeInstrumentationTest.savedFullScreenshot size=${screenshot.width}x${screenshot.height}"
            )
        } finally {
            screenshot.recycle()
        }
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
        Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.stageBounds=$boundedRect")
        return Bitmap.createBitmap(
            this,
            boundedRect.left,
            boundedRect.top,
            boundedRect.width(),
            boundedRect.height(),
        )
    }

    private fun Bitmap.visualMetrics(): VisualMetrics {
        val width = width
        val height = height
        val stepX = maxOf(1, width / 24)
        val stepY = maxOf(1, height / 24)
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

    private fun persistValidationScreenshot(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(
            context.filesDir,
            "validation-screenshots",
        ).apply {
            mkdirs()
        }
        val outputFile = File(outputDir, "$name.png")
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
        }
        Log.i(LOG_TAG, "StartupSmokeInstrumentationTest.savedScreenshot ${outputFile.absolutePath}")
    }

    private data class VisualMetrics(
        val sampleCount: Int,
        val brightSampleCount: Int,
        val uniqueColorCount: Int,
    )

    private companion object {
        const val LOG_TAG = "SolarLabInstrumentation"
        const val PROGRESS_LOG_INTERVAL_MS = 2_000L
    }
}
