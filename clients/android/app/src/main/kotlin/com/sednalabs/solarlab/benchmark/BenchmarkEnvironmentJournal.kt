package com.sednalabs.solarlab.benchmark

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Sensor JSON is captured once, then shared immutably by all uses of that observation. */
internal data class BenchmarkEnvironmentSnapshot(val sourceMonotonicNs: Long, val serializedJson: String) {
    init {
        require(sourceMonotonicNs >= 0) { "Environment source time must be nonnegative" }
        require(serializedJson.isNotBlank()) { "Environment snapshot must not be empty" }
    }

    fun observedAt(monotonicNs: Long): BenchmarkEnvironmentObservation {
        require(monotonicNs >= sourceMonotonicNs) { "Environment observation precedes its source" }
        return BenchmarkEnvironmentObservation(this, monotonicNs - sourceMonotonicNs)
    }
}

internal data class BenchmarkEnvironmentObservation(
    val snapshot: BenchmarkEnvironmentSnapshot,
    val observationAgeNs: Long,
    val observationPolicy: String = CACHED_OBSERVATION_POLICY,
) {
    init {
        require(observationAgeNs >= 0 && observationAgeNs <= Long.MAX_VALUE - snapshot.sourceMonotonicNs) {
            "Environment observation time is out of range"
        }
        require(observationPolicy.isNotBlank()) { "Environment observation policy must not be empty" }
    }

    fun toInlineJson(): JSONObject = JSONObject(snapshot.serializedJson)
        .put("observation_age_ns", observationAgeNs).put("observation_policy", observationPolicy)

    companion object {
        const val CACHED_OBSERVATION_POLICY =
            "latest_periodic_sample; before_after_do_not_imply_two_independent_sensor_reads"
    }
}

internal data class BenchmarkEnvironmentDefinition(val id: String, val snapshot: BenchmarkEnvironmentSnapshot) {
    /** Parsing happens only for a new definition, never for each sample reference. */
    fun toJson(): JSONObject {
        val sensor = JSONObject(snapshot.serializedJson)
        require(sensor.opt("monotonic_ns") is Long || sensor.opt("monotonic_ns") is Int) {
            "Environment snapshot source time must be an integer"
        }
        require(sensor.getLong("monotonic_ns") == snapshot.sourceMonotonicNs) { "Environment source time differs from snapshot" }
        require(!sensor.has("snapshot_ref") && !sensor.has("observation_age_ns") && !sensor.has("observation_policy")) {
            "Environment definition must not contain reference or per-use observation fields"
        }
        return JSONObject().put("id", id).put("snapshot", sensor)
    }
}

internal data class BenchmarkEnvironmentReference(
    val snapshotRef: String,
    val observationAgeNs: Long,
    val observationPolicy: String,
) {
    fun toJson(): JSONObject = JSONObject().put("snapshot_ref", snapshotRef)
        .put("observation_age_ns", observationAgeNs).put("observation_policy", observationPolicy)
}

/** A session owns one journal. A definition must be durably written before its ID can be reused. */
internal class BenchmarkEnvironmentJournal {
    private val mutex = Mutex()
    private val snapshotBodies = mutableMapOf<Long, String>()
    private val emittedIds = mutableMapOf<Long, String>()
    private var nextId = 1L

    suspend fun reference(
        observation: BenchmarkEnvironmentObservation,
        emitDefinition: suspend (BenchmarkEnvironmentDefinition) -> Unit,
    ): BenchmarkEnvironmentReference = mutex.withLock {
        val snapshot = observation.snapshot
        val source = snapshot.sourceMonotonicNs
        val existingBody = snapshotBodies[source]
        require(existingBody == null || existingBody == snapshot.serializedJson) {
            "Conflicting environment snapshots share a source time"
        }
        // Retain the body even if emission throws: it may already have reached the journal.
        snapshotBodies[source] = snapshot.serializedJson
        val id = emittedIds[source] ?: run {
            check(nextId < Long.MAX_VALUE) { "Environment snapshot identifier space exhausted" }
            val allocated = "env-${nextId++}"
            emitDefinition(BenchmarkEnvironmentDefinition(allocated, snapshot))
            // Failed or cancelled emissions never authorise a reference. Consuming the ID
            // above also prevents a duplicate if the write completed just before failure.
            emittedIds[source] = allocated
            allocated
        }
        BenchmarkEnvironmentReference(id, observation.observationAgeNs, observation.observationPolicy)
    }
}
