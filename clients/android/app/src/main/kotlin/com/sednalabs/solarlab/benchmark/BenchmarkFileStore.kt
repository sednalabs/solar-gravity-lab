package com.sednalabs.solarlab.benchmark

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Every completed NDJSON record is synced; a killed process can lose at most the in-progress record. */
class BenchmarkFileStore(val root: File) {
    init { require(root.isDirectory || root.mkdirs()) { "Cannot create benchmark storage" } }

    fun createSession(timestampMs: Long): File = File(root, "$timestampMs-${UUID.randomUUID()}").also {
        check(it.mkdir()) { "Cannot create session directory" }
    }

    fun sessions(): List<File> = root.listFiles()?.filter(File::isDirectory)?.sortedByDescending(File::getName).orEmpty()

    @Synchronized
    fun append(session: File, record: String) {
        requireSession(session)
        require(!record.contains('\n') && !record.contains('\r')) { "A journal record must occupy one line" }
        FileOutputStream(File(session, "events.ndjson"), true).use {
            it.write((record + "\n").toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
    }

    @Synchronized
    fun writeAtomic(session: File, name: String, contents: String) {
        requireSession(session)
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val temporary = File(session, "$name.pending")
        FileOutputStream(temporary).use {
            it.write(contents.toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        check(temporary.renameTo(File(session, name))) { "Could not commit $name" }
    }

    /** Remove only a torn tail, retaining every newline-terminated observation after a crash. */
    @Synchronized
    fun recoverJournal(session: File): Long {
        requireSession(session)
        val journal = File(session, "events.ndjson")
        if (!journal.isFile) return 0
        RandomAccessFile(journal, "rw").use { file ->
            val originalLength = file.length()
            var position = originalLength
            while (position > 0) {
                file.seek(position - 1)
                if (file.read() == '\n'.code) break
                position--
            }
            if (position != originalLength) {
                file.setLength(position)
                file.fd.sync()
            }
            return originalLength - position
        }
    }

    /** Fixed, flat entries prevent traversal; SHA-256 covers the exact bytes placed in the archive. */
    @Synchronized
    fun exportZip(session: File, destination: OutputStream) {
        requireSession(session)
        val allowed = setOf("manifest.json", "events.ndjson", "summary.json", "catalogue.json", "device.json", "research-catalogue.json")
        ZipOutputStream(destination.buffered()).use { zip ->
            val index = mutableListOf<String>()
            for (file in session.listFiles().orEmpty().filter { it.isFile && it.name in allowed }.sortedBy(File::getName)) {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        digest.update(buffer, 0, length)
                        zip.write(buffer, 0, length)
                        size += length
                    }
                }
                zip.closeEntry()
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                index += "{\"path\":\"${file.name}\",\"bytes\":$size,\"sha256\":\"$hash\"}"
            }
            zip.putNextEntry(ZipEntry("archive-index.json"))
            zip.write("{\"schema_version\":1,\"algorithm\":\"SHA-256\",\"files\":[${index.joinToString(",")}] }".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun requireSession(session: File) {
        require(session.isDirectory && session.canonicalFile.parentFile == root.canonicalFile) { "Invalid session" }
    }
}
