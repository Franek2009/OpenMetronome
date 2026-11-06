package org.openmetronome.diagnostics

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openmetronome.domain.audio.*
import org.openmetronome.domain.model.*

class TraceStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(sequence: Long = 0) = PcmTraceRecord(sequence, 123, PcmTraceKind.BEAT, 456,
        0, 1, 2, 256, MetronomeConfig(tempo = Tempo(137)))
    private fun root() = temporary.newFolder()
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("Condition did not become true", condition())
    }

    @Test fun journalPreservesExactRecordsAndConfigurationWithFinalHealth() {
        val root = root(); val journal = TraceJournal(root); journal.recover()
        val id = UUID.randomUUID(); val writer = journal.open(id, 42)
        val expected = record()
        writer.append(expected)
        val trace = BoundedPcmTrace(2); trace.record(PcmTraceKind.BEAT); trace.close()
        writer.checkpoint(trace.health(), "complete"); writer.close()
        val read = mutableListOf<PcmTraceRecord>()
        val scan = journal.scan(File(root, "$id/records.bin"), read::add)
        assertEquals(listOf(expected), read)
        assertEquals(1, scan.records.toInt()); assertFalse(scan.damagedTail)
        val metadata = journal.readMetadata(File(root, id.toString()))
        assertEquals("complete", metadata.getProperty("state"))
        assertEquals("true", metadata.getProperty("finalCountersKnown"))
        assertEquals("42", metadata.getProperty("engineSessionId"))
    }

    @Test fun everyTruncatedTailRecoversOnlyCompleteRecordsAndNeverClaimsFinalCounters() {
        val root = root(); val journal = TraceJournal(root); journal.recover()
        val id = UUID.randomUUID(); val writer = journal.open(id)
        writer.append(record()); writer.checkpoint(null)
        val file = File(root, "$id/records.bin"); val first = file.readBytes()
        writer.append(record(1)); writer.checkpoint(null); writer.close()
        val full = file.readBytes()
        for (cut in first.size until full.size) {
            file.writeBytes(full.copyOf(cut))
            val read = mutableListOf<PcmTraceRecord>()
            val scan = journal.scan(file, read::add)
            assertEquals(listOf(record()), read)
            assertEquals(cut != first.size, scan.damagedTail)
        }
        file.writeBytes(full.copyOf(full.size - 1))
        TraceJournal(root).recover()
        val metadata = journal.readMetadata(file.parentFile!!)
        assertEquals("interrupted", metadata.getProperty("state"))
        assertEquals("false", metadata.getProperty("finalCountersKnown"))
        assertEquals("1", metadata.getProperty("recoveredRecords"))
        assertArrayEquals(full.copyOf(full.size - 1), file.readBytes()) // raw evidence kept
    }

    @Test fun checksumAndAbsurdLengthAreRejectedWithoutAllocatingUnboundedInput() {
        val root = root(); val journal = TraceJournal(root); journal.recover()
        val id = UUID.randomUUID(); journal.open(id).use { it.append(record()); it.checkpoint(null) }
        val file = File(root, "$id/records.bin"); val original = file.readBytes()
        RandomAccessFile(file, "rw").use { it.seek(12); it.write(0x7f) }
        assertEquals(0, journal.scan(file).records.toInt()); assertTrue(journal.scan(file).damagedTail)
        file.writeBytes(original)
        RandomAccessFile(file, "rw").use { it.seek(4); it.writeInt(Int.MAX_VALUE) }
        assertTrue(journal.scan(file).damagedTail)
    }

    @Test fun limitsIncludeMetadataAndRecoverReservationsAcrossRestartWithoutDeletingReports() {
        val root = root()
        val limit = TraceJournal.METADATA_RESERVE + 1
        val journal = TraceJournal(root, limit, limit); journal.recover()
        val id = UUID.randomUUID(); val writer = journal.open(id)
        assertEquals("session_limit", assertThrows(IOException::class.java) { writer.append(record()) }.message)
        writer.close()
        val restarted = TraceJournal(root, limit, limit); restarted.recover()
        assertEquals("total_limit", assertThrows(IOException::class.java) { restarted.open(UUID.randomUUID()) }.message)
        assertTrue(File(root, id.toString()).isDirectory)
    }

    @Test fun totalLimitIsSharedAcrossWritersAndAccountsForEveryRecord() {
        val root = root()
        val payloadBytes = TraceJournal(root).run {
            recover(); val id = UUID.randomUUID()
            open(id).use { it.append(record()); it.checkpoint(null) }
            File(root, "$id/records.bin").length()
        }
        val other = root()
        val journal = TraceJournal(other, totalLimit = TraceJournal.METADATA_RESERVE * 2 + payloadBytes)
        journal.recover()
        journal.open(UUID.randomUUID()).use { first ->
            journal.open(UUID.randomUUID()).use { second ->
                first.append(record())
                assertEquals("total_limit", assertThrows(IOException::class.java) { second.append(record()) }.message)
            }
        }
    }

    @Test fun failedFsyncNeverPublishesACompleteManifest() {
        val root = root()
        val journal = TraceJournal(root, fault = { if (it == "sync") throw IOException("disk") })
        journal.recover(); val id = UUID.randomUUID()
        journal.open(id).use { writer ->
            writer.append(record())
            assertThrows(IOException::class.java) { writer.checkpoint(null, "complete") }
        }
        assertEquals("recording", journal.readMetadata(File(root, id.toString())).getProperty("state"))
        TraceJournal(root).recover()
        assertEquals("interrupted", journal.readMetadata(File(root, id.toString())).getProperty("state"))
    }

    @Test fun failedAtomicMetadataReplacementLeavesPreviousCheckpointReadable() {
        val root = root(); val fail = AtomicBoolean(false)
        val journal = TraceJournal(root, fault = { if (it == "metadata_replace" && fail.get()) throw IOException("disk") })
        journal.recover(); val id = UUID.randomUUID(); val writer = journal.open(id)
        writer.append(record()); writer.checkpoint(null)
        val before = File(root, "$id/metadata.properties").readBytes()
        fail.set(true)
        assertThrows(IOException::class.java) { writer.checkpoint(null, "complete") }
        writer.close()
        assertArrayEquals(before, File(root, "$id/metadata.properties").readBytes())
    }

    @Test fun asynchronousConsumerFinalizesAllRecordsAfterOwnerClose() {
        val root = root(); val store = TraceSessionStore(root, capacity = 1024)
        await { store.status.value.ready }
        val trace = store.start(7)!!
        repeat(800) { trace.record(PcmTraceKind.BEAT, it.toLong()) }
        store.close() // scope cancellation must not discard finalization
        assertNull(store.start(8))
        trace.close()
        assertTrue(store.awaitTermination())
        val directory = root.listFiles()!!.single()
        val journal = TraceJournal(root)
        assertEquals(800, journal.scan(File(directory, "records.bin")).records.toInt())
        assertEquals("complete", journal.readMetadata(directory).getProperty("state"))
        assertEquals(0, store.status.value.active)
    }

    @Test fun blockedDiskKeepsStartAndProducerNonblockingAndReservesClosingSessionMemory() {
        val root = root(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = TraceSessionStore(root, fault = {
            if (it == "metadata") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        })
        await { store.status.value.ready }
        val trace = store.start(1)!!
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(20) { trace.record(PcmTraceKind.BEAT) }
            trace.close()
            assertNull(store.start(2))
            assertEquals("memory_limit", store.status.value.notice)
        } finally { release.countDown(); store.close() }
        assertTrue(store.awaitTermination())
    }

    @Test fun diskFailureDisablesCaptureButReleasesMemoryAfterProducerClosesAndAllowsNextSession() {
        val root = root(); val fail = AtomicBoolean(true)
        val store = TraceSessionStore(root, fault = { if (it == "append" && fail.get()) throw IOException("disk") })
        await { store.status.value.ready }
        val trace = store.start(1)!!
        trace.record(PcmTraceKind.BEAT)
        await { store.status.value.notice == "storage_failed" }
        trace.record(PcmTraceKind.BEAT)
        assertTrue(trace.health()!!.dropped > 0)
        trace.close(); await { store.status.value.active == 0 }
        fail.set(false)
        val next = store.start(2)!!; next.record(PcmTraceKind.BEAT); next.close()
        store.close(); assertTrue(store.awaitTermination())
        val metadata = root.listFiles()!!.map { TraceJournal(root).readMetadata(it) }
        assertEquals(setOf("complete", "incomplete"), metadata.map { it.getProperty("state") }.toSet())
        val failed = metadata.single { it.getProperty("state") == "incomplete" }
        assertEquals("1", failed.getProperty("storageLostRecords"))
        assertEquals("1", failed.getProperty("dropped"))
    }

    @Test fun startupRecoveryDoesNotEraseWarningAboutAnUnrecordedStart() {
        val root = root(); val journal = TraceJournal(root); journal.recover()
        journal.open(UUID.randomUUID()).close() // incomplete session requires recovery metadata
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = TraceSessionStore(root, fault = {
            if (it == "metadata") { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(store.start(1))
            release.countDown()
            await { store.status.value.ready }
            assertEquals("diagnostics_unavailable", store.status.value.notice)
        } finally { release.countDown(); store.close() }
        assertTrue(store.awaitTermination())
    }

    @Test fun unavailableDirectoryDoesNotAcceptCaptureOrLeaveExecutorRunning() {
        val file = temporary.newFile()
        val store = TraceSessionStore(file)
        await { store.status.value.notice == "recovery_failed" }
        assertNull(store.start(1)); store.close(); assertTrue(store.awaitTermination())
    }
}
