package com.sednalabs.solarlab.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Metadata only. Android never interprets or edits the opaque Rust world document. */
data class SavedExperimentInfo(
    val id: String,
    val name: String,
    val updatedAtEpochMillis: Long,
    val scenarioId: String,
    val sizeBytes: Long = 0,
    val problem: String? = null,
)

/** Catalogue operations outlive Activity recreation and routine renderer status updates. */
data class ExperimentStatus(
    val busyLabel: String? = null,
    val notice: String? = null,
    val tone: ShellNoticeTone = ShellNoticeTone.Neutral,
    val openedId: String? = null,
)

data class RuntimeBodyState(
    val bodyId: String,
    val bodyClass: RuntimeBodyClass,
    val massKg: Double,
    val sourceMassKg: Double,
    val radiusM: Double,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val velocityX: Double,
    val velocityY: Double,
    val velocityZ: Double,
)

internal data class SavedRuntimeSession(val scenarioId: String, val bytes: ByteArray)
internal data class StoredExperiment(val info: SavedExperimentInfo, val bytes: ByteArray)

internal interface ExperimentStore {
    fun list(): List<SavedExperimentInfo>
    fun save(name: String, session: SavedRuntimeSession): SavedExperimentInfo
    fun read(id: String): StoredExperiment
    fun delete(id: String) { error("Experiment removal is unavailable") }
}

/** A portable envelope. Filenames and provider metadata are never used to validate content. */
internal object ExperimentDocumentCodec {
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = MAX_PAYLOAD_BYTES + 4096
    private const val MAGIC = 0x53474C45 // SGLE
    private const val VERSION = 1
    internal val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun read(stream: InputStream): StoredExperiment {
        val counted = BoundedInput(stream)
        val input = DataInputStream(counted)
        val (info, length) = readHeader(input)
        val expected = ByteArray(32).also(input::readFully)
        val bytes = ByteArray(length).also(input::readFully)
        require(input.read() == -1) { "Experiment contains unexpected trailing data" }
        require(MessageDigest.isEqual(expected, digest(bytes))) { "Experiment failed its integrity check" }
        return StoredExperiment(info.copy(sizeBytes = counted.count.toLong()), bytes)
    }

    fun write(stream: OutputStream, saved: StoredExperiment) {
        validateMetadata(saved.info)
        require(saved.bytes.size in 1..MAX_PAYLOAD_BYTES) { "Experiment exceeds the 16 MiB save limit" }
        DataOutputStream(stream).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeUTF(saved.info.id)
            writeUTF(saved.info.name)
            writeLong(saved.info.updatedAtEpochMillis)
            writeUTF(saved.info.scenarioId)
            writeInt(saved.bytes.size)
            write(digest(saved.bytes))
            write(saved.bytes)
            flush()
        }
    }

    /** Listing only reads bounded metadata. Full integrity and native validation happen on use. */
    fun readHeader(input: DataInputStream): Pair<SavedExperimentInfo, Int> {
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported experiment file" }
        val info = SavedExperimentInfo(input.readUTF(), input.readUTF(), input.readLong(), input.readUTF())
        validateMetadata(info)
        val length = input.readInt()
        require(length in 1..MAX_PAYLOAD_BYTES) { "Invalid experiment payload size" }
        return info to length
    }

    private fun validateMetadata(info: SavedExperimentInfo) {
        require(ID.matches(info.id) && info.name.isNotBlank() && info.name.length <= 80 &&
            info.name.none { it.isISOControl() } && info.updatedAtEpochMillis >= 0 &&
            info.scenarioId.isNotBlank() && info.scenarioId.none { it.isISOControl() } &&
            info.scenarioId.toByteArray(Charsets.UTF_8).size <= 96) { "Invalid experiment metadata" }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private class BoundedInput(stream: InputStream) : FilterInputStream(stream) {
        var count = 0
            private set
        override fun read(): Int {
            val value = `in`.read()
            if (value != -1) record(1)
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = `in`.read(bytes, offset, minOf(length, MAX_DOCUMENT_BYTES - count + 1))
            if (read > 0) record(read)
            return read
        }
        private fun record(bytes: Int) {
            count += bytes
            require(count <= MAX_DOCUMENT_BYTES) { "Experiment exceeds the document size limit" }
        }
    }
}

/** Each save is an immutable version, committed with a synced, same-directory atomic rename. */
internal class FileExperimentStore(private val directory: File) : ExperimentStore {
    @Synchronized
    override fun list(): List<SavedExperimentInfo> = files().map { file ->
        try {
            require(file.length() in 1..ExperimentDocumentCodec.MAX_DOCUMENT_BYTES.toLong()) { "Invalid experiment file size" }
            val (info, length) = DataInputStream(FileInputStream(file)).use(ExperimentDocumentCodec::readHeader)
            require(file.name == info.id + EXTENSION && length.toLong() <= file.length()) { "Invalid experiment metadata" }
            info.copy(sizeBytes = file.length())
        } catch (_: Exception) {
            // Damaged files still occupy space and the catalogue limit. Keep them visible and removable.
            SavedExperimentInfo(file.name.removeSuffix(EXTENSION), "Unreadable experiment",
                file.lastModified().coerceAtLeast(0), "", file.length(), "The saved file has damaged or unsupported metadata.")
        }
    }.sortedByDescending { it.updatedAtEpochMillis }

    @Synchronized
    override fun save(name: String, session: SavedRuntimeSession): SavedExperimentInfo {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80 && cleanName.none { it.isISOControl() }) { "Choose an experiment name of 1–80 characters" }
        require(session.bytes.isNotEmpty() && session.bytes.size <= MAX_BYTES) { "Experiment exceeds the 16 MiB save limit" }
        require(session.scenarioId.isNotBlank() && session.scenarioId.toByteArray(Charsets.UTF_8).size <= 96) { "Invalid scenario identity" }
        check(directory.isDirectory || directory.mkdirs()) { "Experiment folder is unavailable" }
        check(files().size < MAX_SAVES) { "This device has reached the 256 experiment limit. Export and remove an older saved copy to make room." }
        val info = SavedExperimentInfo(UUID.randomUUID().toString(), cleanName, System.currentTimeMillis(), session.scenarioId)
        val target = path(info.id)
        val temporary = File.createTempFile(".experiment-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { file ->
                ExperimentDocumentCodec.write(file, StoredExperiment(info, session.bytes))
                file.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return info.copy(sizeBytes = target.length())
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun read(id: String): StoredExperiment {
        val file = path(id)
        require(file.length() in 1..ExperimentDocumentCodec.MAX_DOCUMENT_BYTES.toLong()) { "Invalid experiment file size" }
        return FileInputStream(file).use(ExperimentDocumentCodec::read).also {
            require(it.info.id == id) { "Invalid experiment identity" }
        }
    }

    @Synchronized
    override fun delete(id: String) {
        check(path(id).delete()) { "The saved copy could not be removed" }
    }

    private fun files(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.endsWith(EXTENSION) && ExperimentDocumentCodec.ID.matches(file.name.removeSuffix(EXTENSION))
    }?.toList().orEmpty()

    private fun path(id: String): File {
        require(ExperimentDocumentCodec.ID.matches(id)) { "Invalid experiment identity" }
        return File(directory, id + EXTENSION)
    }

    companion object {
        internal const val MAX_BYTES = ExperimentDocumentCodec.MAX_PAYLOAD_BYTES
        internal const val MAX_SAVES = 256
        private const val EXTENSION = ".sglexp"
    }
}
