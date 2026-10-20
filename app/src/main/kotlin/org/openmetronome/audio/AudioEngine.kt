package org.openmetronome.audio

import kotlinx.coroutines.flow.StateFlow
import org.openmetronome.domain.model.MetronomeConfig

enum class PlaybackStatus { STOPPED, STARTING, PLAYING, STOPPING, ERROR }
enum class AudioFailure { FOCUS_DENIED, INITIALIZATION, WRITE_FAILED, DEVICE_LOST, UNEXPECTED }

data class AudioDiagnostics(
    val sampleRate: Int,
    val blockFrames: Int,
    val bufferFrames: Int,
    val framesWritten: Long,
    val underruns: Int,
    val framesPlayed: Long = 0,
    val sessionId: Long = 0,
)

data class PlaybackPulse(val frame: Long, val bar: Long, val beat: Int, val silent: Boolean)

data class AudioEngineState(
    val status: PlaybackStatus = PlaybackStatus.STOPPED,
    val activeConfig: MetronomeConfig? = null,
    val failure: AudioFailure? = null,
    val diagnostics: AudioDiagnostics? = null,
    val pulse: PlaybackPulse? = null,
    val appliedConfig: MetronomeConfig? = null,
)

interface AudioEngine : AutoCloseable {
    val state: StateFlow<AudioEngineState>
    fun start(config: MetronomeConfig)
    fun updateConfig(config: MetronomeConfig)
    fun stop()
    override fun close()
}

/** Platform ports also allow testing partial writes and cancellation on the JVM. */
interface PcmSink : AutoCloseable {
    val sampleRate: Int
    val blockFrames: Int
    val bufferFrames: Int
    val underruns: Int
    /** Monotonic consumed frames, not frames queued by write(). */
    val playedFrames: Long get() = 0
    fun write(samples: ShortArray, offset: Int, count: Int): Int
    fun play()
    fun pause()
    fun flush()
    override fun close()
}

fun interface PcmSinkFactory { fun create(): PcmSink }
fun interface AudioFocusGate { fun acquire(onLoss: () -> Unit): AutoCloseable? }

class AudioEngineException(val reason: AudioFailure, cause: Throwable? = null) :
    RuntimeException(reason.name, cause)
