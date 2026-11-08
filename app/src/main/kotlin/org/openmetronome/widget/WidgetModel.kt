package org.openmetronome.widget

import org.openmetronome.audio.MetronomeState
import org.openmetronome.audio.PlaybackStatus
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.TempoPercent

/** No pulse/frame fields: changing a beat must not cause launcher IPC. */
data class WidgetPlayback(val bpm: String, val base: Int, val percent: Int, val status: String,
    val running: Boolean, val pending: Boolean)

object WidgetModel {
    val commands = setOf("start", "stop", "100", "75", "50", "25", "minus", "plus")
    fun playback(state: MetronomeState): WidgetPlayback {
        val shown = if (state.keepsScreenOn) state.audio.appliedConfig ?: state.audio.activeConfig ?: state.config else state.config
        return WidgetPlayback(shown.effectiveBpmLabel, state.config.tempo.bpm, state.config.tempoPercent.percent,
            when (state.audio.status) {
                PlaybackStatus.PLAYING -> "Gra"
                PlaybackStatus.STARTING -> "Uruchamianie"
                PlaybackStatus.STOPPING -> "Zatrzymywanie"
                PlaybackStatus.ERROR -> "Błąd · otwórz aplikację"
                else -> "Zatrzymany"
            }, state.keepsScreenOn, state.hasPendingChanges)
    }
    fun edit(config: MetronomeConfig, command: String): MetronomeConfig = when (command) {
        "minus", "plus" -> config.copy(tempo = Tempo((config.tempo.bpm + if (command == "plus") 1 else -1).coerceIn(20, 300)),
            ramp = config.ramp.copy(enabled = false))
        else -> TempoPercent.entries.firstOrNull { it.percent.toString() == command }?.let { config.copy(tempoPercent = it) } ?: config
    }
}
