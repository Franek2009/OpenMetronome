package org.openmetronome.domain.model

/** M1 uses quarter-note pulses in /4 and six eighth-note pulses in 6/8. */
enum class TimeSignature(val numerator: Int, val denominator: Int) {
    TWO_FOUR(2, 4),
    THREE_FOUR(3, 4),
    FOUR_FOUR(4, 4),
    SIX_EIGHT(6, 8),
    FIVE_FOUR(5, 4), SEVEN_FOUR(7, 4), FIVE_EIGHT(5, 8), SEVEN_EIGHT(7, 8);

    val pulsesPerBar: Int get() = numerator
    val pulseUnit: PulseUnit get() = if (denominator == 8) PulseUnit.EIGHTH else PulseUnit.QUARTER
    val accentGroupings: List<List<Int>> get() = when (this) {
        FIVE_EIGHT -> listOf(listOf(2, 3), listOf(3, 2))
        SEVEN_EIGHT -> listOf(listOf(2, 2, 3), listOf(2, 3, 2), listOf(3, 2, 2))
        else -> emptyList()
    }
}

enum class PulseUnit { QUARTER, EIGHTH }
