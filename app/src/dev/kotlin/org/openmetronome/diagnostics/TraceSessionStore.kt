package org.openmetronome.diagnostics

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.openmetronome.domain.audio.PcmTrace

/** Process-owned, bounded DEV recorder. Constructor/start do no synchronous disk IO. */
class TraceSessionStore internal constructor(
    root: File,
    private val capacity: Int = BoundedPcmTrace.MAX_CAPACITY,
    sessionLimit: Long = 512L * 1024 * 1024,
    totalLimit: Long = 2L * 1024 * 1024 * 1024,
    fault: (String) -> Unit = {},
) : AutoCloseable {
    data class Status(val ready: Boolean = false, val active: Int = 0, val notice: String? = null)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val journal = TraceJournal(root, sessionLimit, totalLimit, fault)
    private val lock = Any()
    private val sessions = mutableListOf<Session>()
    private var reserved = 0L
    private var closing = false
    private class Session(val id: UUID, val engineId: Long, val trace: BoundedPcmTrace) {
        var writer: TraceJournal.Writer? = null
        var opened = false
        var failure: String? = null
        var lastSync = System.nanoTime()
    }
    private val io = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "OpenMetronome-TraceIO") }

    init {
        require(capacity in 1..BoundedPcmTrace.MAX_CAPACITY && capacity and (capacity - 1) == 0)
        io.execute {
            try { journal.recover(); mutableStatus.update { it.copy(ready = true) } }
            catch (_: Exception) { mutableStatus.update { it.copy(ready = false, notice = "recovery_failed") } }
        }
        io.scheduleWithFixedDelay(::pumpSafely, 10, 10, TimeUnit.MILLISECONDS)
    }

    /** Called by Start before the audio worker. No filesystem access or worker wait. */
    fun start(engineId: Long): PcmTrace? = synchronized(lock) {
        if (closing || !mutableStatus.value.ready) {
            mutableStatus.update { it.copy(notice = "diagnostics_unavailable") }
            return@synchronized null
        }
        // Include IO buffers and leave room for the following control lane stage.
        val bytes = capacity.toLong() * 68 + 1312 + 512 * 1024
        if (reserved + bytes > BoundedPcmTrace.BUFFER_BUDGET_BYTES) {
            mutableStatus.update { it.copy(notice = "memory_limit") }
            return@synchronized null
        }
        val trace = BoundedPcmTrace(capacity)
        val session = Session(UUID.randomUUID(), engineId, trace)
        reserved += bytes
        sessions.add(session)
        mutableStatus.update { it.copy(active = sessions.size, notice = null) }
        trace
    }

    private fun pumpSafely() {
        // No exception may terminate the repeating task and strand reservations.
        val snapshot = synchronized(lock) { sessions.toList() }
        snapshot.forEach { session ->
            try { pump(session) }
            catch (error: Exception) { fail(session, error) }
        }
        if (synchronized(lock) { closing && sessions.isEmpty() }) io.shutdown()
    }

    private fun fail(session: Session, error: Exception) {
        session.trace.stopCapture()
        session.failure = when (error.message) {
            "session_limit", "total_limit" -> error.message
            else -> "storage_failed"
        }
        runCatching { session.writer?.close() }
        session.writer = null
        mutableStatus.update { it.copy(notice = session.failure) }
    }

    private fun pump(session: Session) {
        if (!session.opened) {
            session.opened = true
            session.writer = journal.open(session.id, session.engineId)
        }
        // Bound one pass; a session cannot starve another session's finalization.
        var drained = 0
        while (drained < 256) {
            val record = session.trace.poll() ?: break
            session.writer?.append(record)
            drained++
        }
        val health = session.trace.health()
        // Observe closed before checking empty: a producer may publish after an
        // earlier empty poll, but never after close's volatile publication.
        val finished = health?.closed == true && drained < 256
        if (finished) {
            val last = session.trace.poll()
            if (last != null) { session.writer?.append(last); return }
        }
        val now = System.nanoTime()
        if (session.writer != null && (finished || now - session.lastSync >= 2_000_000_000L)) {
            val incomplete = session.failure != null || health?.faulted == true || (health?.dropped ?: 0) > 0 ||
                (finished && health?.accepted != session.writer!!.persistedRecords)
            session.writer!!.checkpoint(health,
                if (!finished) "recording" else if (incomplete) "incomplete" else "complete", session.failure)
            session.lastSync = now
        }
        if (finished) {
            if (session.writer == null && session.failure != null) {
                // A best-effort final manifest is independent of the failed writer.
                // Failure here leaves a recoverable interrupted journal, not success.
                runCatching { journal.finishFailed(session.id, health, session.failure!!) }
            }
            session.writer?.close()
            session.writer = null
            synchronized(lock) {
                sessions.remove(session)
                reserved -= capacity.toLong() * 68 + 1312 + 512 * 1024
                mutableStatus.update { it.copy(active = sessions.size) }
            }
        }
    }

    /** Does not cancel finalization or block a caller while a producer is active. */
    override fun close() = synchronized(lock) { closing = true }

    internal fun awaitTermination(timeout: Long = 10): Boolean = io.awaitTermination(timeout, TimeUnit.SECONDS)
}
