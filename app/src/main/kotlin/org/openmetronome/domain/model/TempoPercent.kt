package org.openmetronome.domain.model

/** Exact multipliers; the stored integer BPM remains the original practice tempo. */
enum class TempoPercent(val percent: Int, val numerator: Int, val denominator: Int) {
    FULL(100, 1, 1), THREE_QUARTERS(75, 3, 4), HALF(50, 1, 2), QUARTER(25, 1, 4);

    fun display(base: Int): String {
        val hundredths = base * percent
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return if (fraction == 0) whole.toString() else "$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}"
    }
}

enum class TempoChange { NEXT_BAR, NEXT_BEAT }
