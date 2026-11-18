package com.sednalabs.solarlab.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.sednalabs.solarlab.runtime.RuntimeCommand
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeScenarioPack
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveResearchCaptureInstrumentationTest {
    @Test fun foregroundCaptureKeepsFullRendererMessagesAndDrainsBeforeExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "benchmarks")
        val prior = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        val runtime = object : RuntimeFacade {
            override val uiState = MutableStateFlow(ShellUiState(statusLine = "live capture fixture"))
            override val scenarioPacks = emptyList<RuntimeScenarioPack>()
            override suspend fun startSession() = Unit
            override suspend fun loadScenario(scenarioId: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun applyCommand(command: RuntimeCommand) = Unit
        }
        val message = "gpuTiming=[samples:7,frameNs:123456,preGraphicsNs:12000] " + "full-message-".repeat(600) + "END"
        val hardware = "timestamp=[validBits:64,periodNs:19.2,queries:1] calibrated=[hostNs:9007199254740993,deviceTicks:18446744073709551615,maxDeviationNs:42]"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(LiveResearchCapture.start(context, runtime))
            LiveResearchCapture.rendererStatus(RenderBackendStatus(RenderBackend.VULKAN, RenderBackend.VULKAN, true, message, hardware))
            LiveResearchCapture.stop("instrumentation_fixture_complete")
        }
        runBlocking { LiveResearchCapture.awaitClosed() }
        assertFalse(LiveResearchCapture.isRecording)
        val session = root.listFiles()!!.single { it.isDirectory && it.name !in prior }
        val records = File(session, "events.ndjson").readLines().map(::JSONObject)
        assertTrue(records.zipWithNext().all { (a, b) -> a.getLong("monotonic_ns") <= b.getLong("monotonic_ns") })
        val renderer = records.single { it.optString("event") == "observatory_renderer" }.getJSONObject("data")
        assertEquals(message, renderer.getString("message"))
        assertEquals(hardware, renderer.getString("hardware_summary"))
        assertFalse(renderer.getBoolean("truncated"))
        assertEquals(123456L, renderer.getJSONObject("gpu_timing").getLong("frameNs"))
        val calibrated = renderer.getJSONObject("calibrated")
        assertEquals(9007199254740993L, calibrated.getLong("hostNs"))
        assertTrue(calibrated.get("deviceTicks") is String)
        assertEquals("18446744073709551615", calibrated.getString("deviceTicks"))
        assertEquals("completed", JSONObject(File(session, "manifest.json").readText()).getString("status"))
        val summary = JSONObject(File(session, "summary.json").readText())
        assertEquals(summary.getLong("events_offered"), summary.getLong("events_written") + summary.getLong("queue_dropped_events"))
        assertEquals(0, summary.getJSONObject("counts").getInt("PASS"))
    }
}
