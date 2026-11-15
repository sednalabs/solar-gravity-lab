package com.sednalabs.solarlab.runtime

import java.io.File
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
}
