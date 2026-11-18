package com.sednalabs.solarlab.benchmark

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.sednalabs.solarlab.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

data class BenchmarkDiscovery(val cases: List<BenchmarkCase>, val catalogue: JSONObject, val research: JSONObject, val ready: Boolean)
data class BenchmarkProgress(
    val running: Boolean = false,
    val session: String? = null,
    val phase: String = "Ready",
    val detail: String = "Choose a run. Stop at any time and export everything collected so far.",
    val pairsCompleted: Int = 0,
    val pairsPlanned: Int = 0,
    val pass: Int = 0,
    val fail: Int = 0,
    val skip: Int = 0,
    val elapsedSeconds: Long = 0,
)

class BenchmarkRunner(private val context: Context, private val store: BenchmarkFileStore, private val frames: BenchmarkFrameProbe) {
    private val probe = BenchmarkDeviceProbe(context)

    suspend fun discover(): BenchmarkDiscovery = withContext(Dispatchers.Default) {
        recoverInterruptedSessions()
        val cpu = NativeBridge.catalogue()
        val hardware = NativeHardwareBench.catalogue()
        val cpuCases = parseCases(cpu, "cpu")
        val hardwareCases = parseCases(hardware, "hardware")
        val research = runCatching {
            context.assets.open("benchmark_catalogue.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse { failure("SKIP", "research_catalogue_unavailable", it.message.orEmpty()) }
        val ready = NativeBridge.loadFailure == null && NativeHardwareBench.loadFailure == null &&
            cpu.optInt("schema_version") == 1 && hardware.optInt("schema_version") == 1 &&
            cpuCases.isNotEmpty() && cpu.has("cases") && hardware.has("cases")
        if (ready) Log.i("SolarLabBenchmark", "ready cpu_cases=${cpuCases.size} gpu_cases=${hardwareCases.size}")
        else Log.e("SolarLabBenchmark", "unavailable cpu_status=${cpu.optString("status")} gpu_status=${hardware.optString("status")}")
        BenchmarkDiscovery(cpuCases + hardwareCases, JSONObject().put("schema_version", 1).put("cpu", cpu).put("hardware", hardware), research, ready)
    }

    suspend fun run(discovery: BenchmarkDiscovery, plan: BenchmarkPlan, options: BenchmarkOptions, onProgress: (BenchmarkProgress) -> Unit): File {
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "solarlab-benchmark") }.asCoroutineDispatcher()
        return try { withContext(worker) { execute(discovery, plan, options, onProgress) } } finally { worker.close() }
    }

    private suspend fun record(session: File, type: String, value: JSONObject) = withContext(Dispatchers.IO) {
        store.append(session, JSONObject().put("schema_version", 1).put("event", type)
            .put("monotonic_ns", SystemClock.elapsedRealtimeNanos()).put("wall_time_utc", Instant.now().toString())
            .put("data", value).toString())
    }

    private fun createHint(): PerformanceHintManager.Session? = runCatching {
        context.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(Process.myTid()), 100_000_000L)
    }.getOrNull()

    private suspend fun execute(discovery: BenchmarkDiscovery, plan: BenchmarkPlan, options: BenchmarkOptions, onProgress: (BenchmarkProgress) -> Unit): File = coroutineScope {
        val session = withContext(Dispatchers.IO) { store.createSession(System.currentTimeMillis()) }
        val state = RunState(session, plan, options, onProgress)
        val placements = if (plan == BenchmarkPlan.FULL) listOf("system", "single", "big_cluster") else listOf("system")
        val tasks = BenchmarkSchedule.pairs(discovery.cases, plan).flatMap { pair ->
            (if (pair.candidate.backend == "cpu") placements else listOf("system")).map { pair to it }
        }
        state.progress = state.progress.copy(pairsPlanned = tasks.size)
        state.manifest.put("schedule", JSONArray(tasks.map { (pair, placement) ->
            JSONObject().put("pair", "${pair.key}/$placement").put("candidate_case", pair.candidate.id)
                .put("baseline_case", pair.baseline.id).put("fixture_id", pair.fixture).put("steps", pair.steps).put("placement", placement)
        }))
        var sampler: Job? = null
        try {
            state.prepare(discovery)
            sampler = launch(Dispatchers.IO) {
                while (isActive) {
                    record(session, "environment", probe.sample().put("ui_frame_intervals_ns", frames.drain())
                        .put("frame_scope", "benchmark_ui_choreographer_callbacks_not_vulkan_presentations"))
                    delay(2000)
                }
            }
            if (tasks.isEmpty()) { state.outcome = "unavailable"; state.reason = "no_runnable_cases_admitted" }
            var round = 0
            do {
                for ((index, task) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (state.expired()) break
                    state.runPair(task.first, task.second, round)
                    state.progress = state.progress.copy(pairsCompleted = index + 1)
                    state.publish("Comparing", "${task.first.candidate.label} recorded. Raw checks and timings are retained.")
                }
                round++
            } while (plan == BenchmarkPlan.SUSTAINED && tasks.isNotEmpty() && !state.expired())
            if (state.expired()) {
                state.outcome = if (plan == BenchmarkPlan.SUSTAINED) "completed" else "budget_exhausted"
                state.reason = "elapsed_budget_reached; unrun_cases_are_not_execution_evidence"
            }
        } catch (cancelled: CancellationException) {
            state.outcome = "cancelled"; state.reason = cancelled.message ?: "user_or_foreground_lifecycle_cancelled"
            throw cancelled
        } catch (error: Exception) {
            state.outcome = "failed"; state.reason = "${error.javaClass.simpleName}: ${error.message}"
            Log.e("SolarLabBenchmark", "run_failed session=${session.name}", error)
        } finally {
            withContext(NonCancellable) {
                sampler?.cancelAndJoin()
                state.finish()
            }
        }
        session
    }

    private inner class RunState(
        val session: File,
        val plan: BenchmarkPlan,
        val options: BenchmarkOptions,
        val onProgress: (BenchmarkProgress) -> Unit,
    ) {
        val start: Long = SystemClock.elapsedRealtimeNanos()
        val deadline: Long = start + plan.budgetSeconds * 1_000_000_000L
        var progress = BenchmarkProgress(running = true, session = session.name, phase = "Preparing")
        val values = linkedMapOf<String, MutableList<Long>>()
        val counts = linkedMapOf("PASS" to 0, "FAIL" to 0, "SKIP" to 0)
        val blockedCases = mutableSetOf<String>()
        val thermal = ThermalPausePolicy()
        var outcome = "completed"
        var reason = "all_scheduled_comparisons_completed"
        var pausedNs = 0L
        var thermalEpoch = 0
        var hint: PerformanceHintManager.Session? = null
        val manifest: JSONObject = JSONObject().put("schema_version", 1).put("session_id", session.name)
            .put("status", "running").put("plan", plan.name).put("started_utc", Instant.now().toString())
            .put("started_monotonic_ns", start).put("build_commit", BuildConfig.BUILD_COMMIT)
            .put("budget_seconds", plan.budgetSeconds).put("requested_display_mode_id", options.displayModeId)
            .put("performance_hints_requested", options.performanceHints).put("benchmark_thread_tid", Process.myTid())
            .put("protocol", "warmup A,B then ordered A1,B1,B2,A2; identical deterministic fixture and step count; one native repeat per record")
            .put("timing_scope", "native elapsed_ns with native elapsed_scope; consult native timed-scope for scratch allocation/thread costs; Java telemetry and file IO excluded")
            .put("native_repeats", if (plan == BenchmarkPlan.SUSTAINED) 11 else 1)
            .put("hardware_iterations", if (plan == BenchmarkPlan.SUSTAINED) 16 else 1)
            .put("energy_scope", "whole-device observations include UI, telemetry and synchronous logging; no chip-power or joules-per-kernel claim")
            .put("sampling_interval_ms", 2000).put("thermal_headroom_interval_ms", 10000)
            .put("thermal_policy", "pause at SEVERE; resume after LIGHT or NONE for 30s; unchanged numerical settings")
            .put("background_policy", "cancel and retain partial data on activity stop; no background execution")
            .put("percentile_method", "nearest-rank; midpoint median; small cohorts do not estimate reliable tail distributions")
            .put("baseline_scope", "same-APK selected reference case, not an earlier-commit binary")
            .put("data_export", "local-only; explicit document save or Android share")

        fun expired(): Boolean = SystemClock.elapsedRealtimeNanos() >= deadline

        suspend fun publish(phase: String, detail: String) {
            progress = progress.copy(phase = phase, detail = detail, elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000L)
            withContext(Dispatchers.Main.immediate) { onProgress(progress) }
        }

        suspend fun prepare(discovery: BenchmarkDiscovery) {
            withContext(Dispatchers.IO) {
                store.writeAtomic(session, "manifest.json", manifest.toString(2))
                store.writeAtomic(session, "catalogue.json", discovery.catalogue.toString(2))
                store.writeAtomic(session, "research-catalogue.json", discovery.research.toString(2))
                store.writeAtomic(session, "device.json", probe.identity().toString(2))
            }
            record(session, "session_start", manifest)
            record(session, "benchmark_thread", NativeHardwareBench.threadInfo())
            discovery.cases.filterNot(BenchmarkCase::runnable).forEach {
                record(session, "case_admission", JSONObject().put("case_id", it.id).put("backend", it.backend)
                    .put("status", "SKIP").put("implementation_status", it.status).put("reason", it.reason))
            }
        }

        suspend fun runPair(pair: BenchmarkPair, placementMode: String, round: Int) {
            val pairKey = "${pair.key}/$placementMode"
            val placement = NativeHardwareBench.placement(placementMode)
            record(session, "placement", JSONObject().put("mode", placementMode).put("pair", pairKey).put("native", placement))
            if (placementMode != "system" && placement.optString("status").uppercase() != "PASS") {
                record(session, "case_admission", JSONObject().put("case_id", pair.candidate.id).put("pair", pairKey)
                    .put("status", "SKIP").put("reason", "requested_placement_unavailable"))
                return
            }
            publish("Comparing", "${pair.candidate.label} · ${pair.fixture} · ${pair.steps} steps · $placementMode")
            val warming = if (round == 0) listOf("warmup-A", "warmup-B") else emptyList()
            val orders = warming.map { -1 to it } + (0 until plan.blocks).flatMap { block -> BenchmarkSchedule.abba.map { block to it } }
            val blockValues = mutableMapOf<Int, MutableMap<String, MutableList<Long>>>()
            val blockThermalEpochs = mutableMapOf<Int, MutableSet<Int>>()
            for ((block, order) in orders) {
                currentCoroutineContext().ensureActive()
                awaitThermalRecovery(pairKey)
                if (expired()) break
                val role = if (order.endsWith("A") || order.startsWith("A")) "A" else "B"
                val case = if (role == "A") pair.baseline else pair.candidate
                val admissionKey = "${case.key}/${pair.fixture}/${pair.steps}/$placementMode"
                val before = probe.cachedSample()
                if (options.performanceHints && hint == null && case.backend == "cpu") hint = createHint()
                if (case.backend != "cpu") { runCatching { hint?.close() }; hint = null }
                val tid = Process.myTid()
                val wrapperStart = SystemClock.elapsedRealtimeNanos()
                val repeats = if (plan == BenchmarkPlan.SUSTAINED) { if (case.backend == "cpu") 11 else 16 } else 1
                val result = if (admissionKey in blockedCases) failure("SKIP", "case_skipped_after_unavailable_result", admissionKey)
                    else if (case.backend == "cpu") NativeBridge.run(case.id, pair.fixture, pair.steps, repeats)
                    else NativeHardwareBench.run(case.id, repeats)
                val wrapperNs = SystemClock.elapsedRealtimeNanos() - wrapperStart
                runCatching { hint?.reportActualWorkDuration(wrapperNs.coerceAtLeast(1)) }
                val (status, elapsed) = validatedMeasurement(result)
                if (status == "SKIP") blockedCases += admissionKey
                if (status != result.optString("status").uppercase()) result.put("protocol_validation_error", "PASS requires a finite nonnegative native elapsed_ns; wrapper time is never substituted")
                val warmup = block < 0
                if (!warmup) {
                    blockThermalEpochs.getOrPut(block) { mutableSetOf() } += thermalEpoch
                    counts[status] = counts.getValue(status) + 1
                    if (elapsed != null && status == "PASS") {
                        values.getOrPut("$pairKey/$role") { mutableListOf() } += elapsed
                        blockValues.getOrPut(block) { mutableMapOf() }.getOrPut(role) { mutableListOf() } += elapsed
                    }
                }
                record(session, "sample", JSONObject().put("pair", pairKey).put("case_id", case.id).put("backend", case.backend)
                    .put("fixture_id", pair.fixture).put("steps", pair.steps).put("repeats", repeats).put("round", round)
                    .put("block", block).put("order", order).put("role", role).put("warmup", warmup).put("placement", placementMode)
                    .put("status", status).put("elapsed_ns", elapsed ?: JSONObject.NULL)
                    .put("jni_wrapper_elapsed_ns", wrapperNs).put("hint_session_created", hint != null)
                    .put("hint_target_ns", if (hint != null) 100_000_000L else JSONObject.NULL).put("hint_thread_tid", tid)
                    .put("hint_scope", "dedicated_boundary_thread_only; internal_native_workers_not_in_session")
                    .put("elapsed_scope", result.optString("elapsed_scope", "native_declared_scope"))
                    .put("kernel_total_ns", result.opt("kernel_total_ns") ?: JSONObject.NULL)
                    .put("environment_before", before).put("environment_after", probe.cachedSample()).put("native", result))
                progress = progress.copy(pass = counts.getValue("PASS"), fail = counts.getValue("FAIL"), skip = counts.getValue("SKIP"))
            }
            for ((block, roles) in blockValues) {
                val a = roles["A"].orEmpty()
                val b = roles["B"].orEmpty()
                val crossedThermalPause = blockThermalEpochs[block].orEmpty().size > 1
                val valid = a.size == 2 && b.size == 2 && a.sumOf(Long::toDouble) > 0.0 && !crossedThermalPause
                record(session, "paired_block", JSONObject().put("pair", pairKey).put("round", round).put("block", block)
                    .put("complete_abba", valid).put("baseline_case", pair.baseline.id).put("candidate_case", pair.candidate.id)
                    .put("crossed_thermal_pause", crossedThermalPause)
                    .put("comparison_kind", if (pair.baseline.id == pair.candidate.id) "repeatability_control" else "same_build_reference_comparison")
                    .put("baseline_samples_ns", JSONArray(a)).put("candidate_samples_ns", JSONArray(b))
                    .put("candidate_change_percent", if (valid) (b.average() / a.average() - 1.0) * 100.0 else JSONObject.NULL))
            }
        }

        private suspend fun awaitThermalRecovery(pairKey: String) {
            val pauseStarted = SystemClock.elapsedRealtimeNanos()
            var didPause = false
            while (thermal.update(probe.thermalStatus(), SystemClock.elapsedRealtimeNanos())) {
                if (!didPause) {
                    didPause = true
                    runCatching { hint?.close() }; hint = null
                    record(session, "thermal_pause", JSONObject().put("thermal_status", probe.thermalStatus()).put("pair", pairKey))
                }
                publish("Cooling down", "Heat reached the pause threshold. Resume after 30 seconds at light or no thermal pressure.")
                if (expired()) break
                delay(1000)
            }
            if (didPause) {
                thermalEpoch++
                val duration = SystemClock.elapsedRealtimeNanos() - pauseStarted
                pausedNs += duration
                record(session, "thermal_pause_end", JSONObject().put("duration_ns", duration).put("resumed", !thermal.paused))
            }
        }

        suspend fun finish() {
            runCatching { hint?.close() }; hint = null
            record(session, "placement_restore", NativeHardwareBench.placement("system"))
            val finalNs = SystemClock.elapsedRealtimeNanos()
            val summaries = JSONArray(values.mapNotNull { (key, times) ->
                TimingSummary.from(times)?.let {
                    JSONObject().put("group", key).put("count", it.count).put("median_ns", it.median)
                        .put("p90_ns", it.p90).put("p95_ns", it.p95).put("p99_ns", it.p99)
                }
            })
            val summary = JSONObject().put("schema_version", 1).put("session_id", session.name).put("status", outcome)
                .put("comparisons_planned", progress.pairsPlanned).put("comparisons_visited_last_round", progress.pairsCompleted)
                .put("reason", reason).put("elapsed_ns", finalNs - start).put("thermal_paused_ns", pausedNs)
                .put("counts", JSONObject(counts as Map<*, *>)).put("timings", summaries)
                .put("interpretation", "PASS is case-local correctness, not speedup or hardware execution evidence; inspect native selected paths and complete ABBA blocks.")
            withContext(Dispatchers.IO) {
                store.writeAtomic(session, "summary.json", summary.toString(2))
                manifest.put("status", outcome).put("reason", reason).put("finished_utc", Instant.now().toString())
                    .put("finished_monotonic_ns", finalNs).put("thermal_paused_ns", pausedNs)
                store.writeAtomic(session, "manifest.json", manifest.toString(2))
            }
            record(session, "session_end", summary)
            progress = progress.copy(running = false)
            publish(outcome.replace('_', ' ').replaceFirstChar(Char::uppercaseChar),
                "${counts["PASS"]} passed, ${counts["FAIL"]} failed, ${counts["SKIP"]} skipped. Export includes all raw observations.")
        }
    }

    private fun recoverInterruptedSessions() {
        for (session in store.sessions()) {
            val manifestFile = File(session, "manifest.json")
            val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
            if (manifest?.optString("status") == "running" || (!manifestFile.exists() && File(session, "events.ndjson").exists())) {
                val removed = store.recoverJournal(session)
                val recovered = (manifest ?: JSONObject().put("schema_version", 1).put("session_id", session.name))
                    .put("status", "interrupted").put("recovered_utc", Instant.now().toString()).put("discarded_torn_tail_bytes", removed)
                store.writeAtomic(session, "manifest.json", recovered.toString(2))
                store.append(session, JSONObject().put("schema_version", 1).put("event", "recovered_after_process_interruption")
                    .put("monotonic_ns", SystemClock.elapsedRealtimeNanos()).put("discarded_torn_tail_bytes", removed).toString())
            }
        }
    }
}
