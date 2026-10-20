package org.openmetronome.audio

/** Extends Android's unsigned 32-bit playback head to a session-local Long. */
class PlaybackHeadCounter {
    private var previous = 0L
    private var wraps = 0L
    fun update(raw: Int): Long {
        val head = raw.toLong() and 0xffffffffL
        if (head < previous) wraps += 1L shl 32
        previous = head
        return wraps + head
    }
}
