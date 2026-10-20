package org.openmetronome.domain.model

data class SilenceConfig(
    val probability: Int = 0,
    val cycleEnabled: Boolean = false,
    val audibleBars: Int = 2,
    val silentBars: Int = 2,
) {
    init {
        require(probability in 0..100)
        require(audibleBars in 1..64 && silentBars in 1..64)
    }
}
