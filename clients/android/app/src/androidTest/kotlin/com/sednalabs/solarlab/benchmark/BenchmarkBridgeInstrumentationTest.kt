package com.sednalabs.solarlab.benchmark

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
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
