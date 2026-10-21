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

class PcmAudioEngineTest {
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
        assertTrue(PcmAudioEngine.writeFully(sink, pcm, pcm.size, AtomicBoolean()))
        assertEquals(pcm.toList(), written)
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
}
