package com.sednalabs.solarlab.benchmark

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkLifecycleInstrumentationTest {
    @get:Rule val compose = createAndroidComposeRule<BenchmarkActivity>()

    @Test fun backgroundingStopsWorkAndKeepsAnExportableCancelledSession() {
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Ready")).fetchSemanticsNodes().isNotEmpty()
        }
        val root = File(compose.activity.filesDir, "benchmarks")
        val initialOrientation = compose.activity.requestedOrientation
        val prior = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        compose.onNodeWithText("Sustained stability · 15 min").performScrollTo().performClick()
        var session: File? = null
        compose.waitUntil(timeoutMillis = 30_000) {
            session = root.listFiles()?.firstOrNull { it.isDirectory && it.name !in prior && File(it, "events.ndjson").length() > 0 }
            session != null
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching { JSONObject(File(session, "manifest.json").readText()).optString("status") == "cancelled" }.getOrDefault(false)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) { compose.activity.requestedOrientation == initialOrientation }
        compose.onNodeWithText("Stop and save partial run").assertDoesNotExist()
        val manifest = JSONObject(File(session, "manifest.json").readText())
        assertEquals("cancelled", manifest.getString("status"))
        assertTrue(manifest.getBoolean("orientation_lock_requested"))
        // Retain the artefact for hosted diagnosis; the isolated emulator is disposable.
    }
}
