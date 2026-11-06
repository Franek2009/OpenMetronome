package org.openmetronome.diagnostics

import java.io.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

class TraceReportsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    private fun store(root: File = temporary.newFolder()): TraceSessionStore = TraceSessionStore(root).also { await { it.status.value.ready } }
    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
    private fun complete(store: TraceSessionStore): TraceCapture {
        val capture = store.begin(TraceSessionKey(UUID.randomUUID(), 1), TraceEnvironment(model = "quoted\"\nmodel"))!!
        capture.event(TraceControlKind.START_REQUESTED)
        capture.event(TraceControlKind.CONFIG_REQUESTED, config = MetronomeConfig(tempo = Tempo(137)))
        capture.trace.record(PcmTraceKind.SESSION_BEGIN, config = MetronomeConfig())
        capture.trace.record(PcmTraceKind.BEAT, 123, 1, 2, 3, 256)
        capture.trace.record(PcmTraceKind.WRITE_RESULT, 123, 64, 32)
        capture.trace.record(PcmTraceKind.SESSION_END)
        capture.trace.close(); capture.finishControls()
        await { store.status.value.active == 0 }
        return capture
    }

    @Test fun zipContainsExactlyFourFilesWithIndependentSequencesAndExactConfig() = runBlocking<Unit> {
        val store = store()
        try {
            val capture = complete(store); val bytes = ByteArrayOutputStream()
            val result = TraceReports(store).export(capture.reportId) { bytes }
            assertFalse(result.incomplete); assertEquals(4L, result.audioRecords); assertEquals(2L, result.controlRecords)
            val zip = entries(bytes.toByteArray())
            assertEquals(setOf("beats.csv", "events.csv", "audio.csv", "metadata.json"), zip.keys)
            assertTrue(zip.getValue("beats.csv").contains("\"pcm\",\"1\""))
            assertTrue(zip.getValue("beats.csv").contains("\"123\",\"1\",\"2\",\"3\",\"256\""))
            assertTrue(zip.getValue("events.csv").contains("\"control\",\"0\""))
            assertTrue(zip.getValue("events.csv").contains("CONFIG_REQUESTED"))
            assertTrue(zip.getValue("events.csv").contains("bpm=137\n"))
            assertTrue(zip.getValue("audio.csv").contains("WRITE_RESULT"))
            val json = zip.getValue("metadata.json")
            assertTrue(json.contains("\"schemaVersion\": 1"))
            assertTrue(json.contains("quoted\\\"\\nmodel"))
            assertTrue(json.contains("\"physicalJitter\": null"))
            assertTrue(json.contains("\"environment.api\": null"))
            assertTrue(json.contains("\"environment.versionCode\": null"))
            assertTrue(json.contains("\"finalCountersKnown\": true"))
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun historyCanArriveAfterAudioClosedAndEngineRestartDoesNotAliasReport() = runBlocking<Unit> {
        val store = store()
        try {
            val key = TraceSessionKey(UUID.randomUUID(), 1)
            val capture = store.begin(key)!!; capture.trace.record(PcmTraceKind.BEAT); capture.trace.close()
            Thread.sleep(50)
            assertEquals(1, store.status.value.active) // wait for recorder/control finalization
            val history = UUID.randomUUID(); capture.historyCreated(history); capture.finishControls()
            await { store.status.value.active == 0 }
            val reports = TraceReports(store)
            assertEquals(history.toString(), reports.list().single().historyId)
            val second = store.begin(TraceSessionKey(UUID.randomUUID(), 1))!!
            assertNotEquals(capture.reportId, second.reportId)
            second.trace.close(); second.finishControls(); await { store.status.value.active == 0 }
            assertEquals(2, reports.list().size)
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun boundedMultiProducerQueueReportsOverflowWithoutOverwritingOrAliasing() {
        val queue = TraceControlQueue(4)
        val config = MetronomeConfig(tempo = Tempo(91))
        assertTrue(queue.offer(TraceControlKind.CONFIG_REQUESTED, 1, 2, 3, 4, config))
        val start = CountDownLatch(1)
        val threads = List(4) { Thread { start.await(); repeat(50) { queue.offer(TraceControlKind.FOCUS_CHANGE, it.toLong(), 0, 0, 0, null) } } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach(Thread::join)
        queue.close(); val health = queue.health()
        assertEquals(201L, health.attempted); assertEquals(4L, health.accepted); assertEquals(197L, health.dropped)
        assertEquals(197L, health.droppedByKind.getValue(TraceControlKind.FOCUS_CHANGE))
        val read = generateSequence { queue.poll() }.toList()
        assertEquals(4, read.size); assertEquals(config, read.first().config)
        assertEquals(read.map { it.sequence }.sorted(), read.map { it.sequence })
        assertFalse(queue.offer(TraceControlKind.START_REQUESTED, 0, 0, 0, 0, null))
        assertEquals(health, queue.health()) // closed session counters are frozen
    }

    @Test fun cancellationAndDestinationFailurePreserveSourceAndReleaseOperationLease() = runBlocking<Unit> {
        val store = store()
        try {
            val capture = complete(store); val directory = File(store.root, capture.reportId.toString())
            val before = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val reports = TraceReports(store)
            assertThrows(IOException::class.java) { runBlocking {
                reports.export(capture.reportId) { object : OutputStream() { override fun write(b: Int) { throw IOException("disk") } } }
            } }
            val entered = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                reports.export(capture.reportId) {
                    entered.complete(Unit)
                    // Cancellation is checked again before writing records, not hidden as success.
                    this@launch.cancel()
                    ByteArrayOutputStream()
                }
            }
            entered.await(); job.join(); assertTrue(job.isCancelled)
            assertEquals(before, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
            assertEquals(1, reports.list().size)
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun exportAndDeleteAreMutuallyExclusiveAndNewPlaybackCancelsExport() = runBlocking<Unit> {
        val store = store(); val busy = AtomicBoolean(false)
        try {
            val capture = complete(store); val reports = TraceReports(store) { busy.get() }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val task = async(Dispatchers.Default) { runCatching { reports.export(capture.reportId) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ByteArrayOutputStream()
            } } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                assertThrows(IllegalStateException::class.java) { runBlocking { reports.delete(capture.reportId) } }
                assertNull(store.begin(TraceSessionKey(UUID.randomUUID(), 2)))
                busy.set(true)
            } finally { release.countDown() }
            assertTrue(task.await().exceptionOrNull() is CancellationException)
            busy.set(false)
            reports.delete(capture.reportId)
            assertTrue(reports.list().isEmpty())
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun recoveredCorruptTailAndMissingFileAreNeverExportedAsComplete() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = store(root)
        try {
            val capture = complete(store); val audio = File(root, "${capture.reportId}/records.bin")
            audio.appendBytes(byteArrayOf(1, 2))
            val first = ByteArrayOutputStream(); assertTrue(TraceReports(store).export(capture.reportId) { first }.incomplete)
            assertTrue(entries(first.toByteArray()).getValue("metadata.json").contains("\"audioDamagedTail\": true"))
            assertTrue(audio.delete())
            val second = ByteArrayOutputStream(); assertTrue(TraceReports(store).export(capture.reportId) { second }.incomplete)
            val json = entries(second.toByteArray()).getValue("metadata.json")
            assertTrue(json.contains("\"audioAvailable\": false")); assertTrue(json.contains("\"countsMismatch\": true"))
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun invalidFinalCountersCannotProduceACompleteExport() = runBlocking<Unit> {
        val store = store()
        try {
            val capture = complete(store); val directory = File(store.root, capture.reportId.toString())
            val metadata = store.journal.readMetadata(directory)
            metadata.setProperty("accepted", "not-a-number")
            File(directory, "metadata.properties").outputStream().use { metadata.store(it, null) }
            val zip = ByteArrayOutputStream()
            assertTrue(TraceReports(store).export(capture.reportId) { zip }.incomplete)
            assertTrue(entries(zip.toByteArray()).getValue("metadata.json").contains("\"accepted\": null"))
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun legacyPcmOnlyJournalRemainsExportableWithoutInventingControlCounters() = runBlocking<Unit> {
        val store = store()
        try {
            val capture = complete(store); val directory = File(store.root, capture.reportId.toString())
            assertTrue(File(directory, "control.bin").delete())
            val metadata = store.journal.readMetadata(directory)
            metadata.stringPropertyNames().filter { it.startsWith("control") }.forEach { metadata.remove(it) }
            File(directory, "metadata.properties").outputStream().use { metadata.store(it, null) }
            val zip = ByteArrayOutputStream()
            val result = TraceReports(store).export(capture.reportId) { zip }
            assertTrue(result.incomplete); assertEquals(4L, result.audioRecords)
            val json = entries(zip.toByteArray()).getValue("metadata.json")
            assertNull(TraceReports(store).list().single().controlDropped)
            assertTrue(json.contains("\"controlAvailable\": false"))
            assertFalse(json.contains("\"controlDropped\": 0"))
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun deletingReportsReleasesDiskQuotaAndNeverDeletesOutsideReportDirectory() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = TraceSessionStore(root, totalLimit = TraceJournal.METADATA_RESERVE + 4096)
        await { store.status.value.ready }
        try {
            val first = complete(store); val reports = TraceReports(store)
            reports.delete(first.reportId)
            val next = complete(store)
            assertEquals(listOf(next.reportId), reports.list().map { it.id })
            val external = temporary.newFolder(); val important = File(external, "keep").apply { writeText("untouched") }
            val link = File(root, UUID.randomUUID().toString())
            Files.createSymbolicLink(link.toPath(), external.toPath())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { reports.delete(UUID.fromString(link.name)) } }
            assertEquals("untouched", important.readText())
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }

    @Test fun unfinishedReportIsNotExportableAndReadPagesAreBounded() = runBlocking<Unit> {
        val store = store()
        try {
            val capture = store.begin(TraceSessionKey(UUID.randomUUID(), 1))!!
            var opened = false
            assertThrows(IllegalStateException::class.java) { runBlocking { TraceReports(store).export(capture.reportId) { opened = true; ByteArrayOutputStream() } } }
            assertFalse(opened)
            capture.trace.close(); capture.finishControls(); await { store.status.value.active == 0 }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { TraceReports(store).list(limit = 101) } }
        } finally { store.close(); assertTrue(store.awaitTermination()) }
    }
}
