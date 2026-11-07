package org.openmetronome.audio

import org.openmetronome.domain.model.MetronomeConfig

data class ConfigurationChange(val frame: Long, val config: MetronomeConfig)

/** One PCM producer, one recorder consumer. No locks, IO or waiting on the audio worker. */
class ConfigurationTimeline(private val capacity: Int = 512) {
    init { require(capacity in 2..4096) }
    private val frames = LongArray(capacity)
    private val configs = arrayOfNulls<MetronomeConfig>(capacity)
    @Volatile private var written = 0L
    @Volatile private var consumed = 0L
    @Volatile var dropped = 0L
        private set

    fun record(frame: Long, config: MetronomeConfig) {
        val next = written
        if (next - consumed >= capacity) { dropped++; return }
        val index = (next % capacity).toInt()
        frames[index] = frame; configs[index] = config
        written = next + 1
    }

    /** Only played positions may enter practice history; queued future changes stay here. */
    fun drainThrough(playedFrame: Long): List<ConfigurationChange> = buildList {
        var next = consumed
        while (next < written) {
            val index = (next % capacity).toInt()
            if (frames[index] > playedFrame) break
            add(ConfigurationChange(frames[index], checkNotNull(configs[index])))
            configs[index] = null
            consumed = ++next
        }
    }
}
