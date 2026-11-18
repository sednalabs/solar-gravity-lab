package com.sednalabs.solarlab.benchmark

import kotlin.math.ceil

/** Scheduling and statistics are pure Kotlin so the experiment protocol is independently testable. */
enum class BenchmarkPlan(val title: String, val budgetSeconds: Long, val blocks: Int) {
    QUICK("Quick comparison", 180, 2),
    FULL("Full investigation", 600, 5),
    SUSTAINED("15 minute stability run", 900, 3),
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

    fun pairs(cases: List<BenchmarkCase>, plan: BenchmarkPlan, selectedCaseKey: String? = null): List<BenchmarkPair> {
        val byKey = cases.associateBy(BenchmarkCase::key)
        val runnable = cases.filter(BenchmarkCase::runnable)
        val preferred = setOf("production-selected", "source-specialised", "neon-block-4", "target-block-4", "workers-1", "kahan")
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
