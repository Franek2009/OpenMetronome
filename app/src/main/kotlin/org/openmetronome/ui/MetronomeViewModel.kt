package org.openmetronome.ui

import androidx.lifecycle.ViewModel
import org.openmetronome.audio.MetronomePort
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.TimeSignature
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.TapTempoEstimator

class MetronomeViewModel(
    private val controller: MetronomePort,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ViewModel() {
    private val tapEstimator = TapTempoEstimator()
    val state = controller.state
    fun tapTempo() { tapEstimator.tap(monotonicNanos())?.let(::setTempo) }
    fun resetTapTempo() = tapEstimator.reset()
    fun setConfig(config: MetronomeConfig) = controller.editConfig { config }

    fun setTempo(bpm: Int) {
        controller.editConfig {
            it.copy(tempo = Tempo(bpm.coerceIn(Tempo.MIN_BPM, Tempo.MAX_BPM)), ramp = it.ramp.copy(enabled = false))
        }
    }

    fun increaseTempo() {
        adjustTempo(1)
    }

    fun decreaseTempo() {
        adjustTempo(-1)
    }

    fun adjustTempo(delta: Int) {
        controller.editConfig {
            it.copy(tempo = Tempo((it.tempo.bpm.toLong() + delta).coerceIn(Tempo.MIN_BPM.toLong(), Tempo.MAX_BPM.toLong()).toInt()),
                ramp = it.ramp.copy(enabled = false))
        }
    }

    fun setTimeSignature(timeSignature: TimeSignature) {
        controller.editConfig { it.withTimeSignature(timeSignature) }
    }

    fun cycleBeatAccent(beat: Int) {
        controller.editConfig { config ->
            if (beat in 0 until config.timeSignature.pulsesPerBar) config.cycleBeatAccent(beat) else config
        }
    }

    fun setAccentFirstBeat(enabled: Boolean) {
        controller.editConfig { config ->
            config.copy(accentFirstBeat = enabled, beatAccents = if (config.beatAccents.isEmpty()) emptyList() else
                config.beatAccents.toMutableList().also { it[0] = if (enabled) org.openmetronome.domain.model.ClickAccent.ACCENT else org.openmetronome.domain.model.ClickAccent.NORMAL })
        }
    }

    fun start() = controller.start()
    fun stop() = controller.stop()
    override fun onCleared() { controller.close() }
}
