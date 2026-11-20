package com.shoo.cut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** Thrown when a picked document is not a readable video. */
class VideoMetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads duration, resolution, rotation and audio presence directly from a
 * `content://` URI with [MediaMetadataRetriever]. No FFprobe binary and no
 * filesystem path are required, so this works with scoped storage.
 */
class VideoMetadataReader(private val context: Context) {

    fun read(uri: Uri): VideoMetadata {
        val (displayName, sizeBytes) = queryDisplayMetadata(uri)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

            if (durationMs <= 0L || width <= 0 || height <= 0) {
                throw VideoMetadataException("The selected file is not a readable video.")
            }

            return VideoMetadata(
                uriString = uri.toString(),
                displayName = displayName,
                durationMs = durationMs,
                width = width,
                height = height,
                rotationDegrees = rotation,
                hasAudio = hasAudio,
                sizeBytes = sizeBytes
            )
        } catch (e: VideoMetadataException) {
            throw e
        } catch (e: Exception) {
            throw VideoMetadataException(
                "Unable to read video metadata: ${e.message ?: "unknown error"}",
                e
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore release failures.
            }
        }
    }

    private fun queryDisplayMetadata(uri: Uri): Pair<String, Long> {
        var displayName = uri.lastPathSegment ?: "video"
        var sizeBytes = 0L
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // The platform retriever below will still validate the file.
        }
        return displayName to sizeBytes
    }
}
