package com.sednalabs.solarlab

import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sednalabs.solarlab.runtime.RenderHostReadiness
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.SessionConnectionState
import com.sednalabs.solarlab.runtime.ShellUiState
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
                assertVisualReadiness()
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

    private fun assertVisualReadiness() {
        val screenshot = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .takeScreenshot()
            ?: throw AssertionError("Unable to capture startup screenshot from instrumentation")
        try {
            val metrics = screenshot.renderCropMetrics()
            Log.i(
                LOG_TAG,
                "StartupSmokeInstrumentationTest.visualMetrics sampled=${metrics.sampleCount}, " +
                    "bright=${metrics.brightSampleCount}, unique=${metrics.uniqueColorCount}, " +
                    "size=${screenshot.width}x${screenshot.height}"
            )
            assertTrue(
                "Startup screenshot looks too visually empty: $metrics",
                metrics.brightSampleCount >= 20 && metrics.uniqueColorCount >= 12,
            )
        } finally {
            screenshot.recycle()
        }
    }

    private fun Bitmap.renderCropMetrics(): VisualMetrics {
        val width = width
        val height = height
        val cropLeft = (width * 0.08f).toInt().coerceIn(0, width - 1)
        val cropRight = (width * 0.92f).toInt().coerceAtLeast(cropLeft + 1)
        val cropTop = (height * 0.16f).toInt().coerceIn(0, height - 1)
        val cropBottom = (height * 0.62f).toInt().coerceIn(cropTop + 1, height)
        val stepX = maxOf(1, (cropRight - cropLeft) / 18)
        val stepY = maxOf(1, (cropBottom - cropTop) / 18)
        val uniqueColors = linkedSetOf<Int>()
        var brightSamples = 0
        var sampleCount = 0
        for (x in cropLeft until cropRight step stepX) {
            for (y in cropTop until cropBottom step stepY) {
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
