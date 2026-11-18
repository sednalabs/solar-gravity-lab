package com.sednalabs.solarlab.benchmark

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchmarkFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun crashRecoveryRetainsEveryCommittedRecordAndDropsOnlyTornTail() {
        val store = BenchmarkFileStore(temporary.newFolder("benchmarks"))
        val session = store.createSession(1)
        store.append(session, "{\"status\":\"PASS\"}")
        store.append(session, "{\"status\":\"SKIP\"}")
        File(session, "events.ndjson").appendText("{\"status\":\"FA")
        assertEquals(13L, store.recoverJournal(session))
        assertEquals(listOf("{\"status\":\"PASS\"}", "{\"status\":\"SKIP\"}"), File(session, "events.ndjson").readLines())
        assertEquals(0L, store.recoverJournal(session))
    }

    @Test fun exportChecksumsDescribeExactArchiveBytesAndPendingFilesAreExcluded() {
        val store = BenchmarkFileStore(temporary.newFolder("benchmarks"))
        val session = store.createSession(2)
        store.writeAtomic(session, "manifest.json", "{\"status\":\"completed\"}")
        store.append(session, "{\"elapsed_ns\":42}")
        File(session, "manifest.json.pending").writeText("uncommitted")
        File(session, "private.txt").writeText("not an approved report entry")
        val output = ByteArrayOutputStream()
        store.exportZip(session, output)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
        assertEquals(setOf("events.ndjson", "manifest.json", "archive-index.json"), entries.keys)
        val index = entries.getValue("archive-index.json").toString(Charsets.UTF_8)
        for (name in listOf("events.ndjson", "manifest.json")) {
            val hash = MessageDigest.getInstance("SHA-256").digest(entries.getValue(name)).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertTrue(index.contains(hash))
            assertTrue(index.contains("\"bytes\":${entries.getValue(name).size}"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun multilineRecordCannotCorruptNdjsonBoundaries() {
        val store = BenchmarkFileStore(temporary.newFolder("benchmarks"))
        store.append(store.createSession(3), "{}\n{}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportRejectsDirectoryOutsideSessionRoot() {
        val store = BenchmarkFileStore(temporary.newFolder("benchmarks"))
        store.exportZip(temporary.newFolder("outside"), ByteArrayOutputStream())
    }
}
