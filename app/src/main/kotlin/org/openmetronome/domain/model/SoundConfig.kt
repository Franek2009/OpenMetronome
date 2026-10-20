package org.openmetronome.domain.model

enum class ClickSound { CLASSIC, WOOD, SHORT }

data class SoundConfig(val volumePercent: Int = 100, val clickSound: ClickSound = ClickSound.CLASSIC) {
    init { require(volumePercent in 0..100) }
}
