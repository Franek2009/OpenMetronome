package org.openmetronome.diagnostics

import java.util.UUID
import org.openmetronome.domain.model.MetronomeConfig

/** Process/engine identity prevents a service's restarted counter from aliasing old reports. */
data class TraceSessionKey(val engineInstance: UUID, val sessionId: Long) {
    val reportId: UUID get() = UUID.nameUUIDFromBytes("$engineInstance/$sessionId".toByteArray(Charsets.UTF_8))
}

internal data class JournalRecord(
    val sequence: Long, val nanos: Long, val kindCode: Int, val frame: Long,
    val a: Long, val b: Long, val c: Long, val d: Long, val config: MetronomeConfig?,
)

internal data class ControlHealth(
    val attempted: Long, val accepted: Long, val dropped: Long, val closed: Boolean,
    val droppedByKind: Map<TraceControlKind, Long>,
)

/** Only control/storage threads: bounded MPSC, never called by the audio worker. */
internal class TraceControlQueue(private val capacity: Int = 128) {
    init { require(capacity in 1..128) }
    private val queue = ArrayDeque<JournalRecord>(capacity)
    private var attempted = 0L
    private var accepted = 0L
    private var dropped = 0L
    private var closed = false
    private var enabled = true
    private val losses = LongArray(TraceControlKind.entries.size)

    @Synchronized fun offer(kind: TraceControlKind, a: Long, b: Long, c: Long, d: Long,
                            config: MetronomeConfig?): Boolean {
        // Calls after close are outside the session; its final counters are immutable.
        if (closed) return false
        val sequence = attempted++
        if (!enabled || queue.size == capacity) {
            dropped++; losses[kind.ordinal]++; return false
        }
        queue.addLast(JournalRecord(sequence, System.nanoTime(), kind.code, -1, a, b, c, d, config))
        accepted++
        return true
    }
    @Synchronized fun poll(): JournalRecord? = queue.removeFirstOrNull()
    @Synchronized fun isEmpty(): Boolean = queue.isEmpty()
    @Synchronized fun close() { closed = true }
    @Synchronized fun disable() { enabled = false }
    @Synchronized fun health() = ControlHealth(attempted, accepted, dropped, closed,
        TraceControlKind.entries.associateWith { losses[it.ordinal] })
}

/** Caller owns control closure; PCM close remains exclusively the audio producer's job. */
class TraceCapture internal constructor(
    val key: TraceSessionKey,
    val trace: BoundedPcmTrace,
    internal val controls: TraceControlQueue = TraceControlQueue(),
) {
    val reportId: UUID get() = key.reportId
    fun event(kind: TraceControlKind, a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0,
              config: MetronomeConfig? = null): Boolean = controls.offer(kind, a, b, c, d, config)
    fun historyCreated(id: UUID): Boolean = event(TraceControlKind.HISTORY_CREATED, id.mostSignificantBits, id.leastSignificantBits)
    fun observeHistory(observation: org.openmetronome.data.HistoryObservation): Boolean {
        require(observation.engineSessionId == key.sessionId) { "history_session_mismatch" }
        val id = UUID.fromString(observation.historyId)
        val kind = when (observation.stage) {
            org.openmetronome.data.HistoryObservationStage.CREATED -> TraceControlKind.HISTORY_CREATED
            org.openmetronome.data.HistoryObservationStage.CHECKPOINT_REQUESTED -> TraceControlKind.CHECKPOINT_REQUESTED
            org.openmetronome.data.HistoryObservationStage.CHECKPOINT_RETURNED -> TraceControlKind.CHECKPOINT_RETURNED
            org.openmetronome.data.HistoryObservationStage.CHECKPOINT_FAILED -> TraceControlKind.CHECKPOINT_FAILED
        }
        return event(kind, id.mostSignificantBits, id.leastSignificantBits,
            if (observation.finished) 1 else 0, if (observation.interrupted) 1 else 0)
    }
    fun finishControls() = controls.close()
}

/** Deliberate environment allowlist. No serial, Android ID, paths or sample contents. */
data class TraceEnvironment(
    val versionName: String = "unknown", val versionCode: Long? = null, val sourceRevision: String = "unknown",
    val manufacturer: String = "unknown", val model: String = "unknown", val api: Int? = null,
) {
    internal fun fields() = mapOf("versionName" to versionName.take(160), "versionCode" to (versionCode?.toString() ?: "unknown"),
        "sourceRevision" to sourceRevision.take(160), "manufacturer" to manufacturer.take(160),
        "model" to model.take(160), "api" to (api?.toString() ?: "unknown"))
}
