package com.shoo.cut

import android.content.Intent

/** Intents and naming helpers for the Storage Access Framework. */
object VideoPicker {

    const val VIDEO_MIME_TYPE = "video/*"
    const val MP4_MIME_TYPE = "video/mp4"

    /** Intent that opens the system picker for a readable video document. */
    fun pickVideoIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = VIDEO_MIME_TYPE
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    /** Intent that lets the user pick a folder for output files. */
    fun pickOutputFolderIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
}