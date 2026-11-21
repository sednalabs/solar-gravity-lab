package com.sednalabs.solarlab.benchmark

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BenchmarkEnvironmentJournalTest {
    private fun snapshot(time: Long, temperature: Int = 300) = BenchmarkEnvironmentSnapshot(
        time, "{\"monotonic_ns\":$time,\"battery_temperature_deci_c\":$temperature,\"unavailable\":null}",
    )

    @Test fun repeatedUsesShareOneDefinitionAndPreserveEachUseAgeAndPolicy() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        val sensor = snapshot(100)
        val definitions = mutableListOf<BenchmarkEnvironmentDefinition>()
        val first = journal.reference(sensor.observedAt(105)) { definitions += it }
        val second = journal.reference(BenchmarkEnvironmentObservation(sensor, 17, "retained per-use policy")) { definitions += it }
        assertEquals(1, definitions.size)
        assertEquals(sensor.serializedJson, definitions.single().snapshot.serializedJson)
        assertEquals("env-1", first.snapshotRef)
        assertEquals(first.snapshotRef, second.snapshotRef)
        assertEquals(5L, first.observationAgeNs)
        assertEquals(17L, second.observationAgeNs)
        assertEquals(BenchmarkEnvironmentObservation.CACHED_OBSERVATION_POLICY, first.observationPolicy)
        assertEquals("retained per-use policy", second.observationPolicy)
    }

    @Test fun newSourceTimesProduceDefinitionsEvenWhenSensorValuesAreUnchanged() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        val definitions = mutableListOf<BenchmarkEnvironmentDefinition>()
        val first = journal.reference(snapshot(100).observedAt(105)) { definitions += it }
        val second = journal.reference(snapshot(200).observedAt(205)) { definitions += it }
        assertEquals(listOf(100L, 200L), definitions.map { it.snapshot.sourceMonotonicNs })
        assertEquals(listOf("env-1", "env-2"), listOf(first.snapshotRef, second.snapshotRef))
        assertEquals(5L, second.observationAgeNs)
    }

    @Test fun equivalentImmutableSnapshotsReuseTheExistingDefinition() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        var emitted = 0
        val first = journal.reference(snapshot(100).observedAt(101)) { emitted++ }
        val second = journal.reference(snapshot(100).observedAt(120)) { emitted++ }
        assertEquals(1, emitted)
        assertEquals(first.snapshotRef, second.snapshotRef)
        assertEquals(20L, second.observationAgeNs)
    }

    @Test fun conflictingBodiesWithOneSourceTimeAreRejectedWithoutEmission() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        var emitted = 0
        journal.reference(snapshot(100, 300).observedAt(101)) { emitted++ }
        try {
            journal.reference(snapshot(100, 301).observedAt(102)) { emitted++ }
            fail("A source-time collision must not silently change the sensor observation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Conflicting"))
        }
        assertEquals(1, emitted)
    }

    @Test fun failedEmissionDoesNotAuthoriseReuseAndRetryGetsAnUnambiguousNewId() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        val observed = snapshot(100).observedAt(102)
        val attempts = mutableListOf<String>()
        try {
            journal.reference(observed) {
                attempts += it.id
                throw IOException("write may have completed before sync failed")
            }
            fail("A reference must not escape a failed definition write")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("sync failed"))
        }
        val retry = journal.reference(observed) { attempts += it.id }
        val reused = journal.reference(observed) { fail("Successful retry must be reused") }
        assertEquals(listOf("env-1", "env-2"), attempts)
        assertEquals("env-2", retry.snapshotRef)
        assertEquals(retry, reused)
    }

    @Test fun aFailedEmissionStillCannotBeReplacedByAConflictingBodyAtTheSameTime() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        try {
            journal.reference(snapshot(100, 300).observedAt(101)) { throw IOException("uncertain write") }
            fail("Expected emission failure")
        } catch (_: IOException) { }
        try {
            journal.reference(snapshot(100, 301).observedAt(102)) { fail("Conflicting snapshot must not be emitted") }
            fail("Expected source-time collision")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Conflicting"))
        }
    }

    @Test fun concurrentReferencesWaitForOneCompletedDefinitionBeforeReusingIt() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        val definitionEntered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        var definitions = 0
        val first = async {
            journal.reference(snapshot(100).observedAt(101)) {
                definitions++
                definitionEntered.complete(Unit)
                allowCommit.await()
            }
        }
        definitionEntered.await()
        val second = async { journal.reference(snapshot(100).observedAt(102)) { definitions++ } }
        yield()
        try {
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1, definitions)
        } finally { allowCommit.complete(Unit) }
        assertEquals(first.await().snapshotRef, second.await().snapshotRef)
        assertEquals(1, definitions)
    }

    @Test fun cancellationDuringEmissionDoesNotMakeItsIdReusable() = runBlocking {
        val journal = BenchmarkEnvironmentJournal()
        val definitionEntered = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val worker = launch {
            journal.reference(snapshot(100).observedAt(101)) {
                attempts += it.id
                definitionEntered.complete(Unit)
                awaitCancellation()
            }
        }
        definitionEntered.await()
        worker.cancelAndJoin()
        val retry = journal.reference(snapshot(100).observedAt(103)) { attempts += it.id }
        assertEquals(listOf("env-1", "env-2"), attempts)
        assertEquals("env-2", retry.snapshotRef)
        assertEquals(3L, retry.observationAgeNs)
    }

    @Test fun firstAndLaterUsesHaveAnExplicitAgeAndPolicyWithoutChangingSensorBytes() {
        val sensor = BenchmarkEnvironmentSnapshot(100, "{\"monotonic_ns\":100,\"raw_ticks\":9223372036854775807}")
        val initial = sensor.observedAt(100)
        val later = sensor.observedAt(120)
        assertEquals(0L, initial.observationAgeNs)
        assertEquals(20L, later.observationAgeNs)
        assertEquals(initial.observationPolicy, later.observationPolicy)
        assertSame(sensor, initial.snapshot)
        assertSame(sensor, later.snapshot)
        assertTrue(sensor.serializedJson.contains("9223372036854775807"))
    }

    @Test fun impossibleObservationTimesAreRejected() {
        for (invalid in listOf<() -> Unit>(
            { snapshot(-1) },
            { snapshot(100).observedAt(99) },
            { BenchmarkEnvironmentObservation(snapshot(100), -1) },
            { BenchmarkEnvironmentObservation(snapshot(100), Long.MAX_VALUE) },
        )) {
            try {
                invalid()
                fail("Expected invalid observation time to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
