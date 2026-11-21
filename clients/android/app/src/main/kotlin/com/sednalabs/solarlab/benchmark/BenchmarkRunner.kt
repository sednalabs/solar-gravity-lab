package com.sednalabs.solarlab.benchmark

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Debug
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

private data class BenchmarkObservedSample(
    val status: String,
    val elapsedNs: Long?,
    val effectiveRepeats: Int?,
    val thermalEpoch: Int,
    val nativeScope: String,
)

class BenchmarkRunner(private val context: Context, private val store: BenchmarkFileStore, private val frames: BenchmarkFrameProbe) {
    private val probe = BenchmarkDeviceProbe(context)
    private companion object {
        val executionLock = Mutex()
    }

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
        return executionLock.withLock {
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "solarlab-benchmark") }.asCoroutineDispatcher()
            try { withContext(worker) { execute(discovery, plan, options, onProgress) } } finally { worker.close() }
        }
    }

    private suspend fun record(session: File, type: String, value: JSONObject) = withContext(Dispatchers.IO) {
        store.append(session) { JSONObject().put("schema_version", 1).put("event", type)
            .put("monotonic_ns", SystemClock.elapsedRealtimeNanos()).put("wall_time_utc", Instant.now().toString())
            .put("data", value).toString() }
    }

    private fun createHint(): PerformanceHintManager.Session? = runCatching {
        context.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(Process.myTid()), 100_000_000L)
    }.getOrNull()

    private suspend fun execute(discovery: BenchmarkDiscovery, plan: BenchmarkPlan, options: BenchmarkOptions, onProgress: (BenchmarkProgress) -> Unit): File = coroutineScope {
        val session = withContext(Dispatchers.IO) { store.createSession(System.currentTimeMillis()) }
        val state = RunState(session, plan, options, onProgress)
        BenchmarkSessionOwnership.active.add(session.name)
        val placements = if (plan == BenchmarkPlan.FULL) listOf("system", "single", "big_cluster") else listOf("system")
        val tasks = BenchmarkSchedule.pairs(discovery.cases, plan, options.selectedCaseKey).flatMap { pair ->
            (if (pair.candidate.backend == "cpu") placements else listOf("system")).map { pair to it }
        }
        state.ledger = BenchmarkRoundLedger(tasks.map { (pair, placement) -> "${pair.key}/$placement" })
        state.progress = state.progress.copy(pairsPlanned = tasks.size)
        state.manifest.put("selected_case", discovery.cases.firstOrNull { it.key == options.selectedCaseKey }?.let {
            JSONObject().put("key", it.key).put("id", it.id).put("backend", it.backend)
                .put("baseline_case_id", it.baselineId ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
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
                var visitedThisRound = 0
                for (index in BenchmarkProtocol.roundOrder(tasks.size, round)) {
                    currentCoroutineContext().ensureActive()
                    if (state.expired() || state.blockBudgetReached()) break
                    val task = tasks[index]
                    if (state.ledger.isRetired("${task.first.key}/${task.second}")) continue
                    state.runPair(task.first, task.second, round)
                    visitedThisRound++
                    state.progress = state.progress.copy(pairsCompleted = state.ledger.visitedPairs)
                    state.publish("Comparing", "Round ${round + 1} · ${task.first.candidate.label}. ${state.ledger.attemptedBlocks} comparison blocks recorded.")
                }
                state.lastRoundVisited = visitedThisRound
                round++
                state.roundsVisited = round
            } while (!state.expired() && !state.blockBudgetReached() &&
                BenchmarkProtocol.needsAnotherRound(plan, state.activeObservationNs(), state.ledger.activeCompleteUnits(),
                    state.ledger.hasUnmatchedActiveUnits()))
            if (state.expired()) {
                if (store.journalBudgetReached(session)) {
                    state.outcome = "storage_budget_exhausted"
                    state.reason = "256_MiB_journal_budget_reached; completed_records_preserved"
                } else {
                    val targetsMet = BenchmarkProtocol.completionTargetsMet(plan, state.activeObservationNs(),
                        state.ledger.activeCompleteUnits(), state.ledger.hasUnmatchedActiveUnits())
                    state.outcome = if (targetsMet) "completed" else "budget_exhausted"
                    state.reason = if (targetsMet)
                        "elapsed_budget_reached_with_duration_and_complete_counterbalanced_unit_targets; offline_clean_unit_qualification_required"
                    else "elapsed_budget_reached_before_all_duration_and_counterbalanced_unit_targets; unmatched_blocks_and_target_shortfalls_retained"
                }
            } else if (state.blockBudgetReached()) {
                state.outcome = "sample_budget_exhausted"
                state.reason = "bounded_measured_block_budget_reached; target_shortfalls_retained"
            } else if (tasks.isNotEmpty() && state.ledger.activePairCount == 0) {
                state.outcome = if (state.progress.fail > 0) "failed" else "unavailable"
                state.reason = "all_pairs_retired; see_raw_calibration_warmup_and_block_failures"
            } else if (tasks.isNotEmpty()) {
                state.reason = "duration_and_complete_counterbalanced_unit_targets_reached; offline_clean_unit_qualification_required"
            }
        } catch (cancelled: CancellationException) {
            state.outcome = "cancelled"; state.reason = cancelled.message ?: "user_or_foreground_lifecycle_cancelled"
            throw cancelled
        } catch (error: Exception) {
            state.outcome = "failed"; state.reason = "${error.javaClass.simpleName}: ${error.message}"
            Log.e("SolarLabBenchmark", "run_failed session=${session.name}", error)
        } finally {
            try {
                withContext(NonCancellable) {
                    sampler?.cancelAndJoin()
                    state.finish()
                }
            } finally { BenchmarkSessionOwnership.active.remove(session.name) }
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
        lateinit var ledger: BenchmarkRoundLedger
        val fixedRepeats = mutableMapOf<String, Int>()
        val warmupCounts = linkedMapOf("PASS" to 0, "FAIL" to 0, "SKIP" to 0)
        var roundsVisited = 0
        var lastRoundVisited = 0
        val thermal = ThermalPausePolicy()
        var outcome = "completed"
        var reason = "all_scheduled_comparisons_completed"
        val thermalAccounting = BenchmarkThermalAccounting()
        val pausedNs: Long get() = thermalAccounting.pausedNs
        val thermalEpoch: Int get() = thermalAccounting.epoch
        var hint: PerformanceHintManager.Session? = null
        var lastUiPublishNs = 0L
        val manifest: JSONObject = JSONObject().put("schema_version", 1).put("session_id", session.name)
            .put("status", "running").put("plan", plan.name).put("started_utc", Instant.now().toString())
            .put("started_monotonic_ns", start).put("build_commit", BuildConfig.BUILD_COMMIT)
            .put("budget_seconds", plan.budgetSeconds).put("requested_display_mode_id", options.displayModeId)
            .put("journal_budget_bytes", BenchmarkFileStore.JOURNAL_BUDGET_BYTES)
            .put("selected_case_key", options.selectedCaseKey ?: JSONObject.NULL)
            .put("orientation_lock_requested", options.orientationLockRequested)
            .put("orientation_policy", "request_current_orientation_only_while_running; platform_may_ignore; onStop_cancels_and_retains_partial")
            .put("performance_hints_requested", options.performanceHints).put("benchmark_thread_tid", Process.myTid())
            .put("protocol_version", BenchmarkProtocol.VERSION)
            .put("protocol", "retained one-repeat A/B pilots; shared batch calibrated then A/B warmup; even-round A1,B1,B2,A2 then odd-round B1,A1,A2,B2 form one eight-sample counterbalanced unit; A remains baseline and B candidate; fixture and steps unchanged")
            .put("minimum_observation_seconds", plan.minimumObservationSeconds)
            .put("minimum_observation_scope", "session elapsed minus thermal pauses; includes native work, setup, correctness checks and recording; no idle duration padding")
            .put("minimum_complete_blocks_per_pair", plan.minimumCompleteBlocks)
            .put("minimum_complete_counterbalanced_units_per_pair", plan.minimumCompleteCounterbalancedUnits)
            .put("maximum_measured_blocks", plan.maximumMeasuredBlocks)
            .put("complete_block_scope", "four positive PASS samples at fixed repeats and one thermal epoch; offline environmental checks may exclude further blocks")
            .put("complete_counterbalanced_unit_scope", "two complementary consecutive-round blocks; eight positive PASS samples at fixed repeats and one thermal epoch; raw samples and offline qualification remain authoritative")
            .put("counterbalanced_estimator", "geometric_mean_of_complementary_block_B_over_A_ratios; resample_complete_units_not_individual_blocks_or_native_repeats")
            .put("round_policy", "one block per active pair before another round; deterministic rotating start; alternate ABBA and BAAB; normal completion closes active complementary units; unavailable/failed pair retirement never retires other pairs sharing its baseline")
            .put("timing_scope", "native elapsed_ns with native elapsed_scope; consult native timed-scope for scratch allocation/thread costs; Java telemetry and file IO excluded")
            .put("native_repeats", "calibrated_and_frozen_per_pair; inspect_pair_calibration_and_each_sample")
            .put("hardware_iterations", "calibrated_and_frozen_per_pair; native elapsed may be total-of-iterations")
            .put("maximum_native_repeats", BenchmarkProtocol.MAXIMUM_CPU_REPEATS)
            .put("maximum_hardware_iterations", BenchmarkProtocol.MAXIMUM_HARDWARE_ITERATIONS)
            .put("target_native_batch_ns", BenchmarkProtocol.TARGET_NATIVE_BATCH_NS)
            .put("thread_cpu_scope", "Debug.threadCpuTimeNanos around the JNI wrapper on the dedicated caller; includes JSON handling and native setup/oracle/checksum; excludes internal worker threads and GPU execution")
            .put("energy_scope", "whole-device observations include UI, telemetry and synchronous logging; no chip-power or joules-per-kernel claim")
            .put("sampling_interval_ms", 2000).put("thermal_headroom_interval_ms", 10000)
            .put("thermal_policy", "pause at SEVERE; resume after LIGHT or NONE for 30s; unchanged numerical settings")
            .put("background_policy", "cancel and retain partial data on activity stop; no background execution")
            .put("percentile_method", "nearest-rank; midpoint median; small cohorts do not estimate reliable tail distributions")
            .put("baseline_scope", "same-APK selected reference case, not an earlier-commit binary")
            .put("data_export", "local-only; explicit document save or Android share")

        fun expired(): Boolean = SystemClock.elapsedRealtimeNanos() >= deadline || store.journalBudgetReached(session)
        fun blockBudgetReached(): Boolean = ledger.attemptedBlocks >= plan.maximumMeasuredBlocks
        fun activeObservationNs(): Long = (SystemClock.elapsedRealtimeNanos() - start - pausedNs).coerceAtLeast(0)

        suspend fun publish(phase: String, detail: String) {
            val now = SystemClock.elapsedRealtimeNanos()
            val phaseChanged = phase != progress.phase
            progress = progress.copy(phase = phase, detail = detail, elapsedSeconds = (now - start) / 1_000_000_000L)
            if (progress.running && !phaseChanged && now - lastUiPublishNs < 250_000_000L) return
            lastUiPublishNs = now
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
            ledger.visit(pairKey)
            val placement = NativeHardwareBench.placement(placementMode)
            record(session, "placement", JSONObject().put("mode", placementMode).put("pair", pairKey).put("native", placement))
            // Restoring "system" can fail too. Never label a retained restricted
            // affinity as an unrestricted sample after a previous round.
            if (placement.optString("status").uppercase() != "PASS") {
                record(session, "case_admission", JSONObject().put("case_id", pair.candidate.id).put("pair", pairKey)
                    .put("status", "SKIP").put("reason", "requested_placement_unavailable"))
                retire(pairKey, "requested_placement_unavailable")
                return
            }
            publish("Comparing", "${pair.candidate.label} · ${pair.fixture} · ${pair.steps} steps · $placementMode")
            var repeats = fixedRepeats[pairKey]
            if (repeats == null) {
                val pilotA = sample(pair, placementMode, round, -2, "calibration-A", "calibration", 1) ?: return
                val pilotB = sample(pair, placementMode, round, -2, "calibration-B", "calibration", 1) ?: return
                if (pilotA.status != "PASS" || pilotB.status != "PASS") {
                    retire(pairKey, if (pilotA.status == "FAIL" || pilotB.status == "FAIL")
                        "calibration_failed; raw_failure_is_a_comparison_veto" else "calibration_unavailable")
                    return
                }
                val requested = BenchmarkProtocol.calibratedRepeats(pair.candidate.backend, pilotA.elapsedNs, pilotB.elapsedNs,
                    sustained = plan == BenchmarkPlan.SUSTAINED)
                repeats = requested
                var warmA = sample(pair, placementMode, round, -1, "warmup-A", "warmup", repeats) ?: return
                var warmB = sample(pair, placementMode, round, -1, "warmup-B", "warmup", repeats) ?: return
                if (warmA.status != "PASS" || warmB.status != "PASS") {
                    retire(pairKey, if (warmA.status == "FAIL" || warmB.status == "FAIL")
                        "warmup_failed; raw_failure_is_a_comparison_veto" else "warmup_unavailable")
                    return
                }
                // Some hardware primitives deliberately clamp iterations. Admit only a
                // common effective batch and rewarm BOTH roles if its size changes.
                val effective = BenchmarkProtocol.commonEffectiveRepeats(requested, warmA.effectiveRepeats, warmB.effectiveRepeats)
                if (effective == null) {
                    retire(pairKey, "warmup_effective_repeat_count_out_of_requested_bounds")
                    return
                }
                if (effective != requested) {
                    repeats = effective
                    warmA = sample(pair, placementMode, round, -3, "warmup-A", "warmup", repeats) ?: return
                    warmB = sample(pair, placementMode, round, -3, "warmup-B", "warmup", repeats) ?: return
                }
                if (warmA.status != "PASS" || warmB.status != "PASS" ||
                    warmA.effectiveRepeats != repeats || warmB.effectiveRepeats != repeats) {
                    retire(pairKey, "warmup_or_effective_repeat_mismatch; no_measured_comparison_admitted")
                    return
                }
                fixedRepeats[pairKey] = repeats
                record(session, "pair_calibration", JSONObject().put("pair", pairKey).put("protocol_version", BenchmarkProtocol.VERSION)
                    .put("target_native_batch_ns", BenchmarkProtocol.TARGET_NATIVE_BATCH_NS)
                    .put("pilot_baseline_elapsed_ns", pilotA.elapsedNs).put("pilot_candidate_elapsed_ns", pilotB.elapsedNs)
                    .put("requested_repeats", requested).put("fixed_repeats", repeats)
                    .put("max_repeats", if (pair.candidate.backend == "cpu") BenchmarkProtocol.MAXIMUM_CPU_REPEATS else BenchmarkProtocol.MAXIMUM_HARDWARE_ITERATIONS)
                    .put("baseline_native_scope", pilotA.nativeScope).put("candidate_native_scope", pilotB.nativeScope)
                    .put("clamp_rewarmup", requested != repeats)
                    .put("calibration_policy", if (plan == BenchmarkPlan.SUSTAINED) "bounded_sustained_batch" else "slower_one_repeat_native_pilot")
                    .put("qualification", "batch_target_is_an_estimate_not_a_guarantee; fixed_per_pair_across_rounds; native_elapsed_scope_preserved; no_timestep_change"))
            }
            val batchRepeats = requireNotNull(repeats)
            if (expired() || blockBudgetReached()) return
            val observations = mutableListOf<BenchmarkObservedSample>()
            val orders = BenchmarkSchedule.blockOrder(round)
            ledger.beginBlock(pairKey, round)
            for (order in orders) {
                observations += sample(pair, placementMode, round, 0, order, "measurement", batchRepeats) ?: break
            }
            val complete = observations.size == 4 && observations.all {
                it.status == "PASS" && it.elapsedNs != null && it.elapsedNs > 0 && it.effectiveRepeats == batchRepeats
            } && observations.map { it.thermalEpoch }.distinct().size == 1
            val blockEpoch = observations.map { it.thermalEpoch }.distinct().singleOrNull()
            val completedUnit = ledger.recordBlock(pairKey, round, complete, blockEpoch)
            val a = orders.zip(observations).filter { it.first.startsWith("A") }.map { it.second.elapsedNs }
            val b = orders.zip(observations).filter { it.first.startsWith("B") }.map { it.second.elapsedNs }
            record(session, "paired_block", JSONObject().put("pair", pairKey).put("protocol_version", BenchmarkProtocol.VERSION)
                .put("round", round).put("block", 0).put("counterbalanced_unit", round / 2)
                .put("block_order", BenchmarkSchedule.blockOrderName(round)).put("chronological_order", JSONArray(orders))
                .put("thermal_epoch", blockEpoch ?: JSONObject.NULL)
                .put("complete_balanced_block", complete).put("baseline_case", pair.baseline.id).put("candidate_case", pair.candidate.id)
                .put("repeats", batchRepeats).put("crossed_thermal_pause", observations.map { it.thermalEpoch }.distinct().size > 1)
                .put("comparison_kind", if (pair.baseline.id == pair.candidate.id) "repeatability_control" else "same_build_reference_comparison")
                .put("baseline_samples_ns", JSONArray(a.map { it ?: JSONObject.NULL })).put("candidate_samples_ns", JSONArray(b.map { it ?: JSONObject.NULL }))
                .put("candidate_change_percent", if (complete)
                    (kotlin.math.sqrt(b[0]!!.toDouble() / a[0]!! * (b[1]!!.toDouble() / a[1]!!)) - 1.0) * 100.0 else JSONObject.NULL))
            if (completedUnit != null) {
                record(session, "counterbalanced_unit", JSONObject().put("pair", pairKey)
                    .put("protocol_version", BenchmarkProtocol.VERSION).put("unit", completedUnit.unit)
                    .put("rounds", JSONArray(listOf(round - 1, round))).put("block_orders", JSONArray(listOf("ABBA", "BAAB")))
                    .put("complete_counterbalanced_unit", completedUnit.complete).put("reason", completedUnit.reason)
                    .put("thermal_epoch", completedUnit.thermalEpoch ?: JSONObject.NULL))
            }
            if (observations.any { it.status != "PASS" }) {
                retire(pairKey, if (observations.any { it.status == "FAIL" }) "measured_correctness_or_protocol_failure" else "measured_case_unavailable")
            } else if (observations.size == 4 && observations.any { it.elapsedNs == null || it.elapsedNs <= 0 }) {
                retire(pairKey, "nonpositive_native_timing_at_fixed_batch; comparison_unmeasurable")
            }
        }

        private suspend fun retire(pair: String, reason: String) {
            ledger.retire(pair, reason)
            record(session, "pair_retired", JSONObject().put("pair", pair).put("reason", reason)
                .put("scope", "this_fixture_steps_placement_pair_only; shared_baselines_are_not_globally_retired"))
        }

        private suspend fun sample(
            pair: BenchmarkPair, placement: String, round: Int, block: Int, order: String, phase: String, repeats: Int,
        ): BenchmarkObservedSample? {
            currentCoroutineContext().ensureActive()
            val pairKey = "${pair.key}/$placement"
            awaitThermalRecovery(pairKey)
            if (expired()) return null
            val role = if (order.endsWith("A") || order.startsWith("A")) "A" else "B"
            val case = if (role == "A") pair.baseline else pair.candidate
            val warmup = phase != "measurement"
            val unit: Any = if (warmup) JSONObject.NULL else round / 2
            val blockOrder: Any = if (warmup) JSONObject.NULL else BenchmarkSchedule.blockOrderName(round)
            val position: Any = if (warmup) JSONObject.NULL else BenchmarkSchedule.blockOrder(round).indexOf(order)
            val before = probe.cachedSample()
            if (options.performanceHints && hint == null && case.backend == "cpu") hint = createHint()
            if (case.backend != "cpu") { runCatching { hint?.close() }; hint = null }
            val tid = Process.myTid()
            val sampleId = "$pairKey/$round/$block/$order"
            record(session, "sample_start", JSONObject().put("sample_id", sampleId).put("pair", pairKey).put("case_id", case.id)
                .put("backend", case.backend).put("fixture_id", pair.fixture).put("steps", pair.steps)
                .put("placement", placement).put("repeats", repeats).put("round", round).put("block", block)
                .put("protocol_version", BenchmarkProtocol.VERSION).put("counterbalanced_unit", unit)
                .put("block_order", blockOrder).put("position_in_block", position).put("thermal_epoch", thermalEpoch)
                .put("phase", phase).put("warmup", warmup).put("order", order).put("thread_tid", tid).put("native_invocation_planned", true))
            val wrapperStart = SystemClock.elapsedRealtimeNanos()
            val cpuStart = runCatching { Debug.threadCpuTimeNanos() }.getOrNull()
            val result = if (case.backend == "cpu") NativeBridge.run(case.id, pair.fixture, pair.steps, repeats)
                else NativeHardwareBench.run(case.id, repeats)
            val cpuEnd = runCatching { Debug.threadCpuTimeNanos() }.getOrNull()
            val wrapperNs = SystemClock.elapsedRealtimeNanos() - wrapperStart
            val threadCpuNs = if (cpuStart != null && cpuEnd != null && cpuStart >= 0 && cpuEnd >= cpuStart) cpuEnd - cpuStart else null
            runCatching { hint?.reportActualWorkDuration(wrapperNs.coerceAtLeast(1)) }
            val (nativeStatus, elapsed) = validatedMeasurement(result)
            val effectiveValue = result.opt(if (case.backend == "cpu") "repeats" else "iterations") as? Number
            val effectiveRepeats = effectiveValue?.toDouble()?.takeIf { it.isFinite() && it >= 1 && it <= 32 && it % 1.0 == 0.0 }?.toInt()
            val status = if (nativeStatus == "PASS" && (effectiveRepeats == null ||
                (phase == "calibration" && effectiveRepeats != 1) || (!warmup && effectiveRepeats != repeats))) {
                result.put("protocol_validation_error", "effective native repeat count missing or differs from frozen measured batch")
                "FAIL"
            } else nativeStatus
            if (nativeStatus != result.optString("status").uppercase()) {
                result.put("protocol_validation_error", "PASS requires a finite nonnegative native elapsed_ns; wrapper time is never substituted")
            }
            val nativeScope = result.optString("elapsed_scope", result.optString("timing_scope", "native_declared_scope"))
            val targetCounts = if (warmup) warmupCounts else counts
            targetCounts[status] = targetCounts.getValue(status) + 1
            if (!warmup && elapsed != null && status == "PASS") values.getOrPut("$pairKey/$role") { mutableListOf() } += elapsed
            record(session, "sample", JSONObject().put("sample_id", sampleId).put("pair", pairKey).put("case_id", case.id).put("backend", case.backend)
                .put("fixture_id", pair.fixture).put("steps", pair.steps).put("repeats", repeats).put("effective_repeats", effectiveRepeats ?: JSONObject.NULL)
                .put("round", round).put("block", block).put("order", order).put("role", role).put("phase", phase).put("warmup", warmup).put("placement", placement)
                .put("protocol_version", BenchmarkProtocol.VERSION).put("counterbalanced_unit", unit)
                .put("block_order", blockOrder).put("position_in_block", position).put("thermal_epoch", thermalEpoch)
                .put("status", status).put("elapsed_ns", elapsed ?: JSONObject.NULL).put("jni_wrapper_elapsed_ns", wrapperNs)
                .put("thread_cpu_elapsed_ns", threadCpuNs ?: JSONObject.NULL)
                .put("thread_cpu_scope", "dedicated_calling_thread; JNI_wrapper_and_JSON_handling_including_native_setup_oracle_checksum; excludes_internal_worker_threads_and_GPU")
                .put("hint_session_created", hint != null).put("hint_target_ns", if (hint != null) 100_000_000L else JSONObject.NULL).put("hint_thread_tid", tid)
                .put("hint_scope", "dedicated_boundary_thread_only; internal_native_workers_not_in_session").put("elapsed_scope", nativeScope)
                .put("kernel_total_ns", result.opt("kernel_total_ns") ?: JSONObject.NULL)
                .put("environment_before", before).put("environment_after", probe.cachedSample()).put("native", result))
            progress = progress.copy(pass = counts.getValue("PASS"), fail = counts.getValue("FAIL") + warmupCounts.getValue("FAIL"),
                skip = counts.getValue("SKIP") + warmupCounts.getValue("SKIP"))
            return BenchmarkObservedSample(status, elapsed, effectiveRepeats, thermalEpoch, nativeScope)
        }

        private suspend fun awaitThermalRecovery(pairKey: String) {
            if (!thermal.update(probe.thermalStatus(), SystemClock.elapsedRealtimeNanos())) return
            thermalAccounting.observePause(SystemClock::elapsedRealtimeNanos, onEnd = { duration ->
                record(session, "thermal_pause_end", JSONObject().put("duration_ns", duration)
                    .put("resumed", !thermal.paused).put("thermal_epoch", thermalEpoch))
            }) {
                runCatching { hint?.close() }; hint = null
                record(session, "thermal_pause", JSONObject().put("thermal_status", probe.thermalStatus()).put("pair", pairKey))
                while (thermal.paused) {
                    publish("Cooling down", "Heat reached the pause threshold. Resume after 30 seconds at light or no thermal pressure.")
                    if (expired()) break
                    delay(1000)
                    thermal.update(probe.thermalStatus(), SystemClock.elapsedRealtimeNanos())
                }
            }
        }

        suspend fun finish() {
            runCatching { hint?.close() }; hint = null
            record(session, "placement_restore", NativeHardwareBench.placement("system"))
            val finalNs = SystemClock.elapsedRealtimeNanos()
            val activeNs = (finalNs - start - pausedNs).coerceAtLeast(0)
            val blocks = ledger.completedSnapshot()
            val units = ledger.completedUnitsSnapshot()
            val retired = ledger.retiredSnapshot()
            val belowMinimum = blocks.filter { (pair, count) -> pair !in retired && count < plan.minimumCompleteBlocks }
            val belowMinimumUnits = units.filter { (pair, count) -> pair !in retired && count < plan.minimumCompleteCounterbalancedUnits }
            val durationMet = activeNs >= plan.minimumObservationSeconds * 1_000_000_000L
            val summaries = JSONArray(values.mapNotNull { (key, times) ->
                TimingSummary.from(times)?.let {
                    JSONObject().put("group", key).put("count", it.count).put("median_ns", it.median)
                        .put("p90_ns", it.p90).put("p95_ns", it.p95).put("p99_ns", it.p99)
                }
            })
            val summary = JSONObject().put("schema_version", 1).put("session_id", session.name).put("status", outcome)
                .put("protocol_version", BenchmarkProtocol.VERSION).put("comparisons_planned", progress.pairsPlanned)
                .put("comparisons_visited", ledger.visitedPairs).put("comparisons_visited_last_round", lastRoundVisited)
                .put("rounds_visited", roundsVisited).put("measured_blocks_attempted", ledger.attemptedBlocks)
                .put("complete_blocks_by_pair", JSONObject(blocks as Map<*, *>))
                .put("complete_counterbalanced_units_by_pair", JSONObject(units as Map<*, *>))
                .put("unmatched_counterbalanced_units_by_pair", JSONObject(ledger.unmatchedUnitsSnapshot() as Map<*, *>))
                .put("retired_pairs", JSONObject(retired as Map<*, *>))
                .put("active_pairs_below_minimum_blocks", JSONObject(belowMinimum as Map<*, *>))
                .put("active_pairs_below_minimum_counterbalanced_units", JSONObject(belowMinimumUnits as Map<*, *>))
                .put("minimum_complete_blocks_per_pair", plan.minimumCompleteBlocks)
                .put("minimum_complete_counterbalanced_units_per_pair", plan.minimumCompleteCounterbalancedUnits)
                .put("minimum_block_target_met", ledger.activePairCount > 0 && belowMinimum.isEmpty())
                .put("minimum_counterbalanced_unit_target_met", ledger.activePairCount > 0 && belowMinimumUnits.isEmpty())
                .put("all_active_counterbalanced_units_closed", !ledger.hasUnmatchedActiveUnits())
                .put("active_observation_ns", activeNs).put("minimum_observation_seconds", plan.minimumObservationSeconds)
                .put("duration_target_met", durationMet)
                .put("reason", reason).put("elapsed_ns", finalNs - start).put("thermal_paused_ns", pausedNs)
                .put("counts", JSONObject(counts as Map<*, *>)).put("warmup_and_calibration_counts", JSONObject(warmupCounts as Map<*, *>))
                .put("count_scope", "counts=measured_samples; warmup_and_calibration_counts=retained_premeasurement_samples; any_failure_vetoes_a_claim")
                .put("timings", summaries)
                .put("journal_writer", journalWriterSnapshot(store.journalIoStatistics(session)))
                .put("interpretation", "Complete counterbalanced units are not automatically clean independent samples; require offline environmental qualification and repeated sessions. Unmatched blocks are retained but never count as completed units. Native repeats are subsamples. PASS is case-local correctness, not speedup or hardware execution evidence.")
            withContext(Dispatchers.IO) {
                store.writeAtomic(session, "summary.json", summary.toString(2))
                manifest.put("status", outcome).put("reason", reason).put("finished_utc", Instant.now().toString())
                    .put("finished_monotonic_ns", finalNs).put("thermal_paused_ns", pausedNs)
                    .put("duration_target_met", durationMet).put("active_observation_ns", activeNs)
                    .put("minimum_block_target_met", ledger.activePairCount > 0 && belowMinimum.isEmpty())
                    .put("minimum_counterbalanced_unit_target_met", ledger.activePairCount > 0 && belowMinimumUnits.isEmpty())
                    .put("all_active_counterbalanced_units_closed", !ledger.hasUnmatchedActiveUnits())
                    .put("measured_blocks_attempted", ledger.attemptedBlocks).put("rounds_visited", roundsVisited)
                store.writeAtomic(session, "manifest.json", manifest.toString(2))
            }
            record(session, "session_end", summary)
            progress = progress.copy(running = false)
            publish(outcome.replace('_', ' ').replaceFirstChar(Char::uppercaseChar),
                "${counts["PASS"]} measured passes, ${progress.fail} failures, ${progress.skip} skips. Export includes calibration, warmup and target shortfalls.")
        }
    }

    private fun recoverInterruptedSessions() {
        for (session in store.sessions()) {
            if (session.name in BenchmarkSessionOwnership.active) continue
            val manifestFile = File(session, "manifest.json")
            val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
            if (manifest?.optString("status") == "running" || (!manifestFile.exists() && File(session, "events.ndjson").exists())) {
                val removed = store.recoverJournal(session)
                val recovered = (manifest ?: JSONObject().put("schema_version", 1).put("session_id", session.name))
                    .put("status", "interrupted").put("recovered_utc", Instant.now().toString()).put("discarded_torn_tail_bytes", removed)
                    .put("recovered_monotonic_ns", SystemClock.elapsedRealtimeNanos())
                    .put("recovery_clock_scope", "current_boot; may differ from recorded session boot; journal clocks remain unchanged")
                store.writeAtomic(session, "manifest.json", recovered.toString(2))
            }
        }
    }
}

internal fun journalWriterSnapshot(statistics: JournalIoStatistics): JSONObject = JSONObject()
    .put("completed_records", statistics.records).put("bytes", statistics.bytes)
    .put("elapsed_ns", statistics.elapsedNs).put("maximum_append_ns", statistics.maximumAppendNs)
    .put("scope", "journal_record_serialization_UTF8_open_write_flush_fsync_wall_time; excludes_lock_wait_metadata_IO_and_current_summary_record")
    .put("interpretation", "overhead_observation; do_not_subtract_from_native_kernel_time_or_assign_battery_energy_to_it")
