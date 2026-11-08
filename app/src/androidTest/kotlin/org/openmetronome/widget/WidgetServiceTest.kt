package org.openmetronome.widget

import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.TestApplication
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*
import org.openmetronome.platform.audio.MetronomeService

/** Bind to the real service with a memory-only engine and isolated preferences. */
class WidgetServiceTest {
    @Test fun coldCommandsLoadSettingsRespectBackupAndDoNotRestartPlayingEngine() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as TestApplication
        val data = app.data
        data.ready.await()
        val original = data.settings.load()
        data.saveSettings(original.copy(tempo = Tempo(123), tempoPercent = TempoPercent.FULL))
        data.awaitWrites()
        var starts = 0
        val closed = CompletableDeferred<Unit>()
        val fake = object : AudioEngine {
            override val state = MutableStateFlow(AudioEngineState())
            override fun start(config: MetronomeConfig) { starts++; state.value = AudioEngineState(PlaybackStatus.PLAYING, config) }
            override fun updateConfig(config: MetronomeConfig) = Unit
            override fun stop() { state.value = AudioEngineState() }
            override fun close() { stop(); closed.complete(Unit) }
        }
        app.simulatedEngine = fake
        val connected = CompletableDeferred<MetronomeService.LocalBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(binder as MetronomeService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        try {
            instrumentation.runOnMainSync { bound = context.bindService(Intent(context, MetronomeService::class.java), connection, Context.BIND_AUTO_CREATE) }
            assertTrue(bound)
            val binder = withTimeout(10000) { connected.await() }
            withContext(Dispatchers.Main) {
                binder.widgetCommand("50")
                assertEquals(123, binder.controller.state.value.config.tempo.bpm)
                assertEquals("61.5", binder.controller.state.value.config.effectiveBpmLabel)
                data.backupWorking.value = true
                try {
                    var rejected = false
                    try { binder.widgetCommand("25") } catch (_: IllegalStateException) { rejected = true }
                    assertTrue(rejected)
                    assertEquals(TempoPercent.HALF, binder.controller.state.value.config.tempoPercent)
                } finally { data.backupWorking.value = false }
                binder.widgetCommand("start")
            }
            withTimeout(10000) { while (fake.state.value.status != PlaybackStatus.PLAYING) delay(20) }
            withContext(Dispatchers.Main) { binder.widgetCommand("start"); binder.widgetCommand("stop") }
            assertEquals(1, starts)
            withTimeout(10000) { while (data.playbackBusy.value) delay(20) }
        } finally {
            instrumentation.runOnMainSync {
                if (bound) context.unbindService(connection)
                context.stopService(Intent(context, MetronomeService::class.java))
            }
            withTimeout(10000) { while (data.playbackBusy.value) delay(20) }
            withTimeout(10000) { closed.await() }
            app.simulatedEngine = null
            data.saveSettings(original); data.awaitWrites()
        }
    }
}
