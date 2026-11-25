package org.openmetronome.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.MusicActivity
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityInterval
import org.openmetronome.domain.model.MusicActivityKind
import org.openmetronome.domain.model.MusicActivitySource

class BackupArchiveTest {
    private val fitDetails = MusicActivityDetails(MusicActivityKind.INDIVIDUAL_PRACTICE, MusicActivitySource.FIT,
        0, 197_000, "Europe/Warsaw", 133_360, 1_000,
        listOf(MusicActivityInterval(0, 61_000), MusicActivityInterval(125_000, 197_000)), "Kontrabas")

    @Test fun `full backup preserves independent music settings and legacy export omits them`() {
        for (goal in listOf(3, null)) {
            val music = MusicStatisticsPreferences(ZoneId.of("America/Toronto"), 11, goal)
            val preferences = AppPreferences(statistics = StatisticsPreferences(30, 8), musicStatistics = music)
            val data = snapshot().copy(preferences = AppPreferencesCodec.encode(preferences))
            assertEquals(preferences, AppPreferencesCodec.decode(BackupArchive.read(encoded(data).inputStream()).preferences!!))
            val legacy = BackupArchive.read(v2(data).inputStream()).preferences!!
            assertEquals(preferences.copy(musicStatistics = null), AppPreferencesCodec.decode(legacy))
            assertEquals(preferences, AppPreferencesCodec.decode(AppPreferencesCodec.forRestore(legacy, data.preferences)!!))
        }
    }

    @Test fun `backup rejects invalid music preferences before writing output`() {
        for (settings in listOf("musicZone=NoSuchZone", "musicActiveDayMinutes=60",
            "musicZone=UTC\nmusicActiveDayMinutes=-1", "musicZone=UTC\nmusicWeeklyGoalDays=8")) {
            val output = ByteArrayOutputStream()
            val data = snapshot().copy(preferences = "version=1\n$settings")
            assertThrows(RuntimeException::class.java) { BackupArchive.write(data, output) }
            assertEquals(0, output.size())
        }
    }

    @Test fun `v2 preserves timelines and app preferences while compatibility export omits them`() {
        val original = snapshot()
        val timeline = SessionTimelineCodec.encode(SessionTimeline(true, listOf(SessionConfiguration(0, config))))
        val value = original.copy(sessions = original.sessions.map { it.copy(timeline = timeline) },
            preferences = AppPreferencesCodec.encode(AppPreferences(statistics = StatisticsPreferences(30, 10))))
        val restored = BackupArchive.read(ByteArrayInputStream(v2(value)))
        assertEquals(timeline, restored.sessions.single().timeline)
        assertEquals(value.preferences, restored.preferences)
        val legacy = ByteArrayOutputStream().also { BackupArchive.writeLegacy(value, it) }.toByteArray()
        val old = BackupArchive.read(ByteArrayInputStream(legacy))
        assertNull(old.sessions.single().timeline); assertNull(old.preferences)
        assertArrayEquals(wav, old.sampleFiles.getValue(sampleId))
        val newMeter = value.copy(settings = ConfigCodec.encode(MetronomeConfig(timeSignature = org.openmetronome.domain.model.TimeSignature.SEVEN_EIGHT)))
        val out = ByteArrayOutputStream()
        assertThrows(Exception::class.java) { BackupArchive.writeLegacy(newMeter, out) }
        assertEquals(0, out.size())
    }
    @Test fun `sampled sound IDs round trip v2 but legacy export fails before writing any bytes`() {
        for (sound in org.openmetronome.domain.model.ClickSound.sampled) {
            val payload = ConfigCodec.encode(MetronomeConfig(sound = org.openmetronome.domain.model.SoundConfig(37, sound)))
            val original = snapshot()
            for (value in listOf(original.copy(settings = payload),
                original.copy(presets = original.presets.map { it.copy(config = payload) }),
                original.copy(sessions = original.sessions.map { it.copy(config = payload) }))) {
                val restored = BackupArchive.read(ByteArrayInputStream(v2(value)))
                assertEquals(value.settings, restored.settings)
                assertEquals(value.presets, restored.presets)
                assertEquals(value.sessions.map { it.config }, restored.sessions.map { it.config })
                val destination = ByteArrayOutputStream()
                assertThrows(IllegalArgumentException::class.java) { BackupArchive.writeLegacy(value, destination) }
                assertEquals(0, destination.size())
            }
        }
    }
    @Test fun `v3 preserves intervals timer precision summary and deletion while old exports omit activities`() {
        val details = fitDetails.copy(note = "Próba\nżółta część")
        val activities = listOf(
            MusicActivity(UUID.randomUUID().toString(), details, 1, 1),
            MusicActivity(UUID.randomUUID().toString(), details.copy(intervals = emptyList(), intervalResolutionMs = null), 1, 3, 3),
        )
        val value = snapshot().copy(musicActivities = activities)
        assertEquals(activities, BackupArchive.read(encoded(value).inputStream()).musicActivities)
        assertTrue(BackupArchive.read(v2(value).inputStream()).musicActivities.isEmpty())
        val v1 = ByteArrayOutputStream().also { BackupArchive.writeLegacy(value, it) }
        assertTrue(BackupArchive.read(v1.toByteArray().inputStream()).musicActivities.isEmpty())
        val invalid = value.copy(musicActivities = listOf(activities.first(), activities.first()))
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(invalid, output) }
        assertEquals(0, output.size())
    }

    @Test fun `old v3 without the notes trailer is readable and malformed reference is rejected`() {
        val activity = MusicActivity(UUID(0, 1).toString(), fitDetails.copy(note = "tekst"), 1, 1)
        val metadata = entries(encoded(snapshot().copy(musicActivities = listOf(activity))))
        val noteBytes = 4 + 4 + 36 + 4 + 5
        val legacy = metadata.map { (name, data) ->
            name to if (name == "openmetronome.dat") data.copyOf(data.size - noteBytes) else data
        }
        val restored = BackupArchive.read(zip(*legacy.toTypedArray()).inputStream()).musicActivities.single()
        assertEquals(activity.copy(details = fitDetails), restored)
        val badNote = metadata.map { (name, data) ->
            name to if (name == "openmetronome.dat") data.copyOf().also { it[it.size - 10] = '2'.code.toByte() } else data
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(zip(*badNote.toTypedArray()).inputStream()) }
    }

    @Test fun `inconsistent FIT timer is rejected even with a valid ZIP directory and CRC`() {
        val activity = MusicActivity(UUID(0, 1).toString(), fitDetails, 1, 1)
        val valid = entries(encoded(snapshot().copy(musicActivities = listOf(activity))))
        val malformed = valid.map { (name, data) ->
            name to if (name == "openmetronome.dat") data.copyOf().also { metadata ->
                val buffer = ByteBuffer.wrap(metadata)
                val at = (0..metadata.size - 16).single { offset ->
                    buffer.getLong(offset) == fitDetails.timerDurationMs && buffer.getLong(offset + 8) == 1_000L
                }
                buffer.putLong(at, 190_000L)
            } else data
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(zip(*malformed.toTypedArray()).inputStream())
        }
        assertEquals("FIT timer duration disagrees with reconstructed intervals", error.message)
    }

    @Test fun `activity and interval budgets bound v3 encoding and decoding`() {
        val empty = snapshot()
        val prefix = entries(encoded(empty)).first().second.size - Int.SIZE_BYTES
        val intervals = List(MusicActivityDetails.MAX_INTERVALS) { i -> MusicActivityInterval(i.toLong(), i + 1L) }
        val details = MusicActivityDetails(MusicActivityKind.OTHER, MusicActivitySource.FIT,
            0, 4_096, "UTC", 4_096, 1, intervals)
        fun activity(i: Int) = MusicActivity(UUID(0, i.toLong()).toString(), details, 1, 1)
        val count = BackupArchive.MAX_MUSIC_INTERVALS / MusicActivityDetails.MAX_INTERVALS
        val atLimit = List(count, ::activity)
        val valid = entries(encoded(empty.copy(musicActivities = atLimit)))
        assertEquals(atLimit, BackupArchive.read(zip(*valid.toTypedArray()).inputStream()).musicActivities)
        val extra = activity(count)
        val extraMetadata = entries(encoded(empty.copy(musicActivities = listOf(extra)))).first().second
        val extraRecord = extraMetadata.copyOfRange(prefix + Int.SIZE_BYTES, extraMetadata.size)
        val badCount = valid.map { (name, data) ->
            name to if (name == "openmetronome.dat") (data + extraRecord).also {
                ByteBuffer.wrap(it).putInt(prefix, count + 1)
            } else data
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(zip(*badCount.toTypedArray()).inputStream()) }
        val all = empty.copy(musicActivities = List(BackupArchive.MAX_MUSIC_ACTIVITIES + 1) { i ->
            activity(i).copy(details = fitDetails)
        })
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(all, output) }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(empty.copy(musicActivities = atLimit + extra), output) }
        val overLimit = valid.map { (name, data) -> name to if (name == "openmetronome.dat") data.copyOf().also {
            ByteBuffer.wrap(it).putInt(prefix, all.musicActivities.size)
        } else data }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(zip(*overLimit.toTypedArray()).inputStream()) }
        assertEquals(0, output.size())
    }

    private fun v2(value: BackupSnapshot) = ByteArrayOutputStream().also { BackupArchive.writeV2(value, it) }.toByteArray()

    private val sampleId = UUID.randomUUID().toString()
    private val wav = SamplePcm.encodeCanonical(ShortArray(96).also { it[20] = 1000; it[21] = -1000 })
    private val config = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(91)))
    private fun snapshot() = BackupSnapshot(
        config,
        listOf(Preset("preset", "Ćwiczenie", config, 1, 2)),
        listOf(PracticeSession("session", 3, 4, 5, true, false, "Europe/Warsaw", config)),
        listOf(PracticeDay("session", "2026-10-30", 5)),
        listOf(CustomSampleRecord(sampleId, "Klik", 6)),
        mapOf(sampleId to wav),
    )

    @Test fun `round trip preserves complete backup`() {
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(snapshot(), it) }.toByteArray()
        val restored = BackupArchive.read(ByteArrayInputStream(bytes))
        assertEquals(91, ConfigCodec.decode(restored.settings).tempo.bpm)
        assertEquals("Ćwiczenie", restored.presets.single().name)
        assertEquals(5L, restored.sessions.single().durationMs)
        assertEquals("1970-01-01", restored.days.single().date)
        assertEquals(8L, restored.sessions.single().checkpointAt)
        assertArrayEquals(wav, restored.sampleFiles.getValue(sampleId))
    }

    @Test fun `rejects malformed and incomplete documents`() {
        assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val incomplete = snapshot().copy(sampleFiles = emptyMap())
        assertThrows(Exception::class.java) { BackupArchive.write(incomplete, ByteArrayOutputStream()) }
    }
    private fun encoded(value: BackupSnapshot = snapshot()) = ByteArrayOutputStream().also { BackupArchive.write(value, it) }.toByteArray()
    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; add(e.name to zip.readBytes()) }
        }
    }

    @Test fun `released v1 fixture remains readable`() {
        val restored = javaClass.getResourceAsStream("/backups/v1-beta1.ombak")!!.use(BackupArchive::read)
        assertEquals(91, ConfigCodec.decode(restored.settings).tempo.bpm)
        assertEquals("Kopia 0.13", restored.presets.single().name)
        assertEquals(1000L, restored.sessions.single().durationMs)
        assertEquals(restored.samples.single().id, ConfigCodec.decode(restored.settings).sound.customSampleId)
        SamplePcm.decodeCanonical(restored.sampleFiles.values.single())
    }

    @Test fun `historical inconsistent derivatives are rebuilt without losing session data`() {
        val legacy = snapshot().copy(days = listOf(PracticeDay("session", "2026-10-30", 999)))
        val bytes = ByteArrayOutputStream().also { BackupArchive.writeStored(legacy, it, version = 2) }.toByteArray()
        val restored = BackupArchive.read(ByteArrayInputStream(bytes))
        assertEquals(legacy.sessions.single().copy(checkpointAt = 8), restored.sessions.single())
        assertEquals(listOf(PracticeDay("session", "1970-01-01", 5)), restored.days)
        assertEquals(legacy, BackupArchive.readStored(ByteArrayInputStream(bytes)).let { it.copy(sampleFiles = legacy.sampleFiles) })
    }

    @Test fun `timeline overflow and unbounded reconstruction are rejected before output`() {
        for (session in listOf(
            snapshot().sessions.single().copy(startedAt = Long.MAX_VALUE - 1, checkpointAt = Long.MAX_VALUE, durationMs = 5),
            snapshot().sessions.single().copy(durationMs = 100_001L * 86_400_000),
        )) {
            val out = ByteArrayOutputStream()
            assertThrows(Exception::class.java) { BackupArchive.write(snapshot().copy(sessions = listOf(session)), out) }
            assertEquals(0, out.size())
        }
    }

    @Test fun `empty backup round trips without samples`() {
        val empty = snapshot().copy(presets = emptyList(), sessions = emptyList(), days = emptyList(), samples = emptyList(), sampleFiles = emptyMap())
        val restored = BackupArchive.read(ByteArrayInputStream(encoded(empty)))
        assertEquals(empty, restored)
    }

    @Test fun `unknown version truncated zip missing and unexpected entries are rejected`() {
        val valid = entries(encoded())
        val version = valid.map { (name, bytes) -> name to if (name == "openmetronome.dat") bytes.copyOf().also { it[3] = 127 } else bytes }
        for (bad in listOf(zip(*version.toTypedArray()), encoded().copyOf(30), encoded().copyOf(encoded().size - 1),
            zip(valid.first()), zip(*(valid + ("unexpected" to byteArrayOf())).toTypedArray()))) {
            assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream(bad)) }
        }
        val many = zip(*Array(34) { "entry-$it" to byteArrayOf() })
        assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream(many)) }
    }

    @Test fun `duplicate zip names are rejected`() {
        val original = zip("aaaa" to byteArrayOf(1), "bbbb" to byteArrayOf(2))
        // Replace both local and directory names; ZipOutputStream itself forbids duplicates.
        for (i in 0..original.size - 4) if (original.copyOfRange(i, i + 4).contentEquals("bbbb".toByteArray()))
            "aaaa".toByteArray().copyInto(original, i)
        assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream(original)) }
    }

    @Test fun `export rejects invalid references records and noncanonical pcm before writing destination`() {
        val badConfig = ConfigCodec.encode(MetronomeConfig(sound = org.openmetronome.domain.model.SoundConfig(customSampleId = UUID.randomUUID().toString())))
        val cases = listOf(
            snapshot().copy(settings = badConfig),
            snapshot().copy(presets = snapshot().presets + snapshot().presets),
            snapshot().copy(days = listOf(PracticeDay("unknown", "2026-10-30", 1))),
            snapshot().copy(sampleFiles = mapOf(sampleId to SamplePcm.encodeCanonical(ShortArray(96)))),
            snapshot().copy(sampleFiles = mapOf(sampleId to SamplePcm.encodeCanonical(ShortArray(96) { 30000 }))),
        )
        for (bad in cases) {
            val out = ByteArrayOutputStream()
            assertThrows(Exception::class.java) { BackupArchive.write(bad, out) }
            assertEquals(0, out.size())
        }
    }

    @Test fun `import and export enforce decompressed size limit`() {
        val inflated = zip("oversize" to ByteArray(16 * 1024 * 1024 + 1))
        assertThrows(Exception::class.java) { BackupArchive.read(ByteArrayInputStream(inflated)) }
        val oversized = snapshot().copy(presets = List(100_000) { Preset("$it", "x".repeat(120), config, 1, 1) })
        val out = ByteArrayOutputStream()
        assertThrows(Exception::class.java) { BackupArchive.write(oversized, out) }
        assertEquals(0, out.size())
    }

    @Test fun `multi byte notes count toward the overall expanded archive limit`() {
        val details = fitDetails.copy(note = "音".repeat(MusicActivityDetails.MAX_NOTE_LENGTH))
        val activities = List(3_000) { i -> MusicActivity(UUID(0, i.toLong()).toString(), details, 1, 1) }
        val valid = snapshot().copy(musicActivities = activities.take(1))
        assertEquals(valid.musicActivities, BackupArchive.read(encoded(valid).inputStream()).musicActivities)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.write(valid.copy(musicActivities = activities), output)
        }
        assertEquals(0, output.size())
    }

    @Test fun `maximum sample count is accepted and overflow is rejected`() {
        val samples = List(32) { CustomSampleRecord(UUID.randomUUID().toString(), "Klik $it", it.toLong()) }
        val maximum = snapshot().copy(samples = samples, sampleFiles = samples.associate { it.id to wav })
        assertEquals(32, BackupArchive.read(ByteArrayInputStream(encoded(maximum))).samples.size)
        val extra = CustomSampleRecord(UUID.randomUUID().toString(), "Extra", 33)
        assertThrows(Exception::class.java) { encoded(maximum.copy(samples = samples + extra, sampleFiles = maximum.sampleFiles + (extra.id to wav))) }
    }

}
