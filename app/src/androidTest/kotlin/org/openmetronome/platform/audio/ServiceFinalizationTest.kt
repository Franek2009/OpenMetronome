package org.openmetronome.platform.audio

import android.content.*
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.*
import org.openmetronome.audio.*
import org.openmetronome.domain.model.MetronomeConfig
import java.io.ByteArrayOutputStream

/** Real service lifecycle with an in-memory engine; no hardware playback or vibration. */
class ServiceFinalizationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as TestApplication
    private suspend fun until(condition: () -> Boolean) { withTimeout(15000) { while (!condition()) delay(20) } }
    private suspend fun bind(type: Class<out MetronomeService>, action: suspend (MetronomeService.LocalBinder) -> Unit) {
        val result = CompletableDeferred<MetronomeService.LocalBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { result.complete(binder as MetronomeService.LocalBinder) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        instrumentation.runOnMainSync { assertTrue(context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE)) }
        try { action(withTimeout(10000) { result.await() }) }
        finally { instrumentation.runOnMainSync { context.unbindService(connection); context.stopService(Intent(context, type)) } }
    }

    @Test fun forcedForegroundFailureReleasesReservationAndAllowsBackupAndNextStart() = runBlocking {
        val data = app.data
        data.ready.await()
        bind(FailingForegroundService::class.java) { binder ->
            instrumentation.runOnMainSync { assertFalse(binder.startPlayback()) }
            until { !data.playbackBusy.value }
            assertTrue(data.reservePlayback()); data.playbackFinished()
            val bytes = ByteArrayOutputStream()
            data.exportBackup { bytes }; data.awaitWrites()
            assertTrue(bytes.size() > 0)
            // Stay bound beyond Android's foreground timeout; no delayed process crash is allowed.
            delay(12000)
            assertFalse(data.playbackBusy.value)
        }
    }

    @Test fun destroyingServiceDoesNotCancelFinalCheckpoint() = runBlocking {
        val data = app.data
        data.ready.await()
        val original = data.sessionCheckpoint
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = object : AudioEngine {
            override val state = MutableStateFlow(AudioEngineState())
            override fun start(config: MetronomeConfig) {
                state.value = AudioEngineState(PlaybackStatus.PLAYING, config,
                    diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, 48000, 88221))
            }
            override fun updateConfig(config: MetronomeConfig) = Unit
            override fun stop() {
                if (state.value.status == PlaybackStatus.STOPPED || !stopping.compareAndSet(false, true)) return
                state.value = state.value.copy(status = PlaybackStatus.STOPPING)
                fakeScope.launch {
                    delay(150)
                    state.value = state.value.copy(status = PlaybackStatus.STOPPED,
                        diagnostics = state.value.diagnostics?.copy(framesWritten = 96000, framesPlayed = 96000))
                }
            }
            override fun close() = stop()
        }
        app.simulatedEngine = fake
        data.sessionCheckpoint = { session, days ->
            if (session.finished) { assertEquals(2000L, session.durationMs); entered.complete(Unit); release.await() }
            original(session, days)
            if (session.finished) committed.complete(Unit)
        }
        try {
            bind(MetronomeService::class.java) { binder ->
                instrumentation.runOnMainSync { assertTrue(binder.startPlayback()) }
                until { fake.state.value.status == PlaybackStatus.PLAYING }
                // No explicit Stop: onDestroy itself must obtain and persist the terminal state.
            }
            withTimeout(10000) { entered.await() }
            assertTrue(data.playbackBusy.value)
            release.complete(Unit)
            withTimeout(10000) { committed.await() }
            until { !data.playbackBusy.value }
        } finally {
            release.complete(Unit)
            until { !data.playbackBusy.value }
            data.sessionCheckpoint = original
            app.simulatedEngine = null
            fakeScope.cancel()
        }
    }
}
