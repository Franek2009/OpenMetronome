package org.openmetronome.audio

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openmetronome.domain.audio.PcmRenderer
import org.openmetronome.domain.audio.PcmTrace
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.model.MetronomeConfig

/** One owned audio worker. Blocking writes provide backpressure, never beat timing. */
class PcmAudioEngine(
    private val sinks: PcmSinkFactory,
    private val focus: AudioFocusGate,
    private val configureThread: () -> Unit = {},
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val sampleBanks: SampleBankSource = SampleBankSource { SampleBankLease(emptyMap()) },
    private val traceFactory: ((Long) -> PcmTrace?)? = null,
) : AudioEngine {
    private class Session(val id: Long, val bank: SampleBankLease, val trace: PcmTrace?) {
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
        val id = ++sequence
        // Allocate bounded recording storage on the caller, before the audio worker.
        val trace = try { traceFactory?.invoke(id) } catch (error: Exception) {
            runCatching { log("diagnostic capture unavailable", error) }
            null
        }
        val next = Session(id, bank, trace)
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
        val trace = target.trace
        try {
            trace?.record(PcmTraceKind.SESSION_BEGIN, 0, target.id, config = initialConfig)
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
            val renderer = PcmRenderer(sink.sampleRate, initialConfig, customSamples = target.bank.samples, trace = trace, onPulse = { beat, silent ->
                pulses.addLast(PlaybackPulse(beat.frame, beat.barIndex, beat.beatInBar, silent))
            })
            val block = ShortArray(sink.blockFrames)
            var written = 0L
            var presented = 0L
            var pulse: PlaybackPulse? = null
            val initialUnderruns = sink.underruns
            trace?.record(PcmTraceKind.OUTPUT_OPENED, 0, sink.sampleRate.toLong(),
                sink.blockFrames.toLong(), sink.bufferFrames.toLong(), initialUnderruns.toLong())
            var tracedRequested: MetronomeConfig? = null

            fun renderBlock(count: Int) {
                val config = requested.get()
                if (trace == null) {
                    renderer.render(block, count, config)
                    return
                }
                val firstFrame = renderer.renderedFrames
                if (config != tracedRequested) {
                    trace.record(PcmTraceKind.CONFIG_OBSERVED, firstFrame, config = config)
                    tracedRequested = config
                }
                renderer.render(block, count, config)
                trace.record(PcmTraceKind.RENDER, firstFrame, count.toLong(), renderer.renderedFrames)
            }

            fun snapshot(): AudioDiagnostics {
                val underruns = (sink.underruns - initialUnderruns).coerceAtLeast(0)
                trace?.record(PcmTraceKind.OUTPUT_STATUS, presented, written, underruns.toLong())
                return AudioDiagnostics(
                    sink.sampleRate, sink.blockFrames, sink.bufferFrames, written,
                    underruns, presented, target.id, renderer.countInFrames,
                )
            }

            // Fill exactly the available track buffer before play(), without waiting on a clock.
            while (written < sink.bufferFrames && !target.cancelled.get()) {
                val count = minOf(block.size.toLong(), sink.bufferFrames - written).toInt()
                renderBlock(count)
                if (!writeFully(sink, block, count, target.cancelled, trace, written)) return
                written += count
            }
            synchronized(lock) {
                if (target.cancelled.get()) return
                sink.play()
                trace?.record(PcmTraceKind.PLAY, 0, written)
                mutableState.value = AudioEngineState(
                    PlaybackStatus.PLAYING, renderer.activeConfig, diagnostics = snapshot(), appliedConfig = renderer.appliedConfig,
                )
            }
            var lastPublishedFrame = written
            while (!target.cancelled.get()) {
                renderBlock(block.size)
                if (!writeFully(sink, block, block.size, target.cancelled, trace, written)) break
                written += block.size
                presented = sink.playedFrames.coerceIn(0, written)
                trace?.record(PcmTraceKind.HEAD, presented, written)
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
            trace?.record(PcmTraceKind.HEAD, presented, written)
            diagnostics = snapshot()
        } catch (error: Exception) {
            if (!target.cancelled.get()) {
                failure = if (error is org.openmetronome.domain.audio.MissingSampleException) AudioFailure.SAMPLE_UNAVAILABLE
                    else (error as? AudioEngineException)?.reason ?: AudioFailure.UNEXPECTED
                trace?.record(PcmTraceKind.ERROR, a = failure.ordinal.toLong())
                log("audio session failed: $failure", error)
            }
        } finally {
            synchronized(lock) { target.sink = null }
            // Cleanup attempts are independent: a broken track must not retain audio focus.
            runCatching { sink?.pause() }.onFailure { trace?.record(PcmTraceKind.CLEANUP_FAILURE, a = 0); log("cleanup pause failed", it) }
            runCatching { sink?.flush() }.onFailure { trace?.record(PcmTraceKind.CLEANUP_FAILURE, a = 1); log("cleanup flush failed", it) }
            runCatching { sink?.close() }.onFailure { trace?.record(PcmTraceKind.CLEANUP_FAILURE, a = 2); log("release failed", it) }
            runCatching { focusLease?.close() }.onFailure { trace?.record(PcmTraceKind.CLEANUP_FAILURE, a = 3); log("focus release failed", it) }
            runCatching { target.bank.close() }.onFailure { trace?.record(PcmTraceKind.CLEANUP_FAILURE, a = 4); log("sample bank release failed", it) }
            trace?.record(PcmTraceKind.SESSION_END, a = if (target.cancelled.get()) 1 else 0,
                b = failure?.ordinal?.toLong() ?: -1)
            trace?.close()
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
            trace: PcmTrace? = null,
            firstFrame: Long = 0,
        ): Boolean {
            var offset = 0
            while (offset < count) {
                if (cancelled.get()) return false
                trace?.record(PcmTraceKind.WRITE_BEGIN, firstFrame + offset, (count - offset).toLong(), offset.toLong())
                val transferred = try { sink.write(samples, offset, count - offset) } catch (error: Exception) {
                    trace?.record(PcmTraceKind.WRITE_THROW, firstFrame + offset, (count - offset).toLong(), offset.toLong())
                    throw error
                }
                trace?.record(PcmTraceKind.WRITE_RESULT, firstFrame + offset, (count - offset).toLong(), transferred.toLong(), offset.toLong())
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
