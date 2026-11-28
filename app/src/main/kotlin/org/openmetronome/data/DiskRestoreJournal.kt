package org.openmetronome.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Both snapshot trees are immutable once READY exists. Work files and space escrow are disposable. */
internal class DiskRestoreJournal(
    private val legacy: RestoreJournal,
    private val syncDirectory: (File) -> Unit,
) {
    private val active get() = legacy.active
    private val root get() = requireNotNull(active.parentFile)
    private val preparation get() = File(root, "preparing-v2")
    private val ready get() = File(active, "ready")
    private val committed get() = File(active, "committed")
    private val reserve get() = File(active, "rollback-reserve")
    val before get() = RestoreDiskSnapshot(File(active, "before"))
    val after get() = RestoreDiskSnapshot(File(active, "after"))
    val readyExists get() = ready.exists()

    fun begin() {
        check(!active.exists())
        check(root.isDirectory || root.mkdirs())
        syncDirectory(requireNotNull(root.parentFile))
        check(preparation.mkdir())
        syncDirectory(root)
    }

    fun preparingBefore() = RestoreDiskSnapshot(File(preparation, "before"))
    fun preparingAfter() = RestoreDiskSnapshot(File(preparation, "after"))

    fun discardPreparation() {
        require(!Files.isSymbolicLink(preparation.toPath()))
        if (preparation.exists()) {
            check(preparation.isDirectory && preparation.deleteRecursively())
            syncDirectory(root)
        }
    }

    fun seal(forwardBytes: Long, rollbackBytes: Long, allocate: (File, Long) -> Unit,
        checkpoint: (RestoreCheckpoint) -> Unit) {
        require(forwardBytes >= 0 && rollbackBytes > 0)
        val entries = entries(preparation)
        val operation = UUID.randomUUID().toString()
        for (snapshot in listOf(preparingBefore(), preparingAfter())) {
            snapshot.verifyRows()
            snapshot.sync(syncDirectory)
        }
        val manifest = File(preparation, "manifest")
        writeAtomic(manifest) { output ->
            output.writeUTF(MAGIC)
            output.writeUTF(operation)
            output.writeInt(entries.size)
            for ((name, file) in entries) {
                output.writeUTF(name)
                output.writeLong(file.length())
                output.write(hash(file))
            }
        }
        require(preparation.usableSpace >= Math.addExact(forwardBytes, rollbackBytes)) {
            "Insufficient space for restore and rollback"
        }
        val escrow = File(preparation, reserve.name)
        allocate(escrow, rollbackBytes)
        FileOutputStream(escrow, true).use { it.fd.sync() }
        syncDirectory(preparation)
        val marker = Marker(operation, hash(manifest))
        verify(preparation, marker)
        checkpoint(RestoreCheckpoint.RESERVE_DURABLE)
        writeAtomic(File(preparation, ready.name)) { writeMarker(it, marker) }
        Files.move(preparation.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(root)
    }

    fun usesDiskSnapshots(): Boolean = listOf("manifest", "before", "after").any {
        Files.exists(File(active, it).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    fun pending(): Pending {
        require(active.isDirectory && !Files.isSymbolicLink(active.toPath()))
        val marker = readMarker(ready)
        verify(active, marker)
        val finished = committed.exists()
        if (finished) {
            val commit = readMarker(committed)
            require(commit.operation == marker.operation && commit.hash.contentEquals(marker.hash))
        }
        return Pending(if (finished) after else before, finished)
    }

    fun markCommitted() {
        val marker = readMarker(ready)
        writeAtomic(committed) { writeMarker(it, marker) }
    }

    fun releaseReserve() {
        if (reserve.exists()) {
            check(reserve.delete())
            syncDirectory(active)
        }
    }

    data class Pending(val snapshot: RestoreDiskSnapshot, val committed: Boolean)
    private data class Marker(val operation: String, val hash: ByteArray)

    private fun verify(directory: File, marker: Marker) {
        val manifest = File(directory, "manifest")
        require(regular(manifest) && manifest.length() in 1..8192)
        require(hash(manifest).contentEquals(marker.hash)) { "Restore manifest hash mismatch" }
        val actual = entries(directory).toMutableMap()
        manifest.inputStream().buffered().use { stream ->
            val input = DataInputStream(stream)
            require(input.readUTF() == MAGIC && input.readUTF() == marker.operation)
            require(input.readInt() == actual.size)
            repeat(actual.size) {
                val file = requireNotNull(actual.remove(input.readUTF()))
                require(input.readLong() == file.length())
                require(ByteArray(32).also(input::readFully).contentEquals(hash(file))) {
                    "Restore snapshot hash mismatch"
                }
            }
            require(actual.isEmpty() && input.read() == -1)
        }
    }

    private fun entries(directory: File): Map<String, File> = buildMap {
        for (role in listOf("before", "after")) {
            val dir = File(directory, role)
            require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
            val files = requireNotNull(dir.listFiles()).associateBy(File::getName)
            require(files.keys == setOf("records.db", "preferences.pb", "samples"))
            for (name in listOf("records.db", "preferences.pb")) {
                val file = files.getValue(name)
                require(regular(file))
                put("$role/$name", file)
            }
            val samples = File(dir, "samples")
            require(samples.isDirectory && !Files.isSymbolicLink(samples.toPath()))
            val wavs = requireNotNull(samples.listFiles())
            require(wavs.size <= 32)
            for (wav in wavs.sortedBy(File::getName)) {
                require(regular(wav) && wav.name.endsWith(".wav"))
                val id = wav.name.removeSuffix(".wav")
                require(UUID.fromString(id).toString() == id)
                put("$role/samples/${wav.name}", wav)
            }
        }
    }

    private fun readMarker(file: File): Marker {
        require(regular(file) && file.length() in 1..256)
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readUTF() == MAGIC)
            val operation = input.readUTF()
            require(UUID.fromString(operation).toString() == operation)
            val digest = ByteArray(32).also(input::readFully)
            require(input.read() == -1)
            return Marker(operation, digest)
        }
    }

    private fun writeMarker(output: DataOutputStream, marker: Marker) {
        output.writeUTF(MAGIC)
        output.writeUTF(marker.operation)
        output.write(marker.hash)
    }

    private fun writeAtomic(target: File, write: (DataOutputStream) -> Unit) {
        val directory = requireNotNull(target.parentFile)
        val tmp = File(directory, ".${target.name}.tmp")
        FileOutputStream(tmp).use { raw ->
            val output = DataOutputStream(raw.buffered())
            write(output)
            output.flush()
            raw.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
    }

    private fun hash(file: File): ByteArray = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun regular(file: File) = file.isFile && !Files.isSymbolicLink(file.toPath())

    private companion object { const val MAGIC = "OpenMetronome restore journal 2" }
}
