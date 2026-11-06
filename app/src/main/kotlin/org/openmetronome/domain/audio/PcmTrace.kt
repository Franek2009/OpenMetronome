package org.openmetronome.domain.audio

import org.openmetronome.domain.model.MetronomeConfig
import java.util.concurrent.atomic.AtomicLongArray

/** Stable codes for internal PCM observations, not measurements at the speaker. */
enum class PcmTraceKind(val code: Int) {
    SESSION_BEGIN(1), OUTPUT_OPENED(2), CONFIG_OBSERVED(3), CONFIG_APPLIED(4), EFFECTIVE_CONFIG(5),
    BEAT(6), RENDER(7), WRITE_BEGIN(8), WRITE_RESULT(9), WRITE_THROW(10), PLAY(11),
    HEAD(12), OUTPUT_STATUS(13), ERROR(14), CLEANUP_FAILURE(15), SESSION_END(16), VOLUME_TARGET(17), FOCUS_REQUESTED(18), FOCUS_RESULT(19), FOCUS_RELEASE(20),
}

/** Bit flags: several reasons can silence the same planned subdivision. */
object PcmTraceFlags {
    const val TRAINING_MUTE = 1
    const val BEAT_MUTE = 2
    const val SUBDIVISION_MUTE = 4
    const val ACCENT = 256
}

data class PcmTraceHealth(
    val attempted: Long, val accepted: Long, val dropped: Long,
    val faulted: Boolean, val closed: Boolean,
    val droppedByKind: Map<PcmTraceKind, Long>,
)

/** Primitive return avoids boxing a Long on every audio-worker observation. */
fun interface PcmTraceClock { fun nanoTime(): Long }

/**
 * One audio-worker producer for the lifetime of one session. Implementations must
 * never wait or perform IO. Failures disable recording, not playback. A consumer
 * can inspect health off the audio thread; null means a concurrent observation.
 */
abstract class PcmTrace(private val clock: PcmTraceClock = PcmTraceClock(System::nanoTime)) {
    @Volatile private var revision = 0L
    @Volatile private var attempted = 0L
    @Volatile private var accepted = 0L
    @Volatile private var dropped = 0L
    @Volatile private var faulted = false
    @Volatile private var closed = false
    private val losses = AtomicLongArray(PcmTraceKind.entries.size)

    private fun lost(kind: PcmTraceKind) {
        losses.incrementAndGet(kind.ordinal)
        dropped++
    }

    fun record(
        kind: PcmTraceKind, frame: Long = -1,
        a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0,
        config: MetronomeConfig? = null,
    ) {
        revision++
        val sequence = attempted++
        try {
            if (!closed && !faulted && append(sequence, clock.nanoTime(), kind, frame, a, b, c, d, config)) accepted++
            else lost(kind)
        } catch (_: Exception) {
            faulted = true
            lost(kind)
        } finally { revision++ }
    }

    protected abstract fun append(
        sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
        a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?,
    ): Boolean

    /** Called by the same producer, after its last event; never flushes storage. */
    fun close() {
        revision++
        closed = true
        revision++
    }

    fun health(): PcmTraceHealth? {
        val before = revision
        if (before and 1L != 0L) return null
        val snapshot = PcmTraceHealth(attempted, accepted, dropped, faulted, closed,
            PcmTraceKind.entries.associateWith { losses.get(it.ordinal) })
        return snapshot.takeIf { before == revision }
    }
}
