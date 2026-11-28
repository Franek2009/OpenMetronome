package org.openmetronome.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

/** These disposable files are separate from the restore journal. */
internal class BackupStaging private constructor(private val directory: File) : AutoCloseable {
    val archive = File(directory, "archive.ombak")
    val index = File(directory, "validation.sqlite")

    fun stageInput(source: InputStream, limit: Long, checkActive: () -> Unit) {
        source.use { input ->
            archive.outputStream().use { output -> copy(input, BackupOutputLimit(output, limit, checkActive), checkActive) }
        }
    }

    fun deliver(destination: OutputStream, checkActive: () -> Unit) {
        destination.use { output -> archive.inputStream().use { input -> copy(input, output, checkActive) } }
    }

    override fun close() = discard(directory)

    companion object {
        fun create(root: File): BackupStaging {
            check((root.isDirectory || root.mkdirs()) && !Files.isSymbolicLink(root.toPath()))
            val directory = File(root, "stage-${UUID.randomUUID()}")
            check(directory.mkdir())
            return BackupStaging(directory)
        }

        fun cleanInterrupted(root: File) {
            if (Files.isSymbolicLink(root.toPath())) return
            root.listFiles()?.forEach { path ->
                val suffix = path.name.removePrefix("stage-")
                if (suffix != path.name && runCatching { UUID.fromString(suffix).toString() == suffix }.getOrDefault(false)) {
                    discard(path)
                }
            }
        }

        private fun discard(directory: File) {
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return
            for (name in listOf("archive.ombak", "validation.sqlite", "validation.sqlite-journal",
                "validation.sqlite-wal", "validation.sqlite-shm")) File(directory, name).delete()
            directory.delete()
        }

        private fun copy(input: InputStream, out: OutputStream, checkActive: () -> Unit) {
            val bytes = ByteArray(32 * 1024)
            while (true) {
                checkActive()
                val count = input.read(bytes)
                if (count < 0) break
                check(count > 0) { "Backup stream made no progress" }
                out.write(bytes, 0, count)
            }
        }
    }
}
