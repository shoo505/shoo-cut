package com.shoo.cut

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Thrown for storage and file I/O problems with a user-presentable message. */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns all file-system work: application cache temp files, streaming copies from
 * `content://` URIs, streaming writes to the SAF destination folder and free-space
 * checks. Nothing here loads a whole video into memory.
 */
class StorageManager(private val context: Context) {

    fun createTempInputFile(): File =
        File.createTempFile("shoo_input_", ".tmp", context.cacheDir)

    fun createTempOutputFile(): File =
        File.createTempFile("shoo_output_", ".mp4", context.cacheDir)

    /**
     * Streams [uri] into [destination] with a fixed-size buffer.
     * [onProgress] receives the cumulative number of bytes copied.
     */
    fun copyUriToFile(uri: Uri, destination: File, onProgress: ((Long) -> Unit)? = null) {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw StorageException("Cannot open the selected video.")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    onProgress?.invoke(total)
                }
                output.flush()
            }
        }
        if (destination.length() <= 0L) {
            throw StorageException("The selected video is empty.")
        }
    }

    /** Streams [source] into a file inside the user-selected SAF folder [folderUri]. */
    fun writeFileToFolder(folderUri: Uri, source: File, fileName: String): Uri {
        val documentsTree = DocumentsContract.getTreeDocumentId(folderUri)
        val existingId = findDocumentInFolder(folderUri, fileName)
        val outputUri = if (existingId != null) {
            DocumentsContract.buildDocumentUriUsingTree(folderUri, existingId)
        } else {
            val newDoc = DocumentsContract.createDocument(
                context.contentResolver,
                folderUri,
                "video/mp4",
                fileName
            )
            if (newDoc == null) {
                throw StorageException("Cannot create output file in selected folder.")
            }
            newDoc
        }

        val output: OutputStream = context.contentResolver.openOutputStream(outputUri, "w")
            ?: throw StorageException("Cannot open output file for writing.")
        source.inputStream().use { input ->
            output.use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
        return outputUri
    }

    private fun findDocumentInFolder(folderUri: Uri, fileName: String): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri))
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (!cursor.isAfterLast) {
                    if (cursor.getString(nameIndex) == fileName) {
                        return cursor.getString(idIndex)
                    }
                    cursor.moveToNext()
                }
            }
        }
        return null
    }

    /** True when the cache partition can hold [requiredBytes] plus a safety margin. */
    fun hasEnoughFreeSpace(requiredBytes: Long): Boolean =
        context.cacheDir.usableSpace >= requiredBytes.coerceAtLeast(0L) + MIN_FREE_MARGIN_BYTES

    fun freeSpaceBytes(): Long = context.cacheDir.usableSpace

    fun deleteQuietly(file: File?) {
        if (file == null || !file.exists()) return
        try {
            file.delete()
        } catch (_: Exception) {
            // Best-effort cleanup only.
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        const val MIN_FREE_MARGIN_BYTES = 50L * 1024 * 1024
    }
}
