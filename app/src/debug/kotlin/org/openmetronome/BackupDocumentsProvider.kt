package org.openmetronome

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Debug APK only. A real DocumentsProvider serving just two synthetic archive fixtures. */
class BackupDocumentsProvider : DocumentsProvider() {
    private val directory get() = File(context!!.cacheDir, "backup-documents").also { it.mkdirs() }
    override fun onCreate() = true
    private fun file(id: String): File {
        require(id in listOf("roundtrip.ombak", "invalid.ombak"))
        return File(directory, id)
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        return MatrixCursor(columns).apply { newRow().apply {
            add(Root.COLUMN_ROOT_ID, "fixtures"); add(Root.COLUMN_DOCUMENT_ID, "root")
            add(Root.COLUMN_TITLE, "OpenMetronome test backup")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "application/octet-stream")
        } }
    }
    private fun row(cursor: MatrixCursor, id: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == "root") "OpenMetronome test backup" else id)
            add(Document.COLUMN_MIME_TYPE, if (id == "root") Document.MIME_TYPE_DIR else "application/octet-stream")
            add(Document.COLUMN_FLAGS, if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
            add(Document.COLUMN_SIZE, if (id == "root") 0 else file(id).length())
        }
    }
    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE))
    override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply { row(this, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "root")
        return cursor(projection).apply { listOf("roundtrip.ombak", "invalid.ombak").filter { file(it).exists() }.forEach { row(this, it) } }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root")
        file("roundtrip.ombak").createNewFile()
        return "roundtrip.ombak"
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" && documentId in listOf("roundtrip.ombak", "invalid.ombak")
}
