package org.openmetronome.domain.model

/** Immutable, platform-independent settings shared by playback and persistence. */
data class MetronomeConfig(
    val tempo: Tempo = Tempo(Tempo.DEFAULT_BPM),
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val accentFirstBeat: Boolean = true,
    val silence: SilenceConfig = SilenceConfig(),
    val visualPulse: Boolean = true,
    val vibration: Boolean = false,
    val progression: SilenceProgression = SilenceProgression(),
    val ramp: TempoRamp = TempoRamp(),
    val rhythm: RhythmConfig = RhythmConfig(),
    val sound: SoundConfig = SoundConfig(),
    val beatAccents: List<ClickAccent> = emptyList(),
) {
    init { require(beatAccents.isEmpty() || beatAccents.size == timeSignature.pulsesPerBar) }

    fun beatAccentAt(beat: Int): ClickAccent {
        require(beat in 0 until timeSignature.pulsesPerBar)
        return beatAccents.getOrElse(beat) {
            if (beat == 0 && accentFirstBeat) ClickAccent.ACCENT else ClickAccent.NORMAL
        }
    }

    fun cycleBeatAccent(beat: Int): MetronomeConfig {
        val accents = List(timeSignature.pulsesPerBar, ::beatAccentAt).toMutableList()
        accents[beat] = ClickAccent.entries[(accents[beat].ordinal + 1) % ClickAccent.entries.size]
        return copy(beatAccents = accents)
    }

    fun withTimeSignature(signature: TimeSignature): MetronomeConfig = copy(
        timeSignature = signature,
        beatAccents = if (beatAccents.isEmpty()) emptyList() else
            List(signature.pulsesPerBar) { beatAccents.getOrElse(it) { ClickAccent.NORMAL } },
    )
}
