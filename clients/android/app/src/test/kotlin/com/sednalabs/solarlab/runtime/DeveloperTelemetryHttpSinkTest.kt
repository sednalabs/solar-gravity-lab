package com.sednalabs.solarlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperTelemetryHttpSinkTest {
    @Test
    fun flushNowForTesting_serializesQueuedEventsIntoOneBatch() {
        val payloads = mutableListOf<String>()
        val sink = DeveloperTelemetryHttpSink(
            endpointUrl = "https://collector.example.test/v1/android/developer-telemetry",
            authToken = "secret-token",
            sessionId = "session-1",
            appInfo = DeveloperTelemetryAppInfo(
                applicationId = "com.sednalabs.solarlab.internal",
                versionName = "0.1.0-alpha.10",
                versionCode = 11,
            ),
            deviceInfo = DeveloperTelemetryDeviceInfo(
                manufacturer = "Samsung",
                model = "Galaxy S25 Ultra",
                sdkInt = 35,
            ),
            flushIntervalMillis = 60_000L,
            sender = payloads::add,
        )

        sink.publish(
            DeveloperTelemetryEvent(
                recordedAtUnixMs = 100L,
                level = DeveloperTelemetryLevel.Info,
                category = "session.start",
                message = "Opening runtime session",
            ),
        )
        sink.publish(
            DeveloperTelemetryEvent(
                recordedAtUnixMs = 200L,
                level = DeveloperTelemetryLevel.Warning,
                category = "render.unavailable",
                message = "Render export unavailable",
            ),
        )

        sink.flushNowForTesting()

        assertEquals(1, payloads.size)
        val payload = payloads.single()
        assertTrue(payload.contains("\"session_id\":\"session-1\""))
        assertTrue(payload.contains("\"application_id\":\"com.sednalabs.solarlab.internal\""))
        assertTrue(payload.contains("\"manufacturer\":\"Samsung\""))
        assertTrue(payload.contains("\"category\":\"session.start\""))
        assertTrue(payload.contains("\"category\":\"render.unavailable\""))
    }
}
