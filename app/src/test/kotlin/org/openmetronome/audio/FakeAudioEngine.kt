package org.openmetronome.audio

import kotlinx.coroutines.flow.MutableStateFlow
import org.openmetronome.domain.model.MetronomeConfig

class FakeAudioEngine : AudioEngine {
    override val state = MutableStateFlow(AudioEngineState())
    var starts = 0
    var stops = 0
    var closed = false
    override fun start(config: MetronomeConfig) {
        if (state.value.status == PlaybackStatus.PLAYING) return
        starts++
        state.value = AudioEngineState(PlaybackStatus.PLAYING, config)
    }
    override fun updateConfig(config: MetronomeConfig) = Unit
    override fun stop() {
        stops++
        state.value = AudioEngineState()
    }
    override fun close() { closed = true }
}
