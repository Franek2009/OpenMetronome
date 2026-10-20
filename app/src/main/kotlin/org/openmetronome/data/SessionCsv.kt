package org.openmetronome.data

import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure formatter. The caller owns the writer; no storage, clipboard or network access. */
object SessionCsv {
    fun write(sessions: List<PracticeSession>, writer: Writer) {
        writer.write("\uFEFF")
        row(writer, listOf("session_id", "started_at", "checkpoint_at", "zone_id", "duration_ms", "status", "initial_bpm", "meter"))
        sessions.forEach { session ->
            val config = runCatching { ConfigCodec.decode(session.config) }.getOrNull()
            val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.of("UTC"))
            fun date(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            row(writer, listOf(session.id, date(session.startedAt), date(session.checkpointAt), session.zoneId,
                session.durationMs.toString(), when { session.interrupted -> "interrupted"; session.finished -> "finished"; else -> "in_progress" },
                config?.tempo?.bpm?.toString() ?: "", config?.timeSignature?.let { "${it.numerator}/${it.denominator}" } ?: ""))
        }
    }

    private fun row(writer: Writer, cells: List<String>) {
        writer.write(cells.joinToString(";") { cell ->
            // CSV quoting does not prevent spreadsheet formula evaluation.
            val safe = if (cell.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$cell" else cell
            "\"${safe.replace("\"", "\"\"")}\""
        })
        writer.write("\r\n")
    }
}
