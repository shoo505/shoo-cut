package com.shoo.cut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Thrown for storage and file I/O problems with a user-presentable message. */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the platform media stack can read back from a finished output document. */
data class OutputProbe(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

/**
 * Owns all file-system work: application cache temp files, streaming copies from
 * `content://` URIs, document creation/writing inside the selected SAF folder and
 * free-space checks. Nothing here loads a whole video into memory and nothing here
 * converts a `content://` URI into a filesystem path.
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
        Log.d(TAG, "copyUriToFile: $uri")
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
        Log.d(TAG, "copyUriToFile: copied ${destination.length()} bytes")
    }

    /**
     * Resolves the root document URI of the folder returned by
     * ACTION_OPEN_DOCUMENT_TREE.
     *
     * `DocumentsContract.createDocument()` must receive a *document* URI. If the
     * raw tree URI is passed instead, `DocumentsProvider.enforceTree()` calls
     * `DocumentsContract.getDocumentId(treeUri)`, which only accepts
     * `/tree/<id>/document/<id>` URIs and throws
     * `IllegalArgumentException("Invalid URI: content://.../tree/...")`.
     */
    fun resolveRootDocumentUri(treeUri: Uri): Uri {
        validateTreeUri(treeUri)
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        validateDocumentUri(rootDocumentUri)
        Log.d(TAG, "resolveRootDocumentUri: tree=$treeUri root=$rootDocumentUri")
        return rootDocumentUri
    }

    /**
     * Pre-creates (or reuses) the four output documents in the selected folder.
     * Returns them in part order: index 0 -> ShooCut_Part1.mp4 ... index 3 -> Part4.
     */
    fun createOutputDocuments(folderUri: Uri): List<Uri> {
        validateTreeUri(folderUri)
        val rootDocumentUri = resolveRootDocumentUri(folderUri)
        return OutputNaming.partFileNames().map { fileName ->
            createOutputDocument(folderUri, rootDocumentUri, fileName)
        }
    }

    /**
     * Creates one output document.
     *
     * @param treeUri the folder picker tree URI (used only for tree-scoped lookups)
     * @param rootDocumentUri the folder's document URI (used as the create parent)
     */
    private fun createOutputDocument(treeUri: Uri, rootDocumentUri: Uri, fileName: String): Uri {
        val existingId = findDocumentId(treeUri, fileName)
        val documentUri = if (existingId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingId).also {
                Log.d(TAG, "Reusing existing document '$fileName' -> $it")
            }
        } else {
            try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    rootDocumentUri, // document URI, never the raw tree URI
                    MIME_TYPE_MP4,
                    fileName
                )
            } catch (e: FileNotFoundException) {
                throw StorageException("Cannot create '$fileName' in the selected folder: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw StorageException("Cannot create '$fileName' in the selected folder: ${e.message}", e)
            } ?: throw StorageException("Cannot create output file '$fileName' in the selected folder.")
        }
        validateDocumentUri(documentUri)
        Log.d(TAG, "createOutputDocument '$fileName' -> $documentUri")
        return documentUri
    }

    /**
     * Finds an existing document id in the selected folder by display name.
     */
    private fun findDocumentId(treeUri: Uri, fileName: String): String? {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idIndex < 0 || nameIndex < 0) return@use null
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == fileName) {
                        return cursor.getString(idIndex)
                    }
                }
                null
            }
        } catch (e: Exception) {
            // Not fatal: createDocument() below will still try to create the file.
            Log.w(TAG, "Could not list folder children for '$fileName': ${e.message}")
            null
        }
    }

    /**
     * Streams [source] into the pre-created SAF output document [outputUri],
     * closes/flushes the stream and verifies the document's real size by reading
     * it back through the ContentResolver.
     */
    fun writeToOutputDocument(outputUri: Uri, source: File) {
        validateDocumentUri(outputUri)
        val sourceSize = source.length()
        if (sourceSize <= 0L) {
            throw StorageException("Refusing to write an empty output file.")
        }

        var written = 0L
        val output = openTruncatingOutputStream(outputUri)
        output.use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                }
            }
            out.flush()
        }

        if (written != sourceSize) {
            throw StorageException("Write to the output file was incomplete ($written of $sourceSize bytes).")
        }

        val verifiedSize = getDocumentSize(outputUri)
        if (verifiedSize != sourceSize) {
            throw StorageException(
                "The saved file is $verifiedSize bytes but $sourceSize bytes were written."
            )
        }
        Log.d(TAG, "writeToOutputDocument: verified $verifiedSize bytes at $outputUri")
    }

    private fun openTruncatingOutputStream(uri: Uri): OutputStream {
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Provider rejected 'wt' mode, retrying with 'w': ${e.message}")
            context.contentResolver.openOutputStream(uri, "w")
        } catch (e: UnsupportedOperationException) {
            context.contentResolver.openOutputStream(uri, "w")
        }
        return stream ?: throw StorageException("Cannot open the output file for writing.")
    }

    /** Opens an input stream for a SAF document URI. */
    fun openInputStream(uri: Uri): InputStream? {
        validateDocumentUri(uri)
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Returns the real size of a document. Some providers do not report
     * COLUMN_SIZE (or report it stale), so this falls back to the file descriptor
     * stat size and finally to counting the stream.
     */
    fun getDocumentSize(uri: Uri): Long {
        validateDocumentUri(uri)

        val queried = queryColumnSize(uri)
        if (queried > 0L) return queried

        val statSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (e: Exception) {
            Log.w(TAG, "statSize failed for $uri: ${e.message}")
            null
        }
        if (statSize != null && statSize > 0L) return statSize

        return countStreamBytes(uri)
    }

    private fun queryColumnSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
                } else {
                    -1L
                }
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "query size failed for $uri: ${e.message}")
            -1L
        }
    }

    private fun countStreamBytes(uri: Uri): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw StorageException("Cannot read back the saved file.")
        return input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    /**
     * Reads the saved document back with [MediaMetadataRetriever] to prove it is a
     * finalized video that Android's media stack can open, including its duration
     * and stream presence.
     */
    fun probeOutput(uri: Uri): OutputProbe {
        validateDocumentUri(uri)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            OutputProbe(hasVideo, hasAudio, durationMs, width, height)
        } catch (e: Exception) {
            throw StorageException(
                "The saved output could not be read back as a video: ${e.message ?: "unknown error"}",
                e
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Best-effort release.
            }
        }
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

    private fun validateTreeUri(uri: Uri) {
        if (!SafUri.isTreeUri(uri.pathSegments)) {
            Log.e(TAG, "Expected tree URI but got: $uri")
            throw StorageException("The selected folder is not a valid Android folder URI.")
        }
    }

    private fun validateDocumentUri(uri: Uri) {
        if (!SafUri.isDocumentUri(uri.pathSegments)) {
            Log.e(TAG, "Expected document URI but got: $uri")
            throw StorageException("The saved file location is not a valid Android document URI.")
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MIME_TYPE_MP4 = "video/mp4"
        const val MIN_FREE_MARGIN_BYTES = 50L * 1024 * 1024
    }
}
