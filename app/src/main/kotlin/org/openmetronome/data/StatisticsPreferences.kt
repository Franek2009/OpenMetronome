package org.openmetronome.data

import java.time.ZoneId

data class StatisticsPreferences(val minimumSessionSeconds: Int = 20, val activeDayMinutes: Int = 5) {
    init { require(minimumSessionSeconds in 0..3600); require(activeDayMinutes in 0..1440) }
}

data class AppPreferences(val display: PracticeDisplayPreferences = PracticeDisplayPreferences(),
    val statistics: StatisticsPreferences = StatisticsPreferences(),
    val musicStatistics: MusicStatisticsPreferences? = null)

object AppPreferencesCodec {
    fun encode(value: AppPreferences): String = with(value) {
        buildString {
            append("version=1\nawake=${display.keepFullscreenAwake}\nreduceMotion=${display.reduceMotion}\nminimumSessionSeconds=${statistics.minimumSessionSeconds}\nactiveDayMinutes=${statistics.activeDayMinutes}")
            musicStatistics?.let {
                append("\nmusicZone=${it.zone.id}\nmusicActiveDayMinutes=${it.activeDayMinutes}\nmusicWeeklyGoalDays=${it.weeklyGoalDays ?: 0}")
            }
        }
    }
    fun decode(value: String): AppPreferences {
        require(value.length <= 4096)
        val fields = value.lineSequence().filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2); require(parts.size == 2); parts[0] to parts[1]
        }
        require(fields["version"] == "1")
        val music = fields["musicZone"]?.let { zone ->
            MusicStatisticsPreferences(ZoneId.of(zone), fields["musicActiveDayMinutes"]?.toInt() ?: 5,
                (fields["musicWeeklyGoalDays"]?.toInt() ?: 5).takeUnless { it == 0 })
        }
        require(music != null || ("musicActiveDayMinutes" !in fields && "musicWeeklyGoalDays" !in fields))
        return AppPreferences(PracticeDisplayPreferences(fields["awake"]?.toBooleanStrict() ?: false,
            fields["reduceMotion"]?.toBooleanStrict() ?: false), StatisticsPreferences(
            fields["minimumSessionSeconds"]?.toInt() ?: 20, fields["activeDayMinutes"]?.toInt() ?: 5), music)
    }

    internal fun forRestore(incoming: String?, previous: String?): String? {
        if (incoming == null) return previous
        val settings = decode(incoming)
        if (settings.musicStatistics != null) return incoming
        val currentMusic = previous?.let(::decode)?.musicStatistics ?: return incoming
        return encode(settings.copy(musicStatistics = currentMusic))
    }

    internal fun withoutMusic(payload: String?): String? = payload?.let { value ->
        val settings = decode(value)
        if (settings.musicStatistics == null) value else encode(settings.copy(musicStatistics = null))
    }
}
