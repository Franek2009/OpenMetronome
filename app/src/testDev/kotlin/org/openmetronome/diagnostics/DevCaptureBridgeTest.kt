package org.openmetronome.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.HistoryObservation
import org.openmetronome.data.HistoryObservationStage
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.model.MetronomeConfig

class DevCaptureBridgeTest {
    private fun fixture(action: (TraceSessionStore, File) -> Unit) {
        val root = Files.createTempDirectory("dev-bridge-").toFile()
        val store = TraceSessionStore(root, capacity = 128)
        try {
            val until = System.nanoTime() + 5_000_000_000L
            while (!store.status.value.ready && System.nanoTime() < until) Thread.sleep(5)
            assertTrue(store.status.value.ready)
            action(store, root)
        } finally { store.close(); assertTrue(store.awaitTermination()); root.deleteRecursively() }
    }

    @Test fun startLinksHistoryAndRetainsOriginalPrestartTimestamps() = fixture { store, root ->
        val bridge = DevCaptureBridge(store, TraceEnvironment(captureOrigin = "offline_fixture"))
        bridge.event(0, TraceControlKind.SERVICE_CREATED)
        val beforeStart = System.nanoTime()
        bridge.event(1, TraceControlKind.START_REQUESTED, config = MetronomeConfig())
        val trace = checkNotNull(bridge.trace(1))
        trace.record(PcmTraceKind.SESSION_BEGIN, 0)
        val history = UUID.randomUUID()
        bridge.history(HistoryObservation(1, history.toString(), HistoryObservationStage.CREATED, false, false))
        trace.record(PcmTraceKind.SESSION_END, 0); trace.close(); bridge.finish(1)
        store.close(); assertTrue(store.awaitTermination())
        val directory = root.listFiles()!!.single()
        val controls = mutableListOf<JournalRecord>()
        store.journal.scanRecords(File(directory, "control.bin")) { controls.add(it) }
        assertTrue(controls.first { it.kindCode == TraceControlKind.SERVICE_CREATED.code }.nanos <= beforeStart)
        assertEquals(history.toString(), store.journal.readMetadata(directory).getProperty("historyId"))
        assertEquals("complete", store.journal.readMetadata(directory).getProperty("state"))
    }

    @Test fun pendingControlOverflowIsPersistedAsExplicitLoss() = fixture { store, root ->
        val bridge = DevCaptureBridge(store, TraceEnvironment())
        repeat(40) { bridge.event(0, TraceControlKind.BACKGROUND_STATE, a = it.toLong()) }
        bridge.event(1, TraceControlKind.START_REQUESTED)
        checkNotNull(bridge.trace(1)).close(); bridge.finish(1)
        store.close(); assertTrue(store.awaitTermination())
        val metadata = store.journal.readMetadata(root.listFiles()!!.single())
        assertEquals("8", metadata.getProperty("controlDropped"))
        assertEquals("incomplete", metadata.getProperty("state"))
    }

    @Test fun foregroundAndSampleRejectionsFinalizeWithoutAnyPcmProducer() = fixture { store, root ->
        val bridge = DevCaptureBridge(store, TraceEnvironment())
        bridge.event(0, TraceControlKind.SERVICE_CREATED)
        bridge.event(0, TraceControlKind.START_REJECTED, a = 4)
        bridge.event(1, TraceControlKind.START_REQUESTED)
        bridge.event(1, TraceControlKind.START_REJECTED, a = 3)
        bridge.finish(1)
        store.close(); assertTrue(store.awaitTermination())
        assertEquals(2, root.listFiles()!!.size)
        root.listFiles()!!.forEach { directory ->
            val metadata = store.journal.readMetadata(directory)
            assertEquals("complete", metadata.getProperty("state"))
            assertEquals("0", metadata.getProperty("writtenRecords"))
        }
    }
}
