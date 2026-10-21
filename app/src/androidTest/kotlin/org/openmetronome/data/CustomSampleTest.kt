package org.openmetronome.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.audio.PcmRenderer
import org.openmetronome.domain.model.*
import org.openmetronome.platform.audio.AndroidSampleDecoder

/** Fixture-only files/DBs. Decoding produces PCM without acquiring audio focus or playing it. */
@RunWith(AndroidJUnit4::class)
class CustomSampleTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PracticeDatabase::class.java)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun audio() = SamplePcm.Audio(48000, DoubleArray(4800) { kotlin.math.sin(2 * Math.PI * 1000 * it / 48000) })

    @Test fun migrationPreservesV1PresetsSessionsAndDays() {
        val name = "sample-migration-${UUID.randomUUID()}.db"
        val config = "version=1\nbpm=87\nclickSound=CLASSIC\nvolumePercent=37"
        try {
            migration.createDatabase(name, 1).use { db ->
                db.execSQL("INSERT INTO presets VALUES ('fixture','Old preset',?,1,1)", arrayOf(config))
                db.execSQL("INSERT INTO practice_sessions VALUES ('session',1,2,1000,1,0,'UTC',?)", arrayOf(config))
                db.execSQL("INSERT INTO practice_days VALUES ('session','2026-10-20',1000)")
            }
            migration.runMigrationsAndValidate(name, 2, true, PracticeDatabase.MIGRATION_1_2).use { db ->
                db.query("SELECT config FROM presets WHERE id='fixture'").use { assertTrue(it.moveToFirst()); assertEquals(config, it.getString(0)) }
                for (table in listOf("practice_sessions", "practice_days")) db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                db.query("SELECT COUNT(*) FROM custom_samples").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) } // Only the UUID migration fixture.
    }

    @Test fun platformDecodesMp3VorbisOpusAndWavWithoutPlayback() {
        val decoder = AndroidSampleDecoder(context)
        for (name in listOf("tone.mp3", "tone-vorbis.ogg", "tone-opus.ogg")) {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("samples/$name").use { it.readBytes() }
            val audio = decoder.decodeBytes(bytes)
            assertTrue(audio.rate in 8000..192000)
            val pcm = SamplePcm.prepare(audio).pcm
            assertTrue(pcm.size <= 1920); assertEquals(29162, pcm.maxOf { kotlin.math.abs(it.toInt()) })
        }
        val file = File(context.cacheDir, "source-${UUID.randomUUID()}.wav")
        try {
            file.writeBytes(SamplePcm.encodeCanonical(SamplePcm.prepare(audio()).pcm))
            assertEquals(48000, decoder.decode(Uri.fromFile(file)).rate)
        } finally { file.delete() }
        assertThrows(Exception::class.java) { decoder.decodeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(Exception::class.java) { decoder.decodeBytes("ID3 invalid MP3".toByteArray()) }
    }

    @Test fun librarySurvivesReloadBlocksUnsafeEditsAndProtectsPresets(): Unit = runBlocking {
        val directory = File(context.cacheDir, "custom-library-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        try {
            val repo = CustomSampleRepository(db, directory)
            repo.load()
            val (id, trimmed) = repo.importSample("Fixture.wav") { audio() }
            assertTrue(trimmed); assertTrue(repo.items.value.single().available)
            val config = MetronomeConfig(sound = SoundConfig(37, ClickSound.IMPULSE_FULL, id))
            repo.acquire().use { lease ->
                assertThrows(IllegalStateException::class.java) { runBlocking { repo.delete(id) } }
                val renderer = PcmRenderer(48000, config, customSamples = lease.samples)
                val output = ShortArray(48000)
                renderer.render(output, requested = config)
                assertTrue(output.any { it != 0.toShort() })
            }
            // Even duplicate close must not release another session's edit protection.
            val first = repo.acquire(); val second = repo.acquire(); first.close(); first.close()
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.rename(id, "Blocked") } }
            second.close()
            repo.rename(id, "Renamed")
            val reloaded = CustomSampleRepository(db, directory)
            reloaded.load()
            assertEquals("Renamed", reloaded.items.value.single().name)
            reloaded.acquire().use { assertTrue(it.samples.containsKey(id)) }
            db.practice().savePreset(Preset("fixture", "Fixture", ConfigCodec.encode(config), 1, 1))
            assertThrows(IllegalStateException::class.java) { runBlocking { reloaded.delete(id) } }
            db.practice().deletePreset("fixture")
            reloaded.delete(id)
            assertTrue(reloaded.items.value.isEmpty()); assertFalse(File(directory, "$id.wav").exists())
            val count = directory.listFiles()?.size ?: 0
            assertThrows(IllegalArgumentException::class.java) { runBlocking { reloaded.importSample("Silent") { SamplePcm.Audio(48000, DoubleArray(100)) } } }
            assertEquals(count, directory.listFiles()?.size ?: 0)
            assertTrue(db.practice().allSamples().isEmpty())
        } finally { db.close(); directory.deleteRecursively() } // Only this UUID fixture directory.
    }

    @Test fun missingOrCorruptPrivateFileIsUnavailableRatherThanSilentFallback(): Unit = runBlocking {
        val directory = File(context.cacheDir, "missing-library-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        try {
            val repo = CustomSampleRepository(db, directory); repo.load()
            val id = repo.importSample("Fixture") { audio() }.first
            File(directory, "$id.wav").writeBytes(byteArrayOf(1)) // Corrupt our own fixture, never user data.
            val reloaded = CustomSampleRepository(db, directory); reloaded.load()
            assertFalse(reloaded.items.value.single().available)
            reloaded.acquire().use { lease ->
                assertThrows(org.openmetronome.domain.audio.MissingSampleException::class.java) {
                    PcmRenderer(48000, MetronomeConfig(sound = SoundConfig(customSampleId = id)), customSamples = lease.samples)
                }
            }
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun libraryLimitsAndCancelledImportLeaveExistingSamplesUntouched(): Unit = runBlocking {
        val directory = File(context.cacheDir, "limit-library-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        try {
            val repo = CustomSampleRepository(db, directory); repo.load()
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { repo.importSample("Cancelled") { throw kotlinx.coroutines.CancellationException("fixture") } }
            }
            assertTrue(repo.items.value.isEmpty()); assertFalse(repo.busy.value)
            repeat(32) { repo.importSample("Same name.wav") { audio() } }
            assertEquals(32, repo.items.value.map { it.id }.toSet().size)
            assertEquals(1, repo.items.value.map { it.name }.toSet().size)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.importSample("Over limit") { error("Must not decode") } } }
            assertEquals(32, db.practice().allSamples().size)
            assertEquals(32, directory.listFiles()!!.size)
        } finally { db.close(); directory.deleteRecursively() }
    }
}
