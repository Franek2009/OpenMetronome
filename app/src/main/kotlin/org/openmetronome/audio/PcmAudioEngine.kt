package org.openmetronome.audio

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openmetronome.domain.audio.PcmRenderer
import org.openmetronome.domain.model.MetronomeConfig

/** One owned audio worker. Blocking writes provide backpressure, never beat timing. */
class PcmAudioEngine(
    private val sinks: PcmSinkFactory,
    private val focus: AudioFocusGate,
    private val configureThread: () -> Unit = {},
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val sampleBanks: SampleBankSource = SampleBankSource { SampleBankLease(emptyMap()) },
) : AudioEngine {
    private class Session(val id: Long, val bank: SampleBankLease) {
        val cancelled = AtomicBoolean(false)
        var sink: PcmSink? = null // guarded by lock
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OpenMetronome-Audio")
    }
    private val requested = AtomicReference(MetronomeConfig())
    private val mutableState = MutableStateFlow(AudioEngineState())
    override val state = mutableState.asStateFlow()
    private var session: Session? = null
    private var closed = false
    private var sequence = 0L

    override fun start(config: MetronomeConfig) = synchronized(lock) {
        if (closed || session != null) return@synchronized
        requested.set(config)
        val bank = try { sampleBanks.acquire() } catch (error: Exception) {
            mutableState.value = AudioEngineState(PlaybackStatus.ERROR,
                failure = (error as? AudioEngineException)?.reason ?: AudioFailure.SAMPLE_UNAVAILABLE)
            return@synchronized
        }
        val next = Session(++sequence, bank)
        session = next
        mutableState.value = AudioEngineState(PlaybackStatus.STARTING)
        worker.execute { runSession(next, config) }
    }

    override fun updateConfig(config: MetronomeConfig) { requested.set(config) }

    override fun stop() = synchronized(lock) { session?.let(::cancel) ?: Unit }

    private fun cancel(target: Session) {
        synchronized(lock) {
            if (session !== target || target.cancelled.getAndSet(true)) return
            mutableState.value = mutableState.value.copy(status = PlaybackStatus.STOPPING)
            // AudioTrack explicitly permits pause on another thread to unblock write().
            runCatching { target.sink?.pause() }.onFailure { log("pause failed", it) }
        }
    }

    private fun runSession(target: Session, initialConfig: MetronomeConfig) {
        var sink: PcmSink? = null
        var focusLease: AutoCloseable? = null
        var failure: AudioFailure? = null
        var diagnostics: AudioDiagnostics? = null
        try {
            configureThread()
            if (target.cancelled.get()) return
            focusLease = focus.acquire { cancel(target) }
                ?: throw AudioEngineException(AudioFailure.FOCUS_DENIED)
            if (target.cancelled.get()) return
            sink = sinks.create()
            require(sink.blockFrames > 0 && sink.bufferFrames > 0)
            synchronized(lock) { if (!target.cancelled.get()) target.sink = sink }
            if (target.cancelled.get()) return
            val pulses = ArrayDeque<PlaybackPulse>()
            val renderer = PcmRenderer(sink.sampleRate, initialConfig, customSamples = target.bank.samples, onPulse = { beat, silent ->
                pulses.addLast(PlaybackPulse(beat.frame, beat.barIndex, beat.beatInBar, silent))
            })
            val block = ShortArray(sink.blockFrames)
            var written = 0L
            var presented = 0L
            var pulse: PlaybackPulse? = null
            val initialUnderruns = sink.underruns

            fun snapshot() = AudioDiagnostics(
                sink.sampleRate, sink.blockFrames, sink.bufferFrames, written,
                (sink.underruns - initialUnderruns).coerceAtLeast(0),
                presented,
                target.id,
            )

            // Fill exactly the available track buffer before play(), without waiting on a clock.
            while (written < sink.bufferFrames && !target.cancelled.get()) {
                val count = minOf(block.size.toLong(), sink.bufferFrames - written).toInt()
                renderer.render(block, count, requested.get())
                if (!writeFully(sink, block, count, target.cancelled)) return
                written += count
            }
            synchronized(lock) {
                if (target.cancelled.get()) return
                sink.play()
                mutableState.value = AudioEngineState(
                    PlaybackStatus.PLAYING, renderer.activeConfig, diagnostics = snapshot(), appliedConfig = renderer.appliedConfig,
                )
            }
            var lastPublishedFrame = written
            while (!target.cancelled.get()) {
                renderer.render(block, requested = requested.get())
                if (!writeFully(sink, block, block.size, target.cancelled)) break
                written += block.size
                presented = sink.playedFrames.coerceIn(0, written)
                val oldPulse = pulse
                while (presented > 0 && pulses.isNotEmpty() && pulses.first().frame <= presented) pulse = pulses.removeFirst()
                synchronized(lock) {
                    if (!target.cancelled.get() &&
                        (pulse != oldPulse || renderer.activeConfig != mutableState.value.activeConfig ||
                            written - lastPublishedFrame >= sink.sampleRate)
                    ) {
                        diagnostics = snapshot()
                        mutableState.value = AudioEngineState(
                            PlaybackStatus.PLAYING, renderer.activeConfig, diagnostics = diagnostics, pulse = pulse, appliedConfig = renderer.appliedConfig,
                        )
                        lastPublishedFrame = written
                    }
                }
            }
            presented = sink.playedFrames.coerceIn(0, written)
            diagnostics = snapshot()
        } catch (error: Exception) {
            if (!target.cancelled.get()) {
                failure = if (error is org.openmetronome.domain.audio.MissingSampleException) AudioFailure.SAMPLE_UNAVAILABLE
                    else (error as? AudioEngineException)?.reason ?: AudioFailure.UNEXPECTED
                log("audio session failed: $failure", error)
            }
        } finally {
            synchronized(lock) { target.sink = null }
            // Cleanup attempts are independent: a broken track must not retain audio focus.
            runCatching { sink?.pause() }.onFailure { log("cleanup pause failed", it) }
            runCatching { sink?.flush() }.onFailure { log("cleanup flush failed", it) }
            runCatching { sink?.close() }.onFailure { log("release failed", it) }
            runCatching { focusLease?.close() }.onFailure { log("focus release failed", it) }
            runCatching { target.bank.close() }.onFailure { log("sample bank release failed", it) }
            synchronized(lock) {
                if (session === target) {
                    session = null
                    mutableState.value = AudioEngineState(
                        if (failure == null) PlaybackStatus.STOPPED else PlaybackStatus.ERROR,
                        failure = failure, diagnostics = diagnostics,
                    )
                    log("session ended: $diagnostics", null)
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            session?.let(::cancel)
            worker.shutdown() // no join/blocking wait on the UI thread
        }
    }

    companion object {
        internal fun writeFully(
            sink: PcmSink,
            samples: ShortArray,
            count: Int,
            cancelled: AtomicBoolean,
        ): Boolean {
            var offset = 0
            while (offset < count) {
                if (cancelled.get()) return false
                val transferred = sink.write(samples, offset, count - offset)
                if (cancelled.get()) return false
                if (transferred <= 0 || transferred > count - offset) {
                    throw AudioEngineException(AudioFailure.WRITE_FAILED)
                }
                offset += transferred
            }
            return true
        }
    }
}
