package org.openmetronome.diagnostics

import android.os.Debug
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.BuildConfig
import org.openmetronome.audio.*
import org.openmetronome.data.SessionRecorder
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

/** Full worker + real DEV IO, with a paced memory sink and fake focus. Never opens AudioTrack. */
class DevDiagnosticsQualificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val warmup = 3000
    private val measured = 3000

    private class PacedSink(private val blocks: Int) : PcmSink {
        override val sampleRate = 48000
        override val blockFrames = 256
        override val bufferFrames = 1024
        override val underruns = 0
        private var written = 0L
        override val playedFrames get() = (written - bufferFrames).coerceAtLeast(0)
        private var writes = 0
        private var playing = false
        private var deadline = 0L
        private val paused = AtomicBoolean()
        private val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val pcm = MessageDigest.getInstance("SHA-256")
        private val pcmBytes = ByteArray(512)
        val blockCpu = mutableListOf<Long>()
        private var lastCpu = 0L
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            if (paused.get()) return 0
            if (writes >= blocks) {
                done.countDown(); check(release.await(15, TimeUnit.SECONDS)); return 0
            }
            val cpu = Debug.threadCpuTimeNanos()
            if (lastCpu != 0L) blockCpu.add(cpu - lastCpu)
            repeat(count) { index ->
                val sample = samples[offset + index].toInt()
                pcmBytes[index * 2] = sample.toByte(); pcmBytes[index * 2 + 1] = (sample ushr 8).toByte()
            }
            pcm.update(pcmBytes, 0, count * 2)
            writes++; written += count
            if (playing) {
                deadline += count.toLong() * 1_000_000_000L / sampleRate
                val wait = deadline - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
            }
            lastCpu = Debug.threadCpuTimeNanos()
            return count
        }
        override fun play() { playing = true; deadline = System.nanoTime() }
        override fun pause() { paused.set(true); release.countDown() }
        override fun flush() = Unit
        override fun close() = pause()
    }

    private data class Measurement(val renderP99: Long, val workerP99: Long, val pcmHash: String,
        val heapPeak: Long, val pssPeakKb: Long, val dropped: Long)
    private fun percentile(values: List<Long>): Long = values.sorted()[(values.size * .99).toInt().coerceAtMost(values.lastIndex)]

    private fun measure(capture: Boolean): Measurement {
        val root = File(context.cacheDir, "qualification-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val store = if (capture) TraceSessionStore(root) else null
        val bridge = store?.let {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!it.status.value.ready && System.nanoTime() < deadline) Thread.sleep(5)
            check(it.status.value.ready)
            DevCaptureBridge(it, TraceEnvironment(captureOrigin = "offline_fixture", renderClock = "thread_cpu_nanoseconds"))
        }
        val timings = mutableListOf<Long>()
        val sink = PacedSink(warmup + measured)
        val config = MetronomeConfig(tempo = Tempo(137))
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} },
            configureThread = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
            traceFactory = bridge?.let { it::trace }, controls = bridge,
            renderProbe = { timings.add(it) }, renderClock = Debug::threadCpuTimeNanos)
        var heapPeak = 0L; var pssPeak = 0L
        try {
            engine.start(config)
            val deadline = System.nanoTime() + 90_000_000_000L
            while (!sink.done.await(250, TimeUnit.MILLISECONDS)) {
                check(System.nanoTime() < deadline) { "offline worker timed out: ${engine.state.value.status}" }
                heapPeak = maxOf(heapPeak, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                pssPeak = maxOf(pssPeak, Debug.getPss())
                check(engine.state.value.status != PlaybackStatus.ERROR)
            }
            engine.stop()
            val final = runBlocking { withTimeout(10000) { engine.state.first { it.status == PlaybackStatus.STOPPED } } }
            if (bridge != null && store != null) {
                runBlocking { SessionRecorder(onObservation = bridge::history) { _, _ -> }.accept(MetronomeState(config, final)) }
                bridge.finish(engine.diagnosticSessionId)
                val deadline = System.nanoTime() + 10_000_000_000L
                while (store.status.value.active > 0 && System.nanoTime() < deadline) Thread.sleep(5)
                check(store.status.value.active == 0)
                val directory = root.listFiles()!!.single()
                val meta = store.journal.readMetadata(directory)
                assertEquals("complete", meta.getProperty("state"))
                assertEquals("0", meta.getProperty("dropped")); assertEquals("0", meta.getProperty("controlDropped"))
                assertNotNull(meta.getProperty("historyId"))
                val beats = mutableListOf<Long>(); var renderedEnd = 0L
                store.journal.scanRecords(File(directory, "records.bin")) { row ->
                    if (row.kindCode == PcmTraceKind.BEAT.code) beats.add(row.frame)
                    if (row.kindCode == PcmTraceKind.RENDER.code) { renderedEnd = maxOf(renderedEnd, row.b); assertTrue(row.c >= 0) }
                }
                val expected = generateSequence(0L) { it + 1 }.map { it * 48000 * 60 / 137 }.takeWhile { it < renderedEnd }.toList()
                assertEquals(expected, beats)
                val zip = java.io.ByteArrayOutputStream()
                val exported = runBlocking { TraceReports(store).export(UUID.fromString(directory.name)) { zip } }
                assertFalse(exported.incomplete); assertTrue(zip.size() > 0)
            }
            assertTrue(timings.size >= warmup + measured)
            return Measurement(percentile(timings.drop(warmup).take(measured)),
                percentile(sink.blockCpu.drop(warmup - 1).take(measured)), sink.pcm.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, heapPeak, pssPeak, 0)
        } finally {
            engine.close(); bridge?.finish(engine.diagnosticSessionId)
            store?.close(); if (store != null) check(store.awaitTermination())
            root.deleteRecursively()
        }
    }

    @Test fun devApplicationSuppliesBothQualifiedHooksWithoutStartingHardware() {
        val app = context.applicationContext as org.openmetronome.TestApplication
        val hooks = engineDiagnosticHooks(context, app.data)
        assertNotNull(hooks.traceFactory)
        assertNotNull(hooks.controls)
    }

    @Test fun completeOfflineWorkerQualifiesPcmLossesMemoryAndRenderBudget() {
        val baseline = measure(false)
        val observed = measure(true)
        val baselineAfter = measure(false)
        val reference = minOf(baseline.renderP99, baselineAfter.renderP99)
        val ratio = observed.renderP99.toDouble() / reference
        val report = JSONObject().put("origin", "offline_ART_paced_fake_sink")
            .put("sourceRevision", BuildConfig.SOURCE_REVISION).put("api", android.os.Build.VERSION.SDK_INT)
            .put("scenario", "137 BPM, 4/4, single subdivision, volume 100%, 48 kHz / 256 frames")
            .put("warmupBlocks", warmup).put("measuredBlocks", measured).put("clock", "thread_cpu_nanoseconds")
            .put("baselineRenderP99Ns", baseline.renderP99).put("devRenderP99Ns", observed.renderP99)
            .put("baselineAfterRenderP99Ns", baselineAfter.renderP99).put("referenceRenderP99Ns", reference)
            .put("baselineProtocol", "baseline / DEV / baseline; lower baseline p99 is the reference")
            .put("renderP99Ratio", ratio).put("renderBudgetPass", ratio <= 1.10)
            .put("baselineWorkerP99Ns", baseline.workerP99).put("devWorkerP99Ns", observed.workerP99)
            .put("baselineHeapPeakBytes", baseline.heapPeak).put("devHeapPeakBytes", observed.heapPeak)
            .put("baselinePssPeakKb", baseline.pssPeakKb).put("devPssPeakKb", observed.pssPeakKb)
            .put("memorySamplingMs", 250).put("transportBudgetBytes", BoundedPcmTrace.BUFFER_BUDGET_BYTES)
            .put("baselinePcmSha256", baseline.pcmHash).put("devPcmSha256", observed.pcmHash)
            .put("pcmIdentical", baseline.pcmHash == observed.pcmHash && baselineAfter.pcmHash == observed.pcmHash).put("droppedRecords", observed.dropped)
            .put("physicalJitter", JSONObject.NULL).put("physicalLatency", JSONObject.NULL)
        File(context.cacheDir, "dev-qualification.json").writeText(report.toString(2))
        assertEquals("Full worker tracing changed accepted PCM", baseline.pcmHash, observed.pcmHash)
        assertEquals("Repeated baseline changed accepted PCM", baselineAfter.pcmHash, observed.pcmHash)
        assertTrue("DEV render p99 exceeds +10% budget: $report", ratio <= 1.10)
        assertTrue("Offline worker has insufficient CPU margin: $report", observed.workerP99 < 256L * 1_000_000_000 / 48000 / 2)
    }
}
