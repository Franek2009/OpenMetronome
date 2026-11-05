package org.openmetronome.diagnostics

import java.util.concurrent.atomic.AtomicLong
import org.openmetronome.domain.audio.PcmTrace
import org.openmetronome.domain.audio.PcmTraceKind
import org.openmetronome.domain.audio.PcmTraceClock
import org.openmetronome.domain.model.MetronomeConfig

/** Consumer-owned value; allocated only by poll(), never by the audio producer. */
data class PcmTraceRecord(
    val sequence: Long, val nanos: Long, val kind: PcmTraceKind, val frame: Long,
    val a: Long, val b: Long, val c: Long, val d: Long, val config: MetronomeConfig?,
)

/**
 * DEV-only SPSC ring. Full means drop-new, never overwrite unread records or wait.
 * Release/acquire publication keeps fields and immutable config references visible.
 * No CSV, file, Android API, coroutine, lock or per-record producer allocation.
 */
class BoundedPcmTrace(
    val capacity: Int = MAX_CAPACITY,
    clock: PcmTraceClock = PcmTraceClock(System::nanoTime),
) : PcmTrace(clock) {
    init { require(capacity in 1..MAX_CAPACITY && capacity and (capacity - 1) == 0) }
    private val mask = capacity - 1
    private val sequences = LongArray(capacity)
    private val times = LongArray(capacity)
    private val frames = LongArray(capacity)
    private val valuesA = LongArray(capacity)
    private val valuesB = LongArray(capacity)
    private val valuesC = LongArray(capacity)
    private val valuesD = LongArray(capacity)
    private val kinds = IntArray(capacity)
    private val configs = arrayOfNulls<MetronomeConfig>(capacity)
    private val published = AtomicLong()
    private val consumed = AtomicLong()
    private val consumedConfigs = AtomicLong()
    private var writeCursor = 0L // producer only
    private var readCursor = 0L // consumer only
    private var writeConfigCursor = 0L // producer only
    private var readConfigCursor = 0L // consumer only
    val maxConfigRecords = minOf(capacity, 512)

    // Conservative array/reference storage estimate, excluding shared model objects,
    // returned DTOs and VM overhead. Leaves room for future control/storage lanes.
    val reservedBytes: Long = capacity.toLong() * (7 * 8 + 4 + 8) + 9 * 32 + 1024

    override fun append(
        sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
        a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?,
    ): Boolean {
        if (writeCursor - consumed.get() >= capacity) return false
        if (config != null && writeConfigCursor - consumedConfigs.get() >= maxConfigRecords) return false
        val index = writeCursor.toInt() and mask
        sequences[index] = sequence
        times[index] = nanos
        kinds[index] = kind.ordinal
        frames[index] = frame
        valuesA[index] = a
        valuesB[index] = b
        valuesC[index] = c
        valuesD[index] = d
        configs[index] = config
        if (config != null) writeConfigCursor++
        published.lazySet(++writeCursor)
        return true
    }

    /** One non-audio consumer only. The returned record never aliases ring storage. */
    fun poll(): PcmTraceRecord? {
        if (readCursor == published.get()) return null
        val index = readCursor.toInt() and mask
        val result = PcmTraceRecord(sequences[index], times[index], PcmTraceKind.entries[kinds[index]],
            frames[index], valuesA[index], valuesB[index], valuesC[index], valuesD[index], configs[index])
        configs[index] = null
        if (result.config != null) consumedConfigs.lazySet(++readConfigCursor)
        consumed.lazySet(++readCursor)
        return result
    }

    companion object {
        const val BUFFER_BUDGET_BYTES = 4 * 1024 * 1024
        const val MAX_CAPACITY = 32768
    }
}
