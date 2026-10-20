package org.openmetronome.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openmetronome.domain.model.MetronomeConfig

data class MetronomeState(
    val config: MetronomeConfig = MetronomeConfig(),
    val audio: AudioEngineState = AudioEngineState(),
) {
    val keepsScreenOn: Boolean
        get() = audio.status == PlaybackStatus.STARTING || audio.status == PlaybackStatus.PLAYING
    val hasPendingChanges: Boolean
        get() = keepsScreenOn && audio.activeConfig != null && config != (audio.appliedConfig ?: audio.activeConfig)
}

class MetronomeController(private val engine: AudioEngine) : MetronomePort {
    private val commands = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(MetronomeState())
    override val state = mutableState.asStateFlow()
    private var closed = false
    @Volatile private var externalFailure: AudioFailure? = null
    var configurationRevision = 0L
        private set

    init {
        // State transport only. Coroutines never decide when an audio beat occurs.
        scope.launch {
            engine.state.collect { audio ->
                mutableState.update {
                    val failure = externalFailure
                    it.copy(audio = if (failure != null && audio.status == PlaybackStatus.STOPPED)
                        AudioEngineState(PlaybackStatus.ERROR, failure = failure) else audio)
                }
            }
        }
    }

    override fun editConfig(transform: (MetronomeConfig) -> MetronomeConfig) = synchronized(commands) {
        if (closed) return@synchronized
        configurationRevision++
        mutableState.update { it.copy(config = transform(it.config)) }
        engine.updateConfig(mutableState.value.config)
    }

    override fun start() = synchronized(commands) {
        if (!closed) {
            externalFailure = null
            engine.start(mutableState.value.config)
            mutableState.update { it.copy(audio = engine.state.value) }
        }
    }

    override fun stop() = synchronized(commands) {
        if (!closed) {
            externalFailure = null
            engine.stop()
            mutableState.update { it.copy(audio = engine.state.value) }
        }
    }

    fun reportFailure(failure: AudioFailure) = synchronized(commands) {
        if (closed) return@synchronized
        externalFailure = failure
        mutableState.update { it.copy(audio = AudioEngineState(PlaybackStatus.ERROR, failure = failure)) }
    }

    override fun close() = synchronized(commands) {
        if (!closed) {
            closed = true
            engine.close()
            scope.cancel()
        }
    }
}
