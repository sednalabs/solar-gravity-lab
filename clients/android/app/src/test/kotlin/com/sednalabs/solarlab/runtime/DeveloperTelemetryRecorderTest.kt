package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperTelemetryRecorderTest {
    @Test
    fun record_dropsOldestEntries_whenCapacityIsExceeded() {
        val recorder = DeveloperTelemetryRecorder(
            maxEntries = 2,
            enabled = true,
            sinks = emptyList(),
        )

        recorder.record(
            level = DeveloperTelemetryLevel.Info,
            category = "alpha",
            message = "first",
        )
        recorder.record(
            level = DeveloperTelemetryLevel.Warning,
            category = "beta",
            message = "second",
        )
        val presentation = recorder.record(
            level = DeveloperTelemetryLevel.Error,
            category = "gamma",
            message = "third",
        )

        assertTrue(presentation.enabled)
        assertEquals(1, presentation.droppedEntryCount)
        assertEquals(listOf("beta", "gamma"), presentation.entries.map { it.category })
    }

    @Test
    fun record_truncatesOversizedMessages_beforePublishing() {
        val recorder = DeveloperTelemetryRecorder(
            enabled = true,
            maxMessageChars = 96,
            sinks = emptyList(),
        )

        val presentation = recorder.record(
            level = DeveloperTelemetryLevel.Info,
            category = "render.ready",
            message = "x".repeat(200),
        )

        val message = presentation.entries.single().message
        assertTrue(message.length <= 96)
        assertTrue(message.contains("truncated"))
    }
}
