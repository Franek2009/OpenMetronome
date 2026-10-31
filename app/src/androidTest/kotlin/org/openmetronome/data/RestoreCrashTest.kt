package org.openmetronome.data

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.RestoreCrashProbeService
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.*
import java.io.*
import java.util.UUID

/** Kills a real separate debug process. Neither the runner nor user storage is ever killed/cleared. */
@RunWith(AndroidJUnit4::class)
class RestoreCrashTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun archive(bpm: Int): ByteArray {
        val id = UUID.randomUUID().toString()
        val config = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(bpm), sound = SoundConfig(customSampleId = id)))
        val wav = SamplePcm.encodeCanonical(ShortArray(96).also { it[20] = 1000; it[21] = -1000 })
        return ByteArrayOutputStream().also { BackupArchive.write(BackupSnapshot(config,
            listOf(Preset("preset-$bpm", "Preset $bpm", config, 1, 2)),
            listOf(PracticeSession("session-$bpm", 0, 125000, 125000, true, false, "UTC", config)),
            listOf(PracticeDay("session-$bpm", "1970-01-01", 125000)),
            listOf(CustomSampleRecord(id, "Klik $bpm", 1)), mapOf(id to wav)), it) }.toByteArray()
    }
    private inner class Probe(private val fixture: String) : AutoCloseable {
        val connected = Channel<Messenger>(Channel.UNLIMITED)
        val died = Channel<Unit>(Channel.UNLIMITED)
        private val replies = Channel<Bundle>(Channel.UNLIMITED)
        private val reply = Messenger(Handler(Looper.getMainLooper()) { message -> replies.trySend(message.data); true })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.trySend(Messenger(service)) }
            override fun onServiceDisconnected(name: ComponentName) { died.trySend(Unit) }
            override fun onBindingDied(name: ComponentName) { died.trySend(Unit) }
        }
        private var bound = false
        fun bind() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                bound = context.bindService(Intent(context, RestoreCrashProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            check(bound)
        }
        suspend fun request(archive: ByteArray? = null, cut: RestoreCheckpoint? = null): Bundle {
            val service = withTimeout(30000) { connected.receive() }
            val data = Bundle().apply {
                putString("fixture", fixture); archive?.let { putByteArray("archive", it) }
                cut?.let { putString("cut", it.name) }
            }
            service.send(Message.obtain().apply { this.data = data; replyTo = reply })
            return if (cut != null) { withTimeout(30000) { died.receive() }; Bundle() }
                else withTimeout(30000) { replies.receive() }.also {
                    assertNull(it.getString("error"))
                    assertNotEquals("Probe must never kill the instrumentation process", Process.myPid(), it.getInt("pid"))
                }
        }
        override fun close() {
            if (bound) InstrumentationRegistry.getInstrumentation().runOnMainSync { context.unbindService(connection); bound = false }
        }
    }
    private suspend fun request(fixture: String, archive: ByteArray? = null, cut: RestoreCheckpoint? = null): Bundle =
        Probe(fixture).use { it.bind(); it.request(archive, cut) }
    private fun same(expected: ByteArray, actual: ByteArray) {
        val a = BackupArchive.read(ByteArrayInputStream(expected)); val b = BackupArchive.read(ByteArrayInputStream(actual))
        assertEquals(a.copy(sampleFiles = emptyMap()), b.copy(sampleFiles = emptyMap()))
        assertEquals(a.sampleFiles.keys, b.sampleFiles.keys)
        a.sampleFiles.forEach { (id, bytes) -> assertArrayEquals(bytes, b.sampleFiles[id]) }
    }

    @Test fun actualProcessDeathAtEveryRestoreBoundaryRecoversWholeOldOrCommittedNewState(): Unit = runBlocking {
        val before = archive(91); val after = archive(137)
        for (stage in listOf(RestoreCheckpoint.PREPARED, RestoreCheckpoint.OLD_FILES_MOVED,
            RestoreCheckpoint.NEW_FILES_MOVED, RestoreCheckpoint.DATABASE_CHANGED,
            RestoreCheckpoint.SETTINGS_WRITTEN, RestoreCheckpoint.DATABASE_COMMITTED,
            RestoreCheckpoint.STORES_SYNCED, RestoreCheckpoint.COMMITTED, RestoreCheckpoint.JOURNAL_RETIRED)) {
            val fixture = UUID.randomUUID().toString()
            try {
                request(fixture, before)
                request(fixture, after, stage)
                val directory = File(context.cacheDir, "restore-probes/$fixture")
                assertEquals(stage.name, File(directory, "process-cut").readText())
                val actual = requireNotNull(request(fixture).getByteArray("archive"))
                same(if (stage in listOf(RestoreCheckpoint.COMMITTED, RestoreCheckpoint.JOURNAL_RETIRED)) after else before, actual)
                same(actual, requireNotNull(request(fixture).getByteArray("archive")))
            } finally { File(context.cacheDir, "restore-probes/$fixture").deleteRecursively() }
        }
    }

    @Test fun recoveryCanItselfBeKilledAndRepeatedWithoutMixingStores(): Unit = runBlocking {
        val before = archive(91); val after = archive(137)
        for (stage in listOf(RestoreCheckpoint.RECOVERY_FILES_MOVED,
            RestoreCheckpoint.RECOVERY_DATABASE_CHANGED, RestoreCheckpoint.RECOVERY_STORES_SYNCED)) {
            val fixture = UUID.randomUUID().toString()
            try {
                request(fixture, before)
                request(fixture, after, RestoreCheckpoint.SETTINGS_WRITTEN)
                request(fixture, cut = stage)
                same(before, requireNotNull(request(fixture).getByteArray("archive")))
            } finally { File(context.cacheDir, "restore-probes/$fixture").deleteRecursively() }
        }
    }
}
