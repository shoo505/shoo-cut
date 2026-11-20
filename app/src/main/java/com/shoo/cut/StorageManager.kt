package com.shoo.cut

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Thrown for storage and file I/O problems with a user-presentable message. */
class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns all file-system work: application cache temp files, streaming copies from
 * `content://` URIs, streaming writes to the SAF destination and free-space
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

    /** Streams [source] into the user-selected SAF [destination]. */
    fun writeFileToUri(source: File, destination: Uri) {
        val output: OutputStream = context.contentResolver.openOutputStream(destination, "w")
            ?: throw StorageException("Cannot open the output location for writing.")
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
