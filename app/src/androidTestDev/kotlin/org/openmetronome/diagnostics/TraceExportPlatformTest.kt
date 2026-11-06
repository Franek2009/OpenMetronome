package org.openmetronome.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.data.HistoryObservation
import org.openmetronome.data.HistoryObservationStage

/** Offline PCM records, private fixture files and ZIP only; no hardware output. */
class TraceExportPlatformTest {
    @Test fun androidExportsBothLanesAndLateHistoryIntoFourPortableFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "trace-export-test-${UUID.randomUUID()}")
        val store = TraceSessionStore(root)
        var capture: TraceCapture? = null
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(condition())
        }
        try {
            await { store.status.value.ready }
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = info.versionCode.toLong()
            capture = store.begin(TraceSessionKey(UUID.randomUUID(), 1), TraceEnvironment(
                versionName = info.versionName ?: "unknown", versionCode = code,
                manufacturer = android.os.Build.MANUFACTURER, model = android.os.Build.MODEL,
                api = android.os.Build.VERSION.SDK_INT, captureOrigin = "offline_fixture",
            ))!!
            capture.event(TraceControlKind.START_REQUESTED)
            capture.trace.record(PcmTraceKind.BEAT, 123, d = 256)
            capture.trace.close()
            val history = UUID.randomUUID()
            capture.observeHistory(HistoryObservation(1, history.toString(), HistoryObservationStage.CREATED, true, false))
            capture.finishControls(); await { store.status.value.active == 0 }
            val reports = TraceReports(store)
            val bytes = ByteArrayOutputStream()
            assertFalse(reports.export(capture.reportId) { bytes }.incomplete)
            val contents = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals(setOf("beats.csv", "audio.csv", "events.csv", "metadata.json"), contents.keys)
            assertTrue(contents.getValue("metadata.json").contains(history.toString()))
            assertTrue(contents.getValue("events.csv").contains("HISTORY_CREATED"))
            assertTrue(contents.getValue("beats.csv").contains("\"123\""))
            File(context.cacheDir, "instrumentation-trace-export.zip").writeBytes(bytes.toByteArray())
            reports.delete(capture.reportId)
            assertTrue(reports.list().isEmpty())
        } finally {
            capture?.trace?.close(); capture?.finishControls(); store.close()
            if (store.awaitTermination()) root.deleteRecursively()
        }
    }
}
