package com.sednalabs.solarlab.benchmark

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test fun concurrentRecordFactoriesStampInCommitOrderAndWriterAccountingIncludesExactBytes() {
        val store = BenchmarkFileStore(temporary.newFolder("benchmarks"))
        val session = store.createSession(4)
        val firstInsideFactory = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondInsideFactory = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit {
                store.append(session) {
                    firstInsideFactory.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    "first"
                }
            }
            assertTrue(firstInsideFactory.await(5, TimeUnit.SECONDS))
            val second = workers.submit {
                secondAttempting.countDown()
                store.append(session) { secondInsideFactory.countDown(); "second" }
            }
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            assertFalse(secondInsideFactory.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("first", "second"), File(session, "events.ndjson").readLines())
            val accounting = store.journalIoStatistics(session)
            assertEquals(2L, accounting.records)
            assertEquals(File(session, "events.ndjson").length(), accounting.bytes)
            assertTrue(accounting.elapsedNs >= accounting.maximumAppendNs)
            assertTrue(accounting.maximumAppendNs > 0)
        } finally { releaseFirst.countDown(); workers.shutdownNow() }
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
