package org.openmetronome.data

import java.time.ZoneId
import java.util.UUID
import org.openmetronome.audio.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called on a storage coroutine, not on the AudioTrack worker. */
class SessionRecorder internal constructor(
    private val onObservation: ((HistoryObservation) -> Unit)? = null,
    private val checkpoint: suspend (PracticeSession, List<PracticeDay>) -> Unit,
) {
    constructor(data: AppData, onObservation: ((HistoryObservation) -> Unit)? = null) : this(onObservation, { session, _ ->
        data.recordSession(session)
    })
    private var session: PracticeSession? = null
    private var lastSavedMs = -5000L
    private val lock = Mutex()
    private var lastClosedSessionId: Long? = null

    suspend fun accept(state: MetronomeState) = lock.withLock {
        val diagnostics = state.audio.diagnostics
        val played = diagnostics?.let { it.framesPlayed * 1000 / it.sampleRate } ?: 0
        val final = state.audio.status == PlaybackStatus.STOPPED || state.audio.status == PlaybackStatus.ERROR
        if (session == null && (state.audio.status == PlaybackStatus.PLAYING || final) && played > 0 && diagnostics?.sessionId != lastClosedSessionId) {
            val now = System.currentTimeMillis()
            session = PracticeSession(UUID.randomUUID().toString(), now - played, now, 0, false, false,
                ZoneId.systemDefault().id, ConfigCodec.encode(state.config))
            lastSavedMs = -5000
            observe(diagnostics?.sessionId, session!!, HistoryObservationStage.CREATED)
        }
        val old = session ?: return@withLock
        val duration = maxOf(old.durationMs, played)
        val current = old.copy(durationMs = duration, checkpointAt = old.startedAt + duration,
            finished = final, interrupted = state.audio.status == PlaybackStatus.ERROR)
        session = current
        if (final || duration - lastSavedMs >= 5000) {
            observe(diagnostics?.sessionId, current, HistoryObservationStage.CHECKPOINT_REQUESTED)
            try {
                checkpoint(current, PracticeTimeline.days(current.id, current.startedAt, duration, ZoneId.of(current.zoneId)))
            } catch (error: Exception) {
                observe(diagnostics?.sessionId, current, HistoryObservationStage.CHECKPOINT_FAILED)
                throw error
            }
            observe(diagnostics?.sessionId, current, HistoryObservationStage.CHECKPOINT_RETURNED)
            lastSavedMs = duration
            if (final) {
                lastClosedSessionId = diagnostics?.sessionId
                session = null
            }
        }
        if (final) session = null
    }
    /** Optional diagnostics cannot fail the history recorder or change its UUIDs. */
    private fun observe(engineId: Long?, session: PracticeSession, stage: HistoryObservationStage) {
        val observer = onObservation ?: return
        if (engineId == null) return
        try { observer(HistoryObservation(engineId, session.id, stage, session.finished, session.interrupted)) }
        catch (_: Exception) { /* diagnostics failure never rejects a checkpoint */ }
    }
}
