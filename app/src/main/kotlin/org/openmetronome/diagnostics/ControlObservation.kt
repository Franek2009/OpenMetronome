package org.openmetronome.diagnostics

import java.util.concurrent.atomic.AtomicLong
import org.openmetronome.domain.model.MetronomeConfig

/** Stable export codes; these do not share the PCM producer's sequence. */
enum class TraceControlKind(val code: Int) {
    START_REQUESTED(100), START_REJECTED(101), STOP_REQUESTED(102), CONFIG_REQUESTED(103),
    FOCUS_REQUESTED(110), FOCUS_RESULT(111), FOCUS_CHANGE(112), FOCUS_RELEASE(113), OUTPUT_NOISY(114),
    HISTORY_CREATED(120), CHECKPOINT_REQUESTED(121), CHECKPOINT_RETURNED(122), CHECKPOINT_FAILED(123),
    SERVICE_CREATED(130), SERVICE_DESTROYED(131), BACKGROUND_STATE(132),
    STORAGE_RECOVERY(140), STORAGE_ERROR(141),
}

/** Optional bounded, memory-only control lane. Must not be called from PCM rendering. */
abstract class ControlObservation {
    private val failed = AtomicLong()
    val failures: Long get() = failed.get()
    fun event(sessionId: Long, kind: TraceControlKind, a: Long = 0, b: Long = 0,
              c: Long = 0, d: Long = 0, config: MetronomeConfig? = null) {
        try { append(sessionId, kind, a, b, c, d, config) }
        catch (_: Exception) { failed.incrementAndGet() }
    }
    protected abstract fun append(sessionId: Long, kind: TraceControlKind, a: Long, b: Long,
                                  c: Long, d: Long, config: MetronomeConfig?)
}
