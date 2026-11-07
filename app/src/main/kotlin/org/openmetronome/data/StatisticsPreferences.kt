package org.openmetronome.data

data class StatisticsPreferences(val minimumSessionSeconds: Int = 20, val activeDayMinutes: Int = 5) {
    init { require(minimumSessionSeconds in 0..3600); require(activeDayMinutes in 0..1440) }
}

data class AppPreferences(val display: PracticeDisplayPreferences = PracticeDisplayPreferences(),
    val statistics: StatisticsPreferences = StatisticsPreferences())

object AppPreferencesCodec {
    fun encode(value: AppPreferences): String = with(value) {
        "version=1\nawake=${display.keepFullscreenAwake}\nreduceMotion=${display.reduceMotion}\nminimumSessionSeconds=${statistics.minimumSessionSeconds}\nactiveDayMinutes=${statistics.activeDayMinutes}"
    }
    fun decode(value: String): AppPreferences {
        require(value.length <= 4096)
        val fields = value.lineSequence().filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2); require(parts.size == 2); parts[0] to parts[1]
        }
        require(fields["version"] == "1")
        return AppPreferences(PracticeDisplayPreferences(fields["awake"]?.toBooleanStrict() ?: false,
            fields["reduceMotion"]?.toBooleanStrict() ?: false), StatisticsPreferences(
            fields["minimumSessionSeconds"]?.toInt() ?: 20, fields["activeDayMinutes"]?.toInt() ?: 5))
    }
}
