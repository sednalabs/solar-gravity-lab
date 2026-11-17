package com.sednalabs.solarlab.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FileExperimentStoreTest {
    @Test
    fun savedBytesSurviveFreshStoreAndKeepPreviousVersions() {
        val root = Files.createTempDirectory("solarlab-save-test").toFile()
        try {
            val bytes = byteArrayOf(0, 1, 2, -1, 13, 10)
            val store = FileExperimentStore(root)
            val first = store.save("Lunar probe", SavedRuntimeSession("lab.earth-moon", bytes))
            val second = store.save("Lunar probe", SavedRuntimeSession("lab.earth-moon", byteArrayOf(9)))
            assertNotEquals(first.id, second.id)
            val reopened = FileExperimentStore(root)
            assertEquals(2, reopened.list().size)
            assertEquals(first, reopened.read(first.id).info)
            assertArrayEquals(bytes, reopened.read(first.id).bytes)
            assertArrayEquals(byteArrayOf(9), reopened.read(second.id).bytes)
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun corruptOrInterruptedWritesNeverBecomeValidExperiments() {
        val root = Files.createTempDirectory("solarlab-save-test").toFile()
        try {
            val store = FileExperimentStore(root)
            val saved = store.save("Baseline", SavedRuntimeSession("lab.earth-moon", byteArrayOf(1, 2, 3)))
            File(root, ".experiment-interrupted.tmp").writeBytes(byteArrayOf(1))
            assertEquals(listOf(saved), FileExperimentStore(root).list())
            val file = File(root, "${saved.id}.sglexp")
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = 7
            file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { store.read(saved.id) }
            assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
            assertThrows(IllegalArgumentException::class.java) { store.save(" ", SavedRuntimeSession("lab.earth-moon", byteArrayOf(1))) }
            assertEquals(1, root.listFiles().orEmpty().count { it.name.endsWith(".sglexp") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedSaveLeavesEarlierVersionIntact() {
        val root = Files.createTempDirectory("solarlab-save-test").toFile()
        try {
            val store = FileExperimentStore(root)
            val saved = store.save("Good baseline", SavedRuntimeSession("lab.earth-moon", byteArrayOf(42)))
            assertThrows(IllegalArgumentException::class.java) {
                store.save("Too big", SavedRuntimeSession("lab.earth-moon", ByteArray(FileExperimentStore.MAX_BYTES + 1)))
            }
            assertEquals(listOf(saved), store.list())
            assertArrayEquals(byteArrayOf(42), store.read(saved.id).bytes)
        } finally { root.deleteRecursively() }
    }
    @Test
    fun legacyEnvelopeExportsByteForByteAndReadsFromUnknownLengthProvider() {
        val payload = byteArrayOf(4, 9, 0, -1)
        val id = "d9b9a184-a090-4bb3-99d4-254c9ff533cb"
        // Frozen v1 envelope, written independently of the new codec.
        val document = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(0x53474C45); out.writeInt(1)
                out.writeUTF(id); out.writeUTF("Original orbit"); out.writeLong(1234)
                out.writeUTF("lab.earth-moon"); out.writeInt(payload.size)
                out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload)
            }
        }.toByteArray()
        val unknownLength = object : ByteArrayInputStream(document) {
            override fun available() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(3, length))
        }
        val read = ExperimentDocumentCodec.read(unknownLength)
        assertEquals(id, read.info.id)
        assertEquals(document.size.toLong(), read.info.sizeBytes)
        assertArrayEquals(payload, read.bytes)
        val exported = ByteArrayOutputStream().also { ExperimentDocumentCodec.write(it, read) }.toByteArray()
        assertArrayEquals(document, exported)
    }

    @Test
    fun portableContentRejectsCorruptionTruncationTrailingDataAndOversizedPayload() {
        val root = Files.createTempDirectory("portable-limit-test").toFile()
        try {
            val store = FileExperimentStore(root)
            val saved = store.save("Keep this", SavedRuntimeSession("lab.earth-moon", byteArrayOf(8, 9)))
            val original = File(root, "${saved.id}.sglexp").readBytes()
            val damaged = original.copyOf().also { it[it.lastIndex] = 1 }
            for (bytes in listOf(damaged, original.copyOf(original.size - 1), original + byteArrayOf(0))) {
                assertThrows(Exception::class.java) { ExperimentDocumentCodec.read(ByteArrayInputStream(bytes)) }
            }
            val invalidLength = ByteArrayOutputStream().also { stream ->
                DataOutputStream(stream).use { out ->
                    out.writeInt(0x53474C45); out.writeInt(1)
                    out.writeUTF(saved.id); out.writeUTF(saved.name); out.writeLong(0)
                    out.writeUTF(saved.scenarioId); out.writeInt(Int.MAX_VALUE)
                }
            }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                ExperimentDocumentCodec.read(ByteArrayInputStream(invalidLength))
            }
            assertEquals(listOf(saved), store.list())
            assertArrayEquals(byteArrayOf(8, 9), store.read(saved.id).bytes)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun damagedEntriesRemainVisibleAndCanFreeCatalogueCapacity() {
        val root = Files.createTempDirectory("catalogue-limit-test").toFile()
        try {
            repeat(FileExperimentStore.MAX_SAVES) { File(root, "${UUID.randomUUID()}.sglexp").writeBytes(byteArrayOf(0)) }
            val store = FileExperimentStore(root)
            val entries = store.list()
            assertEquals(256, entries.size)
            assertTrue(entries.all { it.problem != null && it.sizeBytes == 1L })
            assertThrows(IllegalStateException::class.java) {
                store.save("Full", SavedRuntimeSession("lab.earth-moon", byteArrayOf(7)))
            }
            assertThrows(IllegalArgumentException::class.java) { store.delete("../outside") }
            store.delete(entries.first().id)
            val saved = store.save("Space recovered", SavedRuntimeSession("lab.earth-moon", byteArrayOf(7)))
            assertArrayEquals(byteArrayOf(7), store.read(saved.id).bytes)
            assertEquals(256, store.list().size)
        } finally { root.deleteRecursively() }
    }

}
