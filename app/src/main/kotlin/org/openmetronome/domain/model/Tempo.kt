package org.openmetronome.domain.model

/** Tempo in main pulses per minute. A subdivision is not an additional main pulse. */
@JvmInline
value class Tempo(val bpm: Int) {
    init {
        require(bpm in MIN_BPM..MAX_BPM) {
            "Tempo must be between $MIN_BPM and $MAX_BPM BPM, got $bpm"
        }
    }

    companion object {
        const val MIN_BPM = 20
        const val MAX_BPM = 300
        const val DEFAULT_BPM = 120
    }
}
