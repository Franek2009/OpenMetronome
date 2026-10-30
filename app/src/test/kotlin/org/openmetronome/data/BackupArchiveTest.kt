package org.openmetronome.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo

class BackupArchiveTest {
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
        assertEquals("2026-10-30", restored.days.single().date)
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

    @Test fun `empty backup round trips without samples`() {
        val empty = snapshot().copy(presets = emptyList(), sessions = emptyList(), days = emptyList(), samples = emptyList(), sampleFiles = emptyMap())
        val restored = BackupArchive.read(ByteArrayInputStream(encoded(empty)))
        assertEquals(empty, restored)
    }

    @Test fun `unknown version truncated zip missing and unexpected entries are rejected`() {
        val valid = entries(encoded())
        val version = valid.map { (name, bytes) -> name to if (name == "openmetronome.dat") bytes.copyOf().also { it[3] = 2 } else bytes }
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

    @Test fun `maximum sample count is accepted and overflow is rejected`() {
        val samples = List(32) { CustomSampleRecord(UUID.randomUUID().toString(), "Klik $it", it.toLong()) }
        val maximum = snapshot().copy(samples = samples, sampleFiles = samples.associate { it.id to wav })
        assertEquals(32, BackupArchive.read(ByteArrayInputStream(encoded(maximum))).samples.size)
        val extra = CustomSampleRecord(UUID.randomUUID().toString(), "Extra", 33)
        assertThrows(Exception::class.java) { encoded(maximum.copy(samples = samples + extra, sampleFiles = maximum.sampleFiles + (extra.id to wav))) }
    }

}
