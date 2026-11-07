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
    val tempoPercent: TempoPercent = TempoPercent.FULL,
    val tempoChange: TempoChange = TempoChange.NEXT_BAR,
    val countInBars: Int = 0,
) {
    val effectiveBpmLabel: String get() = tempoPercent.display(tempo.bpm)
    init { require(beatAccents.isEmpty() || beatAccents.size == timeSignature.pulsesPerBar); require(countInBars in 0..2) }

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

    fun withAccentGroups(groups: List<Int>): MetronomeConfig {
        require(groups.isNotEmpty() && groups.all { it > 0 } && groups.sum() == timeSignature.pulsesPerBar)
        val boundaries = groups.runningFold(0, Int::plus).dropLast(1).toSet()
        return copy(accentFirstBeat = true, beatAccents = List(timeSignature.pulsesPerBar) {
            if (it in boundaries) ClickAccent.ACCENT else ClickAccent.NORMAL
        })
    }

    fun withTimeSignature(signature: TimeSignature): MetronomeConfig = copy(
        timeSignature = signature,
        beatAccents = if (beatAccents.isEmpty()) emptyList() else
            List(signature.pulsesPerBar) { beatAccents.getOrElse(it) { ClickAccent.NORMAL } },
    ).let { changed -> if (signature != timeSignature && signature.accentGroupings.isNotEmpty())
        changed.withAccentGroups(signature.accentGroupings.first()) else changed }
}
