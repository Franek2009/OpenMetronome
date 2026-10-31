package org.openmetronome.data

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Undo before the durable commit marker, redo afterwards. Files belong only to this journal. */
internal class RestoreJournal(
    private val root: File,
    private val syncDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    },
) {
    val active = File(root, "active")
    private val magic = "OpenMetronome restore journal 1\n"
    data class Pending(val snapshot: BackupSnapshot, val committed: Boolean)

    fun prepare(before: BackupSnapshot, after: BackupSnapshot) {
        check(!active.exists()) { "Unfinished restore journal" }
        check(root.isDirectory || root.mkdirs())
        syncDirectory(requireNotNull(root.parentFile))
        check(active.mkdir())
        syncDirectory(root)
        writeAtomic(File(active, "before.ombak")) { BackupArchive.write(before, it) }
        writeAtomic(File(active, "after.ombak")) { BackupArchive.write(after, it) }
        // No store may be modified until this marker and both archives are durable.
        writeAtomic(File(active, "ready")) { it.write(magic.toByteArray(Charsets.UTF_8)) }
    }

    fun pending(): Pending? {
        if (!active.exists()) { collectRetired(); return null }
        check(active.isDirectory && !Files.isSymbolicLink(active.toPath()))
        if (!File(active, "ready").exists()) {
            // Preparation was interrupted before any store mutation was allowed.
            finish()
            return null
        }
        checkMarker(File(active, "ready"))
        val commit = File(active, "committed")
        if (commit.exists()) checkMarker(commit)
        val archive = File(active, if (commit.exists()) "after.ombak" else "before.ombak")
        check(archive.isFile && !Files.isSymbolicLink(archive.toPath()))
        return Pending(archive.inputStream().use(BackupArchive::read), commit.exists())
    }

    fun markCommitted() {
        checkMarker(File(active, "ready"))
        writeAtomic(File(active, "committed")) { it.write(magic.toByteArray(Charsets.UTF_8)) }
    }

    fun finish() {
        if (active.exists()) {
            val retired = File(root, "retired-${UUID.randomUUID()}")
            // Never partially delete an active journal: a crash can only leave a whole one.
            Files.move(active.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(root)
        }
        collectRetired()
    }

    private fun checkMarker(file: File) {
        check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == magic.toByteArray(Charsets.UTF_8).size.toLong())
        check(file.readText() == magic) { "Unreadable restore marker" }
    }

    private fun collectRetired() {
        root.listFiles()?.filter { candidate ->
            candidate.name.startsWith("retired-") &&
                runCatching { UUID.fromString(candidate.name.removePrefix("retired-")).toString() == candidate.name.removePrefix("retired-") }.getOrDefault(false) &&
                !Files.isSymbolicLink(candidate.toPath())
        }?.forEach { it.deleteRecursively() }
    }

    private fun writeAtomic(target: File, write: (FileOutputStream) -> Unit) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output -> write(output); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(requireNotNull(target.parentFile))
    }
}

internal enum class RestoreCheckpoint {
    PREPARED, OLD_FILES_MOVED, NEW_FILES_MOVED, DATABASE_CHANGED, SETTINGS_WRITTEN,
    DATABASE_COMMITTED, STORES_SYNCED, COMMITTED, JOURNAL_RETIRED,
    RECOVERY_FILES_MOVED, RECOVERY_DATABASE_CHANGED, RECOVERY_STORES_SYNCED,
}
