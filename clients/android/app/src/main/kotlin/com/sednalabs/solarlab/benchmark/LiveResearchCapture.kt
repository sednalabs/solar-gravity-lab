package com.sednalabs.solarlab.benchmark

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus
import com.sednalabs.solarlab.BuildConfig
import com.sednalabs.solarlab.runtime.RuntimeFacade
import com.sednalabs.solarlab.runtime.RuntimeObservationCounters
import com.sednalabs.solarlab.runtime.ShellUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit, foreground-only capture. The renderer callback only offers an immutable message to a bounded queue. */
object LiveResearchCapture {
    @Volatile private var active: Recording? = null
    @Volatile private var closing: Job? = null
    private val recordingState = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = recordingState.asStateFlow()
    val isRecording: Boolean get() = active != null

    /** Call on the main thread so the UI-vsync probe shares the Activity's Choreographer. */
    @Synchronized
    fun start(context: Context, runtime: RuntimeFacade): Boolean {
        if (!BuildConfig.BENCHMARK_ENABLED || active != null || closing?.isActive == true) return false
        return try {
            val recording = Recording(context.applicationContext, runtime)
            active = recording
            recording.begin()
            recordingState.value = true
            Log.i("SolarLabBenchmark", "observatory_capture_started session=${recording.session.name}")
            true
        } catch (error: Exception) {
            active?.let { closing = it.stop("start_failed") }
            active = null
            recordingState.value = false
            Log.e("SolarLabBenchmark", "observatory_capture_start_failed", error)
            false
        }
    }

    /** No formatting, file IO, or native calls occur on the inactive path. */
    fun rendererStatus(status: RenderBackendStatus) { active?.offerRenderer(status) }

    /** Call on the main thread; the bounded queue drains asynchronously after foreground recording stops. */
    @Synchronized
    fun stop(reason: String = "foreground_ended"): Job? {
        val recording = active ?: return closing
        active = null
        recordingState.value = false
        closing = recording.stop(reason)
        return closing
    }

    suspend fun awaitClosed() { closing?.join() }

    private data class Event(val kind: String, val monotonicNs: Long, val wallTime: String, val renderer: RenderBackendStatus? = null, val data: JSONObject? = null)

    private class Recording(context: Context, private val runtime: RuntimeFacade) {
        val store = BenchmarkFileStore(File(context.filesDir, "benchmarks"))
        val session: File = store.createSession(System.currentTimeMillis())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val probe = BenchmarkDeviceProbe(context)
        private val frames = BenchmarkFrameProbe()
        private val queue = Channel<Event>(capacity = 64)
        private val accepting = AtomicBoolean(true)
        private val dropped = AtomicLong(0)
        private val offered = AtomicLong(0)
        private val written = AtomicLong(0)
        private val started = SystemClock.elapsedRealtimeNanos()
        @Volatile private var stopReason = "foreground_ended"
        private var sampler: Job? = null
        private var timer: Job? = null
        private val writer = scope.launch(start = CoroutineStart.LAZY) { writeLoop() }

        init { BenchmarkSessionOwnership.active.add(session.name) }

        fun begin() {
            writer.start()
            frames.start()
            sampler = scope.launch {
                while (isActive && accepting.get()) {
                    offer(Event("observatory_snapshot", SystemClock.elapsedRealtimeNanos(), Instant.now().toString(),
                        data = runtimeSnapshot(runtime.uiState.value, runtime.observationCounters()).put("environment", probe.sample())
                            .put("ui_frame_intervals_ns", frames.drain())
                            .put("frame_scope", "Android_UI_vsync_callbacks; not_Vulkan_GPU_or_present_duration")))
                    delay(1000)
                }
            }
            timer = scope.launch(Dispatchers.Main.immediate) {
                delay(30 * 60 * 1000L)
                if (active === this@Recording) LiveResearchCapture.stop("thirty_minute_capture_budget_reached")
            }
        }

        fun offerRenderer(status: RenderBackendStatus) = offer(Event("observatory_renderer", SystemClock.elapsedRealtimeNanos(), Instant.now().toString(), renderer = status))

        @Synchronized
        private fun offer(event: Event) {
            if (!accepting.get()) return
            offered.incrementAndGet()
            if (queue.trySend(event).isFailure) dropped.incrementAndGet()
        }

        @Synchronized
        fun stop(reason: String): Job {
            stopReason = reason
            accepting.set(false)
            frames.stop()
            sampler?.cancel()
            timer?.cancel()
            queue.close()
            return writer
        }

        private fun append(kind: String, data: JSONObject, monotonicNs: Long = SystemClock.elapsedRealtimeNanos(), wallTime: String = Instant.now().toString()) {
            store.append(session) { JSONObject().put("schema_version", 1).put("event", kind).put("monotonic_ns", SystemClock.elapsedRealtimeNanos())
                .put("wall_time_utc", Instant.now().toString()).put("source_monotonic_ns", monotonicNs)
                .put("source_wall_time_utc", wallTime).put("data", data).toString() }
        }

        private suspend fun writeLoop() {
            val manifest = JSONObject().put("schema_version", 1).put("session_id", session.name).put("plan", "LIVE_OBSERVATORY")
                .put("status", "running").put("build_commit", BuildConfig.BUILD_COMMIT).put("started_utc", Instant.now().toString())
                .put("started_monotonic_ns", started).put("queue_capacity_events", 64).put("sampling_interval_ms", 1000)
                .put("capture_budget_seconds", 1800).put("foreground_only", true)
                .put("journal_budget_bytes", BenchmarkFileStore.JOURNAL_BUDGET_BYTES)
                .put("envelope_clock", "write-order_monotonic_ns; source_monotonic_ns_is_callback_or_observation_time")
                .put("renderer_capture", "untruncated_backend_callback_message_and_hardware_summary; callback cadence is not frame cadence")
                .put("timing_units", "gpuTiming.frameNs/preGraphicsNs and calibrated.hostNs/maxDeviationNs are ns; timestamp.periodNs is ns/tick; calibrated.deviceTicks is ticks; UI intervals are ns")
                .put("measurement_scope", "interactive observatory, actual user-driven scenario and renderer; no isolated-kernel speedup inference")
                .put("runtime_observation_counters", "coherent cumulative bridge-lifetime counts; compare only matching scope_id; paused suppression avoids export/decode/publication but retains summary polling")
                .put("runtime_diagnostics_scope", "last published packet; repeated observations do not imply fresh extraction or GPU timing samples")
                .put("energy_scope", "battery terminal for whole phone including screen, logger and other processes; not chip power")
                .put("data_export", "local-only; records may contain scenario/checkpoint/body labels; no automatic upload")
            var outcome = "completed"
            try {
                store.writeAtomic(session, "manifest.json", manifest.toString(2))
                store.writeAtomic(session, "device.json", probe.identity().toString(2))
                append("session_start", manifest)
                for (event in queue) {
                    val data = event.renderer?.let(::rendererSnapshot) ?: event.data ?: JSONObject()
                    data.put("writer_observed_ns", SystemClock.elapsedRealtimeNanos()).put("queue_dropped_events_total", dropped.get())
                    append(event.kind, data, event.monotonicNs, event.wallTime)
                    written.incrementAndGet()
                    if (store.journalBudgetReached(session)) withContext(Dispatchers.Main.immediate) {
                        if (active === this@Recording) LiveResearchCapture.stop("journal_storage_budget_reached")
                    }
                }
            } catch (error: Exception) {
                outcome = "failed"
                stopReason = "${error.javaClass.simpleName}: ${error.message}"
                Log.e("SolarLabBenchmark", "observatory_capture_write_failed session=${session.name}", error)
            } finally {
                accepting.set(false)
                sampler?.cancel()
                timer?.cancel()
                val summary = JSONObject().put("schema_version", 1).put("session_id", session.name).put("status", outcome)
                    .put("reason", stopReason).put("elapsed_ns", SystemClock.elapsedRealtimeNanos() - started)
                    .put("events_offered", offered.get()).put("events_written", written.get()).put("queue_dropped_events", dropped.get())
                    .put("journal_writer", journalWriterSnapshot(store.journalIoStatistics(session)))
                    .put("counts", JSONObject().put("PASS", 0).put("FAIL", if (outcome == "failed") 1 else 0).put("SKIP", 0))
                    .put("interpretation", "observational capture; no synthetic PASS results or comparison claims")
                runCatching {
                    append("session_end", summary)
                    store.writeAtomic(session, "summary.json", summary.toString(2))
                    manifest.put("status", outcome).put("reason", stopReason).put("finished_utc", Instant.now().toString())
                    store.writeAtomic(session, "manifest.json", manifest.toString(2))
                }.onFailure { Log.e("SolarLabBenchmark", "observatory_capture_finalise_failed", it) }
                BenchmarkSessionOwnership.active.remove(session.name)
                withContext(NonCancellable + Dispatchers.Main.immediate) { frames.stop() }
                scope.coroutineContext[Job]?.cancel()
                synchronized(LiveResearchCapture) {
                    if (active === this@Recording) { active = null; recordingState.value = false }
                }
                Log.i("SolarLabBenchmark", "observatory_capture_finished session=${session.name} dropped=${dropped.get()}")
            }
        }
    }
}

/** Preserve original strings as authority; parsed groups make timing units easier to analyse. */
internal fun rendererSnapshot(status: RenderBackendStatus): JSONObject {
    val raw = status.message + " " + status.hardwareSummary.orEmpty()
    val integerLiteral = Regex("[+-]?[0-9]+")
    fun group(name: String): JSONObject? {
        val match = Regex("${Regex.escape(name)}=\\[([^]]*)]").find(raw) ?: return null
        val result = JSONObject()
        for (part in match.groupValues[1].split(',')) {
            val separator = part.indexOf(':')
            if (separator <= 0) continue
            val key = part.substring(0, separator).trim()
            val text = part.substring(separator + 1).trim()
            // Vk timestamps are uint64. A value outside signed Long must not pass through Double.
            val value: Any = text.toLongOrNull() ?: if (integerLiteral.matches(text)) text
                else text.toDoubleOrNull()?.takeIf(Double::isFinite) ?: text
            result.put(key, value)
        }
        return result
    }
    return JSONObject().put("requested_backend", status.requested.name).put("active_backend", status.active.name)
        .put("hardware_accelerated", status.isHardwareAccelerated).put("message", status.message)
        .put("hardware_summary", status.hardwareSummary ?: JSONObject.NULL).put("truncated", false)
        .put("gpu_timing", group("gpuTiming") ?: JSONObject.NULL)
        .put("timestamp", group("timestamp") ?: JSONObject.NULL)
        .put("calibrated", group("calibrated") ?: JSONObject.NULL)
        .put("subgroup", group("subgroup") ?: JSONObject.NULL)
        .put("sync2", group("sync2") ?: JSONObject.NULL)
        .put("bda", group("bda") ?: JSONObject.NULL)
        .put("pipeline_cache_persistence", group("pipelineCachePersistence") ?: JSONObject.NULL)
        .put("pipeline_cache_scope", "sampled cumulative device-lifecycle host counters; repeated callbacks are not new loads or saves; disk-supplied does not prove a cache hit")
        .put("integer_representation", "signed_64_bit_JSON_integer; out_of_range_decimal_integer_preserved_as_string")
        .put("timing_scope", "last_completed_native_Vulkan_timestamp_sample; callback_is_not_every_presented_frame")
        .put("timing_units", "gpu_timing.frameNs/preGraphicsNs=ns; timestamp.periodNs=ns/tick; calibrated.hostNs/maxDeviationNs=ns; calibrated.deviceTicks=ticks")
}

private fun runtimeSnapshot(state: ShellUiState, counters: RuntimeObservationCounters?): JSONObject {
    fun number(value: Number?): Any = when (value) {
        null -> JSONObject.NULL
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value else JSONObject.NULL
        else -> value
    }
    val result = JSONObject().put("connection_state", state.connectionState.name)
        .put("runtime_observation", counters?.let {
            JSONObject().put("schema_version", 1).put("scope_id", it.scopeId)
                .put("periodic_summary_polls", it.periodicSummaryPolls)
                .put("paused_duplicate_suppressions", it.pausedDuplicateSuppressions)
                .put("packet_export_attempts", it.packetExportAttempts)
                .put("packet_export_successes", it.packetExportSuccesses)
                .put("packet_export_failures", it.packetExportFailures)
        } ?: JSONObject.NULL)
        .put("status_line", state.statusLine).put("detail_line", state.detailLine ?: JSONObject.NULL)
        .put("notice_line", state.noticeLine ?: JSONObject.NULL).put("pending_action", state.pendingActionLabel ?: JSONObject.NULL)
        .put("backend_summary", state.backendSummary ?: JSONObject.NULL)
        .put("snapshot_summary", state.snapshotSummary ?: JSONObject.NULL)
        .put("render_packet_summary", state.renderPacketSummary ?: JSONObject.NULL)
        .put("focused_body_id", state.focusedBodyId ?: JSONObject.NULL)
        .put("camera_facing_summary", state.cameraFacingSummary ?: JSONObject.NULL)
        .put("observer_mode", state.observerModeCode ?: JSONObject.NULL)
    state.snapshot?.let {
        result.put("snapshot", JSONObject().put("scenario_id", it.scenarioId).put("branch_id", it.activeBranchId)
            .put("revision", it.revision).put("body_count", it.bodyCount).put("epoch_seconds", number(it.epochSeconds))
            .put("paused", it.paused).put("requested_sim_seconds_per_real_second", number(it.simSecondsPerRealSecond))
            .put("timeline_semantics", it.timelineSemanticsLabel).put("checkpoint_id", it.activeCheckpointId ?: JSONObject.NULL))
    }
    state.playbackMeasurement?.let {
        result.put("playback", JSONObject().put("revision", it.revision).put("requested_seconds", number(it.requestedSeconds))
            .put("applied_seconds", number(it.appliedSeconds)).put("limited", it.limited)
            .put("achieved_sim_seconds_per_real_second", number(it.achievedRate)))
    }
    state.manualAdvance?.let {
        result.put("manual_advance", JSONObject().put("completed_steps", it.completedSteps).put("total_steps", it.totalSteps)
            .put("cancel_requested", it.cancelRequested).put("status", it.status))
    }
    state.renderStatus.let {
        result.put("render", JSONObject().put("readiness", it.readiness.name).put("scene_revision", it.sceneRevision ?: JSONObject.NULL)
            .put("summary", it.summary ?: JSONObject.NULL).put("bodies", it.renderedBodyCount).put("tracers", it.renderedTracerCount)
            .put("trails", it.renderedTrailCount).put("frame_number", it.diagnosticsFrameNumber ?: JSONObject.NULL)
            .put("diagnostics_cpu_extract_ms", number(it.diagnosticsCpuExtractMs))
            .put("diagnostics_gpu_upload_ms", number(it.diagnosticsGpuUploadMs))
            .put("diagnostics_scope", "upstream_runtime_render_status_labels; not_native_Vulkan_timestamp_queries")
            .put("dropped_frames", it.diagnosticsDroppedFrames).put("degraded", it.isDegraded)
            .put("degradation_reason", it.degradationReason ?: JSONObject.NULL).put("issue", it.issue ?: JSONObject.NULL))
    }
    result.put("developer_telemetry", JSONObject().put("enabled", state.developerTelemetry.enabled)
        .put("dropped_entry_count", state.developerTelemetry.droppedEntryCount)
        .put("upstream_message_limit_characters", 512)
        .put("entries", JSONArray(state.developerTelemetry.entries.map {
            JSONObject().put("recorded_at_unix_ms", it.recordedAtUnixMs).put("level", it.level.name)
                .put("category", it.category).put("message", it.message)
        })))
    return result
}
