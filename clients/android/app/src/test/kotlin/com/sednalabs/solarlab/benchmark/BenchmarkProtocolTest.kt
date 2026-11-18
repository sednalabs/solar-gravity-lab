package com.sednalabs.solarlab.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkProtocolTest {
    private fun case(id: String, status: String = "runnable", baseline: String? = null, fixtures: List<String> = listOf("sparse3")) =
        BenchmarkCase(id, id, "cpu", status, "", "cpu", baseline, fixtures)

    @Test fun calibrationUsesSlowerPilotAndHonoursBothNativeCapsWithoutOverflow() {
        assertEquals(5, BenchmarkProtocol.calibratedRepeats("cpu", 10_000, 1_000_000))
        assertEquals(5, BenchmarkProtocol.calibratedRepeats("cpu", 1_000_000, 10_000))
        assertEquals(11, BenchmarkProtocol.calibratedRepeats("cpu", 1, 2))
        assertEquals(16, BenchmarkProtocol.calibratedRepeats("hardware", 1, 2))
        assertEquals(1, BenchmarkProtocol.calibratedRepeats("cpu", Long.MAX_VALUE, 1))
        assertEquals(11, BenchmarkProtocol.calibratedRepeats("cpu", 0, 0))
        assertEquals(1, BenchmarkProtocol.calibratedRepeats("cpu", null, 1))
        assertEquals(1, BenchmarkProtocol.calibratedRepeats("hardware", -1, 1))
        assertEquals(11, BenchmarkProtocol.calibratedRepeats("cpu", Long.MAX_VALUE, 1, sustained = true))
    }

    @Test fun commonBatchRequiresBothEffectiveCountsAndNeverExpandsAnAdmittedRequest() {
        assertEquals(4, BenchmarkProtocol.commonEffectiveRepeats(16, 16, 4))
        assertEquals(4, BenchmarkProtocol.commonEffectiveRepeats(16, 4, 16))
        assertEquals(11, BenchmarkProtocol.commonEffectiveRepeats(11, 11, 11))
        assertNull(BenchmarkProtocol.commonEffectiveRepeats(16, null, 4))
        assertNull(BenchmarkProtocol.commonEffectiveRepeats(16, 4, 0))
        assertNull(BenchmarkProtocol.commonEffectiveRepeats(11, 12, 11))
    }

    @Test fun durationAndCompleteBlockTargetsAreIndependentAndFinite() {
        val second = 1_000_000_000L
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 29 * second, listOf(5, 5)))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 31 * second, listOf(5, 4)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30 * second, listOf(5, 5)))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.FULL, 119 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.FULL, 120 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.SUSTAINED, 900 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 0, emptyList()))
        assertTrue(BenchmarkPlan.QUICK.maximumMeasuredBlocks < BenchmarkPlan.SUSTAINED.maximumMeasuredBlocks)
        assertEquals(180L, BenchmarkPlan.QUICK.budgetSeconds)
        assertEquals(600L, BenchmarkPlan.FULL.budgetSeconds)
    }

    @Test fun roundsVisitEveryPairBeforeRepeatingAndRotateTheFirstSlot() {
        assertEquals(listOf(0, 1, 2, 3, 4), BenchmarkProtocol.roundOrder(5, 0))
        assertEquals(listOf(1, 2, 3, 4, 0), BenchmarkProtocol.roundOrder(5, 1))
        repeat(20) { round -> assertEquals((0..4).toSet(), BenchmarkProtocol.roundOrder(5, round).toSet()) }
        assertEquals((0..4).toSet(), BenchmarkProtocol.roundOrder(5, Int.MAX_VALUE).toSet())
        assertEquals(emptyList<Int>(), BenchmarkProtocol.roundOrder(0, 0))
    }

    @Test fun failedWarmupRetiresOnlyItsPairAndCannotPoisonSharedReferenceControls() {
        val failed = "cpu:candidate-a/sparse3/8/system"
        val sibling = "cpu:candidate-b/sparse3/8/system"
        val control = "cpu:reference/sparse3/8/system"
        val ledger = BenchmarkRoundLedger(listOf(failed, sibling, control))
        ledger.visit(failed)
        ledger.retire(failed, "warmup_failed")
        repeat(5) { ledger.recordBlock(sibling, true); ledger.recordBlock(control, true) }
        ledger.recordBlock(sibling, false)
        assertEquals(2, ledger.activePairCount)
        assertEquals(listOf(5, 5), ledger.activeCompleteBlocks())
        assertEquals(11, ledger.attemptedBlocks)
        assertEquals(0, ledger.completedSnapshot()[failed])
        assertEquals(mapOf(failed to "warmup_failed"), ledger.retiredSnapshot())
        assertFalse(ledger.isRetired(control))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30_000_000_000L, ledger.activeCompleteBlocks()))
    }

    @Test fun quickPlanUsesRefinedReciprocalSqrtOnlyWhenRunnable() {
        val base = listOf(case("scalar"), case("target-block-4", baseline = "scalar"))
        val refined = case("neon-rsqrt-refine-3", baseline = "scalar")
        assertTrue(BenchmarkSchedule.pairs(base + refined, BenchmarkPlan.QUICK).any { it.candidate.id == refined.id })
        assertFalse(BenchmarkSchedule.pairs(base + refined, BenchmarkPlan.QUICK).any { it.candidate.id == "target-block-4" })
        assertTrue(BenchmarkSchedule.pairs(base + refined.copy(status = "unsupported"), BenchmarkPlan.QUICK).any { it.candidate.id == "target-block-4" })
    }

    @Test fun fullScheduleHonoursFixtureCompatibilityAndNeverRunsUnsupportedCases() {
        val cases = listOf(case("reference", fixtures = listOf("sparse3", "kepler")),
            case("candidate", baseline = "reference", fixtures = listOf("kepler", "close-encounter")),
            case("unsupported", status = "unsupported"), case("proposal", status = "not_implemented"))
        val schedule = BenchmarkSchedule.pairs(cases, BenchmarkPlan.FULL)
        assertFalse(schedule.any { it.candidate.id in setOf("unsupported", "proposal") })
        val selected = schedule.filter { it.candidate.id == "candidate" }
        assertEquals(BenchmarkSchedule.fullSteps, selected.map { it.steps })
        assertTrue(selected.all { it.fixture == "kepler" && it.baseline.id == "reference" })
        assertEquals(listOf("A1", "B1", "B2", "A2"), BenchmarkSchedule.abba)
    }

    @Test fun missingReferenceIsLabelledSelfComparisonNotAnInventedBaseline() {
        val only = case("candidate", baseline = "missing")
        assertTrue(BenchmarkSchedule.pairs(listOf(only), BenchmarkPlan.FULL).all { it.baseline == it.candidate })
    }

    @Test fun sustainedRunIncludesDenseWorkWithoutInventingUnsupportedFixtures() {
        val cases = listOf(case("reference", fixtures = listOf("sparse3", "dense256")),
            case("production-selected", baseline = "reference", fixtures = listOf("sparse3", "dense192", "dense256")),
            case("workers-1", baseline = "reference", fixtures = listOf("sparse3")))
        val schedule = BenchmarkSchedule.pairs(cases, BenchmarkPlan.SUSTAINED)
        assertEquals(listOf("sparse3", "dense256"), schedule.filter { it.candidate.id == "production-selected" }.map { it.fixture })
        assertEquals(listOf("sparse3"), schedule.filter { it.candidate.id == "workers-1" }.map { it.fixture })
        assertTrue(schedule.all { it.steps == 64 })
    }

    @Test fun targetedRunPreservesReferenceLookupAndDisambiguatesIdenticalIdsAcrossBackends() {
        val cpuReference = case("reference", fixtures = listOf("kepler", "dense256"))
        val cpuCandidate = case("candidate", baseline = "reference", fixtures = listOf("kepler"))
        val hardwareCandidate = cpuCandidate.copy(backend = "hardware", fixtures = listOf("native-fixed"))
        val hardwareReference = cpuReference.copy(backend = "hardware", fixtures = listOf("native-fixed"))
        val all = listOf(cpuReference, cpuCandidate, hardwareCandidate, hardwareReference)
        val cpu = BenchmarkSchedule.pairs(all, BenchmarkPlan.FULL, "cpu:candidate")
        assertTrue(cpu.all { it.candidate == cpuCandidate && it.baseline == cpuReference && it.fixture == "kepler" })
        assertEquals(BenchmarkSchedule.fullSteps, cpu.map { it.steps })
        val hardware = BenchmarkSchedule.pairs(all, BenchmarkPlan.FULL, "hardware:candidate")
        assertEquals(1, hardware.size)
        assertEquals(hardwareReference, hardware.single().baseline)
        assertTrue(BenchmarkSchedule.pairs(all, BenchmarkPlan.FULL, "cpu:missing").isEmpty())
    }

    @Test fun percentilesUseNearestRankAndMedianDoesNotOverflow() {
        val stats = TimingSummary.from(listOf(100, 1, 2, 3, 4, 5, 6, 7, 8, 9))!!
        assertEquals(5.5, stats.median, 0.0)
        assertEquals(9L, stats.p90)
        assertEquals(100L, stats.p95)
        assertEquals(100L, stats.p99)
        assertNull(TimingSummary.from(emptyList()))
        assertEquals(Long.MAX_VALUE.toDouble(), TimingSummary.from(listOf(Long.MAX_VALUE, Long.MAX_VALUE))!!.median, 0.0)
    }

    @Test fun thermalRecoveryRequiresContinuousLowPressureAndUnknownNeverResumes() {
        val policy = ThermalPausePolicy(recoveryDwellNs = 30)
        assertFalse(policy.update(2, 0))
        assertTrue(policy.update(3, 1))
        assertTrue(policy.update(1, 10))
        assertTrue(policy.update(1, 39))
        assertTrue(policy.update(2, 40))
        assertTrue(policy.update(0, 50))
        assertTrue(policy.update(-1, 79))
        assertTrue(policy.update(0, 90))
        assertFalse(policy.update(0, 120))
        assertTrue(policy.update(4, 121))
    }
}
