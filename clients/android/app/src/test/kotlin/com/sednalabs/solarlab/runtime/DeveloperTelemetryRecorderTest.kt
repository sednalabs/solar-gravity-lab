package com.sednalabs.solarlab.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperTelemetryRecorderTest {
    @Test
    fun concurrentRecordsPublishOnceAndKeepBoundedConsistentStorage() {
        val published = AtomicInteger()
        val recorder = DeveloperTelemetryRecorder(maxEntries = 16, enabled = true,
            sinks = listOf(DeveloperTelemetrySink { published.incrementAndGet() }))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { worker ->
                pool.submit {
                    start.await()
                    repeat(100) { recorder.record(DeveloperTelemetryLevel.Info, "worker", "$worker:$it") }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            val presentation = recorder.presentation()
            assertEquals(800, published.get())
            assertEquals(16, presentation.entries.size)
            assertEquals(784, presentation.droppedEntryCount)
            assertEquals(16, presentation.entries.map { it.message }.distinct().size)
        } finally {
            pool.shutdownNow()
        }
    }

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
