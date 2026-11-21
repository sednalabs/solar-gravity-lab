package com.sednalabs.solarlab.benchmark

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class BenchmarkProtocolTest {
    private fun case(id: String, status: String = "runnable", baseline: String? = null, fixtures: List<String> = listOf("sparse3")) =
        BenchmarkCase(id, id, "cpu", status, "", "cpu", baseline, fixtures)

    private fun fullLedgerAtBlockCutoff(taskCount: Int, blockCutoff: Int): BenchmarkRoundLedger {
        val plan = BenchmarkPlan.FULL
        val keys = List(taskCount) { "pair-$it" }
        val ledger = BenchmarkRoundLedger(keys)
        val limit = minOf(blockCutoff, plan.maximumMeasuredBlocks)
        for (cohort in BenchmarkProtocol.cohortIndices(plan, taskCount)) {
            val cohortKeys = cohort.map { keys[it] }
            var round = 0
            while (BenchmarkProtocol.needsAnotherCohortRound(
                plan, ledger.activeCompleteUnits(cohortKeys), ledger.hasUnmatchedActiveUnits(cohortKeys),
            )) {
                for (localIndex in BenchmarkProtocol.roundOrder(cohort.size, round)) {
                    if (ledger.attemptedBlocks >= limit) return ledger
                    val pair = keys[cohort[localIndex]]
                    ledger.visit(pair)
                    ledger.recordBlock(pair, round, true, 0)
                }
                round++
            }
        }
        return ledger
    }

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

    @Test fun durationAndCompleteUnitTargetsAreIndependentAndAnOpenUnitMustFinish() {
        val second = 1_000_000_000L
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 29 * second, listOf(5, 5)))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 31 * second, listOf(5, 4)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30 * second, listOf(5, 5)))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30 * second, listOf(5, 5), hasUnmatchedUnits = true))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.FULL, 119 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.FULL, 120 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.SUSTAINED, 900 * second, listOf(5)))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 0, emptyList()))
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 0, emptyList(), hasUnmatchedUnits = true))
        assertEquals(5, BenchmarkPlan.QUICK.minimumCompleteCounterbalancedUnits)
        assertEquals(10, BenchmarkPlan.QUICK.minimumCompleteBlocks)
        assertTrue(BenchmarkPlan.QUICK.maximumMeasuredBlocks < BenchmarkPlan.SUSTAINED.maximumMeasuredBlocks)
        assertEquals(180L, BenchmarkPlan.QUICK.budgetSeconds)
        assertEquals(600L, BenchmarkPlan.FULL.budgetSeconds)
    }

    @Test fun aDeadlineCannotCompleteSustainedWorkWithCooldownShortfallOrUnmatchedHalf() {
        val second = 1_000_000_000L
        val plan = BenchmarkPlan.SUSTAINED
        assertFalse(BenchmarkProtocol.completionTargetsMet(plan, 899 * second, listOf(5, 5), false))
        assertFalse(BenchmarkProtocol.completionTargetsMet(plan, 900 * second, listOf(5, 4), false))
        assertFalse(BenchmarkProtocol.completionTargetsMet(plan, 900 * second, listOf(5, 5), true))
        assertFalse(BenchmarkProtocol.completionTargetsMet(plan, 900 * second, emptyList(), false))
        assertTrue(BenchmarkProtocol.completionTargetsMet(plan, 900 * second, listOf(5, 5), false))
    }

    @Test fun cancellationDuringCooldownClosesItOnceAndPreservesTheUnmatchedHalf() = runBlocking {
        val accounting = BenchmarkThermalAccounting()
        val policy = ThermalPausePolicy()
        val ledger = BenchmarkRoundLedger(listOf("pair"))
        ledger.recordBlock("pair", 0, true, 0)
        var now = 10L
        var closures = 0
        var resumed: Boolean? = null
        val waiting = CompletableDeferred<Unit>()
        assertTrue(policy.update(3, now))
        val worker = launch {
            accounting.observePause({ now }, onEnd = { duration ->
                // A cancellation-sensitive suspension must still execute in
                // the closure, as the real journal callback suspends for I/O.
                yield()
                closures++
                resumed = !policy.paused
                assertEquals(40L, duration)
            }) {
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        waiting.await()
        now = 50L
        worker.cancelAndJoin()
        assertEquals(1, closures)
        assertEquals(false, resumed)
        assertEquals(40L, accounting.pausedNs)
        assertEquals(1, accounting.epoch)
        assertEquals(10L, now - accounting.pausedNs)
        assertEquals(mapOf("pair" to 0), ledger.unmatchedUnitsSnapshot())
        assertEquals(listOf(0), ledger.activeCompleteUnits())
    }

    @Test fun cooldownAccountingSurvivesAFailedEndRecord() = runBlocking {
        val accounting = BenchmarkThermalAccounting()
        var now = 10L
        try {
            accounting.observePause({ now }, onEnd = { throw IllegalStateException("journal unavailable") }) {
                now = 40L
            }
            fail("The journal failure must remain visible")
        } catch (expected: IllegalStateException) {
            assertEquals("journal unavailable", expected.message)
        }
        assertEquals(30L, accounting.pausedNs)
        assertEquals(1, accounting.epoch)
    }

    @Test fun roundsVisitEveryPairBeforeRepeatingAndRotateTheFirstSlot() {
        assertEquals(listOf(0, 1, 2, 3, 4), BenchmarkProtocol.roundOrder(5, 0))
        assertEquals(listOf(1, 2, 3, 4, 0), BenchmarkProtocol.roundOrder(5, 1))
        repeat(20) { round -> assertEquals((0..4).toSet(), BenchmarkProtocol.roundOrder(5, round).toSet()) }
        assertEquals((0..4).toSet(), BenchmarkProtocol.roundOrder(5, Int.MAX_VALUE).toSet())
        assertEquals(emptyList<Int>(), BenchmarkProtocol.roundOrder(0, 0))
    }

    @Test fun fullCohortsRetainEveryPlannedIndexOnceAndRotateWithinTheirOwnBounds() {
        val cohorts = BenchmarkProtocol.cohortIndices(BenchmarkPlan.FULL, 4_571)
        assertEquals(32, BenchmarkProtocol.FULL_COHORT_SIZE)
        assertEquals(143, cohorts.size)
        assertTrue(cohorts.dropLast(1).all { it.size == 32 })
        assertEquals(27, cohorts.last().size)
        assertEquals((0 until 4_571).toList(), cohorts.flatten())
        for (cohort in listOf(cohorts.first(), cohorts[1], cohorts.last())) {
            repeat(40) { round ->
                val order = BenchmarkProtocol.roundOrder(cohort.size, round).map { cohort[it] }
                assertEquals(cohort.size, order.distinct().size)
                assertEquals(cohort.toSet(), order.toSet())
                assertEquals(cohort[round % cohort.size], order.first())
            }
        }
        assertTrue(BenchmarkProtocol.cohortIndices(BenchmarkPlan.FULL, 0).isEmpty())
        assertTrue(BenchmarkProtocol.cohortIndices(BenchmarkPlan.FULL, -1).isEmpty())
    }

    @Test fun quickAndSustainedKeepOneWholeCatalogueCohortAndTheirDurationGate() {
        for (plan in listOf(BenchmarkPlan.QUICK, BenchmarkPlan.SUSTAINED)) {
            assertEquals(listOf((0 until 4_571).toList()), BenchmarkProtocol.cohortIndices(plan, 4_571))
            assertTrue(BenchmarkProtocol.cohortIndices(plan, 0).isEmpty())
            assertTrue(BenchmarkProtocol.needsAnotherRound(plan, 0, listOf(5)))
            assertFalse(BenchmarkProtocol.needsAnotherRound(plan, plan.minimumObservationSeconds * 1_000_000_000L, listOf(5)))
        }
    }

    @Test fun cohortClosureRequiresFiveWholeUnitsButDoesNotConsumeTheGlobalMinimumDuration() {
        val plan = BenchmarkPlan.FULL
        assertTrue(BenchmarkProtocol.needsAnotherCohortRound(plan, listOf(5, 4)))
        assertTrue(BenchmarkProtocol.needsAnotherCohortRound(plan, listOf(5, 5), hasUnmatchedUnits = true))
        assertFalse(BenchmarkProtocol.needsAnotherCohortRound(plan, listOf(5, 5)))
        assertFalse(BenchmarkProtocol.needsAnotherCohortRound(plan, emptyList()))
        assertFalse(BenchmarkProtocol.needsAnotherCohortRound(plan, emptyList(), hasUnmatchedUnits = true))
        assertFalse(BenchmarkProtocol.completionTargetsMet(plan, 0, listOf(5, 5), false))
        assertTrue(BenchmarkProtocol.completionTargetsMet(plan, 120_000_000_000L, listOf(5, 5), false))
    }

    @Test fun fullBlockBudgetYieldsQualifiedSubsetsWithoutClaimingUnvisitedComparisonsComplete() {
        val ledger = fullLedgerAtBlockCutoff(4_571, BenchmarkPlan.FULL.maximumMeasuredBlocks)
        val units = ledger.completedUnitsSnapshot()
        assertEquals(3, BenchmarkProtocol.VERSION)
        assertEquals(8_192, ledger.attemptedBlocks)
        assertEquals(832, ledger.visitedPairs)
        assertEquals(4_571, units.size)
        assertEquals(800, units.values.count { it == 5 })
        assertEquals(32, units.values.count { it == 3 })
        assertEquals(3_739, units.values.count { it == 0 })
        assertTrue(ledger.unmatchedUnitsSnapshot().isEmpty())
        assertFalse(BenchmarkProtocol.completionTargetsMet(
            BenchmarkPlan.FULL, 600_000_000_000L, ledger.activeCompleteUnits(), ledger.hasUnmatchedActiveUnits(),
        ))
    }

    @Test fun observedFullQuotaCutoffRetainsUsefulUnitsAndReportsTheInterruptedCohort() {
        // The physical Full export stopped after 3,437 completed four-sample
        // blocks. At that same cutoff, bounded cohorts already close 320 pairs.
        val ledger = fullLedgerAtBlockCutoff(4_571, 3_437)
        val units = ledger.completedUnitsSnapshot()
        assertEquals(3_437, ledger.attemptedBlocks)
        assertEquals(352, ledger.visitedPairs)
        assertEquals(4_571, units.size)
        assertEquals(320, units.values.count { it == 5 })
        assertEquals(13, units.values.count { it == 4 })
        assertEquals(19, units.values.count { it == 3 })
        assertEquals(4_219, units.values.count { it == 0 })
        assertEquals(19, ledger.unmatchedUnitsSnapshot().size)
        assertTrue(ledger.unmatchedUnitsSnapshot().values.all { it == 3 })
        assertTrue(ledger.hasUnmatchedActiveUnits())
        assertFalse(BenchmarkProtocol.completionTargetsMet(
            BenchmarkPlan.FULL, 228_000_000_000L, ledger.activeCompleteUnits(), ledger.hasUnmatchedActiveUnits(),
        ))
    }

    @Test fun cohortQueriesKeepOtherCohortsAndAnInFlightBlockSeparate() {
        val ledger = BenchmarkRoundLedger(listOf("qualified", "other-open", "unvisited"))
        repeat(10) { round -> ledger.recordBlock("qualified", round, true, 0) }
        ledger.recordBlock("other-open", 0, true, 0)
        val cohort = listOf("qualified")
        assertEquals(listOf(5), ledger.activeCompleteUnits(cohort))
        assertFalse(ledger.hasUnmatchedActiveUnits(cohort))
        assertTrue(ledger.hasUnmatchedActiveUnits())
        assertFalse(BenchmarkProtocol.needsAnotherCohortRound(
            BenchmarkPlan.FULL, ledger.activeCompleteUnits(cohort), ledger.hasUnmatchedActiveUnits(cohort),
        ))
        ledger.beginBlock("qualified", 10)
        assertEquals(listOf(5), ledger.activeCompleteUnits(cohort))
        assertTrue(ledger.hasUnmatchedActiveUnits(cohort))
        assertTrue(BenchmarkProtocol.needsAnotherCohortRound(
            BenchmarkPlan.FULL, ledger.activeCompleteUnits(cohort), ledger.hasUnmatchedActiveUnits(cohort),
        ))
        ledger.recordBlock("qualified", 10, true, 0)
        assertTrue(ledger.hasUnmatchedActiveUnits(cohort))
        ledger.recordBlock("qualified", 11, true, 0)
        assertFalse(ledger.hasUnmatchedActiveUnits(cohort))
        assertEquals(listOf(6), ledger.activeCompleteUnits(listOf("qualified", "qualified")))
        assertEquals(mapOf("qualified" to 6, "other-open" to 0, "unvisited" to 0), ledger.completedUnitsSnapshot())
        assertEquals(mapOf("other-open" to 0), ledger.unmatchedUnitsSnapshot())
        assertTrue(ledger.activeCompleteUnits(emptyList()).isEmpty())
        assertFalse(ledger.hasUnmatchedActiveUnits(emptyList()))
    }

    @Test fun retiredCohortsMayAdvanceWithoutErasingTheirUnmatchedDiagnostics() {
        val ledger = BenchmarkRoundLedger(listOf("retired", "active", "unvisited"))
        ledger.visit("retired")
        ledger.recordBlock("retired", 0, true, 0)
        ledger.retire("retired", "measurement_failed")
        val retiredCohort = listOf("retired")
        assertTrue(ledger.activeCompleteUnits(retiredCohort).isEmpty())
        assertFalse(ledger.hasUnmatchedActiveUnits(retiredCohort))
        assertFalse(BenchmarkProtocol.needsAnotherCohortRound(
            BenchmarkPlan.FULL, ledger.activeCompleteUnits(retiredCohort), ledger.hasUnmatchedActiveUnits(retiredCohort),
        ))
        repeat(10) { round -> ledger.recordBlock("active", round, true, 0) }
        assertEquals(listOf(5), ledger.activeCompleteUnits(listOf("retired", "active")))
        assertEquals(listOf(5, 0), ledger.activeCompleteUnits())
        assertFalse(ledger.hasUnmatchedActiveUnits())
        assertEquals(mapOf("retired" to 0), ledger.unmatchedUnitsSnapshot())
        assertEquals(mapOf("retired" to "measurement_failed"), ledger.retiredSnapshot())
        assertEquals(3, ledger.completedUnitsSnapshot().size)
        assertEquals(2, ledger.activePairCount)
        assertFalse(BenchmarkProtocol.completionTargetsMet(
            BenchmarkPlan.FULL, 120_000_000_000L, ledger.activeCompleteUnits(), ledger.hasUnmatchedActiveUnits(),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCohortCannotMasqueradeAsAnEmptyCompletedPopulation() {
        BenchmarkRoundLedger(listOf("known")).activeCompleteUnits(listOf("missing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unmatchedQueryRejectsUnknownCohortKeys() {
        BenchmarkRoundLedger(listOf("known")).hasUnmatchedActiveUnits(listOf("missing"))
    }

    @Test fun complementaryRoundsKeepRolesStableAndBalanceEveryChronologicalPosition() {
        repeat(20) { unit ->
            val first = BenchmarkSchedule.blockOrder(unit * 2)
            val second = BenchmarkSchedule.blockOrder(unit * 2 + 1)
            assertEquals(listOf("A1", "B1", "B2", "A2"), first)
            assertEquals(listOf("B1", "A1", "A2", "B2"), second)
            for (position in 0..3) assertEquals(setOf('A', 'B'), setOf(first[position][0], second[position][0]))
            assertEquals("ABBA", BenchmarkSchedule.blockOrderName(unit * 2))
            assertEquals("BAAB", BenchmarkSchedule.blockOrderName(unit * 2 + 1))
        }
        assertEquals(BenchmarkSchedule.baab, BenchmarkSchedule.blockOrder(Int.MAX_VALUE))
    }

    @Test fun aUnitRequiresBothComplementaryBlocksAndCannotReuseAnEarlierOrFailedHalf() {
        val pair = "cpu:reference/sparse3/8/system"
        val ledger = BenchmarkRoundLedger(listOf(pair))
        assertNull(ledger.recordBlock(pair, 0, true, 0))
        assertEquals(mapOf(pair to 0), ledger.unmatchedUnitsSnapshot())
        assertEquals(listOf(0), ledger.activeCompleteUnits())
        assertTrue(ledger.hasUnmatchedActiveUnits())
        val missing = ledger.recordBlock(pair, 3, true, 0)!!
        assertFalse(missing.complete)
        assertEquals("missing_complementary_first_block", missing.reason)
        assertFalse(ledger.hasUnmatchedActiveUnits())
        ledger.recordBlock(pair, 4, false, 0)
        assertEquals("incomplete_first_block", ledger.recordBlock(pair, 5, true, 0)!!.reason)
        ledger.recordBlock(pair, 6, true, 0)
        assertEquals("incomplete_second_block", ledger.recordBlock(pair, 7, false, 0)!!.reason)
        assertEquals(listOf(0), ledger.activeCompleteUnits())
        assertEquals(4, ledger.completedSnapshot()[pair])
        assertEquals(6, ledger.attemptedBlocks)
        assertNull(ledger.recordBlock(pair, 8, true, 0))
        val completed = ledger.recordBlock(pair, 9, true, 0)!!
        assertTrue(completed.complete)
        assertEquals(4, completed.unit)
        assertEquals(0, completed.thermalEpoch)
        assertEquals(listOf(1), ledger.activeCompleteUnits())
        assertTrue(ledger.unmatchedUnitsSnapshot().isEmpty())
    }

    @Test fun thermalRecoveryBetweenIndividuallyCompleteBlocksExcludesTheWholeUnit() {
        val pair = "hardware:candidate/native-fixed/1/system"
        val ledger = BenchmarkRoundLedger(listOf(pair))
        ledger.recordBlock(pair, 0, true, 0)
        val acrossPause = ledger.recordBlock(pair, 1, true, 1)!!
        assertFalse(acrossPause.complete)
        assertEquals("thermal_epoch_changed_between_blocks", acrossPause.reason)
        assertNull(acrossPause.thermalEpoch)
        assertEquals(listOf(2), ledger.activeCompleteBlocks())
        assertEquals(listOf(0), ledger.activeCompleteUnits())
        ledger.recordBlock(pair, 2, true, 1)
        assertTrue(ledger.recordBlock(pair, 3, true, 1)!!.complete)
        assertEquals(listOf(1), ledger.activeCompleteUnits())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRoundCannotCreateExtraBlocksOrUnits() {
        val ledger = BenchmarkRoundLedger(listOf("pair"))
        ledger.recordBlock("pair", 0, true, 0)
        ledger.recordBlock("pair", 0, true, 0)
    }

    @Test fun interruptionBeforeTheBlockFinishesKeepsItsUnitUnmatchedWithoutCountingIt() {
        val ledger = BenchmarkRoundLedger(listOf("pair"))
        ledger.beginBlock("pair", 0)
        assertEquals(1, ledger.attemptedBlocks)
        assertEquals(mapOf("pair" to 0), ledger.unmatchedUnitsSnapshot())
        assertEquals(listOf(0), ledger.activeCompleteUnits())
        assertTrue(ledger.hasUnmatchedActiveUnits())
        ledger.recordBlock("pair", 0, true, 0)
        assertEquals(1, ledger.attemptedBlocks)
        ledger.beginBlock("pair", 1)
        assertEquals(2, ledger.attemptedBlocks)
        assertEquals(mapOf("pair" to 0), ledger.unmatchedUnitsSnapshot())
        assertEquals(listOf(0), ledger.activeCompleteUnits())
        assertTrue(ledger.hasUnmatchedActiveUnits())
        assertTrue(ledger.recordBlock("pair", 1, true, 0)!!.complete)
        assertEquals(2, ledger.attemptedBlocks)
        assertFalse(ledger.hasUnmatchedActiveUnits())
    }

    @Test fun failedWarmupRetiresOnlyItsPairAndCannotPoisonSharedReferenceControls() {
        val failed = "cpu:candidate-a/sparse3/8/system"
        val sibling = "cpu:candidate-b/sparse3/8/system"
        val control = "cpu:reference/sparse3/8/system"
        val ledger = BenchmarkRoundLedger(listOf(failed, sibling, control))
        ledger.visit(failed)
        ledger.retire(failed, "warmup_failed")
        repeat(10) { round -> ledger.recordBlock(sibling, round, true, 0); ledger.recordBlock(control, round, true, 0) }
        ledger.recordBlock(sibling, 10, false, 0)
        assertEquals(2, ledger.activePairCount)
        assertEquals(listOf(10, 10), ledger.activeCompleteBlocks())
        assertEquals(listOf(5, 5), ledger.activeCompleteUnits())
        assertEquals(21, ledger.attemptedBlocks)
        assertEquals(0, ledger.completedSnapshot()[failed])
        assertEquals(mapOf(failed to "warmup_failed"), ledger.retiredSnapshot())
        assertFalse(ledger.isRetired(control))
        assertTrue(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30_000_000_000L, ledger.activeCompleteUnits(), ledger.hasUnmatchedActiveUnits()))
        ledger.recordBlock(sibling, 11, true, 0)
        assertFalse(BenchmarkProtocol.needsAnotherRound(BenchmarkPlan.QUICK, 30_000_000_000L, ledger.activeCompleteUnits(), ledger.hasUnmatchedActiveUnits()))
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
        assertEquals(BenchmarkSchedule.fullPrioritySteps, selected.map { it.steps })
        assertTrue(selected.all { it.fixture == "kepler" && it.baseline.id == "reference" })
        assertEquals(listOf("A1", "B1", "B2", "A2"), BenchmarkSchedule.abba)
    }

    @Test fun fullPriorityReachesDense64StepPairsEarlyWithoutDroppingCasesFixturesOrSteps() {
        val fixtures = listOf("sparse3", "dense192", "dense256") + (1..10).map { "fixture-$it" }
        val cpuReference = case("reference", fixtures = fixtures)
        val cpu = listOf(cpuReference) + (1..49).map { case("candidate-$it", baseline = "reference", fixtures = fixtures) }
        val hardwareReference = case("reference", fixtures = listOf("native-fixed")).copy(backend = "hardware")
        val hardware = listOf(hardwareReference) + (1..20).map {
            case("hardware-$it", baseline = "reference", fixtures = listOf("native-fixed")).copy(backend = "hardware")
        }
        val cases = cpu + hardware
        val schedule = BenchmarkSchedule.pairs(cases, BenchmarkPlan.FULL)
        val expectedKeys = cpu.flatMap { candidate ->
            fixtures.flatMap { fixture -> BenchmarkSchedule.fullSteps.map { "${candidate.key}/$fixture/$it" } }
        } + hardware.map { "${it.key}/native-fixed/1" }
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64), BenchmarkSchedule.fullSteps)
        assertEquals(listOf(64, 1, 8, 2, 4, 16, 32), BenchmarkSchedule.fullPrioritySteps)
        assertEquals(4_571, schedule.size)
        assertEquals(expectedKeys.toSet(), schedule.map { it.key }.toSet())
        assertEquals(schedule.size, schedule.map { it.key }.distinct().size)
        assertTrue(schedule.all { it.baseline == if (it.candidate.backend == "cpu") cpuReference else hardwareReference })
        assertEquals(cases.map { it.key }.toSet(), schedule.take(cases.size).map { it.candidate.key }.toSet())
        assertEquals(hardware.map { it.key }.toSet(), schedule.take(cases.size)
            .filter { it.candidate.backend == "hardware" }.map { it.candidate.key }.toSet())
        val earlyPairs = schedule.take(800)
        for (fixture in listOf("dense192", "dense256")) {
            assertEquals(cpu.map { it.key }.toSet(), earlyPairs.filter { it.fixture == fixture && it.steps == 64 }
                .map { it.candidate.key }.toSet())
        }
        assertEquals(fixtures, schedule.filter { it.candidate == cpu.last() }.take(fixtures.size).map { it.fixture })
        assertTrue(schedule.filter { it.candidate == cpu.last() }.take(fixtures.size).all { it.steps == 64 })
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
        assertEquals(BenchmarkSchedule.fullPrioritySteps, cpu.map { it.steps })
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
