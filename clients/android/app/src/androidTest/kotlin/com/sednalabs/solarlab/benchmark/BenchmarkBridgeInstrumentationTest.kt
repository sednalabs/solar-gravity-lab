package com.sednalabs.solarlab.benchmark

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BenchmarkBridgeInstrumentationTest {
    @Test fun nativeCataloguesAndBoundedReferenceCaseUseThePublishedSchema() {
        val cpu = NativeBridge.catalogue()
        assertEquals(cpu.toString(), 1, cpu.optInt("schema_version"))
        assertTrue(parseCases(cpu, "cpu").any { it.id == "production-scalar" })
        val hardware = NativeHardwareBench.catalogue()
        assertEquals(hardware.toString(), 1, hardware.optInt("schema_version"))
        assertTrue(hardware.has("cases"))
        val result = NativeBridge.run("production-scalar", "kepler", 1)
        assertEquals(result.toString(), "PASS", validatedMeasurement(result).first)
        assertNotNull(validatedMeasurement(result).second)
        val unknown = NativeBridge.run("not-a-case", "kepler", 1)
        assertNotEquals("PASS", validatedMeasurement(unknown).first)
    }

    @Test fun malformedSuccessCannotBecomeTimingEvidence() {
        assertEquals("FAIL", validatedMeasurement(JSONObject("{\"status\":\"PASS\"}")).first)
        assertEquals("FAIL", validatedMeasurement(JSONObject("{\"status\":\"PASS\",\"elapsed_ns\":-1}")).first)
        assertEquals("SKIP", validatedMeasurement(JSONObject("{\"status\":\"SKIP\",\"elapsed_ns\":1}")).first)
    }

    @Test fun compactEnvironmentReferencesRetainDurableDefinitionsAcrossTornTailRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "benchmark-environment-test-${UUID.randomUUID()}")
        val store = BenchmarkFileStore(root)
        val session = store.createSession(1)
        val journalFile = File(session, "events.ndjson")
        val journal = BenchmarkEnvironmentJournal()
        val source = BenchmarkEnvironmentSnapshot(100,
            "{\"monotonic_ns\":100,\"battery_temperature_deci_c\":311,\"unavailable\":null,\"raw_ticks\":9223372036854775807}")
        var definitions = 0
        try {
            val before = journal.reference(source.observedAt(105)) { definition ->
                store.append(session, JSONObject().put("schema_version", 1).put("event", "environment_snapshot")
                    .put("monotonic_ns", 110).put("data", definition.toJson()).toString())
                definitions++
            }.toJson()
            // A returned reference already has a complete on-disk definition.
            val definition = JSONObject(journalFile.readLines().single()).getJSONObject("data")
            assertEquals(setOf("id", "snapshot"), definition.keys().asSequence().toSet())
            assertEquals(definition.getString("id"), before.getString("snapshot_ref"))
            val sensor = definition.getJSONObject("snapshot")
            assertEquals(100L, sensor.getLong("monotonic_ns"))
            assertEquals(Long.MAX_VALUE, sensor.getLong("raw_ticks"))
            assertTrue(sensor.isNull("unavailable"))
            assertFalse(sensor.has("observation_age_ns"))
            val after = journal.reference(BenchmarkEnvironmentObservation(source, 25, "explicit per-use policy")) {
                fail("An immutable snapshot must not be emitted twice")
            }.toJson()
            assertEquals(1, definitions)
            assertEquals(before.getString("snapshot_ref"), after.getString("snapshot_ref"))
            assertEquals(setOf("snapshot_ref", "observation_age_ns", "observation_policy"),
                before.keys().asSequence().toSet())
            assertEquals(5L, before.getLong("observation_age_ns"))
            assertEquals(25L, after.getLong("observation_age_ns"))
            assertEquals("explicit per-use policy", after.getString("observation_policy"))
            store.append(session, JSONObject().put("schema_version", 1).put("event", "sample")
                .put("monotonic_ns", 130).put("data", JSONObject()
                    .put("environment_before", before).put("environment_after", after)).toString())
            val completeJournal = journalFile.readBytes()
            journalFile.appendText("{\"event\":\"sample\"")
            assertTrue(store.recoverJournal(session) > 0)
            assertArrayEquals(completeJournal, journalFile.readBytes())
            assertEquals(listOf("environment_snapshot", "sample"),
                journalFile.readLines().map { JSONObject(it).getString("event") })
            for (invalid in listOf(
                "{\"monotonic_ns\":99}",
                "{\"monotonic_ns\":100,\"observation_age_ns\":0}",
                "{\"monotonic_ns\":100,\"snapshot_ref\":\"env-1\"}",
            )) {
                try {
                    BenchmarkEnvironmentDefinition("env-invalid", BenchmarkEnvironmentSnapshot(100, invalid)).toJson()
                    fail("Ambiguous environment definitions must be rejected")
                } catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun exportedReportIsReadableThroughRestrictedFileProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "benchmark-test-${UUID.randomUUID()}")
        val store = BenchmarkFileStore(root)
        val session = store.createSession(1)
        store.writeAtomic(session, "manifest.json", "{\"schema_version\":1,\"status\":\"cancelled\"}")
        store.append(session, "{\"schema_version\":1,\"event\":\"sample\",\"data\":{\"status\":\"SKIP\"}}")
        val exportDirectory = File(context.cacheDir, "benchmark-exports").also { it.mkdirs() }
        val archive = File(exportDirectory, "test-${UUID.randomUUID()}.zip")
        try {
            archive.outputStream().use { store.exportZip(session, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.benchmark.files", archive)
            assertEquals("content", uri.scheme)
            val entries = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries += entry.name
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        if (entry.name.endsWith(".json")) assertEquals(1, JSONObject(content).optInt("schema_version"))
                        zip.closeEntry()
                    }
                }
            }
            assertEquals(setOf("manifest.json", "events.ndjson", "archive-index.json"), entries)
        } finally { root.deleteRecursively(); archive.delete() }
    }
}
