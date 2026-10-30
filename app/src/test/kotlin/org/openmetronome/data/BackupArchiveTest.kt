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
}
