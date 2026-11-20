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

    /**
     * Pre-creates four output documents in the selected SAF folder.
     * Returns a map of segment index (0-3) to output document URI.
     */
    fun createOutputDocuments(folderUri: Uri, baseFileName: String): Map<Int, Uri> {
        val outputUris = mutableMapOf<Int, Uri>()
        for (segmentIndex in 0..3) {
            val segmentNumber = segmentIndex + 1
            val fileName = "${baseFileName}_Part$segmentNumber.mp4"
            val outputUri = createOutputDocument(folderUri, fileName)
            outputUris[segmentIndex] = outputUri
        }
        return outputUris
    }

    /**
     * Creates a single output document in the SAF folder.
     */
    private fun createOutputDocument(folderUri: Uri, fileName: String): Uri {
        // Check if document already exists
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
                throw StorageException("Cannot create output file '$fileName' in selected folder.")
            }
            newDoc
        }
        return outputUri
    }

    /**
     * Streams [source] into the pre-created SAF output document [outputUri].
     */
    fun writeToOutputDocument(outputUri: Uri, source: File): Uri {
        val sourceSize = source.length()
        var written = 0L

        val output: OutputStream = context.contentResolver.openOutputStream(outputUri, "w")
            ?: throw StorageException("Cannot open output file for writing.")

        source.inputStream().use { input ->
            output.use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                }
                out.flush()
            }
        }

        // Verify written size matches source
        if (written != sourceSize) {
            throw StorageException("Output file size mismatch: expected $sourceSize bytes, wrote $written bytes")
        }

        // Verify the written document has content
        val writtenSize = getDocumentSize(outputUri)
        if (writtenSize <= 0) {
            throw StorageException("Output file is empty after writing.")
        }
        if (writtenSize != sourceSize) {
            throw StorageException("Output file size mismatch after verification: expected $sourceSize bytes, document reports $writtenSize bytes")
        }

        return outputUri
    }

    /**
     * Finds an existing document in the folder by name.
     * Returns the document ID if found, null otherwise.
     */
    private fun findDocumentInFolder(folderUri: Uri, fileName: String): String? {
        // For tree URIs, we need to use the tree document ID to build the children URI
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
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
            null
        }
    }

    /** Opens an input stream for a SAF document URI. */
    fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    /**
     * Gets the size of a document via ContentResolver.
     */
    fun getDocumentSize(uri: Uri): Long {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    0L
                }
            } else {
                0L
            }
        } ?: 0L
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