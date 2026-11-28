package org.openmetronome.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.data.BackupArchive.writeString
import org.openmetronome.domain.audio.SamplePcm
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

    @Test fun rejectsCentralOffsetPointingInsideCompressedData(): Unit = temp { root ->
        val original = zip(metadata())
        val damaged = original.copyOf()
        val layout = damaged.layout()
        layout.putInt(layout.centralOffset + 42, 30 + BackupArchive.METADATA.length)
        reject(root, damaged)
    }

    @Test fun rejectsLocalRecordAliasedToAnotherEntry() = rejectSampleOffset { 0 }

    @Test fun rejectsLocalRecordOverlappingPreviousDescriptor() = rejectSampleOffset { it - 1 }

    private fun rejectSampleOffset(offset: (Int) -> Int): Unit = temp { root ->
        val id = "ebecb414-79b3-445c-b632-db35f0e60e72"
        val wav = SamplePcm.encodeCanonical(ShortArray(96).also { it[20] = 1000; it[21] = -1000 })
        val snapshot = BackupSnapshot(config, emptyList(), emptyList(), emptyList(),
            listOf(CustomSampleRecord(id, "Klik", 1)), mapOf(id to wav))
        val original = ByteArrayOutputStream().also { BackupArchive.write(snapshot, it) }.toByteArray()
        validate(root, original)
        val layout = original.layout()
        val first = layout.centralOffset
        val sampleCentral = first + 46 + (28..32 step 2).sumOf { layout.getShort(first + it).toInt() and 65535 }
        layout.putInt(sampleCentral + 42, offset(layout.getInt(sampleCentral + 42)))
        reject(root, original)
    }

    @Test fun rejectsInconsistentLocalAndCentralFlags(): Unit = temp { root ->
        val damaged = zip(metadata())
        val layout = damaged.layout()
        val flagsOffset = layout.centralOffset + 8
        layout.putShort(flagsOffset, (layout.getShort(flagsOffset).toInt() xor 0x0800).toShort())
        reject(root, damaged)
    }

    @Test fun rejectsUnindexedBytesBeforeCentralDirectory(): Unit = temp { root ->
        val original = zip(metadata())
        val central = original.layout().centralOffset
        val padded = original.copyOfRange(0, central) + byteArrayOf(1, 2, 3, 4) +
            original.copyOfRange(central, original.size)
        padded.layout().putInt(padded.size - 6, central + 4)
        reject(root, padded)
    }

    @Test fun acceptsStoredEntriesAndDeflatedEntriesWithEitherDescriptorForm(): Unit = temp { root ->
        val payload = metadata()
        validate(root, zip(payload, stored = true))
        val signed = zip(payload)
        validate(root, signed)
        val central = signed.layout().centralOffset
        val descriptor = central - 16
        assertEquals(0x08074b50, signed.layout().getInt(descriptor))
        val unsigned = signed.copyOfRange(0, descriptor) + signed.copyOfRange(descriptor + 4, signed.size)
        unsigned.layout().putInt(unsigned.size - 6, central - 4)
        validate(root, unsigned)
    }

    private fun ByteArray.layout() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private val ByteBuffer.centralOffset get() = getInt(limit() - 6)

    private fun validate(root: File, bytes: ByteArray) {
        BackupStaging.create(root).use { stage ->
            stage.archive.writeBytes(bytes)
            StreamingBackupValidator(BackupStreamLimits()) {}.validate(stage.archive, stage.index)
        }
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

    private fun zip(metadata: ByteArray, extra: String? = null, stored: Boolean = false) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            val entry = ZipEntry(BackupArchive.METADATA)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = metadata.size.toLong()
                entry.crc = CRC32().also { it.update(metadata) }.value
            }
            zip.putNextEntry(entry); zip.write(metadata); zip.closeEntry()
            extra?.let { zip.putNextEntry(ZipEntry(it)); zip.write(42); zip.closeEntry() }
        }
    }.toByteArray()
}
