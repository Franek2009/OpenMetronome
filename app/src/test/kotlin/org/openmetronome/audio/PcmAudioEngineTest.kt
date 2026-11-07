package org.openmetronome.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.audio.CollectingPcmTrace
import org.openmetronome.domain.audio.PcmTrace
import org.openmetronome.domain.audio.PcmTraceKind

class PcmAudioEngineTest {
    @Test fun `failing render probe cannot stop playback or hold the sink`() {
        val sink = Sink()
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} }, renderProbe = { error("probe failed") })
        try {
            engine.start(MetronomeConfig())
            assertNull(await(engine, PlaybackStatus.PLAYING).failure)
            engine.stop(); await(engine, PlaybackStatus.STOPPED)
            assertTrue(sink.released.get())
        } finally { engine.close() }
    }
    @Test fun `displayed configuration follows played pulses rather than prebuffered ramp`() {
        val permits = Semaphore(0); val head = AtomicLong()
        val sink = object : Sink() {
            override val blockFrames = 4096
            override val bufferFrames = 192001
            override val playedFrames get() = head.get()
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                if (playing && !paused) check(permits.tryAcquire(5, TimeUnit.SECONDS))
                return if (paused) 0 else count
            }
            override fun pause() { super.pause(); permits.release() }
        }
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} })
        try {
            engine.start(MetronomeConfig(ramp = org.openmetronome.domain.model.TempoRamp(true, 60, 120, 10, 1)))
            val initial = await(engine, PlaybackStatus.PLAYING)
            assertEquals(60, initial.activeConfig!!.tempo.bpm)
            assertEquals(0L, initial.diagnostics!!.framesPlayed)
            head.set(192000); permits.release()
            val later = runBlocking { withTimeout(5000) { engine.state.first { it.pulse?.bar == 1L } } }
            assertEquals(70, later.activeConfig!!.tempo.bpm)
        } finally { engine.close() }
    }
    private open class Sink : PcmSink {
        override val sampleRate = 48000
        override val blockFrames = 16
        override val bufferFrames = 32
        override val underruns = 0
        val entered = CountDownLatch(1)
        val released = AtomicBoolean(false)
        private val ready = Semaphore(0)
        @Volatile var playing = false
        @Volatile var paused = false
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            if (playing && !paused) {
                entered.countDown()
                check(ready.tryAcquire(5, TimeUnit.SECONDS))
            }
            return if (paused) 0 else minOf(7, count)
        }
        override fun play() { playing = true }
        override fun pause() { paused = true; ready.release() }
        override fun flush() = Unit
        override fun close() { released.set(true) }
    }

    private fun await(engine: AudioEngine, status: PlaybackStatus): AudioEngineState = runBlocking {
        withTimeout(5000) { engine.state.first { it.status == status } }
    }

    @Test fun `a stalled stop observer cannot delay cancellation or resource cleanup`() {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val sink = Sink()
        val observer = object : org.openmetronome.diagnostics.ControlObservation() {
            override fun append(sessionId: Long, kind: org.openmetronome.diagnostics.TraceControlKind,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?) {
                if (kind == org.openmetronome.diagnostics.TraceControlKind.STOP_REQUESTED) {
                    entered.countDown(); check(released.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} }, controls = observer)
        var stopping: Thread? = null
        try {
            engine.start(MetronomeConfig()); await(engine, PlaybackStatus.PLAYING)
            stopping = Thread { engine.stop() }.also(Thread::start)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            await(engine, PlaybackStatus.STOPPED)
            assertTrue(sink.released.get())
        } finally { released.countDown(); stopping?.join(1000); engine.close() }
    }

    @Test fun `control observer failures cannot change start update or stop`() {
        for (broken in listOf(false, true)) {
            val observed = mutableListOf<org.openmetronome.diagnostics.TraceControlKind>()
            val observer = object : org.openmetronome.diagnostics.ControlObservation() {
                override fun append(sessionId: Long, kind: org.openmetronome.diagnostics.TraceControlKind,
                    a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?) {
                    if (broken) error("observer failed")
                    observed.add(kind)
                }
            }
            val engine = PcmAudioEngine({ Sink() }, { AutoCloseable {} }, controls = observer)
            try {
                engine.start(MetronomeConfig()); await(engine, PlaybackStatus.PLAYING)
                engine.updateConfig(MetronomeConfig(tempo = org.openmetronome.domain.model.Tempo(91)))
                engine.stop(); await(engine, PlaybackStatus.STOPPED)
                if (broken) assertEquals(3L, observer.failures)
                else assertEquals(listOf(org.openmetronome.diagnostics.TraceControlKind.START_REQUESTED,
                    org.openmetronome.diagnostics.TraceControlKind.CONFIG_REQUESTED,
                    org.openmetronome.diagnostics.TraceControlKind.STOP_REQUESTED), observed)
            } finally { engine.close() }
        }
    }

    @Test fun `focus startup uses PCM lane and asynchronous changes use guarded control lane`() {
        val trace = CollectingPcmTrace()
        val callback = java.util.concurrent.atomic.AtomicReference<((org.openmetronome.diagnostics.TraceControlKind, Long) -> Unit)?>()
        val received = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val observer = object : org.openmetronome.diagnostics.ControlObservation() {
            override fun append(sessionId: Long, kind: org.openmetronome.diagnostics.TraceControlKind,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?) {
                assertNotEquals("OpenMetronome-Audio", Thread.currentThread().name)
                if (kind == org.openmetronome.diagnostics.TraceControlKind.FOCUS_CHANGE) received.add(a)
            }
        }
        val focus = object : AudioFocusGate {
            override fun acquire(onLoss: () -> Unit): AutoCloseable? = error("new observation overload was not used")
            override fun acquire(onLoss: () -> Unit, trace: PcmTrace?,
                control: ((org.openmetronome.diagnostics.TraceControlKind, Long) -> Unit)?): AutoCloseable {
                assertEquals("OpenMetronome-Audio", Thread.currentThread().name)
                trace!!.record(PcmTraceKind.FOCUS_REQUESTED)
                trace.record(PcmTraceKind.FOCUS_RESULT, a = 1)
                callback.set(control)
                return AutoCloseable { trace.record(PcmTraceKind.FOCUS_RELEASE, a = 1) }
            }
        }
        val engine = PcmAudioEngine({ Sink() }, focus, controls = observer, traceFactory = { trace })
        try {
            engine.start(MetronomeConfig()); await(engine, PlaybackStatus.PLAYING)
            callback.get()!!.invoke(org.openmetronome.diagnostics.TraceControlKind.FOCUS_CHANGE, -2)
            engine.stop(); await(engine, PlaybackStatus.STOPPED)
            assertEquals(listOf(-2L), received.toList())
            assertEquals(listOf(PcmTraceKind.FOCUS_REQUESTED, PcmTraceKind.FOCUS_RESULT, PcmTraceKind.FOCUS_RELEASE),
                trace.entries.filter { it.kind.code in 18..20 }.map { it.kind })
        } finally { engine.close() }
    }

    @Test fun `native write error code is preserved without treating missing code as zero`() {
        for (code in listOf(-6, null)) {
            val trace = CollectingPcmTrace()
            val sink = object : Sink() {
                override fun write(samples: ShortArray, offset: Int, count: Int): Int =
                    throw AudioEngineException(AudioFailure.WRITE_FAILED, platformCode = code)
            }
            val engine = PcmAudioEngine({ sink }, { AutoCloseable {} }, traceFactory = { trace })
            try {
                engine.start(MetronomeConfig()); assertEquals(AudioFailure.WRITE_FAILED, await(engine, PlaybackStatus.ERROR).failure)
                val error = trace.entries.single { it.kind == PcmTraceKind.ERROR }
                assertEquals(code?.toLong() ?: 0L, error.b)
                assertEquals(if (code == null) 0L else 1L, error.c)
                val write = trace.entries.single { it.kind == PcmTraceKind.WRITE_THROW }
                assertEquals(code?.toLong() ?: 0L, write.c)
                assertEquals(if (code == null) 0L else 1L, write.d)
                assertTrue(sink.released.get())
            } finally { engine.close() }
        }
    }

    @Test fun `busy sample library does not acquire focus or construct an audio sink`() {
        val touched = AtomicInteger()
        val engine = PcmAudioEngine({ touched.incrementAndGet(); Sink() }, { touched.incrementAndGet(); AutoCloseable {} },
            sampleBanks = SampleBankSource { throw AudioEngineException(AudioFailure.LIBRARY_BUSY) })
        try {
            engine.start(MetronomeConfig())
            assertEquals(AudioFailure.LIBRARY_BUSY, await(engine, PlaybackStatus.ERROR).failure)
            assertEquals(0, touched.get())
        } finally { engine.close() }
    }

    @Test fun `sample lease is released exactly once after track cleanup and on failure`() {
        val released = AtomicInteger()
        val sink = Sink()
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} }, sampleBanks = SampleBankSource {
            SampleBankLease(emptyMap()) { assertTrue(sink.released.get()); released.incrementAndGet() }
        })
        try {
            engine.start(MetronomeConfig()); await(engine, PlaybackStatus.PLAYING)
            engine.stop(); await(engine, PlaybackStatus.STOPPED)
            assertEquals(1, released.get())
        } finally { engine.close() }
        val failure = PcmAudioEngine({ Sink() }, { null }, sampleBanks = SampleBankSource {
            SampleBankLease(emptyMap()) { released.incrementAndGet() }
        })
        try {
            failure.start(MetronomeConfig()); await(failure, PlaybackStatus.ERROR)
            assertEquals(2, released.get())
        } finally { failure.close() }
    }

    @Test fun `partial writes preserve offsets and sample order`() {
        val written = mutableListOf<Short>()
        val sink = object : Sink() {
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                val size = minOf(3, count)
                repeat(size) { written.add(samples[offset + it]) }
                return size
            }
        }
        val pcm = shortArrayOf(10, 20, 30, 40, 50, 60, 70)
        val trace = CollectingPcmTrace()
        assertTrue(PcmAudioEngine.writeFully(sink, pcm, pcm.size, AtomicBoolean(), trace, 100))
        assertEquals(pcm.toList(), written)
        assertEquals(listOf(100L, 103L, 106L), trace.entries.filter { it.kind == PcmTraceKind.WRITE_BEGIN }.map { it.frame })
        val results = trace.entries.filter { it.kind == PcmTraceKind.WRITE_RESULT }
        assertEquals(listOf(3L, 3L, 1L), results.map { it.b })
        assertEquals(listOf(7L, 4L, 1L), results.map { it.a })
        assertEquals(6L, trace.health()!!.accepted)
    }

    @Test fun `zero or negative writes fail instead of spinning`() {
        for (result in listOf(0, -1, 100)) {
            val sink = object : Sink() {
                override fun write(samples: ShortArray, offset: Int, count: Int) = result
            }
            val failure = assertThrows(AudioEngineException::class.java) {
                PcmAudioEngine.writeFully(sink, ShortArray(4), 4, AtomicBoolean())
            }
            assertEquals(AudioFailure.WRITE_FAILED, failure.reason)
        }
    }

    @Test fun `cancellation unblocks write without turning stop into an error`() {
        val sink = Sink()
        val creates = AtomicInteger()
        val focusReleased = AtomicInteger()
        val engine = PcmAudioEngine(
            { creates.incrementAndGet(); sink },
            { AutoCloseable { focusReleased.incrementAndGet() } },
        )
        try {
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            repeat(20) { engine.start(MetronomeConfig()) }
            repeat(20) { engine.stop() }
            val state = await(engine, PlaybackStatus.STOPPED)
            assertNull(state.failure)
            assertTrue(sink.released.get())
            assertEquals(1, creates.get())
            assertEquals(1, focusReleased.get())
        } finally { engine.close() }
    }

    @Test fun `stop while acquiring focus cancels initialization and releases acquired lease`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val creates = AtomicInteger()
        val released = AtomicInteger()
        val engine = PcmAudioEngine(
            { creates.incrementAndGet(); Sink() },
            {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                AutoCloseable { released.incrementAndGet() }
            },
        )
        try {
            engine.start(MetronomeConfig())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.stop()
            finish.countDown()
            await(engine, PlaybackStatus.STOPPED)
            assertEquals(0, creates.get())
            assertEquals(1, released.get())
        } finally { finish.countDown(); engine.close() }
    }

    @Test fun `focus denial permits an explicit retry`() {
        val denied = AtomicBoolean(true)
        val sink = Sink()
        val engine = PcmAudioEngine({ sink }, {
            if (denied.get()) null else AutoCloseable {}
        })
        try {
            engine.start(MetronomeConfig())
            assertEquals(AudioFailure.FOCUS_DENIED, await(engine, PlaybackStatus.ERROR).failure)
            denied.set(false)
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            engine.stop()
            await(engine, PlaybackStatus.STOPPED)
        } finally { engine.close() }
    }

    @Test fun `dead device reports a failure and releases focus without automatic restart`() {
        val sink = object : Sink() {
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                if (playing) throw AudioEngineException(AudioFailure.DEVICE_LOST)
                return count
            }
        }
        val released = AtomicBoolean()
        val creates = AtomicInteger()
        val engine = PcmAudioEngine({ creates.incrementAndGet(); sink }, { AutoCloseable { released.set(true) } })
        try {
            engine.start(MetronomeConfig())
            assertEquals(AudioFailure.DEVICE_LOST, await(engine, PlaybackStatus.ERROR).failure)
            assertTrue(sink.released.get())
            assertTrue(released.get())
            assertEquals(1, creates.get())
        } finally { engine.close() }
    }

    @Test fun `focus loss from a previous session cannot stop the new session`() {
        val callbacks = mutableListOf<() -> Unit>()
        val engine = PcmAudioEngine({ Sink() }, { onLoss ->
            synchronized(callbacks) { callbacks.add(onLoss) }
            AutoCloseable {}
        })
        try {
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            val first = synchronized(callbacks) { callbacks[0] }
            first()
            await(engine, PlaybackStatus.STOPPED)
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            first()
            assertEquals(PlaybackStatus.PLAYING, engine.state.value.status)
            synchronized(callbacks) { callbacks[1] }()
            await(engine, PlaybackStatus.STOPPED)
        } finally { engine.close() }
    }

    @Test fun `failed release still abandons audio focus and close is idempotent`() {
        val sink = object : Sink() { override fun close() { super.close(); error("broken release") } }
        val released = AtomicInteger()
        val engine = PcmAudioEngine({ sink }, { AutoCloseable { released.incrementAndGet() } })
        engine.start(MetronomeConfig())
        await(engine, PlaybackStatus.PLAYING)
        engine.close()
        engine.close()
        await(engine, PlaybackStatus.STOPPED)
        assertEquals(1, released.get())
        engine.start(MetronomeConfig())
        assertEquals(PlaybackStatus.STOPPED, engine.state.value.status)
    }

    @Test fun `playback diagnostics follow consumed frames rather than queued samples`() {
        val head = AtomicLong()
        val permits = Semaphore(0)
        val sink = object : Sink() {
            override val playedFrames get() = head.get()
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                if (playing && !paused) {
                    entered.countDown()
                    check(permits.tryAcquire(5, TimeUnit.SECONDS))
                }
                return if (paused) 0 else count
            }
            override fun pause() { super.pause(); permits.release() }
        }
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} })
        try {
            engine.start(MetronomeConfig())
            val initial = await(engine, PlaybackStatus.PLAYING)
            val initialDiagnostics = checkNotNull(initial.diagnostics)
            assertEquals(32L, initialDiagnostics.framesWritten)
            assertEquals(0L, initialDiagnostics.framesPlayed)
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            head.set(8)
            permits.release()
            val advanced = runBlocking { withTimeout(5000) { engine.state.first { it.pulse != null } } }
            val advancedDiagnostics = checkNotNull(advanced.diagnostics)
            assertEquals(8L, advancedDiagnostics.framesPlayed)
            assertTrue(advancedDiagnostics.framesWritten > advancedDiagnostics.framesPlayed)
            engine.stop()
            assertEquals(8L, await(engine, PlaybackStatus.STOPPED).diagnostics!!.framesPlayed)
        } finally { engine.close() }
    }

    @Test fun `trace session closes after cancellation unblocks a partial write`() {
        val sink = Sink()
        val trace = CollectingPcmTrace()
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} }, traceFactory = { trace })
        try {
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            engine.stop()
            assertNull(await(engine, PlaybackStatus.STOPPED).failure)
            assertTrue(sink.released.get())
            assertEquals(PcmTraceKind.SESSION_BEGIN, trace.entries.first().kind)
            assertEquals(PcmTraceKind.SESSION_END, trace.entries.last().kind)
            assertEquals(1L, trace.entries.last().a)
            assertEquals(-1L, trace.entries.last().frame) // no invented presentation position
            assertTrue(trace.entries.any { it.kind == PcmTraceKind.WRITE_RESULT && it.b == 0L })
            assertFalse(trace.entries.any { it.kind == PcmTraceKind.ERROR })
            assertTrue(trace.health()!!.closed)
            assertEquals(0L, trace.health()!!.dropped)
        } finally { engine.close() }
    }

    @Test fun `broken recording cannot stop playback or retain the sample lease`() {
        val sink = Sink()
        val released = AtomicBoolean()
        val trace = object : PcmTrace() {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean = error("recorder failed")
        }
        val engine = PcmAudioEngine({ sink }, { AutoCloseable {} },
            sampleBanks = SampleBankSource { SampleBankLease(emptyMap()) { released.set(true) } },
            traceFactory = { trace })
        try {
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            engine.stop()
            assertNull(await(engine, PlaybackStatus.STOPPED).failure)
            assertTrue(sink.released.get())
            assertTrue(released.get())
            val health = trace.health()!!
            assertTrue(health.closed)
            assertTrue(health.faulted)
            assertEquals(health.attempted, health.dropped)
        } finally { engine.close() }
    }

    @Test fun `failure to allocate tracing is reported without preventing an explicit start`() {
        val reported = AtomicBoolean()
        val engine = PcmAudioEngine({ Sink() }, { AutoCloseable {} },
            log = { text, _ -> if (text == "diagnostic capture unavailable") reported.set(true) },
            traceFactory = { error("capture unavailable") })
        try {
            engine.start(MetronomeConfig())
            await(engine, PlaybackStatus.PLAYING)
            assertTrue(reported.get())
            engine.stop()
            assertNull(await(engine, PlaybackStatus.STOPPED).failure)
        } finally { engine.close() }
    }

    @Test fun `write error and broken cleanup are retained in the closed trace`() {
        val trace = CollectingPcmTrace()
        val focusReleased = AtomicBoolean()
        val sink = object : Sink() {
            override fun write(samples: ShortArray, offset: Int, count: Int): Int {
                if (playing) throw AudioEngineException(AudioFailure.DEVICE_LOST)
                return count
            }
            override fun close() { super.close(); error("cleanup failed") }
        }
        val engine = PcmAudioEngine({ sink }, { AutoCloseable { focusReleased.set(true) } }, traceFactory = { trace })
        try {
            engine.start(MetronomeConfig())
            assertEquals(AudioFailure.DEVICE_LOST, await(engine, PlaybackStatus.ERROR).failure)
            assertTrue(focusReleased.get())
            assertTrue(trace.entries.any { it.kind == PcmTraceKind.WRITE_THROW })
            assertEquals(AudioFailure.DEVICE_LOST.ordinal.toLong(), trace.entries.single { it.kind == PcmTraceKind.ERROR }.a)
            assertEquals(2L, trace.entries.single { it.kind == PcmTraceKind.CLEANUP_FAILURE }.a)
            assertEquals(PcmTraceKind.SESSION_END, trace.entries.last().kind)
            assertTrue(trace.health()!!.closed)
        } finally { engine.close() }
    }
}
