package com.sednalabs.solarlab.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkProtocolTest {
    private fun case(id: String, status: String = "runnable", baseline: String? = null, fixtures: List<String> = listOf("sparse3")) =
        BenchmarkCase(id, id, "cpu", status, "", "cpu", baseline, fixtures)

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
