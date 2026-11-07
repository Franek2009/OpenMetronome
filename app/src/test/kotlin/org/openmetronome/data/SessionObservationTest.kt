package org.openmetronome.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*

class SessionObservationTest {
    private fun state(finished: Boolean = false) = MetronomeState(audio = AudioEngineState(
        status = if (finished) PlaybackStatus.STOPPED else PlaybackStatus.PLAYING,
        diagnostics = AudioDiagnostics(1000, 20, 80, 1000, 0, framesPlayed = 1000, sessionId = 7),
    ))

    @Test fun optionalObserverReportsCreationAndCheckpointWithoutChangingHistoryIdentity() = runBlocking<Unit> {
        val events = mutableListOf<HistoryObservation>(); val saved = mutableListOf<PracticeSession>()
        val recorder = SessionRecorder(onObservation = events::add, checkpoint = { session, _ -> saved.add(session) })
        recorder.accept(state()); recorder.accept(state(true))
        assertEquals(listOf(HistoryObservationStage.CREATED, HistoryObservationStage.CHECKPOINT_REQUESTED,
            HistoryObservationStage.CHECKPOINT_RETURNED, HistoryObservationStage.CHECKPOINT_REQUESTED,
            HistoryObservationStage.CHECKPOINT_RETURNED), events.map { it.stage })
        assertEquals(1, events.map { it.historyId }.toSet().size)
        assertEquals(events.first().historyId, saved.first().id)
        assertTrue(events.all { it.engineSessionId == 7L })
        assertFalse(saved.first().finished); assertTrue(saved.last().finished)
    }

    @Test fun observerFailureNeverRejectsARealCheckpoint() = runBlocking<Unit> {
        var saved: PracticeSession? = null
        val recorder = SessionRecorder(onObservation = { throw IOException("diagnostics disk") },
            checkpoint = { session, _ -> saved = session })
        recorder.accept(state(true))
        assertNotNull(saved); assertTrue(saved!!.finished)
    }

    @Test fun checkpointFailureRemainsARealFailureAndRetryUsesSameHistoryUuid() = runBlocking<Unit> {
        val events = mutableListOf<HistoryObservation>(); var fail = true
        val recorder = SessionRecorder(onObservation = events::add, checkpoint = { _, _ -> if (fail) throw IOException("history disk") })
        assertThrows(IOException::class.java) { runBlocking { recorder.accept(state(true)) } }
        assertEquals(HistoryObservationStage.CHECKPOINT_FAILED, events.last().stage)
        fail = false; recorder.accept(state(true))
        assertEquals(HistoryObservationStage.CHECKPOINT_RETURNED, events.last().stage)
        assertEquals(1, events.count { it.stage == HistoryObservationStage.CREATED })
        assertEquals(1, events.map { it.historyId }.toSet().size)
    }
}
