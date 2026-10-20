package org.openmetronome.data

import java.io.StringWriter
import java.io.Writer
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class SessionCsvTest {
    private fun session(id: String = "s") = PracticeSession(id, 0, 125000, 125000, true, false, "Europe/Warsaw",
        ConfigCodec.encode(MetronomeConfig(tempo = Tempo(87))))
    private fun csv(sessions: List<PracticeSession>) = StringWriter().also { SessionCsv.write(sessions, it) }.toString()

    @Test fun `empty history has BOM header and CRLF but no invented session`() {
        val text = csv(emptyList())
        assertTrue(text.startsWith("\uFEFF\"session_id\";"))
        assertEquals(1, text.count { it == '\n' })
        assertTrue(text.endsWith("\r\n"))
    }
    @Test fun `exports all supplied sessions with dates durations and three statuses`() {
        val sessions = (0..150).map { session("s$it").copy(finished = it != 0, interrupted = it == 1) }
        val text = csv(sessions)
        assertEquals(152, text.count { it == '\n' })
        assertTrue(text.contains("1970-01-01T01:00:00+01:00"))
        assertTrue(text.contains("\"125000\";\"finished\";\"87\";\"4/4\""))
        assertTrue(text.contains("\"interrupted\"")); assertTrue(text.contains("\"in_progress\""))
        assertEquals(151, sessions.size)
    }
    @Test fun `quotes delimiters newlines quotes and neutralizes spreadsheet formulas`() {
        assertTrue(csv(listOf(session("ż;\"\n"))).contains("\"ż;\"\"\n\""))
        for (prefix in listOf("=", "+", "-", "@", "  =")) {
            assertTrue(csv(listOf(session("${prefix}formula"))).contains("\"'${prefix}formula\""))
        }
    }
    @Test fun `unsupported historical config leaves tempo empty and write failures propagate`() {
        assertTrue(csv(listOf(session().copy(config = "version=999"))).endsWith(";\"\";\"\"\r\n"))
        val broken = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) { throw IOException("fixture") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { SessionCsv.write(listOf(session()), broken) }
    }
}
