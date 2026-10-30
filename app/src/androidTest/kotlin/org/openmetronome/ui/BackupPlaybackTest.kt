package org.openmetronome.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openmetronome.*
import org.openmetronome.audio.*
import org.openmetronome.domain.model.MetronomeConfig
import java.io.ByteArrayOutputStream

/** Real service/client/recorder, memory-only fake engine: no AudioTrack, focus or haptics. */
@RunWith(AndroidJUnit4::class)
class BackupPlaybackTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fake = object : AudioEngine {
        override val state = MutableStateFlow(AudioEngineState())
        override fun start(config: MetronomeConfig) {
            state.value = AudioEngineState(PlaybackStatus.PLAYING, config,
                diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, 48000, 991))
        }
        override fun updateConfig(config: MetronomeConfig) = Unit
        override fun stop() { state.value = state.value.copy(status = PlaybackStatus.STOPPED) }
        override fun close() = stop()
    }
    private val installFake = object : ExternalResource() {
        private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TestApplication
        override fun before() { application.simulatedEngine = fake }
        override fun after() { application.simulatedEngine = null }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(AudioPermissionRule()).around(UnlockedScreenRule()).around(installFake).around(compose)

    @Test fun backupRemainsBlockedUntilStoppedSessionIsActuallySaved(): Unit = runBlocking {
        val data = (compose.activity.application as TestApplication).data
        val model = ViewModelProvider(compose.activity)[MetronomeViewModel::class.java]
        data.ready.await(); data.awaitWrites()
        withContext(Dispatchers.IO) { data.database.clearAllTables(); data.samples.load() }
        compose.runOnIdle { model.setConfig(MetronomeConfig()) }
        data.awaitWrites()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val transaction = async(Dispatchers.IO) {
            data.database.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            compose.runOnIdle { model.start() }
            compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.PLAYING }
            assertTrue(data.playbackBusy.value)
            compose.runOnIdle { model.stop() }
            compose.waitUntil(10000) { model.state.value.audio.status == PlaybackStatus.STOPPED }
            assertTrue("Final checkpoint is still waiting for the database", data.playbackBusy.value)
            var opened = false
            data.exportBackup { opened = true; ByteArrayOutputStream() }
            assertFalse(opened)
        } finally { release.complete(Unit); transaction.await(); compose.runOnIdle { model.stop() } }
        compose.waitUntil(10000) { !data.playbackBusy.value }
        assertEquals(1L, data.dao.total().first().sessions)
        assertTrue(data.dao.sessionsForExport().single().finished)
        val output = ByteArrayOutputStream()
        data.exportBackup { output }; data.awaitWrites()
        assertTrue(output.size() > 0)
    }
}
