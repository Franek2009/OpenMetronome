package org.openmetronome.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.data.BackupArchive.writeString
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.SoundConfig

@RunWith(AndroidJUnit4::class)
class StreamingBackupValidatorTest {
    private val config = ConfigCodec.encode(MetronomeConfig())
    private fun temp(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "backup-validation-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun validatesLegacyVersionsAndNormalizesDaysOnPublicRead(): Unit = temp { root ->
        val session = PracticeSession("legacy", 0, 333, 2_000, true, false, "UTC", config)
        val snapshot = BackupSnapshot(config, emptyList(), listOf(session), emptyList(), emptyList(), emptyMap())
        for (version in 1..3) BackupStaging.create(root).use { stage ->
            stage.archive.outputStream().use { BackupArchive.writeStored(snapshot, it, version) }
            StreamingBackupValidator(BackupStreamLimits()) {}.validate(stage.archive, stage.index)
            val restored = stage.archive.inputStream().use(BackupArchive::read)
            assertEquals(2_000L, restored.sessions.single().checkpointAt)
            assertEquals(2_000L, restored.days.single().durationMs)
        }
    }

    @Test fun rejectsDuplicateKeysMissingReferencesAndInvalidWavs(): Unit = temp { root ->
        val archive = metadata(presets = listOf("same", "same"))
        val orphanDay = metadata(dayOf = "missing")
        val missingSample = metadata(settings = ConfigCodec.encode(MetronomeConfig(sound =
            SoundConfig(customSampleId = "ebecb414-79b3-445c-b632-db35f0e60e72"))))
        for (payload in listOf(archive, orphanDay, missingSample)) reject(root, zip(payload))
        reject(root, zip(metadata(), "samples/ebecb414-79b3-445c-b632-db35f0e60e72.wav"))
        reject(root, zip(metadata(), "../bad"))
        val duplicateEntry = zip(metadata(), "x".repeat(BackupArchive.METADATA.length))
            .toString(Charsets.ISO_8859_1).replace("x".repeat(BackupArchive.METADATA.length), BackupArchive.METADATA)
            .toByteArray(Charsets.ISO_8859_1)
        reject(root, duplicateEntry)
    }

    @Test fun rejectsTruncationWrongCentralCrcAndExpandedBudget(): Unit = temp { root ->
        val original = zip(metadata())
        reject(root, original.copyOf(original.size - 1))
        val badCrc = original.copyOf()
        val central = badCrc.indices.first { i -> i + 3 < badCrc.size &&
            badCrc[i] == 0x50.toByte() && badCrc[i + 1] == 0x4b.toByte() &&
            badCrc[i + 2] == 1.toByte() && badCrc[i + 3] == 2.toByte() }
        badCrc[central + 16] = (badCrc[central + 16].toInt() xor 1).toByte()
        reject(root, badCrc)
        reject(root, original, BackupStreamLimits(expanded = 40))
        reject(root, original, BackupStreamLimits(compressed = 40))
    }

    @Test fun boundsInputCopyAndPropagatesCancellation(): Unit = temp { root ->
        BackupStaging.create(root).use { stage ->
            val input = zip(metadata())
            assertThrows(IllegalArgumentException::class.java) {
                stage.stageInput(input.inputStream(), input.size.toLong() - 1) {}
            }
            stage.stageInput(input.inputStream(), input.size.toLong()) {}
            assertArrayEquals(input, stage.archive.readBytes())
            assertThrows(CancellationException::class.java) {
                StreamingBackupValidator(BackupStreamLimits()) { throw CancellationException() }.validate(stage.archive, stage.index)
            }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun reject(root: File, bytes: ByteArray, limits: BackupStreamLimits = BackupStreamLimits()) {
        BackupStaging.create(root).use { stage ->
            stage.archive.writeBytes(bytes)
            assertThrows(Exception::class.java) { StreamingBackupValidator(limits) {}.validate(stage.archive, stage.index) }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun metadata(presets: List<String> = emptyList(), dayOf: String? = null, settings: String = config) =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(2); out.writeString(settings); out.writeInt(presets.size)
                presets.forEach { id ->
                    out.writeString(id); out.writeString("Preset"); out.writeString(config); out.writeLong(0); out.writeLong(0)
                }
                out.writeInt(0); out.writeInt(if (dayOf == null) 0 else 1)
                dayOf?.let { out.writeString(it); out.writeString("2020-01-01"); out.writeLong(1) }
                out.writeInt(0); out.writeBoolean(false)
            }
        }.toByteArray()

    private fun zip(metadata: ByteArray, extra: String? = null) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.METADATA)); zip.write(metadata); zip.closeEntry()
            extra?.let { zip.putNextEntry(ZipEntry(it)); zip.write(42); zip.closeEntry() }
        }
    }.toByteArray()
}
