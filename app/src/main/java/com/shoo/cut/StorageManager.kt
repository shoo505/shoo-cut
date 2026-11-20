package com.shoo.cut

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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

    private val TAG = "StorageManager"

    fun createTempInputFile(): File =
        File.createTempFile("shoo_input_", ".tmp", context.cacheDir)

    fun createTempOutputFile(): File =
        File.createTempFile("shoo_output_", ".mp4", context.cacheDir)

    /**
     * Streams [uri] into [destination] with a fixed-size buffer.
     * [onProgress] receives the cumulative number of bytes copied.
     */
    fun copyUriToFile(uri: Uri, destination: File, onProgress: ((Long) -> Unit)? = null) {
        logUri("copyUriToFile input", uri)
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
        logUri("createOutputDocuments folderUri", folderUri)
        validateTreeUri(folderUri)
        val outputUris = mutableMapOf<Int, Uri>()
        for (segmentIndex in 0..3) {
            val segmentNumber = segmentIndex + 1
            val fileName = "${baseFileName}_Part$segmentNumber.mp4"
            val outputUri = createOutputDocument(folderUri, fileName)
            logUri("createOutputDocuments outputUri[$segmentIndex]", outputUri)
            outputUris[segmentIndex] = outputUri
        }
        return outputUris
    }

    /**
     * Creates a single output document in the SAF folder.
     */
    private fun createOutputDocument(folderUri: Uri, fileName: String): Uri {
        logUri("createOutputDocument folderUri", folderUri)
        validateTreeUri(folderUri)
        // Check if document already exists
        val existingId = findDocumentInFolder(folderUri, fileName)
        val outputUri = if (existingId != null) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(folderUri, existingId)
            logUri("buildDocumentUriUsingTree result", uri)
            uri
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
            logUri("createDocument result", newDoc)
            newDoc
        }
        validateDocumentUri(outputUri)
        return outputUri
    }

    /**
     * Streams [source] into the pre-created SAF output document [outputUri].
     */
    fun writeToOutputDocument(outputUri: Uri, source: File): Uri {
        logUri("writeToOutputDocument outputUri", outputUri)
        validateDocumentUri(outputUri)
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
        logUri("findDocumentInFolder folderUri", folderUri)
        validateTreeUri(folderUri)
        // For tree URIs, we need to use the tree document ID to build the children URI
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        logUri("getTreeDocumentId", Uri.parse("content://docid/$treeDocId"))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
        logUri("buildChildDocumentsUriUsingTree childrenUri", childrenUri)
        return context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (!cursor.isAfterLast) {
                    val name = cursor.getString(nameIndex)
                    val id = cursor.getString(idIndex)
                    logUri("Found document", Uri.parse("content://docid/$id"), "name=$name")
                    if (name == fileName) {
                        return id
                    }
                    cursor.moveToNext()
                }
            }
            null
        }
    }

    /** Opens an input stream for a SAF document URI. */
    fun openInputStream(uri: Uri): InputStream? {
        logUri("openInputStream", uri)
        validateDocumentUri(uri)
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Gets the size of a document via ContentResolver.
     */
    fun getDocumentSize(uri: Uri): Long {
        logUri("getDocumentSize", uri)
        validateDocumentUri(uri)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        return context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex)
                }
            }
            0L
        } ?: 0L
    }

    /** Validates that a URI is a tree URI (from ACTION_OPEN_DOCUMENT_TREE). */
    private fun validateTreeUri(uri: Uri) {
        if (!isTreeUri(uri)) {
            Log.e(TAG, "Expected tree URI but got: $uri")
            throw StorageException("Expected tree URI (from folder picker), got: $uri")
        }
    }

    /** Validates that a URI is a document URI (from createDocument or buildDocumentUriUsingTree). */
    private fun validateDocumentUri(uri: Uri) {
        if (isTreeUri(uri)) {
            Log.e(TAG, "Expected document URI but got tree URI: $uri")
            throw StorageException("Expected document URI (for file operations), got tree URI: $uri")
        }
    }

    /** Checks if a URI is a tree URI (from ACTION_OPEN_DOCUMENT_TREE). */
    private fun isTreeUri(uri: Uri): Boolean {
        return "/tree/" in uri.toString()
    }

    private fun logUri(label: String, uri: Uri, extra: String = "") {
        val scheme = uri.scheme
        val authority = uri.authority
        val path = uri.path
        val isTree = isTreeUri(uri)
        Log.d(TAG, "$label: scheme=$scheme, authority=$authority, path=$path, isTree=$isTree $extra")
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