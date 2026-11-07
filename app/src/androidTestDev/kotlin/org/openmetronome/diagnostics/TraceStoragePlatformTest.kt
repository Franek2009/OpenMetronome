package org.openmetronome.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.audio.PcmTraceKind

/** Files and in-memory events only: never creates an engine, Activity or AudioTrack. */
class TraceStoragePlatformTest {
    @Test fun androidFilesystemSupportsAtomicCheckpointsAndRecoveryOfInterruptedJournal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "trace-storage-test-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        val store = TraceSessionStore(root, capacity = 128)
        var trace: org.openmetronome.domain.audio.PcmTrace? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!store.status.value.ready && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(store.status.value.toString(), store.status.value.ready)
            trace = store.start(9)
            assertNotNull(trace)
            repeat(100) { trace!!.record(PcmTraceKind.BEAT, it.toLong()) }
            trace!!.close(); store.close()
            assertTrue(store.awaitTermination())
            val directory = root.listFiles()!!.single()
            val journal = TraceJournal(root)
            assertEquals(100L, journal.scan(File(directory, "records.bin")).records)
            assertEquals("complete", journal.readMetadata(directory).getProperty("state"))
            val interruptedId = UUID.randomUUID()
            journal.recover()
            journal.open(interruptedId).use { writer ->
                writer.append(PcmTraceRecord(0, 5, PcmTraceKind.BEAT, 10, 0, 0, 0, 0, null))
                writer.checkpoint(null)
            }
            File(root, "$interruptedId/records.bin").appendBytes(byteArrayOf(1, 2, 3))
            journal.recover()
            val recovered = journal.readMetadata(File(root, interruptedId.toString()))
            assertEquals("interrupted", recovered.getProperty("state"))
            assertEquals("1", recovered.getProperty("recoveredRecords"))
            assertEquals("true", recovered.getProperty("damagedTail"))
            assertEquals("false", recovered.getProperty("finalCountersKnown"))
        } finally {
            trace?.close(); store.close()
            if (store.awaitTermination()) root.deleteRecursively() // this UUID test fixture only
        }
    }
}
