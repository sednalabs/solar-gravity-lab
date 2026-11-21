package com.sednalabs.solarlab.benchmark

import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Scheduling and statistics are pure Kotlin so the experiment protocol is independently testable. */
enum class BenchmarkPlan(
    val title: String,
    val budgetSeconds: Long,
    val minimumObservationSeconds: Long,
    val minimumCompleteCounterbalancedUnits: Int,
    val maximumMeasuredBlocks: Int,
) {
    QUICK("Quick comparison", 180, 30, 5, 4096),
    FULL("Full investigation", 600, 120, 5, 8192),
    SUSTAINED("15 minute stability run", 900, 900, 5, 65536);

    val minimumCompleteBlocks: Int get() = minimumCompleteCounterbalancedUnits * 2
}

/** Bounds native batching without changing fixture, timestep or scientific horizon. */
object BenchmarkProtocol {
    const val VERSION = 3
    const val TARGET_NATIVE_BATCH_NS = 5_000_000L
    const val MAXIMUM_CPU_REPEATS = 11
    // The pipeline-cache workload has a stricter cap than the native API's 32.
    const val MAXIMUM_HARDWARE_ITERATIONS = 16

    fun calibratedRepeats(backend: String, baselineNs: Long?, candidateNs: Long?, sustained: Boolean = false): Int {
        val maximum = if (backend == "cpu") MAXIMUM_CPU_REPEATS else MAXIMUM_HARDWARE_ITERATIONS
        if (sustained) return maximum
        if (baselineNs == null || candidateNs == null || baselineNs < 0 || candidateNs < 0) return 1
        val slower = maxOf(baselineNs, candidateNs)
        if (slower <= 0) return maximum
        // This division form cannot overflow even for a Long.MAX_VALUE pilot.
        val needed = TARGET_NATIVE_BATCH_NS / slower + if (TARGET_NATIVE_BATCH_NS % slower == 0L) 0 else 1
        return needed.coerceIn(1, maximum.toLong()).toInt()
    }

    /** Downward hardware clamps can be reconciled only by rewarming both roles at the common batch. */
    fun commonEffectiveRepeats(requested: Int, baseline: Int?, candidate: Int?): Int? =
        if (requested < 1 || baseline == null || candidate == null || baseline !in 1..requested || candidate !in 1..requested) null
        else minOf(baseline, candidate)

    /** Every task gets one block before the next round; rotation avoids a permanent first slot. */
    fun roundOrder(taskCount: Int, round: Int): List<Int> {
        if (taskCount <= 0) return emptyList()
        val start = Math.floorMod(round, taskCount)
        return List(taskCount) { ((start.toLong() + it) % taskCount).toInt() }
    }

    fun needsAnotherRound(
        plan: BenchmarkPlan, activeObservationNs: Long, completeUnits: Collection<Int>, hasUnmatchedUnits: Boolean = false,
    ): Boolean = completeUnits.isNotEmpty() && !completionTargetsMet(plan, activeObservationNs, completeUnits, hasUnmatchedUnits)

    fun completionTargetsMet(
        plan: BenchmarkPlan, activeObservationNs: Long, completeUnits: Collection<Int>, hasUnmatchedUnits: Boolean,
    ): Boolean = completeUnits.isNotEmpty() && activeObservationNs >= plan.minimumObservationSeconds * 1_000_000_000L &&
        completeUnits.all { it >= plan.minimumCompleteCounterbalancedUnits } && !hasUnmatchedUnits
}

/** Closing an interrupted cooldown must still exclude its wall time from active observation. */
class BenchmarkThermalAccounting {
    var pausedNs: Long = 0
        private set
    var epoch: Int = 0
        private set

    suspend fun observePause(nowNs: () -> Long, onEnd: suspend (Long) -> Unit, waitForRecovery: suspend () -> Unit) {
        val started = nowNs()
        try {
            waitForRecovery()
        } finally {
            val duration = (nowNs() - started).coerceAtLeast(0)
            pausedNs += duration
            epoch++
            // Update accounting before journal I/O: even a failed closure write
            // must not turn cooldown time into active observation.
            withContext(NonCancellable) { onEnd(duration) }
        }
    }
}

data class BenchmarkCounterbalancedUnit(val unit: Int, val complete: Boolean, val reason: String, val thermalEpoch: Int?)

/** A failure retires its fixture/steps/placement pair, never a shared baseline in other pairs. */
class BenchmarkRoundLedger(pairKeys: Collection<String>) {
    private val completed = pairKeys.associateWith { 0 }.toMutableMap()
    private val completedUnits = pairKeys.associateWith { 0 }.toMutableMap()
    private data class FirstBlock(val round: Int, val complete: Boolean, val thermalEpoch: Int?)
    private val firstBlocks = mutableMapOf<String, FirstBlock>()
    private val activeBlocks = mutableMapOf<String, Int>()
    private val lastRounds = mutableMapOf<String, Int>()
    private val retired = linkedMapOf<String, String>()
    private val visited = mutableSetOf<String>()
    var attemptedBlocks: Int = 0
        private set
    val visitedPairs: Int get() = visited.size
    val activePairCount: Int get() = completed.size - retired.size

    fun visit(pair: String) { require(pair in completed); visited += pair }
    fun isRetired(pair: String): Boolean = pair in retired
    fun retire(pair: String, reason: String) { require(pair in completed); retired.putIfAbsent(pair, reason) }
    fun beginBlock(pair: String, round: Int) {
        require(pair in completed && pair !in activeBlocks)
        require(round >= 0 && (lastRounds[pair]?.let { round > it } ?: true))
        activeBlocks[pair] = round
        attemptedBlocks++
    }
    fun recordBlock(pair: String, round: Int, complete: Boolean, thermalEpoch: Int?): BenchmarkCounterbalancedUnit? {
        require(pair in completed)
        require(round >= 0 && (lastRounds[pair]?.let { round > it } ?: true))
        require(!complete || (thermalEpoch != null && thermalEpoch >= 0))
        if (pair !in activeBlocks) beginBlock(pair, round)
        require(activeBlocks[pair] == round)
        activeBlocks.remove(pair)
        lastRounds[pair] = round
        if (complete) completed[pair] = completed.getValue(pair) + 1
        if (round % 2 == 0) {
            firstBlocks[pair] = FirstBlock(round, complete, thermalEpoch)
            return null
        }
        val first = firstBlocks.remove(pair)
        val reason = when {
            first == null || first.round != round - 1 -> "missing_complementary_first_block"
            !first.complete -> "incomplete_first_block"
            !complete -> "incomplete_second_block"
            first.thermalEpoch != thermalEpoch -> "thermal_epoch_changed_between_blocks"
            else -> "eight_positive_PASS_samples_at_fixed_batch_and_one_thermal_epoch; offline_qualification_required"
        }
        val unitComplete = first != null && first.round == round - 1 && first.complete && complete && first.thermalEpoch == thermalEpoch
        if (unitComplete) completedUnits[pair] = completedUnits.getValue(pair) + 1
        return BenchmarkCounterbalancedUnit(round / 2, unitComplete, reason, if (unitComplete) thermalEpoch else null)
    }
    fun activeCompleteBlocks(): List<Int> = completed.filterKeys { it !in retired }.values.toList()
    fun activeCompleteUnits(): List<Int> = completedUnits.filterKeys { it !in retired }.values.toList()
    fun hasUnmatchedActiveUnits(): Boolean = (firstBlocks.keys + activeBlocks.keys).any { it !in retired }
    fun completedSnapshot(): Map<String, Int> = completed.toMap()
    fun completedUnitsSnapshot(): Map<String, Int> = completedUnits.toMap()
    fun unmatchedUnitsSnapshot(): Map<String, Int> =
        firstBlocks.mapValues { it.value.round / 2 } + activeBlocks.mapValues { it.value / 2 }
    fun retiredSnapshot(): Map<String, String> = retired.toMap()
}

data class BenchmarkOptions(
    val performanceHints: Boolean = false,
    val displayModeId: Int = 0,
    val selectedCaseKey: String? = null,
    val orientationLockRequested: Boolean = false,
)

data class BenchmarkCase(
    val id: String,
    val label: String,
    val family: String,
    val status: String,
    val reason: String,
    val backend: String,
    val baselineId: String?,
    val fixtures: List<String>,
) {
    val runnable: Boolean get() = status.lowercase() in setOf("runnable", "implemented", "available", "ready")
    val key: String get() = "$backend:$id"
}

data class BenchmarkPair(
    val baseline: BenchmarkCase,
    val candidate: BenchmarkCase,
    val fixture: String,
    val steps: Int,
) {
    val key: String get() = "${candidate.key}/$fixture/$steps"
}

object BenchmarkSchedule {
    val fullSteps: List<Int> = listOf(1, 2, 4, 8, 16, 32, 64)
    val abba: List<String> = listOf("A1", "B1", "B2", "A2")
    val baab: List<String> = listOf("B1", "A1", "A2", "B2")

    /** Complementary rounds put each role in every chronological position once per eight-sample unit. */
    fun blockOrder(round: Int): List<String> {
        require(round >= 0)
        return if (round % 2 == 0) abba else baab
    }

    fun blockOrderName(round: Int): String = if (blockOrder(round) == abba) "ABBA" else "BAAB"

    fun pairs(cases: List<BenchmarkCase>, plan: BenchmarkPlan, selectedCaseKey: String? = null): List<BenchmarkPair> {
        val byKey = cases.associateBy(BenchmarkCase::key)
        val runnable = cases.filter(BenchmarkCase::runnable)
        // Use the existing quick-plan slot for the hardware refinement when
        // available. Other architectures retain the portable block comparison.
        val blockComparison = if (runnable.any { it.key == "cpu:neon-rsqrt-refine-3" })
            "neon-rsqrt-refine-3" else "target-block-4"
        val preferred = setOf("production-selected", "source-specialised", "neon-block-4", blockComparison, "workers-1", "kahan")
        val hardwareQuick = setOf("gpu.passive-fp32", "gpu.compact-subgroup", "native.prefetch-4", "native.vector-fp64", "native.fcma-f64", "native.sve-fp64", "gpu.visual-fp16", "gpu.pipeline-cache", "gpu.transfer-persistent")
        val selected = if (selectedCaseKey != null) runnable.filter { it.key == selectedCaseKey } else when (plan) {
            BenchmarkPlan.QUICK -> runnable.filter { it.id in preferred || it.id in hardwareQuick }.take(15)
                .ifEmpty { runnable.take(8) }
            BenchmarkPlan.FULL -> runnable
            BenchmarkPlan.SUSTAINED -> runnable.filter { it.id in setOf("production-selected", "workers-1", "gpu.passive-fp32", "gpu.compact-subgroup") }
                .ifEmpty { runnable.take(2) }
        }
        val perCase = selected.map { candidate ->
            val baseline = candidate.baselineId?.let { byKey["${candidate.backend}:$it"] }
                ?.takeIf(BenchmarkCase::runnable) ?: candidate
            val commonFixtures = candidate.fixtures.filter { it in baseline.fixtures }
                .ifEmpty { if (candidate.backend == "hardware") listOf("native-fixed") else emptyList() }
            val fixtures = when {
                plan == BenchmarkPlan.FULL -> commonFixtures
                plan == BenchmarkPlan.SUSTAINED && candidate.backend == "cpu" ->
                    (commonFixtures.take(1) + commonFixtures.filter { it == "dense256" }).distinct()
                else -> commonFixtures.take(1)
            }
            val steps = when {
                candidate.backend == "hardware" -> listOf(1)
                plan == BenchmarkPlan.QUICK -> listOf(1, 8, 64)
                plan == BenchmarkPlan.SUSTAINED -> listOf(64)
                else -> fullSteps
            }
            fixtures.flatMap { fixture -> steps.map { BenchmarkPair(baseline, candidate, fixture, it) } }
        }
        // Spread the finite run budget across candidates before adding more step/fixture cohorts.
        // A costly early candidate must not prevent all later GPU/scientific cases from being visited.
        val longest = perCase.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { index -> perCase.mapNotNull { it.getOrNull(index) } }
    }
}

data class TimingSummary(val count: Int, val median: Double, val p90: Long, val p95: Long, val p99: Long) {
    companion object {
        fun from(values: List<Long>): TimingSummary? {
            val sorted = values.filter { it >= 0 }.sorted()
            if (sorted.isEmpty()) return null
            fun percentile(p: Double): Long = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) sorted[middle - 1] / 2.0 + sorted[middle] / 2.0 else sorted[middle].toDouble()
            return TimingSummary(sorted.size, median, percentile(0.9), percentile(0.95), percentile(0.99))
        }
    }
}

/** Android thermal states: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
class ThermalPausePolicy(private val recoveryDwellNs: Long = 30_000_000_000L) {
    var paused: Boolean = false
        private set
    private var recoverySince: Long? = null

    fun update(status: Int, nowNs: Long): Boolean {
        if (status >= 3) {
            paused = true
            recoverySince = null
        } else if (paused) {
            if (status in 0..1) {
                val start = recoverySince ?: nowNs.also { recoverySince = it }
                if (nowNs - start >= recoveryDwellNs) {
                    paused = false
                    recoverySince = null
                }
            } else {
                recoverySince = null
            }
        }
        return paused
    }
}
