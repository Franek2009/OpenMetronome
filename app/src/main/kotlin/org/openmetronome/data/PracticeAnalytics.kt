package org.openmetronome.data

import java.time.*
import java.time.temporal.TemporalAdjusters

sealed interface StatisticsHistory {
    data object Loading : StatisticsHistory
    data class Ready(val sessions: List<PracticeSession>) : StatisticsHistory
    data object Unavailable : StatisticsHistory
}

data class PracticeAnalytics(
    val qualifying: List<PracticeSession>, val days: List<DayTotal>, val activeDates: Set<LocalDate>,
    val summary: PracticeStatsSummary, val todayMs: Long, val weekMs: Long, val monthMs: Long,
    val tempoMs: Map<String, Long>, val meterMs: Map<String, Long>,
    val trainingMs: Long, val rampMs: Long, val knownMs: Long, val unknownMs: Long,
)

object PracticeAnalyticsCalculator {
    fun calculate(sessions: List<PracticeSession>, preferences: StatisticsPreferences, today: LocalDate,
        timelineFor: (PracticeSession) -> SessionTimeline? = { session ->
            session.timeline?.let { runCatching { SessionTimelineCodec.decode(it, session.durationMs) }.getOrNull() }
        }): PracticeAnalytics {
        val qualifying = sessions.filter { it.durationMs > 0 && it.durationMs >= preferences.minimumSessionSeconds * 1000L }
        val totals = sortedMapOf<String, Long>()
        qualifying.forEach { session ->
            PracticeTimeline.days(session.id, session.startedAt, session.durationMs, ZoneId.of(session.zoneId)).forEach {
                totals[it.date] = Math.addExact(totals[it.date] ?: 0, it.durationMs)
            }
        }
        val days = totals.map { DayTotal(it.key, it.value) }
        val active = days.filter { it.durationMs > 0 && it.durationMs >= preferences.activeDayMinutes * 60000L }
        val total = PracticeTotal(qualifying.size.toLong(), qualifying.fold(0L) { sum, s -> Math.addExact(sum, s.durationMs) })
        val regularity = PracticeStats.summary(active, total, today)
        val summary = PracticeStats.summary(days, total, today).copy(currentStreak = regularity.currentStreak, longestStreak = regularity.longestStreak)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun sumFrom(first: LocalDate) = days.filter { LocalDate.parse(it.date) in first..today }.sumOf { it.durationMs }
        val tempos = linkedMapOf("5–59.99 BPM" to 0L, "60–89.99 BPM" to 0L, "90–119.99 BPM" to 0L, "120–179.99 BPM" to 0L, "180–300 BPM" to 0L)
        val meters = sortedMapOf<String, Long>()
        var known = 0L; var training = 0L; var ramps = 0L
        qualifying.forEach { session ->
            val timeline = timelineFor(session)
            if (timeline?.complete != true) return@forEach
            known = Math.addExact(known, session.durationMs)
            timeline.changes.forEachIndexed { index, change ->
                val duration = (timeline.changes.getOrNull(index + 1)?.offsetMs ?: session.durationMs) - change.offsetMs
                val config = ConfigCodec.decode(change.config)
                val n = config.tempo.bpm * config.tempoPercent.numerator
                val d = config.tempoPercent.denominator
                val bucket = when { n < 60 * d -> 0; n < 90 * d -> 1; n < 120 * d -> 2; n < 180 * d -> 3; else -> 4 }
                val key = tempos.keys.elementAt(bucket); tempos[key] = Math.addExact(tempos.getValue(key), duration)
                val meter = "${config.timeSignature.numerator}/${config.timeSignature.denominator}"
                meters[meter] = Math.addExact(meters[meter] ?: 0, duration)
                if (config.silence.probability > 0 || config.silence.cycleEnabled || config.progression.enabled) training += duration
                if (config.ramp.enabled) ramps += duration
            }
        }
        return PracticeAnalytics(qualifying, days, active.map { LocalDate.parse(it.date) }.toSet(), summary,
            totals[today.toString()] ?: 0, sumFrom(monday), sumFrom(today.withDayOfMonth(1)), tempos, meters,
            training, ramps, known, total.durationMs - known)
    }
}
