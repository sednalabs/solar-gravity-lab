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
}
