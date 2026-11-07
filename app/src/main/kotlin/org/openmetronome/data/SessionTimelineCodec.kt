package org.openmetronome.data

import java.io.*
import java.util.Base64

data class SessionConfiguration(val offsetMs: Long, val config: String)
data class SessionTimeline(val complete: Boolean, val changes: List<SessionConfiguration>)

/** Bounded per-session history. Null in Room means legacy/unknown, never an invented zero. */
object SessionTimelineCodec {
    const val MAX_BYTES = 512 * 1024
    const val MAX_CHANGES = 4096
    fun encode(value: SessionTimeline): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeBoolean(value.complete); out.writeInt(value.changes.size)
            value.changes.forEach { change ->
                val config = change.config.toByteArray(Charsets.UTF_8)
                require(config.size <= 16384)
                out.writeLong(change.offsetMs); out.writeInt(config.size); out.write(config)
                require(bytes.size() <= MAX_BYTES)
            }
        }
        require(value.changes.size <= MAX_CHANGES)
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(payload: String, durationMs: Long): SessionTimeline {
        require(payload.length <= (MAX_BYTES + 2) / 3 * 4)
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val complete = input.readBoolean()
            val count = input.readInt(); require(count in 0..MAX_CHANGES)
            var previous = -1L
            val changes = List(count) {
                val offset = input.readLong(); require(offset > previous && offset in 0..durationMs); previous = offset
                val size = input.readInt(); require(size in 1..16384)
                val config = String(ByteArray(size).also(input::readFully), Charsets.UTF_8).also(ConfigCodec::decode)
                SessionConfiguration(offset, config)
            }
            require(!complete || durationMs == 0L || changes.firstOrNull()?.offsetMs == 0L)
            require(input.read() == -1)
            SessionTimeline(complete, changes)
        }
    }
}
