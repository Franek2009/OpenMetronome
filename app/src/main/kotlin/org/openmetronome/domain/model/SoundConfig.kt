package org.openmetronome.domain.model

// Names are persisted in settings/presets: keep the original three identifiers.
enum class ClickSound { CLASSIC, WOOD, SHORT, WOODBLOCK, RIMSHOT, COWBELL, CLAP, PULSE }

data class SoundConfig(val volumePercent: Int = 100, val clickSound: ClickSound = ClickSound.CLASSIC) {
    init { require(volumePercent in 0..100) }
}
