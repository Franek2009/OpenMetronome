package org.openmetronome.domain.model

data class SilenceProgression(val enabled: Boolean = false, val increment: Int = 10, val intervalSeconds: Int = 60) {
    init { require(increment in 1..100); require(intervalSeconds in 1..3600) }
}

data class TempoRamp(
    val enabled: Boolean = false, val startBpm: Int = 60, val targetBpm: Int = 120,
    val step: Int = 5, val barsPerStep: Int = 4,
) {
    init {
        Tempo(startBpm); Tempo(targetBpm)
        require(step in 1..280 && barsPerStep in 1..64)
    }
}

enum class ClickAccent { NORMAL, ACCENT, MUTE }
enum class Subdivision(val label: String, val offsets: List<Pair<Int, Int>>) {
    SINGLE("1× puls", listOf(0 to 1)),
    EIGHTHS("2× puls", listOf(0 to 2, 1 to 2)),
    TRIPLETS("Triole", listOf(0 to 3, 1 to 3, 2 to 3)),
    SIXTEENTHS("4× puls", listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4)),
    DOTTED("Punktowany 3:1", listOf(0 to 4, 3 to 4)),
}
data class RhythmConfig(val subdivision: Subdivision = Subdivision.SINGLE, val accents: List<ClickAccent> = emptyList()) {
    init { require(accents.isEmpty() || accents.size == subdivision.offsets.size) }
    fun accentAt(slot: Int) = accents.getOrElse(slot) { ClickAccent.NORMAL }
}
