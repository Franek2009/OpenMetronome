package org.openmetronome.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

class RestoreJournalTest {
    private fun snapshot(bpm: Int) = BackupSnapshot(ConfigCodec.encode(MetronomeConfig(tempo = Tempo(bpm))),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
    private fun fixture(action: (File, RestoreJournal) -> Unit) {
        val dir = Files.createTempDirectory("restore-journal-test-").toFile()
        try { action(dir, RestoreJournal(File(dir, "journal"))) }
        finally { dir.deleteRecursively() }
    }
    @Test fun `reopened prepared journal selects old state and is replayable`() = fixture { dir, journal ->
        journal.prepare(snapshot(91), snapshot(137))
        repeat(3) {
            val pending = RestoreJournal(File(dir, "journal")).pending()!!
            assertFalse(pending.committed)
            assertEquals(91, ConfigCodec.decode(pending.snapshot.settings).tempo.bpm)
        }
        journal.finish()
        assertNull(journal.pending())
    }
    @Test fun `durable commit selects new state until journal is retired`() = fixture { dir, journal ->
        journal.prepare(snapshot(91), snapshot(137)); journal.markCommitted()
        val reopened = RestoreJournal(File(dir, "journal"))
        assertTrue(reopened.pending()!!.committed)
        assertEquals(137, ConfigCodec.decode(reopened.pending()!!.snapshot.settings).tempo.bpm)
        reopened.finish(); assertNull(RestoreJournal(File(dir, "journal")).pending())
    }
    @Test fun `incomplete preparation is discarded without a recovery decision`() = fixture { dir, _ ->
        val active = File(dir, "journal/active").also { it.mkdirs() }
        File(active, ".before.ombak.tmp").writeText("partial")
        val journal = RestoreJournal(File(dir, "journal"))
        assertNull(journal.pending()); assertFalse(active.exists())
    }
    @Test fun `unreadable committed marker and selected archive preserve recovery files`() = fixture { _, journal ->
        journal.prepare(snapshot(91), snapshot(137))
        File(journal.active, "committed").writeText("partial")
        assertThrows(Exception::class.java) { journal.pending() }
        assertTrue(File(journal.active, "before.ombak").exists())
        File(journal.active, "committed").delete()
        File(journal.active, "before.ombak").writeText("corrupt")
        assertThrows(Exception::class.java) { journal.pending() }
        assertTrue(File(journal.active, "ready").exists())
    }
    @Test fun `interrupted retirement cannot partially delete an active journal`() = fixture { dir, _ ->
        var fail = false
        val journal = RestoreJournal(File(dir, "journal")) { directory ->
            if (fail && directory.name == "journal") throw java.io.IOException("fsync failed")
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        journal.prepare(snapshot(91), snapshot(137)); journal.markCommitted(); fail = true
        assertThrows(Exception::class.java) { journal.finish() }
        assertFalse(journal.active.exists())
        val retired = File(dir, "journal").listFiles()!!.single { it.name.startsWith("retired-") }
        assertTrue(File(retired, "before.ombak").isFile)
        assertTrue(File(retired, "after.ombak").isFile)
        assertNull(RestoreJournal(File(dir, "journal")).pending())
    }
}
