package org.openmetronome.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*

class SessionRecorderTest {
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
