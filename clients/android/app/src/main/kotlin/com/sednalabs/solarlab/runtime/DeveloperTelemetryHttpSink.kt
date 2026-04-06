package com.sednalabs.solarlab.runtime

import android.os.Build
import android.util.Log
import com.sednalabs.solarlab.BuildConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal data class DeveloperTelemetryAppInfo(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
)

internal data class DeveloperTelemetryDeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
)

internal fun defaultDeveloperTelemetryHttpSink(): DeveloperTelemetrySink? {
    val endpoint = BuildConfig.DEV_TELEMETRY_ENDPOINT.trim()
    if (endpoint.isEmpty()) {
        return null
    }

    return DeveloperTelemetryHttpSink(
        endpointUrl = endpoint,
        authToken = BuildConfig.DEV_TELEMETRY_TOKEN.trim().ifEmpty { null },
    )
}

internal fun developerTelemetryStreamingTargetLabel(): String? {
    val endpoint = BuildConfig.DEV_TELEMETRY_ENDPOINT.trim()
    if (endpoint.isEmpty()) {
        return null
    }

    return runCatching {
        val url = URL(endpoint)
        if (url.host.isNullOrBlank()) endpoint else "${url.protocol}://${url.host}${url.port.takeIf { it > 0 }?.let { ":$it" } ?: ""}"
    }.getOrElse {
        endpoint
    }
}

internal class DeveloperTelemetryHttpSink internal constructor(
    private val endpointUrl: String,
    private val authToken: String? = null,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val appInfo: DeveloperTelemetryAppInfo = DeveloperTelemetryAppInfo(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    ),
    private val deviceInfo: DeveloperTelemetryDeviceInfo = DeveloperTelemetryDeviceInfo(
        manufacturer = Build.MANUFACTURER ?: "unknown",
        model = Build.MODEL ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
    ),
    private val queueCapacity: Int = 256,
    private val maxBatchSize: Int = 16,
    private val flushIntervalMillis: Long = 1_500L,
    private val connectTimeoutMillis: Int = 3_000,
    private val readTimeoutMillis: Int = 3_000,
    private val sender: (String) -> Unit = { payload ->
        postDeveloperTelemetryPayload(
            endpointUrl = endpointUrl,
            authToken = authToken,
            payload = payload,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
        )
    },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        DeveloperTelemetryThreadFactory,
    ),
) : DeveloperTelemetrySink {
    private val queue = LinkedBlockingDeque<DeveloperTelemetryEvent>(queueCapacity)

    init {
        executor.scheduleWithFixedDelay(
            ::flushSafely,
            flushIntervalMillis,
            flushIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun publish(event: DeveloperTelemetryEvent) {
        if (!queue.offerLast(event)) {
            queue.pollFirst()
            queue.offerLast(event)
        }

        if (queue.size >= maxBatchSize) {
            executor.execute(::flushSafely)
        }
    }

    internal fun flushNowForTesting() {
        flushSafely()
    }

    private fun flushSafely() {
        val batch = mutableListOf<DeveloperTelemetryEvent>()
        while (batch.size < maxBatchSize) {
            val next = queue.pollFirst() ?: break
            batch += next
        }

        if (batch.isEmpty()) {
            return
        }

        runCatching {
            sender(buildPayload(batch))
        }.getOrElse { error ->
            runCatching {
                Log.w(
                    DEVELOPER_TELEMETRY_LOG_TAG,
                    "stream_upload_failed|${error.message ?: error::class.java.simpleName}",
                )
            }.getOrElse {
                println(
                    "$DEVELOPER_TELEMETRY_LOG_TAG stream_upload_failed|${error.message ?: error::class.java.simpleName}",
                )
            }
        }
    }

    private fun buildPayload(events: List<DeveloperTelemetryEvent>): String {
        return buildString {
            append('{')
            append("\"captured_at_unix_ms\":").append(System.currentTimeMillis()).append(',')
            append("\"source\":").append("clients/android".toJsonString()).append(',')
            append("\"session_id\":").append(sessionId.toJsonString()).append(',')
            append("\"app\":{")
            append("\"application_id\":").append(appInfo.applicationId.toJsonString()).append(',')
            append("\"version_name\":").append(appInfo.versionName.toJsonString()).append(',')
            append("\"version_code\":").append(appInfo.versionCode)
            append("},")
            append("\"device\":{")
            append("\"manufacturer\":").append(deviceInfo.manufacturer.toJsonString()).append(',')
            append("\"model\":").append(deviceInfo.model.toJsonString()).append(',')
            append("\"sdk_int\":").append(deviceInfo.sdkInt)
            append("},")
            append("\"events\":[")
            events.forEachIndexed { index, event ->
                if (index > 0) {
                    append(',')
                }
                append('{')
                append("\"recorded_at_unix_ms\":").append(event.recordedAtUnixMs).append(',')
                append("\"level\":").append(event.level.name.lowercase().toJsonString()).append(',')
                append("\"category\":").append(event.category.toJsonString()).append(',')
                append("\"message\":").append(event.message.toJsonString())
                append('}')
            }
            append("]}")
        }
    }

    private object DeveloperTelemetryThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "solarlab-dev-telemetry").apply {
                isDaemon = true
            }
        }
    }
}

private fun String.toJsonString(): String = buildString {
    append('"')
    this@toJsonString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun postDeveloperTelemetryPayload(
    endpointUrl: String,
    authToken: String?,
    payload: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
) {
    val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = connectTimeoutMillis
        readTimeout = readTimeoutMillis
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        authToken?.takeIf(String::isNotBlank)?.let { token ->
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    connection.outputStream.use { output ->
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
        }
    }

    val status = connection.responseCode
    if (status !in 200..299) {
        val errorBody = runCatching {
            connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        throw IllegalStateException(
            "telemetry collector rejected batch: status=$status body=${errorBody ?: "<empty>"}",
        )
    }
}
