package org.openmetronome.data

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig

class SessionWriteQueueTest {
    private fun session() = PracticeSession(UUID.randomUUID().toString(), 0, 1000, 1000, true, false,
        "UTC", ConfigCodec.encode(MetronomeConfig()))
    private fun sync(file: File) { FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) } }

    @Test fun `disk failure retains durable intent and restart retries idempotently`() = runBlocking {
        val root = Files.createTempDirectory("session-write-test").toFile()
        val row = session()
        try {
            val queue = SessionWriteQueue(root, ::sync) { _, _ -> throw java.io.IOException("database full") }
            try { queue.record(row); fail() } catch (_: java.io.IOException) { }
            assertTrue(File(root, "${row.id}.ombak").isFile)
            val saved = mutableMapOf<String, PracticeSession>()
            val reopened = SessionWriteQueue(root, ::sync) { item, _ -> saved[item.id] = item }
            reopened.retry(recovering = true)
            reopened.retry(recovering = true)
            assertEquals(mapOf(row.id to row), saved)
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".ombak") })
        } finally { root.deleteRecursively() }
    }

    @Test fun `journal failure retains multiple sessions in memory for later retry`() = runBlocking {
        val root = Files.createTempDirectory("session-write-test").toFile()
        var full = true
        val saved = mutableMapOf<String, PracticeSession>()
        val queue = SessionWriteQueue(root, { if (full) throw java.io.IOException("disk full") else sync(it) }) { row, _ -> saved[row.id] = row }
        val rows = listOf(session(), session())
        try {
            for (row in rows) try { queue.record(row); fail() } catch (_: java.io.IOException) { }
            full = false
            queue.retry()
            assertEquals(rows.associateBy { it.id }, saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun `unfinished durable checkpoint becomes interrupted on restart`() = runBlocking {
        val root = Files.createTempDirectory("session-write-test").toFile()
        val row = session().copy(finished = false)
        try {
            try { SessionWriteQueue(root, ::sync) { _, _ -> throw java.io.IOException() }.record(row) } catch (_: java.io.IOException) { }
            var saved: PracticeSession? = null
            SessionWriteQueue(root, ::sync) { item, _ -> saved = item }.retry(recovering = true)
            assertEquals(row.copy(finished = true, interrupted = true), saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun `failed startup recovery remains interrupted when retried later`() = runBlocking {
        val root = Files.createTempDirectory("session-write-test").toFile()
        val row = session().copy(finished = false)
        try {
            try { SessionWriteQueue(root, ::sync) { _, _ -> throw java.io.IOException() }.record(row) } catch (_: java.io.IOException) { }
            var full = true
            var saved: PracticeSession? = null
            val reopened = SessionWriteQueue(root, ::sync) { item, _ ->
                if (full) throw java.io.IOException()
                saved = item
            }
            try { reopened.retry(recovering = true); fail() } catch (_: java.io.IOException) { }
            full = false
            reopened.retry()
            assertEquals(row.copy(finished = true, interrupted = true), saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun `corrupt intent is preserved and retry refuses partial success`() = runBlocking {
        val root = Files.createTempDirectory("session-write-test").toFile()
        val corrupt = File(root, "broken.ombak").also { it.writeText("broken") }
        try {
            var called = false
            try { SessionWriteQueue(root, ::sync) { _, _ -> called = true }.retry(); fail() } catch (_: IllegalArgumentException) { }
            assertFalse(called)
            assertEquals("broken", corrupt.readText())
        } finally { root.deleteRecursively() }
    }
}
