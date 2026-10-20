package org.openmetronome.domain.model

/** M1 uses quarter-note pulses in /4 and six eighth-note pulses in 6/8. */
enum class TimeSignature(val numerator: Int, val denominator: Int) {
    TWO_FOUR(2, 4),
    THREE_FOUR(3, 4),
    FOUR_FOUR(4, 4),
    SIX_EIGHT(6, 8);

    val pulsesPerBar: Int get() = numerator
    val pulseUnit: PulseUnit get() = if (denominator == 8) PulseUnit.EIGHTH else PulseUnit.QUARTER
}

enum class PulseUnit { QUARTER, EIGHTH }
