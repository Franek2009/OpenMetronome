package org.openmetronome.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*

class SessionRecorderTest {
    @Test fun historyIncludesPlayedChangesOnly() = runBlocking {
        val saved = mutableListOf<PracticeSession>()
        val recorder = SessionRecorder { session, _ -> saved.add(session) }
        val timeline = ConfigurationTimeline(2)
        timeline.record(0, org.openmetronome.domain.model.MetronomeConfig())
        timeline.record(48000, org.openmetronome.domain.model.MetronomeConfig(tempo = org.openmetronome.domain.model.Tempo(60)))
        fun state(frames: Long, final: Boolean) = MetronomeState(audio = AudioEngineState(if (final) PlaybackStatus.STOPPED else PlaybackStatus.PLAYING,
            diagnostics = AudioDiagnostics(48000, 256, 1024, 96000, 0, frames, 77, timeline = timeline)))
        recorder.accept(state(24000, false))
        val first = SessionTimelineCodec.decode(saved.single().timeline!!, 500)
        assertEquals(listOf(0L), first.changes.map { it.offsetMs }); assertTrue(first.complete)
        recorder.accept(state(72000, true))
        val last = SessionTimelineCodec.decode(saved.last().timeline!!, 1500)
        assertEquals(listOf(0L, 1000L), last.changes.map { it.offsetMs }); assertTrue(last.complete)
        assertEquals(60, ConfigCodec.decode(last.changes.last().config).tempo.bpm)
        assertEquals(1500L, saved.last().durationMs)
    }
    @Test fun overflowIsExplicitlyIncompleteWithoutBlockingSessionSave() = runBlocking {
        val timeline = ConfigurationTimeline(2)
        repeat(3) { timeline.record(it * 4800L, org.openmetronome.domain.model.MetronomeConfig()) }
        val saved = mutableListOf<PracticeSession>()
        val recorder = SessionRecorder { session, _ -> saved.add(session) }
        recorder.accept(MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPED,
            diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, 48000, 78, timeline = timeline))))
        assertEquals(1000L, saved.single().durationMs)
        assertFalse(SessionTimelineCodec.decode(saved.single().timeline!!, 1000).complete)
    }
    @Test fun countInDoesNotCreateOrInflatePracticeHistory() = runBlocking {
        val saved = mutableListOf<PracticeSession>()
        val recorder = SessionRecorder { session, _ -> saved.add(session) }
        fun snapshot(played: Long, final: Boolean = false) = MetronomeState(audio = AudioEngineState(
            if (final) PlaybackStatus.STOPPED else PlaybackStatus.PLAYING,
            diagnostics = AudioDiagnostics(48000, 256, 1024, played, 0, played, 42, 192000)))
        recorder.accept(snapshot(144000))
        assertTrue(saved.isEmpty())
        recorder.accept(snapshot(192000))
        assertTrue(saved.isEmpty())
        recorder.accept(snapshot(240000, true))
        assertEquals(1000L, saved.single().durationMs)
    }
    private fun stopped(id: Long) = MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPED,
        diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, framesPlayed = 48000, sessionId = id)))

    @Test fun `failed final checkpoint can be retried with same identity exactly once`() = runBlocking {
        var fail = true
        val attempts = mutableListOf<PracticeSession>()
        val recorder = SessionRecorder { session, _ ->
            attempts.add(session)
            if (fail) throw java.io.IOException("disk full")
        }
        try { recorder.accept(stopped(1)); fail("Expected disk failure") } catch (_: java.io.IOException) { }
        fail = false
        recorder.accept(stopped(1))
        recorder.accept(stopped(1))
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].id, attempts[1].id)
        assertTrue(attempts[1].finished)
    }
}
