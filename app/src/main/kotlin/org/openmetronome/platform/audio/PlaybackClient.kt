package org.openmetronome.platform.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openmetronome.audio.*
import org.openmetronome.domain.model.MetronomeConfig

/** Bound while the screen's ViewModel lives; unbinding never stops a started session. */
class PlaybackClient(context: Context) : MetronomePort {
    private val context = context.applicationContext
    private val data = (this.context as org.openmetronome.OpenMetronomeApplication).data
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(MetronomeState())
    override val state = mutableState.asStateFlow()
    private var controller: MetronomeController? = null
    private var stopRemote: (() -> Unit)? = null
    private var startRemote: (() -> Boolean)? = null
    private var collector: Job? = null
    private var pendingStart = false
    private var waitingForEngine = false
    private var edited = false
    private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            val local = binder as MetronomeService.LocalBinder
            val remote = local.controller
            stopRemote = local::stopPlayback
            startRemote = local::startPlayback
            controller = remote
            if (edited) remote.editConfig { mutableState.value.config }
            else mutableState.value = remote.state.value
            collector?.cancel()
            collector = scope.launch { remote.state.collect {
                if (it.audio.status != PlaybackStatus.STOPPED) waitingForEngine = false
                mutableState.value = if (waitingForEngine) it.copy(audio = AudioEngineState(PlaybackStatus.STARTING)) else it
            } }
            if (pendingStart) { pendingStart = false; dispatchStart() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            controller = null
            stopRemote = null
            startRemote = null
            collector?.cancel()
            mutableState.update { it.copy(audio = AudioEngineState(PlaybackStatus.ERROR, failure = AudioFailure.DEVICE_LOST)) }
        }
    }
    private val bound = this.context.bindService(Intent(this.context, MetronomeService::class.java), connection, Context.BIND_AUTO_CREATE)

    override fun editConfig(transform: (MetronomeConfig) -> MetronomeConfig) {
        data.editConfiguration {
            if (!closed) {
                edited = true
                mutableState.update { it.copy(config = transform(it.config)) }
                controller?.editConfig { mutableState.value.config }
                // Enqueue synchronously before a following backup can reserve admission.
                data.saveSettings(mutableState.value.config)
            }
        }
    }
    override fun start() {
        if (closed || mutableState.value.keepsScreenOn || data.backupWorking.value || data.recoveryRequired.value) return
        waitingForEngine = true
        mutableState.update { it.copy(audio = AudioEngineState(PlaybackStatus.STARTING)) }
        pendingStart = controller == null
        if (pendingStart) {
            if (!bound) fail()
            return
        }
        dispatchStart()
    }
    private fun dispatchStart() { try { if (startRemote?.invoke() != true) fail() } catch (_: RuntimeException) { fail() } }
    private fun fail() {
        pendingStart = false
        waitingForEngine = false
        mutableState.update { it.copy(audio = AudioEngineState(PlaybackStatus.ERROR, failure = AudioFailure.INITIALIZATION)) }
    }
    override fun stop() {
        pendingStart = false
        waitingForEngine = false
        stopRemote?.invoke()
        mutableState.update { it.copy(audio = controller?.state?.value?.audio ?: AudioEngineState()) }
    }
    override fun close() {
        if (closed) return
        closed = true
        pendingStart = false
        collector?.cancel()
        scope.cancel()
        if (bound) context.unbindService(connection)
    }
}
