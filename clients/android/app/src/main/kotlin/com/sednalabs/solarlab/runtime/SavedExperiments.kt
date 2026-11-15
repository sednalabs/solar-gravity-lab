package com.sednalabs.solarlab.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
}

/** Each save is an immutable version. A synced temp file is atomically renamed only
 * when its header, checksum and complete native payload have been written. */
internal class FileExperimentStore(private val directory: File) : ExperimentStore {
    @Synchronized
    override fun list(): List<SavedExperimentInfo> = files().mapNotNull { file ->
        runCatching {
            DataInputStream(FileInputStream(file)).use { readHeader(it, file).first }
        }.getOrNull()
    }.sortedByDescending { it.updatedAtEpochMillis }

    @Synchronized
    override fun save(name: String, session: SavedRuntimeSession): SavedExperimentInfo {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80 && cleanName.none { it.isISOControl() }) { "Choose an experiment name of 1–80 characters" }
        require(session.bytes.isNotEmpty() && session.bytes.size <= MAX_BYTES) { "Experiment exceeds the 16 MiB save limit" }
        require(session.scenarioId.isNotBlank() && session.scenarioId.toByteArray(Charsets.UTF_8).size <= 96) { "Invalid scenario identity" }
        check(directory.isDirectory || directory.mkdirs()) { "Experiment folder is unavailable" }
        check(files().size < MAX_SAVES) { "This device has reached the 256 experiment limit" }
        val info = SavedExperimentInfo(UUID.randomUUID().toString(), cleanName, System.currentTimeMillis(), session.scenarioId)
        val target = path(info.id)
        val temporary = File.createTempFile(".experiment-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(info.id)
                output.writeUTF(info.name)
                output.writeLong(info.updatedAtEpochMillis)
                output.writeUTF(info.scenarioId)
                output.writeInt(session.bytes.size)
                output.write(digest(session.bytes))
                output.write(session.bytes)
                output.flush()
                file.fd.sync()
            }
            // Same directory ensures the commit stays on one filesystem. Never expose
            // a partial destination or replace an earlier experiment on write failure.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return info
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun read(id: String): StoredExperiment {
        val file = path(id)
        return DataInputStream(FileInputStream(file)).use { input ->
            val (info, length) = readHeader(input, file)
            val expected = ByteArray(32).also(input::readFully)
            val bytes = ByteArray(length).also(input::readFully)
            require(input.read() == -1) { "Experiment contains unexpected trailing data" }
            require(MessageDigest.isEqual(expected, digest(bytes))) { "Experiment failed its integrity check" }
            StoredExperiment(info, bytes)
        }
    }

    private fun files(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.endsWith(EXTENSION) && ID.matches(file.name.removeSuffix(EXTENSION))
    }?.toList().orEmpty()

    private fun path(id: String): File {
        require(ID.matches(id)) { "Invalid experiment identity" }
        return File(directory, id + EXTENSION)
    }

    private fun readHeader(input: DataInputStream, file: File): Pair<SavedExperimentInfo, Int> {
        require(file.length() in 1..(MAX_BYTES + 4096).toLong()) { "Invalid experiment file size" }
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported experiment file" }
        val info = SavedExperimentInfo(input.readUTF(), input.readUTF(), input.readLong(), input.readUTF())
        require(ID.matches(info.id) && file.name == info.id + EXTENSION && info.name.isNotBlank() && info.name.length <= 80 && info.name.none { it.isISOControl() } && info.updatedAtEpochMillis >= 0 && info.scenarioId.isNotBlank() && info.scenarioId.toByteArray(Charsets.UTF_8).size <= 96) { "Invalid experiment metadata" }
        val length = input.readInt()
        require(length in 1..MAX_BYTES && length.toLong() <= file.length()) { "Invalid experiment payload size" }
        return info to length
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        internal const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_SAVES = 256
        private const val MAGIC = 0x53474C45 // SGLE
        private const val VERSION = 1
        private const val EXTENSION = ".sglexp"
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
