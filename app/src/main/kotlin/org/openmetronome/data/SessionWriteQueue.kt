package org.openmetronome.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openmetronome.domain.model.MetronomeConfig

/** Process-owned retry queue. Durable intent precedes the idempotent Room transaction. */
internal class SessionWriteQueue(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
    private val checkpoint: suspend (PracticeSession, List<PracticeDay>) -> Unit,
) {
    private val lock = Mutex()
    private val pending = linkedMapOf<String, PracticeSession>()

    suspend fun record(session: PracticeSession) = lock.withLock {
        pending[session.id] = session
        flushLocked()
    }

    suspend fun retry(recovering: Boolean = false) = lock.withLock { flushLocked(recovering) }

    private suspend fun flushLocked(recovering: Boolean = false) {
        // Read every durable entry before writing anything; preserve corrupt files for recovery.
        val files = if (root.exists()) requireNotNull(root.listFiles()) { "Unreadable session write journal" } else emptyArray()
        val saved = files.filter { it.name.endsWith(".ombak") }.map { file ->
            val snapshot = file.inputStream().use(BackupArchive::readStored)
            val session = snapshot.sessions.single()
            check(file.name == "${session.id}.ombak")
            session
        }
        saved.forEach { pending.putIfAbsent(it.id, it) }
        for (original in pending.values.toList()) {
            val session = if (recovering && !original.finished) original.copy(finished = true, interrupted = true) else original
            pending[session.id] = session
            UUID.fromString(session.id).also { require(it.toString() == session.id) }
            check(root.isDirectory || root.mkdirs())
            syncDirectory(requireNotNull(root.parentFile))
            val target = File(root, "${session.id}.ombak")
            val temporary = File(root, ".${session.id}.tmp")
            val days = PracticeTimeline.days(session.id, session.startedAt, session.durationMs, java.time.ZoneId.of(session.zoneId))
            val snapshot = BackupSnapshot(ConfigCodec.encode(MetronomeConfig()), emptyList(), listOf(session), days, emptyList(), emptyMap())
            FileOutputStream(temporary).use { BackupArchive.writeStored(snapshot, it); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(root)
            checkpoint(session, days)
            check(target.delete())
            syncDirectory(root)
            pending.remove(session.id)
        }
    }
}
