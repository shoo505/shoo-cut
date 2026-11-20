package com.shoo.cut

/**
 * Immutable description of the video the user selected.
 *
 * [width] and [height] are the coded dimensions reported by the platform;
 * [rotationDegrees] describes how the picture must be rotated for display.
 */
data class VideoMetadata(
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
    val sizeBytes: Long
) {
    val durationSeconds: Double
        get() = durationMs / 1000.0

    val isValid: Boolean
        get() = durationMs > 0L && width > 0 && height > 0

    /** Width after applying rotation metadata. */
    val displayWidth: Int
        get() = if (rotationDegrees % 180 != 0) height else width

    /** Height after applying rotation metadata. */
    val displayHeight: Int
        get() = if (rotationDegrees % 180 != 0) width else height
}
