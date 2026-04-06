package com.sednalabs.solarlab.runtime

import android.util.Log
import com.sednalabs.solarlab.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

internal const val DEVELOPER_TELEMETRY_LOG_TAG = "SolarLabDevTelemetry"
internal const val DEVELOPER_TELEMETRY_MAX_MESSAGE_CHARS = 512

internal fun defaultDeveloperTelemetryRecorder(): DeveloperTelemetryRecorder {
    val sinks = mutableListOf<DeveloperTelemetrySink>(LogcatDeveloperTelemetrySink)
    defaultDeveloperTelemetryHttpSink()?.let(sinks::add)
    return DeveloperTelemetryRecorder(sinks = sinks)
}

enum class DeveloperTelemetryLevel {
    Debug,
    Info,
    Warning,
    Error,
}

data class DeveloperTelemetryEvent(
    val recordedAtUnixMs: Long,
    val level: DeveloperTelemetryLevel,
    val category: String,
    val message: String,
)

data class DeveloperTelemetryPresentation(
    val enabled: Boolean = false,
    val entries: List<DeveloperTelemetryEvent> = emptyList(),
    val droppedEntryCount: Int = 0,
)

internal fun interface DeveloperTelemetrySink {
    fun publish(event: DeveloperTelemetryEvent)
}

internal class DeveloperTelemetryRecorder(
    private val maxEntries: Int = 48,
    private val maxMessageChars: Int = DEVELOPER_TELEMETRY_MAX_MESSAGE_CHARS,
    private val enabled: Boolean = BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".internal"),
    private val sinks: List<DeveloperTelemetrySink> = listOf(LogcatDeveloperTelemetrySink),
) {
    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
        require(maxMessageChars >= 64) { "maxMessageChars must be at least 64" }
    }

    private val entries = ArrayDeque<DeveloperTelemetryEvent>()
    private var droppedEntryCount: Int = 0

    fun presentation(): DeveloperTelemetryPresentation = DeveloperTelemetryPresentation(
        enabled = enabled,
        entries = entries.toList(),
        droppedEntryCount = droppedEntryCount,
    )

    fun record(
        level: DeveloperTelemetryLevel,
        category: String,
        message: String,
    ): DeveloperTelemetryPresentation {
        if (!enabled) {
            return presentation()
        }

        val event = DeveloperTelemetryEvent(
            recordedAtUnixMs = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message.truncatedForDeveloperTelemetry(maxMessageChars),
        )
        if (entries.size >= maxEntries) {
            entries.removeFirst()
            droppedEntryCount += 1
        }
        entries.addLast(event)
        sinks.forEach { sink ->
            runCatching {
                sink.publish(event)
            }.getOrElse {
                println("$DEVELOPER_TELEMETRY_LOG_TAG sink_failure=${it.message ?: it::class.java.simpleName}")
            }
        }
        return presentation()
    }
}

private fun String.truncatedForDeveloperTelemetry(maxChars: Int): String {
    if (length <= maxChars) {
        return this
    }

    val suffix = "... [truncated ${length - maxChars} chars]"
    val prefixLength = (maxChars - suffix.length).coerceAtLeast(0)
    return take(prefixLength) + suffix
}

private object LogcatDeveloperTelemetrySink : DeveloperTelemetrySink {
    override fun publish(event: DeveloperTelemetryEvent) {
        val line = event.toLogLine()
        runCatching {
            when (event.level) {
                DeveloperTelemetryLevel.Debug -> Log.d(DEVELOPER_TELEMETRY_LOG_TAG, line)
                DeveloperTelemetryLevel.Info -> Log.i(DEVELOPER_TELEMETRY_LOG_TAG, line)
                DeveloperTelemetryLevel.Warning -> Log.w(DEVELOPER_TELEMETRY_LOG_TAG, line)
                DeveloperTelemetryLevel.Error -> Log.e(DEVELOPER_TELEMETRY_LOG_TAG, line)
            }
        }.getOrElse {
            println("$DEVELOPER_TELEMETRY_LOG_TAG $line")
        }
    }
}

internal fun DeveloperTelemetryEvent.toLogLine(): String {
    return "${level.name.lowercase()}|$category|$message"
}

internal fun DeveloperTelemetryEvent.toDisplayLine(locale: Locale = Locale.US): String {
    val formatter = SimpleDateFormat("HH:mm:ss", locale)
    return "[${formatter.format(Date(recordedAtUnixMs))}] ${level.name.uppercase(locale)} $category: $message"
}

internal fun DeveloperTelemetryPresentation.toShareText(
    maxEntries: Int = entries.size,
    locale: Locale = Locale.US,
): String {
    if (!enabled) {
        return "Developer telemetry is disabled for this build."
    }

    if (entries.isEmpty()) {
        return "Developer telemetry is enabled but no events have been captured yet."
    }

    return buildString {
        appendLine("Solar Gravity Lab developer telemetry")
        appendLine("logcatTag=$DEVELOPER_TELEMETRY_LOG_TAG")
        appendLine("droppedEntries=$droppedEntryCount")
        entries.takeLast(maxEntries).forEach { event ->
            appendLine(event.toDisplayLine(locale))
        }
    }.trimEnd()
}
